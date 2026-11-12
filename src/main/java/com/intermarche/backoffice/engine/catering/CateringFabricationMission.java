package com.intermarche.backoffice.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.FabricationLine;
import com.intermarche.backoffice.domain.catering.FabricationLineUnit;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the operator mission read of a fabrication order (rule F12, T3.024-025).
 * <p>
 * For each fabrication line it serves the article, variant, quantity, frozen choice labels, cake text,
 * allergens, the components with their quantities and locations, the planned time, and, under the line,
 * the units to serve in order of availability with their order reference, slot and quantity. No client
 * identity is ever served (P6, T3.042): no name, address or phone appears in a fabrication mission.
 */
@ApplicationScoped
public class CateringFabricationMission {

    @Inject
    CateringRecipeResolver recipes;
    @Inject
    CateringParameters parameters;
    @Inject
    ObjectMapper mapper;

    /**
     * Builds the fabrication-line projection of an order (T3.024-025).
     *
     * @param order the fabrication order.
     * @return one row per fabrication line.
     */
    public List<Map<String, Object>> lines(FabricationOrder order) {
        Store store = Hibernate.unproxy(order.store, Store.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FabricationLine line : FabricationLine.<FabricationLine>list("order", order)) {
            rows.add(lineView(store, line));
        }
        return rows;
    }

    /**
     * Builds one fabrication-line row and its served units (T3.024-025).
     *
     * @param store the store.
     * @param line  the fabrication line.
     * @return the line projection.
     */
    private Map<String, Object> lineView(Store store, FabricationLine line) {
        CateringItem item = Hibernate.unproxy(line.item, CateringItem.class);
        Product product = Hibernate.unproxy(item.product, Product.class);
        List<FabricationLineUnit> lineUnits = FabricationLineUnit.list("line", line);
        boolean supplied = !lineUnits.isEmpty()
                && Hibernate.unproxy(lineUnits.get(0).unit, CateringUnit.class).sourcingMode == SourcingMode.SUPPLIED;
        CateringUnit first = lineUnits.isEmpty() ? null : Hibernate.unproxy(lineUnits.get(0).unit, CateringUnit.class);

        var row = new LinkedHashMap<String, Object>();
        row.put("lineId", line.id);
        row.put("itemId", item.id);
        row.put("productName", product.name);
        row.put("variantId", line.variantId);
        row.put("plannedQuantity", line.plannedQuantity);
        row.put("choiceLabels", first == null ? null : first.choiceLabels);
        row.put("cakeText", first == null ? null : cakeText(first));
        row.put("allergens", first == null ? null : first.allergens);
        row.put("plannedMinutes", plannedMinutes(store, line, supplied, first));
        row.put("components", components(store, line, supplied, product, first));
        row.put("units", units(lineUnits));
        return row;
    }

    /**
     * Computes the planned minutes of a line: the base/default labour for a supplied line, otherwise the
     * resolved recipe labour, rounded up to the minute.
     *
     * @param store    the store.
     * @param line     the line.
     * @param supplied whether the line is a mise-en-support line.
     * @param first    the first unit, or null.
     * @return the planned minutes.
     */
    private int plannedMinutes(Store store, FabricationLine line, boolean supplied, CateringUnit first) {
        var recipe = recipes.resolve(item(line), line.variantId, configuration(first), line.plannedQuantity,
                parameters.defaultLaborSeconds(store));
        return (int) Math.ceil(recipe.laborSeconds() / 60.0);
    }

    /**
     * Builds the component rows of a line: the received article for a supplied line, otherwise the
     * resolved recipe components, each with its quantity and picking locations.
     *
     * @param store    the store.
     * @param line     the line.
     * @param supplied whether the line is a mise-en-support line.
     * @param product  the article product.
     * @param first    the first unit, or null.
     * @return the component rows.
     */
    private List<Map<String, Object>> components(Store store, FabricationLine line, boolean supplied,
                                                 Product product, CateringUnit first) {
        List<Map<String, Object>> components = new ArrayList<>();
        if (supplied) {
            components.add(component(product, line.plannedQuantity));
            return components;
        }
        var recipe = recipes.resolve(item(line), line.variantId, configuration(first), line.plannedQuantity,
                parameters.defaultLaborSeconds(store));
        for (Map.Entry<Product, BigDecimal> entry : recipe.components().entrySet()) {
            components.add(component(entry.getKey(), entry.getValue()));
        }
        return components;
    }

    /**
     * Builds a component row with its quantity and picking locations.
     *
     * @param component the component product.
     * @param quantity  the quantity for the whole line.
     * @return the component row.
     */
    private Map<String, Object> component(Product component, BigDecimal quantity) {
        var locations = new ArrayList<String>();
        for (BaseLocation location : BaseLocation.<BaseLocation>list("product = ?1", component)) {
            if (location.locationCode != null) {
                locations.add(location.locationCode);
            }
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("ean", component.ean);
        row.put("name", component.name);
        row.put("quantity", quantity);
        row.put("locations", locations);
        return row;
    }

    /**
     * Builds the served-unit rows of a line, ordered by availability, without any client identity (P6).
     *
     * @param lineUnits the line units.
     * @return the unit rows.
     */
    private List<Map<String, Object>> units(List<FabricationLineUnit> lineUnits) {
        List<CateringUnit> units = lineUnits.stream()
                .map(lu -> Hibernate.unproxy(lu.unit, CateringUnit.class))
                .sorted(java.util.Comparator.comparing(u -> u.availableAt))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CateringUnit unit : units) {
            var row = new LinkedHashMap<String, Object>();
            row.put("orderRef", Hibernate.unproxy(unit.order, Order.class).orderId);
            row.put("slot", unit.availableAt);
            row.put("quantity", unit.quantity);
            rows.add(row); // no name, address or phone (P6, T3.042)
        }
        return rows;
    }

    /**
     * Returns the datasheet of a line.
     *
     * @param line the line.
     * @return the datasheet.
     */
    private CateringItem item(FabricationLine line) {
        return Hibernate.unproxy(line.item, CateringItem.class);
    }

    /**
     * Extracts the cake text of a unit from its configuration, or null.
     *
     * @param unit the unit.
     * @return the cake text, or null.
     */
    private String cakeText(CateringUnit unit) {
        JsonNode configuration = configuration(unit);
        if (configuration == null) {
            return null;
        }
        JsonNode text = configuration.path("text");
        return text.isTextual() ? text.asText() : null;
    }

    /**
     * Parses the configuration of a unit, or returns null.
     *
     * @param unit the unit, or null.
     * @return the configuration node, or null.
     */
    private JsonNode configuration(CateringUnit unit) {
        if (unit == null || unit.configuration == null) {
            return null;
        }
        try {
            return mapper.readTree(unit.configuration);
        } catch (Exception e) {
            return null;
        }
    }
}
