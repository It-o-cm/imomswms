package com.intermarche.backoffice.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringRecipe;
import com.intermarche.backoffice.domain.catering.CateringRecipeLine;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the bill of materials of a catering unit (F4).
 * <p>
 * The base recipe of the variant is completed by the composition-choice complements and the cake
 * flavour complement, then component quantities and labour seconds are summed and multiplied by the
 * fabrication-line quantity. The highest active revision wins for each recipe key; an inactive
 * revision is ignored. When no base recipe exists the caller decides, from {@code recipeRequired},
 * whether to block the unit or plan it with the default labour.
 */
@ApplicationScoped
public class CateringRecipeResolver {

    /**
     * The resolved recipe.
     *
     * @param components   the component quantities for the whole fabrication-line quantity.
     * @param laborSeconds the labour seconds for the whole fabrication-line quantity.
     * @param hasBase      whether a base recipe was found.
     */
    public record ResolvedRecipe(Map<Product, BigDecimal> components, long laborSeconds, boolean hasBase) {
    }

    /**
     * Resolves the recipe of a unit (F4).
     *
     * @param item                the catering article.
     * @param variantId           the variant.
     * @param configuration       the received configuration (composition or cake), or null.
     * @param quantity            the fabrication-line quantity.
     * @param defaultLaborSeconds the default labour seconds for a unit without a recipe.
     * @return the resolved recipe.
     */
    public ResolvedRecipe resolve(CateringItem item, String variantId, JsonNode configuration,
                                  BigDecimal quantity, int defaultLaborSeconds) {
        CateringRecipe base = highestActive(item, variantId, null, null, null);
        if (base == null) {
            long labor = BigDecimal.valueOf(defaultLaborSeconds).multiply(quantity).setScale(0, RoundingMode.CEILING).longValueExact();
            return new ResolvedRecipe(new LinkedHashMap<>(), labor, false);
        }
        var components = new LinkedHashMap<Product, BigDecimal>();
        BigDecimal unitLabor = BigDecimal.ZERO;
        unitLabor = unitLabor.add(accumulate(base, components));
        if (configuration != null && configuration.path("choices").isObject()) {
            var steps = configuration.path("choices").fields();
            while (steps.hasNext()) {
                var step = steps.next();
                for (JsonNode choice : step.getValue()) {
                    CateringRecipe complement = highestActive(item, variantId, step.getKey(), choice.asText(), null);
                    if (complement != null) {
                        unitLabor = unitLabor.add(accumulate(complement, components));
                    }
                }
            }
        }
        if (configuration != null && configuration.hasNonNull("flavourId")) {
            CateringRecipe flavour = highestActive(item, variantId, null, null, configuration.path("flavourId").asText());
            if (flavour != null) {
                unitLabor = unitLabor.add(accumulate(flavour, components));
            }
        }
        components.replaceAll((component, value) -> value.multiply(quantity));
        long labor = unitLabor.multiply(quantity).setScale(0, RoundingMode.CEILING).longValueExact();
        return new ResolvedRecipe(components, labor, true);
    }

    /**
     * Resolves the {@code PICKED} products of a {@code STOCKED} unit (F11, T3.018): the picked lines of
     * the base recipe and its applicable complements, each multiplied by the unit quantity. Returns an
     * empty map when the unit has no base recipe (T3.019: the article itself is then picked).
     *
     * @param item          the catering article.
     * @param variantId     the variant.
     * @param configuration the received configuration (composition or cake), or null.
     * @param quantity      the unit quantity.
     * @return the picked product quantities, empty when there is no base recipe.
     */
    public Map<Product, BigDecimal> pickedProducts(CateringItem item, String variantId, JsonNode configuration,
                                                   BigDecimal quantity) {
        CateringRecipe base = highestActive(item, variantId, null, null, null);
        if (base == null) {
            return new LinkedHashMap<>();
        }
        var picked = new LinkedHashMap<Product, BigDecimal>();
        accumulatePicked(base, picked);
        if (configuration != null && configuration.path("choices").isObject()) {
            var steps = configuration.path("choices").fields();
            while (steps.hasNext()) {
                var step = steps.next();
                for (JsonNode choice : step.getValue()) {
                    CateringRecipe complement = highestActive(item, variantId, step.getKey(), choice.asText(), null);
                    if (complement != null) {
                        accumulatePicked(complement, picked);
                    }
                }
            }
        }
        if (configuration != null && configuration.hasNonNull("flavourId")) {
            CateringRecipe flavour = highestActive(item, variantId, null, null, configuration.path("flavourId").asText());
            if (flavour != null) {
                accumulatePicked(flavour, picked);
            }
        }
        picked.replaceAll((component, value) -> value.multiply(quantity));
        return picked;
    }

    /**
     * Adds the {@code PICKED} lines of a recipe to the accumulator (for a single unit).
     *
     * @param recipe the recipe.
     * @param picked the picked-product accumulator.
     */
    private void accumulatePicked(CateringRecipe recipe, Map<Product, BigDecimal> picked) {
        for (CateringRecipeLine line : CateringRecipeLine.<CateringRecipeLine>list("recipe = ?1 and kind = ?2",
                recipe, CateringRecipeLine.RecipeLineKind.PICKED)) {
            picked.merge(line.component, line.quantity, BigDecimal::add);
        }
    }

    /**
     * Adds the lines and labour of a recipe to the accumulator (for a single unit).
     *
     * @param recipe     the recipe.
     * @param components the component accumulator.
     * @return the recipe labour seconds (0 if null).
     */
    private BigDecimal accumulate(CateringRecipe recipe, Map<Product, BigDecimal> components) {
        for (CateringRecipeLine line : CateringRecipeLine.<CateringRecipeLine>list("recipe", recipe)) {
            components.merge(line.component, line.quantity, BigDecimal::add);
        }
        return recipe.laborSeconds == null ? BigDecimal.ZERO : BigDecimal.valueOf(recipe.laborSeconds);
    }

    /**
     * Finds the highest active revision for a recipe key.
     *
     * @param item      the catering article.
     * @param variantId the variant.
     * @param stepId    the composition step, or null.
     * @param choiceId  the composition choice, or null.
     * @param flavourId the cake flavour, or null.
     * @return the recipe, or null.
     */
    private CateringRecipe highestActive(CateringItem item, String variantId, String stepId, String choiceId, String flavourId) {
        return CateringRecipe.<CateringRecipe>find(
                "item = ?1 and variantId = ?2 and active = true"
                        + " and ((?3 is null and stepId is null) or stepId = ?3)"
                        + " and ((?4 is null and choiceId is null) or choiceId = ?4)"
                        + " and ((?5 is null and flavourId is null) or flavourId = ?5)"
                        + " order by revision desc",
                item, variantId, stepId, choiceId, flavourId).firstResult();
    }
}
