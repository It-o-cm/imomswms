package com.intermarche.e2e.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringRecipe;
import com.intermarche.backoffice.domain.catering.CateringRecipeLine;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.engine.catering.CateringRecipeResolver;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the recipe resolution (F4): base alone, composition complements, cake flavour, highest active
 * revision, inactive revision ignored, and a missing recipe planned with the default labour.
 */
@QuarkusTest
public class CateringRecipeTest {

    @Inject
    CateringRecipeResolver resolver;
    @Inject
    ObjectMapper mapper;

    private int counter = 0;

    /**
     * Creates a catering article.
     *
     * @return the article.
     */
    private CateringItem item() {
        Product product = DomainUtils.createAndPersistProduct("REC-" + (counter++), "Article", ProductType.UNIT);
        CateringItem item = new CateringItem();
        item.product = product;
        item.sourcingMode = SourcingMode.FABRICATED;
        item.active = true;
        item.persistAndFlush();
        return item;
    }

    /**
     * Creates a component product.
     *
     * @param ean the EAN.
     * @return the product.
     */
    private Product component(String ean) {
        return DomainUtils.createAndPersistProduct(ean, "C-" + ean, ProductType.UNIT);
    }

    /**
     * Persists a recipe with a single component line.
     *
     * @param item      the article.
     * @param variantId the variant.
     * @param stepId    the step, or null.
     * @param choiceId  the choice, or null.
     * @param flavourId the flavour, or null.
     * @param revision  the revision.
     * @param active    whether active.
     * @param labor     the labour seconds.
     * @param component the component.
     * @param quantity  the component quantity.
     */
    private void recipe(CateringItem item, String variantId, String stepId, String choiceId, String flavourId,
                        int revision, boolean active, Integer labor, Product component, String quantity) {
        CateringRecipe recipe = new CateringRecipe();
        recipe.item = item;
        recipe.variantId = variantId;
        recipe.stepId = stepId;
        recipe.choiceId = choiceId;
        recipe.flavourId = flavourId;
        recipe.revision = revision;
        recipe.active = active;
        recipe.laborSeconds = labor;
        recipe.persist();
        CateringRecipeLine line = new CateringRecipeLine();
        line.recipe = recipe;
        line.component = component;
        line.quantity = new BigDecimal(quantity);
        line.kind = CateringRecipeLine.RecipeLineKind.INGREDIENT;
        line.persist();
    }

    /**
     * Parses a configuration JSON.
     *
     * @param json the JSON.
     * @return the node.
     */
    private JsonNode config(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * F4.1: the base recipe alone resolves its components and labour.
     */
    @Test
    @TestTransaction
    void baseAlone() {
        CateringItem item = item();
        Product flour = component("FLOUR");
        recipe(item, "16", null, null, null, 1, true, 600, flour, "2");
        var resolved = resolver.resolve(item, "16", null, BigDecimal.ONE, 600);
        assertTrue(resolved.hasBase());
        assertEquals(0, new BigDecimal("2").compareTo(resolved.components().get(flour)));
        assertEquals(600, resolved.laborSeconds());
    }

    /**
     * F4.2/F4.3: composition and cake complements add to the base; an absent complement is ignored.
     */
    @Test
    @TestTransaction
    void complementsAddAndAbsentIgnored() {
        CateringItem item = item();
        Product base = component("BASE");
        Product extra = component("EXTRA");
        Product cocoa = component("COCOA");
        recipe(item, "15", null, null, null, 1, true, 100, base, "1");
        recipe(item, "15", "garniture", "jambon", null, 1, true, 50, extra, "3");
        recipe(item, "15", null, null, "chocolate", 1, true, 40, cocoa, "5");
        JsonNode composition = config("{\"choices\":{\"garniture\":[\"jambon\",\"absent\"]},\"flavourId\":\"chocolate\"}");
        var resolved = resolver.resolve(item, "15", composition, BigDecimal.ONE, 600);
        assertEquals(0, new BigDecimal("1").compareTo(resolved.components().get(base)));
        assertEquals(0, new BigDecimal("3").compareTo(resolved.components().get(extra)));
        assertEquals(0, new BigDecimal("5").compareTo(resolved.components().get(cocoa)));
        assertEquals(190, resolved.laborSeconds());
    }

    /**
     * F4.4: quantities and labour are multiplied by the fabrication-line quantity.
     */
    @Test
    @TestTransaction
    void multipliedByQuantity() {
        CateringItem item = item();
        Product flour = component("FLOUR2");
        recipe(item, "16", null, null, null, 1, true, 100, flour, "2");
        var resolved = resolver.resolve(item, "16", null, new BigDecimal("3"), 600);
        assertEquals(0, new BigDecimal("6").compareTo(resolved.components().get(flour)));
        assertEquals(300, resolved.laborSeconds());
    }

    /**
     * F4: the highest active revision wins; an inactive revision is ignored.
     */
    @Test
    @TestTransaction
    void highestActiveRevision() {
        CateringItem item = item();
        Product flour = component("FLOUR3");
        recipe(item, "16", null, null, null, 1, true, 100, flour, "1");
        recipe(item, "16", null, null, null, 2, true, 200, flour, "2");
        recipe(item, "16", null, null, null, 3, false, 999, flour, "9");
        var resolved = resolver.resolve(item, "16", null, BigDecimal.ONE, 600);
        assertEquals(200, resolved.laborSeconds(), "The highest active revision (2) wins, not the inactive 3");
        assertEquals(0, new BigDecimal("2").compareTo(resolved.components().get(flour)));
    }

    /**
     * F4.5: with no base recipe, resolution plans with the default labour and no components.
     */
    @Test
    @TestTransaction
    void noBaseUsesDefaultLabor() {
        CateringItem item = item();
        var resolved = resolver.resolve(item, "99", null, new BigDecimal("2"), 600);
        assertFalse(resolved.hasBase());
        assertTrue(resolved.components().isEmpty());
        assertEquals(1200, resolved.laborSeconds());
    }
}
