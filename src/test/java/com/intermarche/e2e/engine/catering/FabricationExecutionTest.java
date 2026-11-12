package com.intermarche.e2e.engine.catering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringRecipe;
import com.intermarche.backoffice.domain.catering.CateringRecipeLine;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.FabricationStation;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import com.intermarche.backoffice.engine.catering.CateringFabricationMission;
import com.intermarche.backoffice.engine.catering.CateringProposalEngine;
import com.intermarche.backoffice.engine.catering.CateringValidationService;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution tests for a fabrication order (§ 11.1, {@code FabricationExecutionTest}). This packet
 * covers the operator mission read of a fabrication order (F12, T3.024-025) and the absence of any
 * client identity in it (P6, T3.042). The FABRICATE/LOSS/SHORT actions are covered in later packets.
 */
@QuarkusTest
public class FabricationExecutionTest {

    @Inject
    CateringProposalEngine engine;
    @Inject
    CateringValidationService validation;
    @Inject
    CateringFabricationMission mission;
    @Inject
    com.intermarche.backoffice.engine.catering.CateringFabricationExecution execution;
    @Inject
    ObjectMapper mapper;

    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 11, 16, 9, 0);
    private static final LocalDateTime AVAILABLE = LocalDateTime.of(2026, 11, 18, 10, 0);

    private com.intermarche.backoffice.domain.store.Store store;
    private FabricationStation station;
    private CateringItem item;
    private Product component;
    private int counter = 0;

    @BeforeEach
    void fixClock() {
        DateTimeProvider.setFixedDateTime(MONDAY);
    }

    @AfterEach
    void clearClock() {
        DateTimeProvider.clear();
    }

    /**
     * Sets up a store, a station and a FABRICATED article with a base recipe consuming one component.
     */
    private void setup() {
        store = DomainUtils.createAndPersistStore("FEX-" + (counter++), 48.0, 2.0);
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "FEX-SHOP-" + counter;
        store.buildings.add(building);
        building.persist();
        store.persistAndFlush();
        station = new FabricationStation();
        station.store = store;
        station.code = "LAB";
        station.name = "Laboratoire";
        station.active = true;
        station.building = building;
        station.weekdays = "1,2,3,4,5,6,7";
        station.opensAt = LocalTime.of(0, 0);
        station.closesAt = LocalTime.of(23, 59);
        station.dailyMinutes = 0;
        station.maxOrderMinutes = 600;
        station.persist();
        Product product = DomainUtils.createAndPersistProduct("FEX-ITEM-" + counter, "Gâteau chiffre", ProductType.UNIT);
        item = new CateringItem();
        item.product = product;
        item.sourcingMode = SourcingMode.FABRICATED;
        item.station = station;
        item.shelfLifeHours = 240;
        item.maxAdvanceHours = 72;
        item.active = true;
        item.persistAndFlush();
        component = DomainUtils.createAndPersistProduct("FEX-COMP-" + counter, "Génoise", ProductType.UNIT);
        CateringRecipe recipe = new CateringRecipe();
        recipe.item = item;
        recipe.variantId = "15";
        recipe.revision = 1;
        recipe.active = true;
        recipe.laborSeconds = 600;
        recipe.persist();
        CateringRecipeLine line = new CateringRecipeLine();
        line.recipe = recipe;
        line.component = component;
        line.quantity = new BigDecimal("2");
        line.kind = CateringRecipeLine.RecipeLineKind.INGREDIENT;
        line.persistAndFlush();
    }

    /**
     * Persists a FABRICATED unit still RECEIVED, carrying frozen labels, allergens and a cake text.
     *
     * @param orderId the order id.
     * @return the unit.
     */
    private CateringUnit unit(String orderId) {
        return unit(orderId, BigDecimal.ONE);
    }

    /**
     * Persists a FABRICATED unit of a given quantity, carrying frozen labels, allergens and a cake text.
     *
     * @param orderId the order id.
     * @param qty     the unit quantity.
     * @return the unit.
     */
    private CateringUnit unit(String orderId, BigDecimal qty) {
        Order order = new Order();
        order.orderId = orderId;
        order.orderDate = DateTimeProvider.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        OrderLine orderLine = new OrderLine();
        orderLine.lineNumber = 1;
        orderLine.requestedQuantity = qty;
        orderLine.product = item.product;
        order.addLine(orderLine);
        order.persistAndFlush();
        CateringUnit unit = new CateringUnit();
        unit.order = order;
        unit.orderLine = orderLine;
        unit.lineId = orderId + "-u";
        unit.ean = item.product.ean;
        unit.item = item;
        unit.sourcingMode = SourcingMode.FABRICATED;
        unit.variantId = "15";
        unit.quantity = qty;
        unit.availableAt = AVAILABLE;
        unit.allergens = "gluten, œuf";
        unit.choiceLabels = "[\"Chiffre\",\"Chocolat\"]";
        unit.configuration = "{\"shapeId\":\"NUMBER\",\"text\":\"30\",\"flavourId\":\"chocolate\"}";
        unit.state = CateringUnitState.RECEIVED;
        unit.persistAndFlush();
        return unit;
    }

    private CateringValidationService.OrderValidation input(FabricationOrder order) {
        var input = new CateringValidationService.OrderValidation();
        input.id = order.id;
        input.revision = order.version;
        return input;
    }

    private FabricationOrder validatedOrder() {
        engine.recompute(store);
        FabricationOrder order = FabricationOrder.<FabricationOrder>find("store = ?1 and proposalState = ?2", store,
                FabricationOrder.FabricationProposalState.PROPOSED).firstResult();
        validation.validate(store, List.of(input(order)), true);
        return FabricationOrder.findById(order.id);
    }

    /**
     * T3.024/T3.025/T3.042: the fabrication mission read serves, per line, the article, variant,
     * quantity, frozen labels, cake text, allergens, components with quantities, planned time and the
     * served units (order ref, slot, quantity) — and never a client name, address or phone.
     */
    @Test
    @TestTransaction
    void missionReadServesLinesWithoutClientIdentity() {
        setup();
        CateringUnit unit = unit("O1");
        FabricationOrder order = validatedOrder();

        List<Map<String, Object>> lines = mission.lines(order);
        assertEquals(1, lines.size());
        Map<String, Object> line = lines.get(0);
        assertEquals("Gâteau chiffre", line.get("productName"));
        assertEquals("15", line.get("variantId"));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) line.get("plannedQuantity")));
        assertEquals("gluten, œuf", line.get("allergens"));
        assertEquals("30", line.get("cakeText"), "The cake text is served as a fabrication instruction");
        assertTrue((int) line.get("plannedMinutes") >= 10);

        List<?> components = (List<?>) line.get("components");
        assertEquals(1, components.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> comp = (Map<String, Object>) components.get(0);
        assertEquals("FEX-COMP-" + counter, comp.get("ean"));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) comp.get("quantity")));

        List<?> units = (List<?>) line.get("units");
        assertEquals(1, units.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> servedUnit = (Map<String, Object>) units.get(0);
        assertEquals("O1", servedUnit.get("orderRef"));
        assertEquals(AVAILABLE, servedUnit.get("slot"));

        // P6/T3.042: no client identity anywhere in the mission read.
        String serialized = serialize(lines).toLowerCase();
        assertFalse(serialized.contains("firstname"), "no client first name");
        assertFalse(serialized.contains("address"), "no client address");
        assertFalse(serialized.contains("phone"), "no client phone");
    }

    /**
     * T3.029: the first FABRICATE moves the order to PROCESSING and the unit to IN_PROGRESS; the
     * completing quantity moves the unit to PRODUCED.
     */
    @Test
    @TestTransaction
    void fabricateTracksOrderAndUnitState() {
        setup();
        CateringUnit unit = unit("O1", new BigDecimal("2"));
        FabricationOrder order = validatedOrder();
        com.intermarche.backoffice.domain.catering.FabricationLineUnit lineUnit =
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.find("line.order", order).firstResult();

        execution.fabricate(order, lineUnit.id, BigDecimal.ONE);
        assertEquals(FabricationOrder.FabricationProposalState.VALIDATED,
                FabricationOrder.<FabricationOrder>findById(order.id).proposalState);
        assertEquals(com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.PROCESSING,
                FabricationOrder.<FabricationOrder>findById(order.id).status, "The first action starts the order");
        assertEquals(CateringUnitState.IN_PROGRESS, CateringUnit.<CateringUnit>findById(unit.id).state);

        execution.fabricate(order, lineUnit.id, BigDecimal.ONE);
        assertEquals(CateringUnitState.PRODUCED, CateringUnit.<CateringUnit>findById(unit.id).state,
                "The completing quantity moves the unit to PRODUCED");
        assertEquals(0, new BigDecimal("2").compareTo(
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.<com.intermarche.backoffice.domain.catering.FabricationLineUnit>findById(lineUnit.id).producedQuantity));
    }

    /**
     * T3.028: a total fabricated quantity above the planned quantity for the unit is refused with
     * QUANTITY_EXCEEDED.
     */
    @Test
    @TestTransaction
    void fabricateRefusesQuantityExceeded() {
        setup();
        unit("O1", BigDecimal.ONE);
        FabricationOrder order = validatedOrder();
        com.intermarche.backoffice.domain.catering.FabricationLineUnit lineUnit =
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.find("line.order", order).firstResult();
        com.intermarche.backoffice.engine.operator.OperatorFailure failure =
                assertThrows(com.intermarche.backoffice.engine.operator.OperatorFailure.class,
                        () -> execution.fabricate(order, lineUnit.id, new BigDecimal("2")));
        assertEquals(409, failure.getResponse().getStatus());
    }

    /**
     * Serializes a projection to JSON for identity-leak assertions.
     *
     * @param value the value.
     * @return the JSON string.
     */
    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
