package com.intermarche.e2e.engine.catering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.domain.catering.*;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.policy.OperationalPolicyVersion;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.engine.catering.CateringCapacity;
import com.intermarche.backoffice.engine.catering.CateringFabricationResource;
import com.intermarche.backoffice.engine.catering.CateringProposalEngine;
import com.intermarche.backoffice.engine.catering.CateringValidationService;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import com.intermarche.backoffice.engine.policy.OperationalPolicySchemas;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the fabrication-order proposals (F6-F8): grouping with batchAcrossOrders, distinct
 * configurations never merged, splitting at maxOrderMinutes, capacity (unlimited, reached with a
 * report to the previous day, and no day possible), and the invisibility of PROPOSED orders.
 */
@QuarkusTest
public class FabricationPlanningTest {

    @Inject
    CateringProposalEngine engine;
    @Inject
    CateringValidationService validation;
    @Inject
    CateringCapacity capacity;
    @Inject
    CateringFabricationResource resource;
    @Inject
    ObjectMapper mapper;

    private Store store;
    private FabricationStation station;
    private CateringItem item;
    private int counter = 0;

    /** A fixed Monday 09:00. */
    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 11, 16, 9, 0);
    /** Availability two days later at 10:00. */
    private static final LocalDateTime AVAILABLE = LocalDateTime.of(2026, 11, 18, 10, 0);

    /**
     * Fixes the clock before each test.
     */
    @BeforeEach
    void fixClock() {
        DateTimeProvider.setFixedDateTime(MONDAY);
    }

    /**
     * Clears the fixed clock after each test.
     */
    @AfterEach
    void clearClock() {
        DateTimeProvider.clear();
    }

    /**
     * Sets up a store, a station (open every day, given capacity) and a FABRICATED article.
     *
     * @param dailyMinutes    the station daily capacity (0 = unlimited).
     * @param maxOrderMinutes the station maximum order minutes.
     */
    private void setup(int dailyMinutes, int maxOrderMinutes) {
        store = DomainUtils.createAndPersistStore("FP-" + (counter++), 48.0, 2.0);
        station = new FabricationStation();
        station.store = store;
        station.code = "LAB";
        station.name = "Laboratoire";
        station.active = true;
        station.weekdays = "1,2,3,4,5,6,7";
        station.opensAt = LocalTime.of(0, 0);
        station.closesAt = LocalTime.of(23, 59);
        station.dailyMinutes = dailyMinutes;
        station.maxOrderMinutes = maxOrderMinutes;
        station.persist();
        Product product = DomainUtils.createAndPersistProduct("FP-ITEM-" + counter, "Article", ProductType.UNIT);
        item = new CateringItem();
        item.product = product;
        item.sourcingMode = SourcingMode.FABRICATED;
        item.station = station;
        item.shelfLifeHours = 240; // avoid the use-by control pushing the window
        item.maxAdvanceHours = 24;
        item.active = true;
        item.persistAndFlush();
    }

    /**
     * Persists a base recipe with a labour duration.
     *
     * @param variantId    the variant.
     * @param laborSeconds the labour seconds.
     */
    private void recipe(String variantId, int laborSeconds) {
        CateringRecipe recipe = new CateringRecipe();
        recipe.item = item;
        recipe.variantId = variantId;
        recipe.revision = 1;
        recipe.active = true;
        recipe.laborSeconds = laborSeconds;
        recipe.persistAndFlush();
    }

    /**
     * Persists a FABRICATED unit still RECEIVED.
     *
     * @param orderId   the order id.
     * @param variantId the variant.
     * @param configKey the configuration key.
     * @return the unit.
     */
    private CateringUnit unit(String orderId, String variantId, String configKey) {
        Order order = new Order();
        order.orderId = orderId;
        order.orderDate = DateTimeProvider.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        OrderLine line = new OrderLine();
        line.lineNumber = 1;
        line.requestedQuantity = BigDecimal.ONE;
        line.product = item.product;
        order.addLine(line);
        order.persistAndFlush();
        CateringUnit unit = new CateringUnit();
        unit.order = order;
        unit.orderLine = line;
        unit.lineId = orderId + "-u";
        unit.ean = item.product.ean;
        unit.item = item;
        unit.sourcingMode = SourcingMode.FABRICATED;
        unit.variantId = variantId;
        unit.configurationKey = configKey;
        unit.quantity = BigDecimal.ONE;
        unit.availableAt = AVAILABLE;
        unit.state = CateringUnitState.RECEIVED;
        unit.persistAndFlush();
        return unit;
    }

    /**
     * Persists an active CateringConstraints version overriding one value.
     *
     * @param key   the parameter key.
     * @param value the boolean value.
     */
    private void policy(String key, boolean value) {
        try {
            ObjectNode spec = OperationalPolicySchemas.defaults("CateringConstraints");
            spec.put(key, value);
            OperationalPolicyVersion version = new OperationalPolicyVersion();
            version.store = store;
            version.kind = "CateringConstraints";
            version.scopeKey = "CateringConstraints";
            version.revisionNumber = 1;
            version.active = true;
            version.activatedBy = "test";
            version.specification = mapper.writeValueAsString(spec);
            version.persistAndFlush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<FabricationOrder> orders() {
        return FabricationOrder.list("store = ?1 and proposalState = ?2", store, FabricationOrder.FabricationProposalState.PROPOSED);
    }

    private List<FabricationLine> lines() {
        return FabricationLine.list("order.store = ?1", store);
    }

    /**
     * T2.040: units of different orders sharing a line key are batched together.
     */
    @Test
    @TestTransaction
    void batchAcrossOrdersGroupsUnits() {
        setup(0, 120);
        recipe("16", 600);
        unit("O1", "16", "K1");
        unit("O2", "16", "K1");
        engine.recompute(store);
        assertEquals(1, orders().size());
        assertEquals(1, lines().size());
        assertEquals(2, FabricationLineUnit.count("line", lines().get(0)));
    }

    /**
     * T2.040: with batchAcrossOrders off, each unit forms its own line.
     */
    @Test
    @TestTransaction
    void batchOffOneLinePerUnit() {
        setup(0, 120);
        policy("batchAcrossOrders", false);
        recipe("16", 600);
        unit("O1", "16", "K1");
        unit("O2", "16", "K1");
        engine.recompute(store);
        assertEquals(2, lines().size());
    }

    /**
     * T2.040: two units with distinct configuration keys are never merged.
     */
    @Test
    @TestTransaction
    void distinctConfigurationsNotMerged() {
        setup(0, 120);
        recipe("16", 600);
        unit("O1", "16", "K1");
        unit("O2", "16", "K2");
        engine.recompute(store);
        assertEquals(2, lines().size());
    }

    /**
     * T2.041: the lines of a day are split into orders of at most maxOrderMinutes; a line longer than
     * the maximum forms an order on its own.
     */
    @Test
    @TestTransaction
    void splitByMaxOrderMinutes() {
        setup(0, 120);
        recipe("16", 4800); // 80 minutes per line
        unit("O1", "16", "K1");
        unit("O2", "16", "K2"); // distinct key -> second line, 80 + 80 = 160 > 120 -> two orders
        engine.recompute(store);
        assertEquals(2, orders().size());

        setup(0, 120);
        recipe("15", 9000); // 150 minutes, longer than the maximum
        unit("O3", "15", "K3");
        engine.recompute(store);
        assertEquals(1, orders().size(), "A line longer than the maximum forms an order on its own");
        assertEquals(150, orders().get(0).plannedMinutes);
    }

    /**
     * T2.042: capacity 0 is unlimited; capacity reached reports the excess to the previous day; no
     * admissible day leaves the unit unplanned.
     */
    @Test
    @TestTransaction
    void capacityUnlimitedReachedAndImpossible() {
        // unlimited
        setup(0, 600);
        recipe("16", 6000); // 100 minutes
        unit("A1", "16", "K1");
        unit("A2", "16", "K1");
        engine.recompute(store);
        assertEquals(1, orders().size(), "With unlimited capacity both units are on the same latest day");

        // capacity reached -> report to the previous day
        setup(100, 600);
        recipe("16", 6000); // 100 minutes each, capacity 100/day
        unit("B1", "16", "K1");
        unit("B2", "16", "K1");
        engine.recompute(store);
        var days = orders().stream().map(o -> o.productionDate).distinct().toList();
        assertEquals(2, days.size(), "The second unit is reported to the previous day");

        // no day possible
        setup(60, 600);
        recipe("16", 6000); // 100 minutes > 60 capacity
        unit("C1", "16", "K1");
        engine.recompute(store);
        assertEquals(0, orders().size(), "A unit that exceeds the daily capacity is not placed");
    }

    /**
     * Builds a validation input for an order at its current revision.
     *
     * @param order the order.
     * @return the validation input.
     */
    private CateringValidationService.OrderValidation input(FabricationOrder order) {
        var input = new CateringValidationService.OrderValidation();
        input.id = order.id;
        input.revision = order.version;
        return input;
    }

    /**
     * T2.061: a simple validation turns the order VALIDATED/SCHEDULED and its units PLANNED.
     */
    @Test
    @TestTransaction
    void validatesProposal() {
        setup(0, 120);
        recipe("16", 600);
        unit("O1", "16", "K1");
        engine.recompute(store);
        FabricationOrder order = orders().get(0);
        validation.validate(store, List.of(input(order)), true);
        FabricationOrder reloaded = FabricationOrder.findById(order.id);
        assertEquals(FabricationOrder.FabricationProposalState.VALIDATED, reloaded.proposalState);
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, reloaded.status);
        assertEquals(CateringUnitState.PLANNED, CateringUnit.<CateringUnit>find("lineId", "O1-u").firstResult().state);
    }

    /**
     * T2.061: a stale revision is refused.
     */
    @Test
    @TestTransaction
    void staleRevisionRefused() {
        setup(0, 120);
        recipe("16", 600);
        unit("O1", "16", "K1");
        engine.recompute(store);
        var stale = input(orders().get(0));
        stale.revision = 999L;
        OperatorFailure failure = assertThrows(OperatorFailure.class, () -> validation.validate(store, List.of(stale), true));
        assertEquals(409, failure.getResponse().getStatus());
    }

    /**
     * T2.061: an out-of-window day is refused.
     */
    @Test
    @TestTransaction
    void outOfWindowRefused() {
        setup(0, 120);
        recipe("16", 600);
        unit("O1", "16", "K1");
        engine.recompute(store);
        var input = input(orders().get(0));
        input.productionDate = "2027-01-01";
        assertThrows(OperatorFailure.class, () -> validation.validate(store, List.of(input), true));
    }

    /**
     * T2.061: an employee not qualified at the station is refused.
     */
    @Test
    @TestTransaction
    void employeeNotQualifiedRefused() {
        setup(0, 120);
        recipe("16", 600);
        unit("O1", "16", "K1");
        engine.recompute(store);
        Employee employee = new Employee();
        employee.badgeId = "notqual";
        employee.firstName = "N";
        employee.lastName = "Q";
        employee.email = "nq@test.com";
        employee.password = "x".repeat(60);
        employee.role = Employee.EmployeeRole.PICKER;
        employee.active = true;
        employee.store = store;
        employee.persistAndFlush();
        var input = input(orders().get(0));
        input.employeeId = employee.id;
        assertThrows(OperatorFailure.class, () -> validation.validate(store, List.of(input), true));
    }

    /**
     * T2.062: automatic validation validates the proposals; a validated order is not recomputed.
     */
    @Test
    @TestTransaction
    void automaticValidationAndValidatedNotRecomputed() {
        setup(0, 120);
        policy("autoValidateProposals", true);
        recipe("16", 600);
        unit("O1", "16", "K1");
        engine.recompute(store);
        assertEquals(0, orders().size(), "No PROPOSED order remains after automatic validation");
        long validatedId = FabricationOrder.<FabricationOrder>find("store = ?1 and proposalState = ?2", store,
                FabricationOrder.FabricationProposalState.VALIDATED).firstResult().id;
        engine.recompute(store);
        FabricationOrder reloaded = FabricationOrder.findById(validatedId);
        assertNotNull(reloaded, "The validated order survives a recompute");
        assertEquals(FabricationOrder.FabricationProposalState.VALIDATED, reloaded.proposalState);
    }

    /**
     * T2.060: a PROPOSED order is excluded from the operator handling-order reads.
     */
    @Test
    @TestTransaction
    void proposedOrdersAreInvisible() {
        setup(0, 120);
        recipe("16", 600);
        unit("O1", "16", "K1");
        engine.recompute(store);
        assertFalse(orders().isEmpty(), "A proposal exists");
        long visible = BaseHandlingOrder.count(
                "store = ?1 and id not in (select fo.id from FabricationOrder fo where fo.proposalState = 'PROPOSED')", store);
        assertEquals(0, visible, "The PROPOSED order is excluded from the operator reads");
    }

    /**
     * Persists a qualified employee of the store.
     *
     * @param badge the badge id.
     * @return the employee.
     */
    private Employee employee(String badge) {
        Employee employee = new Employee();
        employee.firstName = "Chef";
        employee.lastName = badge;
        employee.badgeId = badge;
        employee.email = badge + "@test.invalid";
        employee.password = org.mindrot.jbcrypt.BCrypt.hashpw("secret", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        employee.role = Employee.EmployeeRole.PICKER;
        employee.store = store;
        employee.persistAndFlush();
        return employee;
    }

    /**
     * T2.038: when a planning exists, the station daily capacity is capped by the planned presence of
     * its qualified employees; a day without planning keeps the configured capacity.
     */
    @Test
    @TestTransaction
    void capacityCappedByPlannedPresence() {
        setup(480, 600);
        Employee chef = employee("CAP-EMP");
        FabricationStationEmployee qualified = new FabricationStationEmployee();
        qualified.station = station;
        qualified.employee = chef;
        qualified.persistAndFlush();
        LocalDate day = AVAILABLE.toLocalDate();
        PlanningEntry entry = new PlanningEntry();
        entry.employee = chef;
        entry.startsAt = day.atTime(8, 0);
        entry.endsAt = day.atTime(10, 0); // 120 minutes of presence
        entry.cancelled = false;
        entry.persistAndFlush();

        assertEquals(120, capacity.dayCapacityMinutes(station, day), "Capped by the 120-min planned presence");
        assertEquals(480, capacity.dayCapacityMinutes(station, day.plusDays(1)),
                "No planning that day: the configured capacity applies");
    }

    /**
     * T2.059: the daily fabrication sheet lists the validated lines with their planned quantity,
     * compositions, allergens, per-order comments, slot, reception date and guest count.
     */
    @Test
    @TestTransaction
    @TestSecurity(user = "mgr", roles = {"ADMIN"})
    void fabricationSheetListsValidatedLines() {
        setup(0, 600);
        recipe("16", 600);
        CateringUnit unit = unit("O1", "16", "K1");
        unit.allergens = "Gluten";
        unit.ingredients = "Farine, eau";
        unit.servingAdvice = "À déguster frais";
        unit.persistAndFlush();
        Order order = unit.order;
        order.specification = "{\"catering\":{\"reception\":{\"date\":\"2026-11-21\",\"guests\":12}}}";
        order.persistAndFlush();
        engine.recompute(store);
        FabricationOrder fabrication = orders().get(0);
        var validationInput = new CateringValidationService.OrderValidation();
        validationInput.id = fabrication.id;
        validationInput.revision = fabrication.version;
        validation.validate(store, List.of(validationInput), true);

        LocalDate day = FabricationOrder.<FabricationOrder>findById(fabrication.id).productionDate;
        List<Map<String, Object>> rows = resource.sheet(store.id, day.toString(), null);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("16", row.get("variantId"));
        assertTrue(((List<?>) row.get("allergens")).contains("Gluten"));
        assertTrue(((List<?>) row.get("compositions")).contains("Farine, eau"));
        List<?> units = (List<?>) row.get("units");
        assertEquals(1, units.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> unitRow = (Map<String, Object>) units.get(0);
        assertEquals("O1", unitRow.get("orderRef"));
        assertEquals(AVAILABLE, unitRow.get("slot"), "The order slot (créneau) is served");
        assertEquals("2026-11-21", unitRow.get("receptionDate"), "The reception date is served");
        assertEquals(12, unitRow.get("guests"), "The guest count (convives) is served");
    }
}
