package com.intermarche.e2e.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.commerce.CommercePickupState;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.policy.OperationalPolicyVersion;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.OrderRecorder;
import com.intermarche.backoffice.engine.catering.CateringUnitStateService;
import com.intermarche.backoffice.engine.policy.OperationalPolicySchemas;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle tests for the catering article and its unit (§ 11.1, {@code CateringUnitLifecycleTest}):
 * F1 (active and deactivated datasheet) and F2 (resolved and frozen mode, unresolved station). The
 * state machine (F3) and stocked articles (F11) are covered in later packets.
 */
@QuarkusTest
public class CateringUnitLifecycleTest {

    private static final String KEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Inject
    OrderRecorder recorder;
    @Inject
    ObjectMapper mapper;
    @Inject
    CateringUnitStateService states;

    /**
     * Builds a catering order payload referencing a single catering line.
     *
     * @param orderId   the order id.
     * @param storeCode the store code.
     * @param ean       the catering line EAN.
     * @param lineId    the catering line id.
     * @return the parsed payload.
     */
    private JsonNode payload(String orderId, String storeCode, String ean, String lineId) {
        try {
            return mapper.readTree("""
                    {
                      "orderHeader": { "orderId": "%s", "createdAt": "2026-10-14T10:00:00Z", "channel": "ECOMMERCE", "status": "VALIDATED",
                        "store": { "code": "%s" } },
                      "customer": { "customerId": "C1", "firstName": "Jane", "lastName": "Smith", "email": "jane@test.com",
                        "address": { "streetLine1": "1 Rue", "postalCode": "75001", "city": "Paris", "country": "France" } },
                      "fulfillment": { "type": "DRIVE", "slot": { "start": "2026-10-16T10:00:00Z", "end": "2026-10-16T12:00:00Z" } },
                      "catering": { "contractVersion": "1" },
                      "lines": [ { "lineNumber": 1, "product": { "ean": "%s", "name": "Article", "productType": "UNIT" }, "requestedQuantity": 1,
                        "catering": { "lineId": "%s", "variant": { "id": "32" }, "configurationKey": "%s" } } ],
                      "summary": { "totalItems": 1 }
                    }
                    """.formatted(orderId, storeCode, ean, lineId, KEY));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a store with a catering article datasheet.
     *
     * @param storeCode the store code.
     * @param ean       the article EAN.
     * @param mode      the datasheet mode.
     * @param active    whether the datasheet is active.
     * @return the store.
     */
    private Store storeWithItem(String storeCode, String ean, SourcingMode mode, boolean active) {
        Store store = DomainUtils.createAndPersistStore(storeCode, 48.0, 2.0);
        Product product = DomainUtils.createAndPersistProduct(ean, "Article", ProductType.UNIT);
        CateringItem item = new CateringItem();
        item.product = product;
        item.sourcingMode = mode;
        item.active = active;
        item.persistAndFlush();
        return store;
    }

    /**
     * T1.057: an active datasheet yields a RECEIVED unit; a deactivated datasheet yields a BLOCKED unit
     * (no new planning), existing units being unaffected.
     */
    @Test
    @TestTransaction
    void activeAndDeactivatedDatasheet() {
        storeWithItem("CUL_1", "CUL-ACTIVE", SourcingMode.FABRICATED, true);
        recorder.persistOrderAndFulfillmentFromJson(payload("CUL-1", "CUL_1", "CUL-ACTIVE", "u-active"));
        assertEquals(CateringUnitState.RECEIVED, CateringUnit.<CateringUnit>find("lineId", "u-active").firstResult().state);

        storeWithItem("CUL_2", "CUL-OFF", SourcingMode.FABRICATED, false);
        recorder.persistOrderAndFulfillmentFromJson(payload("CUL-2", "CUL_2", "CUL-OFF", "u-off"));
        CateringUnit blocked = CateringUnit.find("lineId", "u-off").firstResult();
        assertEquals(CateringUnitState.BLOCKED, blocked.state, "A deactivated datasheet accepts no new planning");
        assertNull(blocked.item);
    }

    /**
     * T1.058: the mode is resolved from the datasheet and frozen on the unit; a later datasheet change
     * does not move an already-received unit.
     */
    @Test
    @TestTransaction
    void modeResolvedThenFrozen() {
        Store store = storeWithItem("CUL_3", "CUL-MODE", SourcingMode.FABRICATED, true);
        recorder.persistOrderAndFulfillmentFromJson(payload("CUL-3", "CUL_3", "CUL-MODE", "u-mode"));
        CateringUnit unit = CateringUnit.find("lineId", "u-mode").firstResult();
        assertEquals(SourcingMode.FABRICATED, unit.sourcingMode);

        CateringItem item = CateringItem.find("product.ean", "CUL-MODE").firstResult();
        item.sourcingMode = SourcingMode.STOCKED;
        item.persistAndFlush();
        assertEquals(SourcingMode.FABRICATED,
                CateringUnit.<CateringUnit>find("lineId", "u-mode").firstResult().sourcingMode,
                "The unit mode is frozen at reception");
    }

    /**
     * T1.059: a FABRICATED datasheet without a resolved station leaves the unit RECEIVED (it stays under
     * CATERING_UNREFERENCED until the datasheet is completed), not BLOCKED.
     */
    @Test
    @TestTransaction
    void fabricatedWithoutStationStaysReceived() {
        storeWithItem("CUL_4", "CUL-NOSTATION", SourcingMode.FABRICATED, true);
        recorder.persistOrderAndFulfillmentFromJson(payload("CUL-4", "CUL_4", "CUL-NOSTATION", "u-nostation"));
        CateringUnit unit = CateringUnit.find("lineId", "u-nostation").firstResult();
        assertEquals(CateringUnitState.RECEIVED, unit.state);
        assertNull(unit.item.station, "The I5-like datasheet has no station yet");
    }

    // ----- F3 state machine (T2.001 .. T2.006, T2.008) -----

    /**
     * Creates an active catering datasheet on a product.
     *
     * @param ean the product EAN.
     * @return the datasheet.
     */
    private CateringItem item(String ean) {
        Product product = DomainUtils.createAndPersistProduct(ean, "Article", ProductType.UNIT);
        CateringItem item = new CateringItem();
        item.product = product;
        item.sourcingMode = SourcingMode.FABRICATED;
        item.active = true;
        item.persistAndFlush();
        return item;
    }

    /**
     * Attaches an active datasheet to the already-referenced product of an EAN.
     *
     * @param ean the product EAN.
     * @return the datasheet.
     */
    private CateringItem attachDatasheet(String ean) {
        CateringItem item = new CateringItem();
        item.product = Product.findByEan(ean);
        item.sourcingMode = SourcingMode.FABRICATED;
        item.active = true;
        item.persistAndFlush();
        return item;
    }

    /**
     * Creates an order carrying one catering unit in a given state.
     *
     * @param store the store.
     * @param id    the order id.
     * @param ean   the unit EAN.
     * @param item  the datasheet, or null for an unreferenced unit.
     * @param state the initial unit state.
     * @return the unit.
     */
    private CateringUnit unit(Store store, String id, String ean, CateringItem item, CateringUnitState state) {
        Order order = new Order();
        order.orderId = id;
        order.orderDate = DateTimeProvider.now();
        order.channel = "ECOMMERCE";
        order.status = "VALIDATED";
        order.store = store;
        OrderLine line = new OrderLine();
        line.lineNumber = 1;
        line.requestedQuantity = new BigDecimal("2");
        Product existing = Product.findByEan(ean);
        line.product = item != null ? item.product
                : existing != null ? existing : DomainUtils.createAndPersistProduct(ean, "Article", ProductType.UNIT);
        order.addLine(line);
        order.persistAndFlush();
        CateringUnit unit = new CateringUnit();
        unit.order = order;
        unit.orderLine = line;
        unit.lineId = id + "-u";
        unit.ean = ean;
        unit.item = item;
        unit.sourcingMode = SourcingMode.FABRICATED;
        unit.quantity = new BigDecimal("2");
        unit.availableAt = DateTimeProvider.now().plusDays(2);
        unit.state = state;
        unit.persistAndFlush();
        return unit;
    }

    /**
     * Returns the published pickup revision of an order, or 0 when none exists yet.
     *
     * @param order the order.
     * @return the revision.
     */
    private long pickupRevision(Order order) {
        CommercePickupState state = CommercePickupState.find("order", order).firstResult();
        return state == null ? 0 : state.revision;
    }

    /**
     * Writes an active {@code CateringConstraints} policy for a store with a single boolean override.
     *
     * @param store the store.
     * @param key   the policy key.
     * @param value the boolean value.
     */
    private void policy(Store store, String key, boolean value) {
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

    /**
     * T2.001/T2.002: each state is reached by its entry event, and each change increments both the
     * unit revision and the published order revision.
     */
    @Test
    @TestTransaction
    void everyStateChangeBumpsBothRevisions() {
        Store store = DomainUtils.createAndPersistStore("CUL_STATE", 48.0, 2.0);
        CateringUnit unit = unit(store, "S1", "CUL-STATE", item("CUL-STATE"), CateringUnitState.RECEIVED);
        Order order = unit.order;
        int unitRevision = unit.revision;
        long pickupRevision = pickupRevision(order);
        for (CateringUnitState next : new CateringUnitState[]{CateringUnitState.PLANNED, CateringUnitState.IN_PROGRESS,
                CateringUnitState.PRODUCED, CateringUnitState.STAGED, CateringUnitState.HANDED_OVER}) {
            switch (next) {
                case PLANNED -> states.markPlanned(unit);
                case IN_PROGRESS -> states.markInProgress(unit);
                case PRODUCED -> states.markProduced(unit);
                case STAGED -> states.markStaged(unit);
                case HANDED_OVER -> states.markHandedOver(unit);
                default -> throw new IllegalStateException();
            }
            assertEquals(next, unit.state);
            assertEquals(unitRevision + 1, unit.revision, "unit revision grows on " + next);
            assertTrue(pickupRevision(order) > pickupRevision, "pickup revision grows on " + next);
            unitRevision = unit.revision;
            pickupRevision = pickupRevision(order);
        }
        // CANCELLED and SHORT are reached from a fresh unit by their own event.
        CateringUnit cancelled = unit(store, "S2", "CUL-STATE-2", item("CUL-STATE-2"), CateringUnitState.RECEIVED);
        states.markCancelled(cancelled);
        assertEquals(CateringUnitState.CANCELLED, cancelled.state);
    }

    /**
     * T2.003: a BLOCKED unit does not stop the other units of the same order.
     */
    @Test
    @TestTransaction
    void blockedUnitDoesNotStopOthers() {
        Store store = DomainUtils.createAndPersistStore("CUL_ISO", 48.0, 2.0);
        CateringItem item = item("CUL-ISO");
        CateringUnit served = unit(store, "ISO", "CUL-ISO", item, CateringUnitState.RECEIVED);
        CateringUnit blocked = new CateringUnit();
        blocked.order = served.order;
        blocked.orderLine = served.orderLine;
        blocked.lineId = "ISO-blocked";
        blocked.ean = "CUL-ISO-UNKNOWN";
        blocked.sourcingMode = SourcingMode.FABRICATED;
        blocked.quantity = BigDecimal.ONE;
        blocked.availableAt = served.availableAt;
        blocked.state = CateringUnitState.BLOCKED;
        blocked.persistAndFlush();

        states.markPlanned(served);

        assertEquals(CateringUnitState.PLANNED, served.state, "The referenced unit advances");
        assertEquals(CateringUnitState.BLOCKED,
                CateringUnit.<CateringUnit>find("lineId", "ISO-blocked").firstResult().state,
                "The blocked unit is untouched");
    }

    /**
     * T2.004: a BLOCKED unit returns to RECEIVED at the first recompute that follows the disappearance
     * of its cause — an unreferenced article now referenced.
     */
    @Test
    @TestTransaction
    void blockedRecoversWhenArticleReferenced() {
        Store store = DomainUtils.createAndPersistStore("CUL_REF", 48.0, 2.0);
        CateringUnit unit = unit(store, "REF", "CUL-REF", null, CateringUnitState.BLOCKED);
        states.recompute(store);
        assertEquals(CateringUnitState.BLOCKED, unit.state, "Still blocked: no datasheet yet");
        attachDatasheet("CUL-REF");
        states.recompute(store);
        CateringUnit recovered = CateringUnit.find("lineId", "REF-u").firstResult();
        assertEquals(CateringUnitState.RECEIVED, recovered.state);
        assertNotNull(recovered.item);
    }

    /**
     * T2.004: a BLOCKED unit returns to RECEIVED once its deactivated datasheet is reactivated.
     */
    @Test
    @TestTransaction
    void blockedRecoversWhenDatasheetReactivated() {
        Store store = DomainUtils.createAndPersistStore("CUL_REACT", 48.0, 2.0);
        CateringItem item = item("CUL-REACT");
        item.active = false;
        item.persistAndFlush();
        CateringUnit unit = unit(store, "REACT", "CUL-REACT", null, CateringUnitState.BLOCKED);
        states.recompute(store);
        assertEquals(CateringUnitState.BLOCKED, unit.state, "Still blocked: datasheet inactive");
        item.active = true;
        item.persistAndFlush();
        states.recompute(store);
        assertEquals(CateringUnitState.RECEIVED, CateringUnit.<CateringUnit>find("lineId", "REACT-u").firstResult().state);
    }

    /**
     * T2.004: a BLOCKED unit returns to RECEIVED once catering is re-enabled; while disabled the
     * recompute leaves it blocked.
     */
    @Test
    @TestTransaction
    void blockedRecoversWhenCateringReEnabled() {
        Store store = DomainUtils.createAndPersistStore("CUL_EN", 48.0, 2.0);
        CateringItem item = item("CUL-EN");
        CateringUnit unit = unit(store, "EN", "CUL-EN", null, CateringUnitState.BLOCKED);
        policy(store, "enabled", false);
        states.recompute(store);
        assertEquals(CateringUnitState.BLOCKED, unit.state, "Still blocked: catering disabled");
        OperationalPolicyVersion.update("active = false where kind = 'CateringConstraints'");
        states.recompute(store);
        assertEquals(CateringUnitState.RECEIVED, CateringUnit.<CateringUnit>find("lineId", "EN-u").firstResult().state);
    }

    /**
     * T2.006: a partially served unit keeps its state and only carries a short quantity; a wholly
     * unserved unit becomes SHORT.
     */
    @Test
    @TestTransaction
    void partialServiceKeepsStateWholeUnservedIsShort() {
        Store store = DomainUtils.createAndPersistStore("CUL_SHORT", 48.0, 2.0);
        CateringUnit partial = unit(store, "PART", "CUL-PART", item("CUL-PART"), CateringUnitState.IN_PROGRESS);
        partial.producedQuantity = BigDecimal.ONE;
        states.recordShort(partial, BigDecimal.ONE);
        assertEquals(CateringUnitState.IN_PROGRESS, partial.state, "A partially served unit keeps its state");
        assertEquals(0, BigDecimal.ONE.compareTo(partial.shortQuantity));

        CateringUnit whole = unit(store, "WHOLE", "CUL-WHOLE", item("CUL-WHOLE"), CateringUnitState.IN_PROGRESS);
        states.recordShort(whole, new BigDecimal("2"));
        assertEquals(CateringUnitState.SHORT, whole.state, "A wholly unserved unit becomes SHORT");
    }

    /**
     * T2.005: a STOCKED unit that recovers from BLOCKED has the line that was routed into the catering
     * chunk returned to a PENDING chunk of its order, so the subsequent fulfillers pick it; its
     * {@code fulfillmentLine} is cleared because a STOCKED unit carries no catering line.
     */
    @Test
    @TestTransaction
    void stockedUnitUnblockedRejoinsPendingChunk() {
        Store store = DomainUtils.createAndPersistStore("CUL_STK", 48.0, 2.0);
        // An inactive STOCKED datasheet: the unit is BLOCKED and recovers once the datasheet is active.
        Product product = DomainUtils.createAndPersistProduct("CUL-STK", "Boissons", ProductType.UNIT);
        CateringItem datasheet = new CateringItem();
        datasheet.product = product;
        datasheet.sourcingMode = SourcingMode.STOCKED;
        datasheet.active = false;
        datasheet.persistAndFlush();

        CateringUnit unit = unit(store, "STK", "CUL-STK", null, CateringUnitState.BLOCKED);
        unit.sourcingMode = SourcingMode.STOCKED;
        unit.persistAndFlush();
        // Reproduce the block-time routing: the line sits in the READY catering chunk, no location.
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = unit.order;
        FulfillmentChunk catering = new FulfillmentChunk();
        catering.type = FulfillmentChunk.READY;
        catering.catering = true;
        catering.label = "Catering";
        fulfillment.addChunk(catering);
        fulfillment.persistAndFlush();
        FulfillmentLine routed = new FulfillmentLine();
        routed.lineNumber = unit.orderLine.lineNumber;
        routed.product = product;
        routed.requestedQuantity = unit.quantity;
        routed.persistAndFlush();
        catering.addLine(routed);
        catering.persistAndFlush();
        unit.fulfillmentLine = routed;
        unit.persistAndFlush();

        datasheet.active = true;
        datasheet.persistAndFlush();
        states.recompute(store);

        CateringUnit recovered = CateringUnit.find("lineId", "STK-u").firstResult();
        assertEquals(CateringUnitState.RECEIVED, recovered.state);
        assertEquals(SourcingMode.STOCKED, recovered.sourcingMode);
        assertNull(recovered.fulfillmentLine, "A STOCKED unit carries no catering line");
        Fulfillment reloaded = Fulfillment.find("order", recovered.order).firstResult();
        FulfillmentChunk pending = reloaded.chunks.stream()
                .filter(c -> FulfillmentChunk.PENDING.equals(c.type)).findFirst().orElse(null);
        assertNotNull(pending, "The products to pick rejoined a PENDING chunk");
        assertTrue(pending.lines.stream().anyMatch(l -> l.product.ean.equals("CUL-STK")),
                "The stocked product is now pickable in the pending chunk");
        assertTrue(reloaded.chunks.stream().noneMatch(c -> c.catering && c.lines.isEmpty()),
                "The emptied catering chunk is dropped");
    }

    /**
     * T2.005: when the order already has a PENDING chunk (its grocery lines), the recovered STOCKED
     * unit's products rejoin that existing chunk rather than a freshly created one.
     */
    @Test
    @TestTransaction
    void stockedUnitUnblockedReusesExistingPendingChunk() {
        Store store = DomainUtils.createAndPersistStore("CUL_STK2", 48.0, 2.0);
        Product product = DomainUtils.createAndPersistProduct("CUL-STK2", "Boissons", ProductType.UNIT);
        CateringItem datasheet = new CateringItem();
        datasheet.product = product;
        datasheet.sourcingMode = SourcingMode.STOCKED;
        datasheet.active = false;
        datasheet.persistAndFlush();

        CateringUnit unit = unit(store, "STK2", "CUL-STK2", null, CateringUnitState.BLOCKED);
        unit.sourcingMode = SourcingMode.STOCKED;
        unit.persistAndFlush();
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = unit.order;
        FulfillmentChunk pending = new FulfillmentChunk();
        pending.type = FulfillmentChunk.PENDING;
        FulfillmentChunk catering = new FulfillmentChunk();
        catering.type = FulfillmentChunk.READY;
        catering.catering = true;
        fulfillment.addChunk(pending);
        fulfillment.addChunk(catering);
        fulfillment.persistAndFlush();
        FulfillmentLine routed = new FulfillmentLine();
        routed.lineNumber = unit.orderLine.lineNumber;
        routed.product = product;
        routed.requestedQuantity = unit.quantity;
        routed.persistAndFlush();
        catering.addLine(routed);
        catering.persistAndFlush();
        unit.fulfillmentLine = routed;
        unit.persistAndFlush();
        long pendingId = pending.id;

        datasheet.active = true;
        datasheet.persistAndFlush();
        states.recompute(store);

        Fulfillment reloaded = Fulfillment.find("order", unit.order).firstResult();
        long pendingChunks = reloaded.chunks.stream().filter(c -> FulfillmentChunk.PENDING.equals(c.type)).count();
        assertEquals(1, pendingChunks, "No new PENDING chunk is created");
        FulfillmentChunk reused = reloaded.chunks.stream()
                .filter(c -> FulfillmentChunk.PENDING.equals(c.type)).findFirst().orElseThrow();
        assertEquals(pendingId, reused.id, "The existing PENDING chunk is reused");
        assertTrue(reused.lines.stream().anyMatch(l -> l.product.ean.equals("CUL-STK2")));
    }

    /**
     * T2.005: a recovered STOCKED unit that was never routed out of the pending flow (no catering
     * line) recovers to RECEIVED without any chunk rewrite.
     */
    @Test
    @TestTransaction
    void stockedUnitUnblockedWithoutRoutedLineIsNoOp() {
        Store store = DomainUtils.createAndPersistStore("CUL_STK3", 48.0, 2.0);
        Product product = DomainUtils.createAndPersistProduct("CUL-STK3", "Boissons", ProductType.UNIT);
        CateringItem datasheet = new CateringItem();
        datasheet.product = product;
        datasheet.sourcingMode = SourcingMode.STOCKED;
        datasheet.active = false;
        datasheet.persistAndFlush();

        CateringUnit unit = unit(store, "STK3", "CUL-STK3", null, CateringUnitState.BLOCKED);
        unit.sourcingMode = SourcingMode.STOCKED;
        unit.fulfillmentLine = null; // never routed (e.g. product was unknown while blocked)
        unit.persistAndFlush();

        datasheet.active = true;
        datasheet.persistAndFlush();
        states.recompute(store);

        CateringUnit recovered = CateringUnit.find("lineId", "STK3-u").firstResult();
        assertEquals(CateringUnitState.RECEIVED, recovered.state);
        assertNull(recovered.fulfillmentLine);
    }
}
