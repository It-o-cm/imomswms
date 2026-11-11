package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
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
 * unit is left in the pending chunk for the subsequent fulfillers (F11).
 */
@ApplicationScoped
public class CateringFulfillerFactory extends BaseFulfillerFactory {

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
        return new CateringFulfiller();
    }

    /**
     * The catering fulfiller implementation.
     */
    public static class CateringFulfiller extends BaseFulfiller {

        /**
         * Routes the catering lines of a fulfillment into a catering READY chunk (I7).
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
                Iterator<FulfillmentLine> lineIterator = chunk.lines.iterator();
                while (lineIterator.hasNext()) {
                    FulfillmentLine line = lineIterator.next();
                    CateringUnit unit = byLineNumber.get(line.lineNumber);
                    if (unit == null) {
                        continue;
                    }
                    // A STOCKED unit that is not blocked is left for the subsequent fulfillers (F11).
                    if (unit.sourcingMode == SourcingMode.STOCKED && unit.state != CateringUnitState.BLOCKED) {
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
                Panache.getEntityManager().flush();
            }
            current.chunks.removeIf(c -> FulfillmentChunk.PENDING.equals(c.type) && c.lines.isEmpty());
        }
    }
}
