package com.intermarche.e2e.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.policy.OperationalPolicyVersion;
import com.intermarche.backoffice.engine.policy.OperationalPolicySchemas;
import io.quarkus.test.TestTransaction;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.OrderRecorder;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import java.math.BigDecimal;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Intake tests for the catering-aware {@code POST /orders} contract (§ 11.1, {@code CateringIntakeTest}):
 * schema extension (I2), structural validation (I3), idempotency (I4), the at-pickup payment block,
 * and non-regression of plain orders (P5). Rules I5/I7/I8 are covered in later packets.
 */
@QuarkusTest
@TestProfile(SchedulerDisabledTestProfile.class)
public class CateringIntakeTest {

    /** A 64-character hexadecimal configuration key. */
    private static final String KEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Inject
    TransactionManager tm;
    @Inject
    OrderRecorder recorder;
    @Inject
    ObjectMapper mapper;

    /**
     * Cleans the database before each test.
     */
    @BeforeEach
    void cleanDatabase() {
        withTransaction(() -> {
            Event.deleteAll();
            OrderRequest.deleteAll();
            com.intermarche.backoffice.domain.commerce.CommerceStoreGrant.deleteAll();
            Store.deleteAll();
            return null;
        });
    }

    /**
     * Runs a unit of work in a transaction.
     *
     * @param runnable the work.
     * @param <R>      the result type.
     * @return the result.
     */
    private <R> R withTransaction(Supplier<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (Exception e) {
            try {
                tm.setRollbackOnly();
            } catch (SystemException ignored) {
                // falls through to rethrow
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a store granted to the integration account "svc".
     *
     * @param code the store code.
     * @return the persisted store.
     */
    private Store createStore(String code) {
        return withTransaction(() -> {
            Store store = DomainUtils.createAndPersistStore(code, 48.0, 2.0);
            DomainUtils.grantStore(store, "svc");
            return store;
        });
    }

    // --------------------------------------------------
    // Payload builders
    // --------------------------------------------------

    /**
     * Builds a base valid order with an empty lines array.
     *
     * @param orderId   the order id.
     * @param storeCode the store code.
     * @return the mutable order node.
     */
    private ObjectNode baseOrder(String orderId, String storeCode) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode header = root.putObject("orderHeader");
        header.put("orderId", orderId).put("createdAt", "2026-10-14T10:00:00Z").put("channel", "ECOMMERCE").put("status", "VALIDATED");
        header.putObject("store").put("code", storeCode).put("name", "Store " + storeCode);
        ObjectNode customer = root.putObject("customer");
        customer.put("customerId", "CUST-1").put("firstName", "Jane").put("lastName", "Smith").put("email", "jane@test.com");
        customer.putObject("address").put("streetLine1", "1 Rue").put("postalCode", "75001").put("city", "Paris").put("country", "France");
        ObjectNode fulfillment = root.putObject("fulfillment");
        fulfillment.put("type", "DRIVE");
        fulfillment.putObject("slot").put("start", "2026-10-15T10:00:00Z").put("end", "2026-10-15T12:00:00Z");
        root.putArray("lines");
        ObjectNode summary = root.putObject("summary");
        summary.put("totalItems", 1).put("totalPriceExclTax", 3.0).put("totalTax", 0.6).put("totalPriceInclTax", 3.6);
        return root;
    }

    /**
     * Builds a grocery (non-catering) line.
     *
     * @param lineNumber the line number.
     * @param ean        the product EAN.
     * @return the line node.
     */
    private ObjectNode groceryLine(int lineNumber, String ean) {
        ObjectNode line = mapper.createObjectNode();
        line.put("lineNumber", lineNumber);
        line.putObject("product").put("ean", ean).put("name", "Milk").put("productType", "UNIT");
        line.put("requestedQuantity", 2);
        return line;
    }

    /**
     * Builds a catering line.
     *
     * @param lineNumber    the line number.
     * @param lineId        the catering line id (UUID).
     * @param configKey     the configuration key, or null to omit it.
     * @param configuration the configuration object, or null to omit it.
     * @return the line node.
     */
    private ObjectNode cateringLine(int lineNumber, String lineId, String configKey, JsonNode configuration) {
        ObjectNode line = mapper.createObjectNode();
        line.put("lineNumber", lineNumber);
        line.putObject("product").put("ean", "DEMO-TRA-PAIN-SURPRISE").put("name", "Pain surprise").put("productType", "UNIT");
        line.put("requestedQuantity", 1);
        ObjectNode catering = line.putObject("catering");
        catering.put("lineId", lineId);
        catering.putObject("variant").put("id", "32").put("label", "32 pièces");
        if (configKey != null) {
            catering.put("configurationKey", configKey);
        }
        if (configuration != null) {
            catering.set("configuration", configuration);
        }
        return line;
    }

    /**
     * Builds a valid root catering block.
     *
     * @return the catering node.
     */
    private ObjectNode rootCatering() {
        ObjectNode catering = mapper.createObjectNode();
        catering.put("contractVersion", "1");
        catering.putObject("reception").put("date", "2026-11-21").put("guests", 12);
        catering.put("pickupPoint", "Accueil drive piéton");
        return catering;
    }

    /**
     * Returns the mutable lines array of a payload.
     *
     * @param root the payload node.
     * @return the lines array.
     */
    private ArrayNode lines(ObjectNode root) {
        return (ArrayNode) root.get("lines");
    }

    /**
     * Submits a payload and returns the HTTP status code.
     *
     * @param root the payload node.
     * @return the status code.
     */
    private int submit(JsonNode root) {
        try {
            return given().body(mapper.writeValueAsString(root)).contentType(ContentType.JSON)
                    .when().post("/orders").getStatusCode();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------
    // T1.027 — non-regression
    // --------------------------------------------------

    /**
     * T1.027: a payload without any added block behaves exactly as before.
     */
    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void plainOrderUnchanged() throws Exception {
        createStore("CAT_INTK1");
        ObjectNode root = baseOrder("CI-001", "CAT_INTK1");
        lines(root).add(groceryLine(1, "1234567890123"));

        given().body(mapper.writeValueAsString(root)).contentType(ContentType.JSON)
                .when().post("/orders").then().statusCode(202).body(containsString("accepted"));

        assertEquals(1, OrderRequest.count());
        assertEquals(1, Event.list("label", "Commande Acceptée").size());
    }

    // --------------------------------------------------
    // T1.028 — payment recorded, no catering unit
    // --------------------------------------------------

    /**
     * T1.028: an at-pickup payment is recorded on the order, and no catering unit is created.
     */
    @Test
    void atPickupPaymentRecordedWithoutUnit() throws Exception {
        ObjectNode root = baseOrder("CI-028", "CAT_INTK1");
        lines(root).add(groceryLine(1, "1234567890123"));
        ObjectNode payment = root.putObject("payment");
        payment.put("method", "AT_PICKUP").put("state", "DUE").put("amount", 148.12);

        Store store = new Store();
        store.code = "CAT_INTK1";
        store.name = "Store";
        Order order = recorder.createOrder(root.path("orderHeader"), root, store, new Customer());

        assertEquals("AT_PICKUP", order.paymentMethod);
        assertEquals("DUE", order.paymentState);
        assertEquals(0, new BigDecimal("148.12").compareTo(order.paymentAmount));
        assertEquals(0, CateringUnit.count(), "A plain order creates no catering unit");
    }

    // --------------------------------------------------
    // T1.029 — AT_PICKUP requires state and amount
    // --------------------------------------------------

    /**
     * T1.029: an AT_PICKUP payment without state or amount is rejected.
     */
    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void atPickupWithoutStateOrAmountRejected() {
        createStore("CAT_INTK1");
        ObjectNode root = baseOrder("CI-029", "CAT_INTK1");
        lines(root).add(groceryLine(1, "1234567890123"));
        root.putObject("payment").put("method", "AT_PICKUP");

        int status = submit(root);
        assertEquals(400, status);
        assertEquals(0, OrderRequest.count());
    }

    // --------------------------------------------------
    // T1.030 — malformed root catering block
    // --------------------------------------------------

    /**
     * T1.030: a malformed root catering block (missing version, or guests out of bounds) is a schema 400.
     */
    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void malformedRootCateringRejected() {
        createStore("CAT_INTK1");

        ObjectNode noVersion = baseOrder("CI-030a", "CAT_INTK1");
        lines(noVersion).add(cateringLine(1, "u1", KEY, null));
        ObjectNode catering = noVersion.putObject("catering");
        catering.putObject("reception").put("date", "2026-11-21").put("guests", 12);
        assertEquals(400, submit(noVersion));

        ObjectNode badGuests = baseOrder("CI-030b", "CAT_INTK1");
        lines(badGuests).add(cateringLine(1, "u1", KEY, null));
        ObjectNode catering2 = rootCatering();
        ((ObjectNode) catering2.get("reception")).put("guests", 2000);
        badGuests.set("catering", catering2);
        assertEquals(400, submit(badGuests));

        assertEquals(0, OrderRequest.count());
    }

    // --------------------------------------------------
    // T1.031 — catering line field out of bounds / pattern
    // --------------------------------------------------

    /**
     * T1.031: a catering line field with a bad pattern (configurationKey, variant.id) is a schema 400.
     */
    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void cateringLineFieldOutOfBoundsRejected() {
        createStore("CAT_INTK1");

        ObjectNode badKey = baseOrder("CI-031a", "CAT_INTK1");
        badKey.set("catering", rootCatering());
        lines(badKey).add(cateringLine(1, "u1", "not-hex", null));
        assertEquals(400, submit(badKey));

        ObjectNode badVariant = baseOrder("CI-031b", "CAT_INTK1");
        badVariant.set("catering", rootCatering());
        ObjectNode line = cateringLine(1, "u1", KEY, null);
        ((ObjectNode) line.get("catering").get("variant")).put("id", "bad id with spaces");
        lines(badVariant).add(line);
        assertEquals(400, submit(badVariant));

        assertEquals(0, OrderRequest.count());
    }

    // --------------------------------------------------
    // T1.032 — I3 structural cases, one per branch
    // --------------------------------------------------

    /**
     * T1.032: duplicate lineId, missing configurationKey, unknown configuration, and a catering line
     * without the root block are each rejected with CATERING_LINE_INVALID.
     */
    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void structuralCateringViolationsRejected() {
        createStore("CAT_INTK1");

        // Duplicate lineId.
        ObjectNode dup = baseOrder("CI-032a", "CAT_INTK1");
        dup.set("catering", rootCatering());
        lines(dup).add(cateringLine(1, "same", KEY, null));
        lines(dup).add(cateringLine(2, "same", KEY, null));
        assertCateringInvalid(dup);

        // Missing configurationKey.
        ObjectNode noKey = baseOrder("CI-032b", "CAT_INTK1");
        noKey.set("catering", rootCatering());
        lines(noKey).add(cateringLine(1, "u1", null, null));
        assertCateringInvalid(noKey);

        // Unknown configuration (neither composition nor cake).
        ObjectNode badConfig = baseOrder("CI-032c", "CAT_INTK1");
        badConfig.set("catering", rootCatering());
        ObjectNode unknown = mapper.createObjectNode();
        unknown.put("foo", "bar");
        lines(badConfig).add(cateringLine(1, "u1", KEY, unknown));
        assertCateringInvalid(badConfig);

        // Catering line without the root catering block.
        ObjectNode noRoot = baseOrder("CI-032d", "CAT_INTK1");
        lines(noRoot).add(cateringLine(1, "u1", KEY, null));
        assertCateringInvalid(noRoot);

        assertEquals(0, OrderRequest.count());
    }

    /**
     * Asserts that a payload is rejected with a 400 carrying CATERING_LINE_INVALID.
     *
     * @param root the payload node.
     */
    private void assertCateringInvalid(JsonNode root) {
        try {
            given().body(mapper.writeValueAsString(root)).contentType(ContentType.JSON)
                    .when().post("/orders").then().statusCode(400).body(containsString("CATERING_LINE_INVALID"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------
    // T1.033 — idempotent replay
    // --------------------------------------------------

    /**
     * T1.033: an identical replay (including with JSON keys in another order) is accepted without
     * creating a second OrderRequest.
     */
    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void identicalReplayIsIdempotent() {
        createStore("CAT_INTK1");
        ObjectNode root = baseOrder("CI-033", "CAT_INTK1");
        root.set("catering", rootCatering());
        ObjectNode composition = mapper.createObjectNode();
        composition.putObject("choices").putArray("step1").add("choice1");
        lines(root).add(cateringLine(1, "unit-1", KEY, composition));

        assertEquals(202, submit(root));
        assertEquals(202, submit(root));
        assertEquals(1, OrderRequest.count(), "Identical replay must not create a second request");

        // Same content, keys in a different order: still idempotent.
        ObjectNode reordered = mapper.createObjectNode();
        reordered.set("summary", root.get("summary"));
        reordered.set("lines", root.get("lines"));
        reordered.set("catering", root.get("catering"));
        reordered.set("fulfillment", root.get("fulfillment"));
        reordered.set("customer", root.get("customer"));
        reordered.set("orderHeader", root.get("orderHeader"));
        assertEquals(202, submit(reordered));
        assertEquals(1, OrderRequest.count(), "A key-reordered replay must be recognised as identical");
    }

    // --------------------------------------------------
    // T1.034 — conflicting replay
    // --------------------------------------------------

    /**
     * T1.034: a different payload under the same order id is rejected with 409 ORDER_ALREADY_SUBMITTED,
     * leaving the original request intact.
     */
    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void conflictingReplayIsRejected() throws Exception {
        createStore("CAT_INTK1");
        ObjectNode root = baseOrder("CI-034", "CAT_INTK1");
        lines(root).add(groceryLine(1, "1234567890123"));
        assertEquals(202, submit(root));
        assertEquals(1, OrderRequest.count());

        ObjectNode different = baseOrder("CI-034", "CAT_INTK1");
        lines(different).add(groceryLine(1, "1234567890123"));
        ((ObjectNode) lines(different).get(0)).put("requestedQuantity", 99);

        given().body(mapper.writeValueAsString(different)).contentType(ContentType.JSON)
                .when().post("/orders").then().statusCode(409).body(containsString("ORDER_ALREADY_SUBMITTED"));
        assertEquals(1, OrderRequest.count(), "The original request must stay intact");
    }

    // --------------------------------------------------
    // Reception-level rules (I5, I6, F2) — recorder called directly
    // --------------------------------------------------

    /**
     * Builds a catering order with a single catering line.
     *
     * @param orderId   the order id.
     * @param storeCode the store code.
     * @param ean       the catering line EAN.
     * @param lineId    the catering line id.
     * @return the payload node.
     */
    private ObjectNode cateringOrder(String orderId, String storeCode, String ean, String lineId) {
        ObjectNode root = baseOrder(orderId, storeCode);
        root.set("catering", rootCatering());
        ObjectNode line = cateringLine(1, lineId, KEY, null);
        ((ObjectNode) line.get("product")).put("ean", ean);
        lines(root).add(line);
        return root;
    }

    /**
     * Persists an active CateringConstraints version with one overridden value.
     *
     * @param store the store.
     * @param key   the parameter key.
     * @param value the value (boolean or string).
     */
    private void persistPolicy(Store store, String key, Object value) {
        try {
            ObjectNode spec = OperationalPolicySchemas.defaults("CateringConstraints");
            if (value instanceof Boolean b) {
                spec.put(key, b);
            } else {
                spec.put(key, String.valueOf(value));
            }
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
     * T1.035: an unknown EAN with auto-referencing creates the product and datasheet, and the unit is RECEIVED.
     */
    @Test
    @TestTransaction
    void unknownEanAutoReferencedCreatesProductAndUnit() {
        Store store = DomainUtils.createAndPersistStore("CAT_INTK2", 48.0, 2.0);
        recorder.persistOrderAndFulfillmentFromJson(cateringOrder("CI-035", "CAT_INTK2", "DEMO-TRA-TEST-035", "u-035"));

        Product product = Product.findByEan("DEMO-TRA-TEST-035");
        assertNotNull(product, "The product must be auto-created");
        assertNotNull(CateringItem.find("product", product).firstResult(), "The catering datasheet must be created");
        CateringUnit unit = CateringUnit.find("lineId", "u-035").firstResult();
        assertNotNull(unit);
        assertEquals(CateringUnitState.RECEIVED, unit.state);
        assertEquals(SourcingMode.FABRICATED, unit.sourcingMode);
        assertEquals("32", unit.variantId);
    }

    /**
     * T1.036: an unknown EAN without auto-referencing creates nothing, and the unit is BLOCKED with a product-less line.
     */
    @Test
    @TestTransaction
    void unknownEanNotReferencedBlocksUnit() {
        Store store = DomainUtils.createAndPersistStore("CAT_INTK3", 48.0, 2.0);
        persistPolicy(store, "autoReferenceUnknownItems", Boolean.FALSE);
        recorder.persistOrderAndFulfillmentFromJson(cateringOrder("CI-036", "CAT_INTK3", "DEMO-TRA-TEST-036", "u-036"));

        assertNull(Product.findByEan("DEMO-TRA-TEST-036"), "No product must be created");
        CateringUnit unit = CateringUnit.find("lineId", "u-036").firstResult();
        assertNotNull(unit);
        assertEquals(CateringUnitState.BLOCKED, unit.state);
        assertNull(unit.item, "No datasheet for an unreferenced unit");
        assertNull(unit.orderLine.product, "The order line keeps no product");
        assertNull(unit.fulfillmentLine, "An unreferenced unit has no lot line");
    }

    /**
     * T1.037: an EAN of 23 characters is accepted and read back.
     */
    @Test
    @TestTransaction
    void longEanAcceptedAndReadBack() {
        Store store = DomainUtils.createAndPersistStore("CAT_INTK4", 48.0, 2.0);
        String ean = "DEMO-TRA-VERRINES-ABCDE"; // 23 characters
        assertEquals(23, ean.length());
        recorder.persistOrderAndFulfillmentFromJson(cateringOrder("CI-037", "CAT_INTK4", ean, "u-037"));

        Product product = Product.findByEan(ean);
        assertNotNull(product, "A 23-character EAN must be accepted");
        assertEquals(ean, CateringUnit.<CateringUnit>find("lineId", "u-037").firstResult().ean);
    }

    /**
     * T1.038: a FRESH temperature zone is accepted (synonym of CHILLED, I8).
     */
    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void freshZoneAccepted() throws Exception {
        createStore("CAT_INTK1");
        ObjectNode root = baseOrder("CI-038", "CAT_INTK1");
        ObjectNode line = groceryLine(1, "1234567890123");
        line.putObject("logistics").put("temperatureZone", "FRESH");
        lines(root).add(line);

        given().body(mapper.writeValueAsString(root)).contentType(ContentType.JSON)
                .when().post("/orders").then().statusCode(202);
    }

    /**
     * T1.039: with catering disabled, a received unit is BLOCKED.
     */
    @Test
    @TestTransaction
    void disabledCateringBlocksUnit() {
        Store store = DomainUtils.createAndPersistStore("CAT_INTK5", 48.0, 2.0);
        persistPolicy(store, "enabled", Boolean.FALSE);
        recorder.persistOrderAndFulfillmentFromJson(cateringOrder("CI-039", "CAT_INTK5", "DEMO-TRA-TEST-039", "u-039"));

        CateringUnit unit = CateringUnit.find("lineId", "u-039").firstResult();
        assertNotNull(unit);
        assertEquals(CateringUnitState.BLOCKED, unit.state);
    }
}
