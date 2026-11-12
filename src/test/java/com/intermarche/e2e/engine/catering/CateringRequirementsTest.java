package com.intermarche.e2e.engine.catering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.domain.catering.*;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.policy.OperationalPolicyVersion;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.stock.StockCommitment;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import com.intermarche.backoffice.engine.catering.CateringProposalEngine;
import com.intermarche.backoffice.engine.catering.CateringSupplyService;
import com.intermarche.backoffice.engine.catering.CateringValidationService;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import com.intermarche.backoffice.engine.policy.OperationalPolicySchemas;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import io.quarkus.test.security.TestSecurity;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the component commitments created at validation (F9): a StockCommitment per INGREDIENT
 * component, and the BLOCK versus WARN shortage policy.
 */
@QuarkusTest
public class CateringRequirementsTest {

    @Inject
    CateringProposalEngine engine;
    @Inject
    CateringValidationService validation;
    @Inject
    com.intermarche.backoffice.engine.catering.CateringSupplyService supplyService;
    @Inject
    com.intermarche.backoffice.engine.catering.CateringFabricationResource resource;
    @Inject
    com.intermarche.backoffice.engine.stock.TemporalStockService stocks;
    @Inject
    ObjectMapper mapper;

    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 11, 16, 9, 0);
    private static final LocalDateTime AVAILABLE = LocalDateTime.of(2026, 11, 18, 10, 0);

    private Store store;
    private FabricationStation station;
    private CateringItem item;
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
     * Sets up a store with a building, a station on that building and a FABRICATED article with a
     * base recipe consuming one component.
     *
     * @return the component product.
     */
    private Product setup() {
        store = DomainUtils.createAndPersistStore("REQ-" + (counter++), 48.0, 2.0);
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "REQ-SHOP-" + counter;
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
        Product product = DomainUtils.createAndPersistProduct("REQ-ITEM-" + counter, "Article", ProductType.UNIT);
        item = new CateringItem();
        item.product = product;
        item.sourcingMode = SourcingMode.FABRICATED;
        item.station = station;
        item.shelfLifeHours = 240;
        item.maxAdvanceHours = 24;
        item.active = true;
        item.persistAndFlush();
        Product component = DomainUtils.createAndPersistProduct("REQ-COMP-" + counter, "Farine", ProductType.UNIT);
        CateringRecipe recipe = new CateringRecipe();
        recipe.item = item;
        recipe.variantId = "16";
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
        return component;
    }

    private void unit(String orderId) {
        Order order = new Order();
        order.orderId = orderId;
        order.orderDate = DateTimeProvider.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        OrderLine orderLine = new OrderLine();
        orderLine.lineNumber = 1;
        orderLine.requestedQuantity = BigDecimal.ONE;
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
        unit.variantId = "16";
        unit.quantity = BigDecimal.ONE;
        unit.availableAt = AVAILABLE;
        unit.state = CateringUnitState.RECEIVED;
        unit.persistAndFlush();
    }

    private void policy(String key, String value) {
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

    private CateringValidationService.OrderValidation input(FabricationOrder order) {
        var input = new CateringValidationService.OrderValidation();
        input.id = order.id;
        input.revision = order.version;
        return input;
    }

    /**
     * Returns the minimum projected stock balance across the component's availability timeline. The
     * balance is the non-floored supply minus demand, so a fabrication commitment lowers it even when
     * there is no on-hand stock to promise against.
     *
     * @param accountId  the stock account id.
     * @param buildingId the building id.
     * @param asOf       the reference instant.
     * @return the minimum balance.
     */
    private BigDecimal minBalance(Long accountId, Long buildingId, LocalDateTime asOf) {
        return stocks.availability(accountId, buildingId, asOf).periods.stream()
                .map(period -> period.balance)
                .min(java.util.Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
    }

    private FabricationOrder proposed() {
        engine.recompute(store);
        return FabricationOrder.<FabricationOrder>find("store = ?1 and proposalState = ?2", store,
                FabricationOrder.FabricationProposalState.PROPOSED).firstResult();
    }

    /**
     * F9: validation creates a StockCommitment for the INGREDIENT component (WARN by default accepts a shortage).
     */
    @Test
    @TestTransaction
    void validationCreatesComponentCommitment() {
        setup();
        unit("O1");
        FabricationOrder order = proposed();
        validation.validate(store, List.of(input(order)), true);
        long commitments = StockCommitment.count("reference like 'FAB:%'");
        assertEquals(1, commitments, "One commitment for the single INGREDIENT component");
        StockCommitment commitment = StockCommitment.find("reference like 'FAB:%'").firstResult();
        assertEquals(0, new BigDecimal("2").compareTo(commitment.quantity));
    }

    /**
     * F9: with componentShortagePolicy = BLOCK, an unavailable component refuses the validation.
     */
    @Test
    @TestTransaction
    void blockPolicyRefusesShortage() {
        setup();
        policy("componentShortagePolicy", "BLOCK");
        unit("O1");
        FabricationOrder order = proposed();
        OperatorFailure failure = assertThrows(OperatorFailure.class,
                () -> validation.validate(store, List.of(input(order)), true));
        assertEquals(409, failure.getResponse().getStatus());
        assertEquals(0, StockCommitment.count("reference like 'FAB:%'"), "Nothing committed when blocked");
    }

    /**
     * F9: with componentShortagePolicy = WARN, an unavailable component is accepted.
     */
    @Test
    @TestTransaction
    void warnPolicyAcceptsShortage() {
        setup();
        policy("componentShortagePolicy", "WARN");
        unit("O1");
        FabricationOrder order = proposed();
        validation.validate(store, List.of(input(order)), true);
        assertEquals(1, StockCommitment.count("reference like 'FAB:%'"));
    }

    /**
     * T2.070/T2.071: reducing a line quantity reduces its active commitment, and cancelling the order
     * releases every active commitment, all in the same transaction.
     */
    @Test
    @TestTransaction
    void commitmentsReducedThenCancelled() {
        setup();
        unit("O1");
        FabricationOrder order = proposed();
        validation.validate(store, List.of(input(order)), true);
        assertEquals(1, StockCommitment.count("reference like 'FAB:%' and status = ?1",
                StockCommitment.CommitmentStatus.ACTIVE));
        StockCommitment active = StockCommitment.find("reference like 'FAB:%' and status = ?1",
                StockCommitment.CommitmentStatus.ACTIVE).firstResult();
        assertEquals(0, new BigDecimal("2").compareTo(active.quantity));

        FabricationLine line = FabricationLine.find("order", order).firstResult();
        line.plannedQuantity = new BigDecimal("0.5");
        line.persistAndFlush();
        validation.reduceOrderCommitments(order);
        assertEquals(1, StockCommitment.count("reference like 'FAB:%' and status = ?1",
                StockCommitment.CommitmentStatus.ACTIVE));
        StockCommitment reduced = StockCommitment.find("reference like 'FAB:%' and status = ?1",
                StockCommitment.CommitmentStatus.ACTIVE).firstResult();
        assertEquals(0, BigDecimal.ONE.compareTo(reduced.quantity), "2 x 0.5 = 1");

        validation.cancelOrderCommitments(order);
        assertEquals(0, StockCommitment.count("reference like 'FAB:%' and status = ?1",
                StockCommitment.CommitmentStatus.ACTIVE));
    }

    /**
     * T2.073: the existing stock projection reacts to a fabrication commitment — the component's
     * available-to-promise at the preparation date drops by the committed quantity.
     */
    @Test
    @TestTransaction
    void existingReplenishmentDemandReactsToCommitment() {
        Product component = setup();
        unit("O1");
        FabricationOrder order = proposed();
        var account = stocks.account(store.id, component.id);
        Long buildingId = station.building.id;
        BigDecimal before = minBalance(account.id, buildingId, order.requestedStart);
        assertEquals(0, StockCommitment.count("account = ?1 and status = ?2", account,
                StockCommitment.CommitmentStatus.ACTIVE), "No fabrication demand yet");
        validation.validate(store, List.of(input(order)), true);
        BigDecimal after = minBalance(account.id, buildingId, order.requestedStart);
        assertEquals(1, StockCommitment.count("account = ?1 and status = ?2", account,
                StockCommitment.CommitmentStatus.ACTIVE), "The fabrication commitment is now visible demand");
        assertEquals(0, before.subtract(new BigDecimal("2")).compareTo(after),
                "The existing stock projection reacts: the balance drops by the fabrication demand");
    }

    // ----- F10 purchase needs (T3.001-003, T3.013, T3.014, T3.017) -----

    /**
     * Sets up the store, a building, a station and a SUPPLIED article for the supply-need tests.
     *
     * @param maxAdvanceHours the article maximum advance in hours.
     * @param supplierLeadDays the article supplier lead days.
     * @param supplierName    the supplier name.
     */
    private void suppliedSetup(int maxAdvanceHours, int supplierLeadDays, String supplierName) {
        store = DomainUtils.createAndPersistStore("SUP-" + (counter++), 48.0, 2.0);
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "SUP-SHOP-" + counter;
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
        station.stagingLeadMinutes = 60;
        station.dailyMinutes = 0;
        station.maxOrderMinutes = 600;
        station.persist();
        Product product = DomainUtils.createAndPersistProduct("SUP-ITEM-" + counter, "Tarte", ProductType.UNIT);
        item = new CateringItem();
        item.product = product;
        item.sourcingMode = SourcingMode.SUPPLIED;
        item.station = station;
        item.shelfLifeHours = 48;
        item.maxAdvanceHours = maxAdvanceHours;
        item.supplierLeadDays = supplierLeadDays;
        item.supplierName = supplierName;
        item.active = true;
        item.persistAndFlush();
    }

    /**
     * Persists a SUPPLIED unit on its own order.
     *
     * @param orderId     the order id.
     * @param availableAt the availability instant.
     * @return the unit.
     */
    private CateringUnit suppliedUnit(String orderId, LocalDateTime availableAt) {
        Order order = new Order();
        order.orderId = orderId;
        order.orderDate = DateTimeProvider.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        OrderLine orderLine = new OrderLine();
        orderLine.lineNumber = 1;
        orderLine.requestedQuantity = BigDecimal.ONE;
        orderLine.product = item.product;
        order.addLine(orderLine);
        order.persistAndFlush();
        CateringUnit unit = new CateringUnit();
        unit.order = order;
        unit.orderLine = orderLine;
        unit.lineId = orderId + "-u";
        unit.ean = item.product.ean;
        unit.item = item;
        unit.sourcingMode = SourcingMode.SUPPLIED;
        unit.variantId = "std";
        unit.quantity = BigDecimal.ONE;
        unit.availableAt = availableAt;
        unit.state = CateringUnitState.RECEIVED;
        unit.persistAndFlush();
        return unit;
    }

    /**
     * T3.001/T3.002/T3.014: a SUPPLIED unit yields a PROPOSED need; dueAt = availability − maxAdvance;
     * orderBy = day of dueAt − supplierLeadDays, at the end of that day when no cutoff is set, and at the
     * cutoff time when one is set.
     */
    @Test
    @TestTransaction
    void proposesNeedWithDueAndOrderBy() {
        suppliedSetup(24, 2, "Fournisseur démo");
        suppliedUnit("S1", AVAILABLE); // AVAILABLE = 2026-11-18 10:00
        supplyService.recomputeNeeds(store);
        CateringPurchaseNeed need = CateringPurchaseNeed.find("store", store).firstResult();
        assertNotNull(need);
        assertEquals(CateringPurchaseNeed.PurchaseNeedState.PROPOSED, need.state);
        assertEquals("Fournisseur démo", need.supplierName);
        assertEquals(LocalDateTime.of(2026, 11, 17, 10, 0), need.dueAt, "dueAt = 2026-11-18 10:00 − 24h");
        assertEquals(LocalDateTime.of(2026, 11, 15, 23, 59), need.orderBy, "no cutoff: end of the day 2026-11-15");
        // recompute is idempotent: no duplicate need for the same unit.
        supplyService.recomputeNeeds(store);
        assertEquals(1, CateringPurchaseNeed.count("store", store));
    }

    /**
     * T3.014: with a supplier order cutoff, orderBy is taken at the cutoff time of its day.
     */
    @Test
    @TestTransaction
    void orderByUsesCutoffWhenSet() {
        suppliedSetup(24, 2, "Fournisseur démo");
        policy("supplierOrderCutoff", "14:00");
        suppliedUnit("S1", AVAILABLE);
        supplyService.recomputeNeeds(store);
        CateringPurchaseNeed need = CateringPurchaseNeed.find("store", store).firstResult();
        assertEquals(LocalDateTime.of(2026, 11, 15, 14, 0), need.orderBy, "cutoff 14:00 on 2026-11-15");
    }

    /**
     * T3.003/T3.014: the read groups needs by supplier, article, variant and due day, summing the
     * quantity to order.
     */
    @Test
    @TestTransaction
    @TestSecurity(user = "mgr", roles = {"ADMIN"})
    void purchaseNeedsReadGroupsBySupplierArticleVariantDay() {
        suppliedSetup(24, 2, "Fournisseur démo");
        suppliedUnit("S1", AVAILABLE);
        suppliedUnit("S2", AVAILABLE); // same supplier, article, variant, due day
        supplyService.recomputeNeeds(store);
        List<Map<String, Object>> groups = resource.purchaseNeeds(store.id, null);
        assertEquals(1, groups.size(), "Two needs with the same key collapse into one group");
        Map<String, Object> group = groups.get(0);
        assertEquals("Fournisseur démo", group.get("supplierName"));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) group.get("totalQuantity")));
        assertEquals(2, ((List<?>) group.get("needs")).size());
    }

    /**
     * T3.005: the order form lists, for one supplier and due day, the order lines by article and
     * variant with their summed quantity.
     */
    @Test
    @TestTransaction
    @TestSecurity(user = "mgr", roles = {"ADMIN"})
    void orderFormListsSupplierLinesForDueDay() {
        suppliedSetup(24, 2, "Fournisseur démo");
        suppliedUnit("S1", AVAILABLE);
        suppliedUnit("S2", AVAILABLE);
        supplyService.recomputeNeeds(store);
        String dueDate = AVAILABLE.minusHours(24).toLocalDate().toString(); // 2026-11-17
        Map<String, Object> form = resource.orderForm(store.id, "Fournisseur démo", dueDate);
        assertEquals("Fournisseur démo", form.get("supplier"));
        List<?> lines = (List<?>) form.get("lines");
        assertEquals(1, lines.size(), "Same article and variant collapse into one order line");
        @SuppressWarnings("unchecked")
        Map<String, Object> line = (Map<String, Object>) lines.get(0);
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) line.get("quantity")));
    }

    /**
     * T3.004: validating a proposed need turns it VALIDATED and creates an EXPECTED supply with a
     * CAT:… reference towards the receiving location.
     */
    @Test
    @TestTransaction
    void validateCreatesExpectedSupply() {
        suppliedSetup(24, 2, "Fournisseur démo");
        suppliedUnit("S1", AVAILABLE);
        supplyService.recomputeNeeds(store);
        CateringPurchaseNeed need = CateringPurchaseNeed.find("store", store).firstResult();
        var input = new CateringSupplyService.NeedValidation();
        input.id = need.id;
        input.revision = need.version;
        supplyService.validate(store, List.of(input), true);
        CateringPurchaseNeed reloaded = CateringPurchaseNeed.findById(need.id);
        assertEquals(CateringPurchaseNeed.PurchaseNeedState.VALIDATED, reloaded.state);
        ExpectedSupply supply = reloaded.expectedSupply;
        assertNotNull(supply, "An expected supply is created");
        assertEquals(ExpectedSupply.SupplyStatus.EXPECTED, supply.status);
        assertTrue(supply.reference.startsWith("CAT:"), "CAT:… reference");
        assertEquals(0, need.quantity.compareTo(supply.quantity));
    }

    /**
     * T3.004: a stale revision is refused.
     */
    @Test
    @TestTransaction
    void validateStaleRevisionRefused() {
        suppliedSetup(24, 2, "Fournisseur démo");
        suppliedUnit("S1", AVAILABLE);
        supplyService.recomputeNeeds(store);
        CateringPurchaseNeed need = CateringPurchaseNeed.find("store", store).firstResult();
        var input = new CateringSupplyService.NeedValidation();
        input.id = need.id;
        input.revision = need.version + 99;
        OperatorFailure failure = assertThrows(OperatorFailure.class,
                () -> supplyService.validate(store, List.of(input), true));
        assertEquals(409, failure.getResponse().getStatus());
    }

    /**
     * T3.009: once the expected supply is received through the existing flow, reconciliation turns the
     * need RECEIVED and commits the received quantity for its unit.
     */
    @Test
    @TestTransaction
    void receptionReconcilesNeedAndCommits() {
        suppliedSetup(24, 2, "Fournisseur démo");
        CateringUnit unit = suppliedUnit("S1", AVAILABLE);
        supplyService.recomputeNeeds(store);
        CateringPurchaseNeed need = CateringPurchaseNeed.find("store", store).firstResult();
        var input = new CateringSupplyService.NeedValidation();
        input.id = need.id;
        input.revision = need.version;
        supplyService.validate(store, List.of(input), true);
        ExpectedSupply supply = CateringPurchaseNeed.<CateringPurchaseNeed>findById(need.id).expectedSupply;

        // Receive the supply at a receiving shelf through the existing reception flow.
        StoreShelfLocation shelf = new StoreShelfLocation();
        shelf.area = "RECEP";
        shelf.aisle = "0";
        shelf.unit = "0";
        shelf.position = "1";
        shelf.elevation = "1";
        station.building.locations.add(shelf);
        shelf.persistAndFlush();
        stocks.receive(supply.id, "rcpt-1", need.quantity, DateTimeProvider.now(), true, shelf.id);
        assertEquals(ExpectedSupply.SupplyStatus.RECEIVED, ExpectedSupply.<ExpectedSupply>findById(supply.id).status);

        supplyService.reconcileReceipts(store);
        assertEquals(CateringPurchaseNeed.PurchaseNeedState.RECEIVED,
                CateringPurchaseNeed.<CateringPurchaseNeed>findById(need.id).state);
        assertEquals(1, StockCommitment.count("reference = ?1", "CATSUP:" + unit.id),
                "The received quantity is committed for the unit");
        // Idempotent: a second reconciliation does not commit again.
        supplyService.reconcileReceipts(store);
        assertEquals(1, StockCommitment.count("reference = ?1", "CATSUP:" + unit.id));
    }

    /**
     * T3.013/T3.017: a PROPOSED need past its order-by is at RISK (order late); a VALIDATED need past
     * its due instant is at RISK (delivery late); once the latest supply end is passed the need is
     * CONFIRMED late.
     */
    @Test
    @TestTransaction
    void supplyLateRiskThenConfirmed() {
        suppliedSetup(24, 2, "Fournisseur démo");
        CateringPurchaseNeed need = new CateringPurchaseNeed();
        need.store = store;
        need.unit = suppliedUnit("S1", AVAILABLE); // availableAt 2026-11-18 10:00; latest end 09:00
        need.supplierName = "Fournisseur démo";
        need.quantity = BigDecimal.ONE;
        need.dueAt = LocalDateTime.of(2026, 11, 17, 10, 0);
        need.orderBy = LocalDateTime.of(2026, 11, 15, 23, 59);
        need.state = CateringPurchaseNeed.PurchaseNeedState.PROPOSED;
        need.persistAndFlush();

        // On MONDAY 2026-11-16 09:00: order-by (11-15) passed, still PROPOSED -> order late (RISK).
        assertEquals(CateringSupplyService.SupplyLateState.RISK, supplyService.lateState(need), "order late");

        // Validated, clock at 2026-11-17 12:00: due (10:00) passed, not received -> delivery late (RISK).
        need.state = CateringPurchaseNeed.PurchaseNeedState.VALIDATED;
        need.persistAndFlush();
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 11, 17, 12, 0));
        assertEquals(CateringSupplyService.SupplyLateState.RISK, supplyService.lateState(need), "delivery late");

        // Clock past the latest supply end (2026-11-18 09:00) -> confirmed.
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 11, 18, 10, 0));
        assertEquals(CateringSupplyService.SupplyLateState.CONFIRMED, supplyService.lateState(need), "confirmed late");

        // A received need is never late.
        need.state = CateringPurchaseNeed.PurchaseNeedState.RECEIVED;
        need.persistAndFlush();
        assertEquals(CateringSupplyService.SupplyLateState.NONE, supplyService.lateState(need), "received: on time");
    }
}
