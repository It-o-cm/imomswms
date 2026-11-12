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
    com.intermarche.backoffice.engine.stock.TemporalStockService stocksService;
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
        // Seed the component stock in the station building so FABRICATE can draw it (F13).
        componentLocation = new com.intermarche.backoffice.domain.store.StoreShelfLocation();
        componentLocation.area = "LAB";
        componentLocation.aisle = "1";
        componentLocation.unit = "1";
        componentLocation.position = "1";
        componentLocation.elevation = "1";
        componentLocation.product = component;
        building.locations.add(componentLocation);
        componentLocation.persistAndFlush();
        var account = stocksService.account(store.id, component.id);
        stocksService.setPhysical(account.id, componentLocation.id, "seed-" + counter,
                BigDecimal.ZERO, new BigDecimal("100"), "seed");
    }

    private com.intermarche.backoffice.domain.store.StoreShelfLocation componentLocation;

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
     * T3.043/T3.044: the first fabricated quantity of a line fixes producedAt (server time), useBy
     * (producedAt + shelf life) and lotCode (order code - line rank); a later quantity does not change them.
     */
    @Test
    @TestTransaction
    void fabricateStampsTraceabilityOnFirstQuantity() {
        setup();
        unit("O1", new BigDecimal("2"));
        FabricationOrder order = validatedOrder();
        com.intermarche.backoffice.domain.catering.FabricationLineUnit lineUnit =
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.find("line.order", order).firstResult();
        execution.fabricate(order, lineUnit.id, BigDecimal.ONE);
        com.intermarche.backoffice.domain.catering.FabricationLine line =
                com.intermarche.backoffice.domain.catering.FabricationLine.find("order", order).firstResult();
        assertEquals(MONDAY, line.producedAt, "producedAt is the server time of the first quantity");
        assertEquals(MONDAY.plusHours(240), line.useBy, "useBy = producedAt + shelf life (240h)");
        assertEquals(order.code + "-1", line.lotCode, "lotCode = order code - line rank");

        execution.fabricate(order, lineUnit.id, BigDecimal.ONE);
        com.intermarche.backoffice.domain.catering.FabricationLine reloaded =
                com.intermarche.backoffice.domain.catering.FabricationLine.find("order", order).firstResult();
        assertEquals(MONDAY, reloaded.producedAt, "A later quantity does not move producedAt");
    }

    /**
     * T3.046 (primitive): produce() enters finished-goods stock under a manufacturer lot at a location,
     * creating the lot on first use and being idempotent on the event key.
     */
    @Test
    @TestTransaction
    void produceEntersFinishedStockUnderLot() {
        setup();
        com.intermarche.backoffice.domain.store.StoreShelfLocation loc =
                new com.intermarche.backoffice.domain.store.StoreShelfLocation();
        loc.area = "SUP";
        loc.aisle = "1";
        loc.unit = "1";
        loc.position = "1";
        loc.elevation = "1";
        loc.product = item.product;
        station.building.locations.add(loc);
        loc.persistAndFlush();
        var account = stocksService.account(store.id, item.product.id);

        com.intermarche.backoffice.domain.stock.ManufacturerLot lot = stocksService.produce(account.id, loc.id,
                "LOT-FAB-1", MONDAY.toLocalDate(), MONDAY.plusHours(240).toLocalDate(), new BigDecimal("3"), "k1", MONDAY);
        assertEquals("LOT-FAB-1", lot.code);
        assertEquals(0, new BigDecimal("3").compareTo(positionQuantity(account.id, loc.id)), "3 produced into stock");

        stocksService.produce(account.id, loc.id, "LOT-FAB-1", MONDAY.toLocalDate(),
                MONDAY.plusHours(240).toLocalDate(), new BigDecimal("3"), "k1", MONDAY);
        assertEquals(0, new BigDecimal("3").compareTo(positionQuantity(account.id, loc.id)), "idempotent on the event key");
        assertEquals(1, com.intermarche.backoffice.domain.stock.ManufacturerLot.count("code = ?1", "LOT-FAB-1"));
    }

    /**
     * Returns the stock balance of an account at a location.
     *
     * @param accountId  the stock account id.
     * @param locationId the location id.
     * @return the balance, or zero.
     */
    private BigDecimal positionQuantity(Long accountId, Long locationId) {
        return stocksService.positions(accountId).stream()
                .filter(p -> p.locationId.equals(locationId))
                .map(p -> p.quantity)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    /**
     * T3.045/T3.051: FABRICATE draws each component out of stock against its commitment (the commitment
     * is reduced) and records a FabricationConsumption, in the action's transaction.
     */
    @Test
    @TestTransaction
    void fabricateConsumesComponentsAndReleasesCommitment() {
        setup();
        unit("O1", BigDecimal.ONE);
        FabricationOrder order = validatedOrder();
        com.intermarche.backoffice.domain.stock.StockCommitment commitment =
                com.intermarche.backoffice.domain.stock.StockCommitment.find("reference like 'FAB:%' and status = ?1",
                        com.intermarche.backoffice.domain.stock.StockCommitment.CommitmentStatus.ACTIVE).firstResult();
        assertNotNull(commitment, "A component commitment exists after validation");
        assertEquals(0, new BigDecimal("2").compareTo(commitment.remainingQuantity), "recipe: 2 per unit");

        com.intermarche.backoffice.domain.catering.FabricationLineUnit lineUnit =
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.find("line.order", order).firstResult();
        execution.fabricate(order, lineUnit.id, BigDecimal.ONE);

        com.intermarche.backoffice.domain.stock.StockCommitment after =
                com.intermarche.backoffice.domain.stock.StockCommitment.findById(commitment.id);
        assertEquals(0, BigDecimal.ZERO.compareTo(after.remainingQuantity), "The commitment is drawn down");
        assertEquals(com.intermarche.backoffice.domain.stock.StockCommitment.CommitmentStatus.PREPARED, after.status);
        assertEquals(1, com.intermarche.backoffice.domain.catering.FabricationConsumption.count("line.order = ?1", order),
                "A consumption is recorded");
    }

    /**
     * T3.030: FABRICATION_LOSS records the lost quantity on the line and leaves the quantity still to
     * fabricate unchanged.
     */
    @Test
    @TestTransaction
    void declareLossRecordsLossKeepsRemainder() {
        setup();
        unit("O1", new BigDecimal("2"));
        FabricationOrder order = validatedOrder();
        com.intermarche.backoffice.domain.catering.FabricationLineUnit lineUnit =
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.find("line.order", order).firstResult();
        execution.declareLoss(order, lineUnit.id, BigDecimal.ONE, "Raté", "FEX-OP", "k-loss-1");
        com.intermarche.backoffice.domain.catering.FabricationLine line =
                com.intermarche.backoffice.domain.catering.FabricationLine.find("order", order).firstResult();
        assertEquals(0, BigDecimal.ONE.compareTo(line.lostQuantity), "The loss is recorded");
        assertEquals(0, BigDecimal.ZERO.compareTo(
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.<com.intermarche.backoffice.domain.catering.FabricationLineUnit>findById(lineUnit.id).producedQuantity),
                "The quantity still to fabricate is unchanged");
        assertEquals(1, com.intermarche.backoffice.domain.fieldops.FieldIncident.count(
                "kind = ?1 and handlingOrder = ?2", "FABRICATION_LOSS", order), "An incident is opened");
        // Idempotent on the request key.
        execution.declareLoss(order, lineUnit.id, BigDecimal.ONE, "Raté", "FEX-OP", "k-loss-1");
        assertEquals(1, com.intermarche.backoffice.domain.fieldops.FieldIncident.count(
                "kind = ?1 and handlingOrder = ?2", "FABRICATION_LOSS", order));
    }

    /**
     * T3.032: FABRICATION_SHORT on the whole quantity moves a wholly unserved unit to SHORT.
     */
    @Test
    @TestTransaction
    void declareShortWholeUnitBecomesShort() {
        setup();
        CateringUnit unit = unit("O1", BigDecimal.ONE);
        FabricationOrder order = validatedOrder();
        com.intermarche.backoffice.domain.catering.FabricationLineUnit lineUnit =
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.find("line.order", order).firstResult();
        execution.declareShort(order, lineUnit.id, BigDecimal.ONE, "Composant manquant", "FEX-OP", "k-short-1");
        assertEquals(CateringUnitState.SHORT, CateringUnit.<CateringUnit>findById(unit.id).state);
        com.intermarche.backoffice.domain.catering.FabricationLine line =
                com.intermarche.backoffice.domain.catering.FabricationLine.find("order", order).firstResult();
        assertEquals(0, BigDecimal.ONE.compareTo(line.shortQuantity));
        assertEquals(1, com.intermarche.backoffice.domain.fieldops.FieldIncident.count(
                "kind = ?1 and handlingOrder = ?2", "FABRICATION_SHORT", order), "An incident is opened");
    }

    /**
     * T3.031: a partially fabricated unit that is then declared short for the rest keeps its state and
     * only carries a short quantity.
     */
    @Test
    @TestTransaction
    void declareShortPartialKeepsState() {
        setup();
        CateringUnit unit = unit("O1", new BigDecimal("2"));
        FabricationOrder order = validatedOrder();
        com.intermarche.backoffice.domain.catering.FabricationLineUnit lineUnit =
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.find("line.order", order).firstResult();
        execution.fabricate(order, lineUnit.id, BigDecimal.ONE);
        execution.declareShort(order, lineUnit.id, BigDecimal.ONE);
        CateringUnit reloaded = CateringUnit.findById(unit.id);
        assertEquals(CateringUnitState.IN_PROGRESS, reloaded.state, "A partially served unit keeps its state");
        assertEquals(0, BigDecimal.ONE.compareTo(reloaded.shortQuantity));
    }

    /**
     * T3.034: CONCLUDE is refused while a line unit still has a remainder neither fabricated nor declared
     * short, and accepted once every remainder is covered.
     */
    @Test
    @TestTransaction
    void concludeRefusedUntilComplete() {
        setup();
        unit("O1", new BigDecimal("2"));
        FabricationOrder order = validatedOrder();
        com.intermarche.backoffice.domain.catering.FabricationLineUnit lineUnit =
                com.intermarche.backoffice.domain.catering.FabricationLineUnit.find("line.order", order).firstResult();
        com.intermarche.backoffice.domain.executing.FabricationExecution exec =
                new com.intermarche.backoffice.domain.executing.FabricationExecution(order, employee("FEX-OP"));
        exec.persistAndFlush();

        execution.fabricate(order, lineUnit.id, BigDecimal.ONE);
        assertThrows(com.intermarche.backoffice.engine.operator.OperatorFailure.class,
                () -> execution.conclude(exec, order), "A remainder blocks the conclusion");

        execution.fabricate(order, lineUnit.id, BigDecimal.ONE);
        execution.conclude(exec, order);
        assertEquals(com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus.DONE,
                FabricationOrder.<FabricationOrder>findById(order.id).status);
    }

    /**
     * Persists a PICKER employee of the store.
     *
     * @param badge the badge id.
     * @return the employee.
     */
    private com.intermarche.backoffice.domain.store.Employee employee(String badge) {
        com.intermarche.backoffice.domain.store.Employee employee = new com.intermarche.backoffice.domain.store.Employee();
        employee.firstName = "Chef";
        employee.lastName = badge;
        employee.badgeId = badge;
        employee.email = badge + "@test.invalid";
        employee.password = org.mindrot.jbcrypt.BCrypt.hashpw("secret", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        employee.role = com.intermarche.backoffice.domain.store.Employee.EmployeeRole.PICKER;
        employee.store = store;
        employee.persistAndFlush();
        return employee;
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
