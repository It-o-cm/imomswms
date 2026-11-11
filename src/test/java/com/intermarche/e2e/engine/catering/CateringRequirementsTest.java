package com.intermarche.e2e.engine.catering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.domain.catering.*;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.policy.OperationalPolicyVersion;
import com.intermarche.backoffice.domain.stock.StockCommitment;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import com.intermarche.backoffice.engine.catering.CateringProposalEngine;
import com.intermarche.backoffice.engine.catering.CateringValidationService;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import com.intermarche.backoffice.engine.policy.OperationalPolicySchemas;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
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
}
