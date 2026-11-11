package com.intermarche.e2e.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.OrderRecorder;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
}
