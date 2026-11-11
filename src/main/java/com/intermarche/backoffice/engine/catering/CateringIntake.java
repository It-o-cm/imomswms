package com.intermarche.backoffice.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringItemStore;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Catering-aware part of order reception (rules I1, I5, I6, F2).
 * <p>
 * Resolves the catering article and obtention mode of a received line, auto-references an
 * unknown EAN when the policy allows it, and creates the {@link CateringUnit} that keeps the
 * received composition. The matching supervision situations ({@code CATERING_UNREFERENCED},
 * {@code CATERING_DISABLED}) are produced by the supervision service (F22, lot T6); this class
 * sets the resulting unit state.
 */
@ApplicationScoped
public class CateringIntake {

    @Inject
    CateringParameters parameters;

    /**
     * Tells whether a received line is a catering line (I1): it carries a {@code catering} object.
     *
     * @param lineNode the received line node.
     * @return true if the line is a catering line.
     */
    public boolean isCateringLine(JsonNode lineNode) {
        return lineNode.path("catering").isObject();
    }

    /**
     * Resolves the product of a received line, creating it for an unknown catering EAN when the
     * policy {@code autoReferenceUnknownItems} is true (I5).
     * <p>
     * Returns null for a non-catering unknown EAN (the caller keeps the current behaviour) and for
     * an unreferenced catering EAN when auto-referencing is disabled.
     *
     * @param lineNode the received line node.
     * @param store    the store.
     * @return the resolved or created product, or null.
     */
    public Product resolveProduct(JsonNode lineNode, Store store) {
        String ean = lineNode.path("product").path("ean").asText();
        Product product = Product.findByEan(ean);
        if (product != null) {
            return product;
        }
        if (!isCateringLine(lineNode) || !parameters.autoReferenceUnknownItems(store)) {
            return null;
        }
        Product created = new Product();
        created.ean = ean;
        created.name = lineNode.path("product").path("name").asText(ean);
        created.productType = parseType(lineNode.path("product").path("productType").asText("UNIT"));
        created.temperatureZone = parameters.defaultTemperatureZone(store);
        created.active = true;
        created.persist();
        CateringItem item = new CateringItem();
        item.product = created;
        item.sourcingMode = parameters.defaultSourcingMode(store);
        item.active = true;
        item.persist();
        return created;
    }

    /**
     * Parses a product type, defaulting to {@code UNIT} on an unknown value.
     *
     * @param value the raw type value.
     * @return the product type.
     */
    private ProductType parseType(String value) {
        try {
            return ProductType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ProductType.UNIT;
        }
    }

    /**
     * Returns the active catering datasheet of a product, or null (F1).
     *
     * @param product the product, or null.
     * @return the active {@link CateringItem}, or null.
     */
    public CateringItem activeItem(Product product) {
        if (product == null) {
            return null;
        }
        return CateringItem.find("product = ?1 and active = true", product).firstResult();
    }

    /**
     * Resolves the obtention mode of a unit (F2): the store override wins over the datasheet, which
     * wins over the policy default.
     *
     * @param item  the catering datasheet, or null.
     * @param store the store.
     * @return the resolved {@link SourcingMode}.
     */
    public SourcingMode resolveMode(CateringItem item, Store store) {
        if (item != null) {
            CateringItemStore override = CateringItemStore.find("item = ?1 and store = ?2 and active = true", item, store).firstResult();
            if (override != null && override.sourcingMode != null) {
                return override.sourcingMode;
            }
            if (item.sourcingMode != null) {
                return item.sourcingMode;
            }
        }
        return parameters.defaultSourcingMode(store);
    }

    /**
     * Creates and persists the {@link CateringUnit} of a received catering line (I6).
     *
     * @param order       the persisted order.
     * @param orderLine   the order line mirroring the catering line.
     * @param lineNode    the received line node.
     * @param store       the store.
     * @param availableAt the pickup slot start.
     * @return the created unit.
     */
    public CateringUnit createUnit(Order order, OrderLine orderLine, JsonNode lineNode, Store store, LocalDateTime availableAt) {
        JsonNode catering = lineNode.path("catering");
        Product product = orderLine.product;
        CateringItem item = activeItem(product);
        CateringUnit unit = new CateringUnit();
        unit.order = order;
        unit.orderLine = orderLine;
        unit.lineId = catering.path("lineId").asText();
        unit.ean = lineNode.path("product").path("ean").asText();
        unit.item = item;
        unit.sourcingMode = resolveMode(item, store);
        JsonNode variant = catering.path("variant");
        unit.variantId = variant.path("id").asText(null);
        unit.variantLabel = variant.path("label").asText(null);
        unit.configurationKey = catering.path("configurationKey").asText(null);
        if (catering.path("configuration").isObject()) {
            unit.configuration = catering.path("configuration").toString();
        }
        if (catering.path("choiceLabels").isArray()) {
            unit.choiceLabels = catering.path("choiceLabels").toString();
        }
        unit.allergens = catering.path("allergens").asText(null);
        unit.ingredients = catering.path("ingredients").asText(null);
        unit.storage = catering.path("storage").asText(null);
        unit.servingAdvice = catering.path("servingAdvice").asText(null);
        unit.quantity = orderLine.requestedQuantity;
        unit.availableAt = availableAt;
        unit.state = initialState(store, item);
        unit.persist();
        return unit;
    }

    /**
     * Computes the initial unit state: BLOCKED when catering is disabled (CATERING_DISABLED) or the
     * article is unreferenced (I5 with auto-referencing off, or a known product without datasheet),
     * otherwise RECEIVED.
     *
     * @param store the store.
     * @param item  the resolved catering datasheet, or null.
     * @return the initial state.
     */
    private CateringUnitState initialState(Store store, CateringItem item) {
        if (!parameters.enabled(store)) {
            return CateringUnitState.BLOCKED;
        }
        if (item == null) {
            return CateringUnitState.BLOCKED;
        }
        return CateringUnitState.RECEIVED;
    }

    /**
     * Creates the catering units of an order after it is persisted (I6).
     *
     * @param order       the persisted order.
     * @param linesNode   the received lines array.
     * @param availableAt the pickup slot start.
     * @param store       the store.
     */
    public void recordUnits(Order order, JsonNode linesNode, LocalDateTime availableAt, Store store) {
        if (!linesNode.isArray()) {
            return;
        }
        Map<Integer, OrderLine> byLineNumber = new HashMap<>();
        for (OrderLine line : order.lines) {
            byLineNumber.put(line.lineNumber, line);
        }
        int lineNum = 1;
        for (JsonNode lineNode : linesNode) {
            int number = lineNode.path("lineNumber").asInt(lineNum);
            lineNum++;
            if (!isCateringLine(lineNode)) {
                continue;
            }
            OrderLine orderLine = byLineNumber.get(number);
            if (orderLine != null) {
                createUnit(order, orderLine, lineNode, store, availableAt);
            }
        }
    }
}
