package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.engine.catering.CateringRecipeResolver;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fulfiller that routes catering lines out of the ordinary picking flow (I7).
 * <p>
 * It is run first by {@link com.intermarche.backoffice.engine.FulfillmentEngine}, before the store's
 * configured fulfillers and without being declared in that configuration. A {@code FABRICATED} or
 * {@code SUPPLIED} unit — and any {@code BLOCKED} unit whose product is known — has its line moved
 * into a {@code READY} chunk marked {@code catering}, without a picking location; its lot is created
 * by the existing mechanism but prepared only by fabrication or supplier receipt. A {@code STOCKED}
 * unit has its line replaced by its products to pick (F11): the {@code PICKED} lines of its recipe,
 * multiplied by the unit quantity, or the article itself when it has no recipe. Those products stay
 * in the pending chunk for the subsequent fulfillers.
 */
@ApplicationScoped
public class CateringFulfillerFactory extends BaseFulfillerFactory {

    @Inject
    CateringRecipeResolver recipes;
    @Inject
    ObjectMapper mapper;

    /**
     * Returns the fulfiller name.
     *
     * @return the string {@code "Catering"}.
     */
    @Override
    public String getName() {
        return "Catering";
    }

    /**
     * Builds the catering fulfiller.
     *
     * @param spec the configuration node (ignored).
     * @return a new {@link CateringFulfiller}.
     */
    @Override
    public BaseFulfiller build(JsonNode spec) {
        return new CateringFulfiller(recipes, mapper);
    }

    /**
     * The catering fulfiller implementation.
     */
    public static class CateringFulfiller extends BaseFulfiller {

        private final CateringRecipeResolver recipes;
        private final ObjectMapper mapper;

        /**
         * Builds the fulfiller with its collaborators.
         *
         * @param recipes the recipe resolver, used to expand a stocked unit into its picked products.
         * @param mapper  the JSON mapper, used to parse a unit configuration.
         */
        public CateringFulfiller(CateringRecipeResolver recipes, ObjectMapper mapper) {
            this.recipes = recipes;
            this.mapper = mapper;
        }

        /**
         * Routes the catering lines of a fulfillment (I7): fabricated, supplied and known-blocked lines
         * move to a catering {@code READY} chunk; stocked lines are expanded into their picked products
         * in the pending chunk (F11).
         *
         * @param fulfillment the fulfillment being processed.
         */
        @Override
        public void process(Fulfillment fulfillment) {
            Fulfillment current = Fulfillment.findById(fulfillment.id);
            if (current == null) {
                return;
            }
            List<CateringUnit> units = CateringUnit.list("order", current.order);
            if (units.isEmpty()) {
                return;
            }
            Map<Integer, CateringUnit> byLineNumber = new HashMap<>();
            for (CateringUnit unit : units) {
                if (unit.orderLine != null) {
                    byLineNumber.put(unit.orderLine.lineNumber, unit);
                }
            }

            FulfillmentChunk cateringChunk = null;
            for (FulfillmentChunk chunk : new ArrayList<>(current.chunks)) {
                if (!FulfillmentChunk.PENDING.equals(chunk.type)) {
                    continue;
                }
                List<FulfillmentLine> pickedToAdd = new ArrayList<>();
                Iterator<FulfillmentLine> lineIterator = chunk.lines.iterator();
                while (lineIterator.hasNext()) {
                    FulfillmentLine line = lineIterator.next();
                    CateringUnit unit = byLineNumber.get(line.lineNumber);
                    if (unit == null) {
                        continue;
                    }
                    // A STOCKED unit that is not blocked is replaced by its products to pick (F11).
                    if (unit.sourcingMode == SourcingMode.STOCKED && unit.state != CateringUnitState.BLOCKED) {
                        Map<Product, BigDecimal> picked = pickedProducts(unit);
                        if (picked.isEmpty()) {
                            continue; // no recipe: the article itself is picked, its line stays as is
                        }
                        lineIterator.remove();
                        Panache.getEntityManager().flush();
                        for (Map.Entry<Product, BigDecimal> entry : picked.entrySet()) {
                            FulfillmentLine product = new FulfillmentLine();
                            product.lineNumber = line.lineNumber;
                            product.product = entry.getKey();
                            product.requestedQuantity = entry.getValue();
                            product.persist();
                            pickedToAdd.add(product);
                        }
                        continue;
                    }
                    int lineNumber = line.lineNumber;
                    var product = line.product;
                    var quantity = line.requestedQuantity;
                    // Remove from the source chunk and flush so orphanRemoval deletes the old line
                    // before the copy is created (the chunk-to-line mapping is unidirectional).
                    lineIterator.remove();
                    Panache.getEntityManager().flush();
                    if (cateringChunk == null) {
                        cateringChunk = new FulfillmentChunk();
                        cateringChunk.type = FulfillmentChunk.READY;
                        cateringChunk.catering = true;
                        cateringChunk.label = "Catering";
                        cateringChunk.deliveryDate = chunk.deliveryDate;
                        cateringChunk.persist();
                        current.addChunk(cateringChunk);
                    }
                    FulfillmentLine moved = new FulfillmentLine();
                    moved.lineNumber = lineNumber;
                    moved.product = product;
                    moved.requestedQuantity = quantity;
                    // No picking location: a catering line is prepared by fabrication or supplier receipt.
                    moved.persist();
                    cateringChunk.addLine(moved);
                    unit.fulfillmentLine = moved;
                }
                for (FulfillmentLine product : pickedToAdd) {
                    chunk.addLine(product); // F11: picked products stay in the pending chunk
                }
                Panache.getEntityManager().flush();
            }
            current.chunks.removeIf(c -> FulfillmentChunk.PENDING.equals(c.type) && c.lines.isEmpty());
        }

        /**
         * Resolves the products to pick of a stocked unit (F11): the {@code PICKED} recipe lines times
         * the unit quantity, or an empty map when the unit has no recipe.
         *
         * @param unit the stocked unit.
         * @return the products to pick, empty when the article itself is picked.
         */
        private Map<Product, BigDecimal> pickedProducts(CateringUnit unit) {
            if (unit.item == null) {
                return new HashMap<>();
            }
            return recipes.pickedProducts(unit.item, unit.variantId, configuration(unit), unit.quantity);
        }

        /**
         * Parses the configuration of a unit, or returns null.
         *
         * @param unit the unit.
         * @return the configuration node, or null.
         */
        private JsonNode configuration(CateringUnit unit) {
            if (unit.configuration == null) {
                return null;
            }
            try {
                return mapper.readTree(unit.configuration);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
