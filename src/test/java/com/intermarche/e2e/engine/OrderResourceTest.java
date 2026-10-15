package com.intermarche.e2e.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.OrderResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the {@link OrderResource} REST endpoint ({@code POST /orders}).
 * <p>
 * Unlike the GraphQL resource tests, this endpoint is exercised over HTTP with RestAssured,
 * so fixtures must be committed (no {@code @TestTransaction}): the endpoint runs in its own
 * transaction on the server side and must be able to see the store. The database is cleaned
 * before each test.
 * <p>
 * Covered paths: 202 acceptance (OrderRequest + success event), 400 on malformed JSON,
 * 400 on schema violation (rejection event), 400 on missing mandatory business fields
 * (rejection event), and security (401/403).
 */
@QuarkusTest
@TestProfile(SchedulerDisabledTestProfile.class)
public class OrderResourceTest {

    @Inject
    TransactionManager tm;

    @BeforeEach
    void cleanDatabase() {
        withTransaction(() -> {
            Event.deleteAll(); // events reference stores: delete them first
            OrderRequest.deleteAll();
            Store.deleteAll();
            return null;
        });
    }

    private <R> R withTransaction(Supplier<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (NotSupportedException | SystemException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            try {
                tm.setRollbackOnly();
            } catch (SystemException ex) {
                throw new RuntimeException(e);
            }
            throw new RuntimeException(e);
        }
    }

    private Store createStore(String code) {
        return withTransaction(() -> DomainUtils.createAndPersistStore(code, 48.0, 2.0));
    }

    private String validPayload(String orderId, String storeCode) {
        return """
                {
                  "orderHeader": {
                    "orderId": "%s",
                    "createdAt": "2026-10-14T10:00:00Z",
                    "channel": "ECOMMERCE",
                    "status": "VALIDATED",
                    "store": { "code": "%s", "name": "Store %s" }
                  },
                  "customer": {
                    "customerId": "CUST-1",
                    "firstName": "Jane",
                    "lastName": "Smith",
                    "email": "jane.smith@test.com",
                    "address": { "streetLine1": "1 Rue de Paris", "postalCode": "75001", "city": "Paris", "country": "France" }
                  },
                  "fulfillment": {
                    "type": "HOME_DELIVERY",
                    "slot": { "start": "2026-10-15T10:00:00Z", "end": "2026-10-15T12:00:00Z" }
                  },
                  "lines": [
                    { "lineNumber": 1, "product": { "ean": "1234567890123", "name": "Milk", "productType": "UNIT" }, "requestedQuantity": 2 }
                  ],
                  "summary": { "totalItems": 2, "totalPriceExclTax": 3.0, "totalTax": 0.6, "totalPriceInclTax": 3.6 }
                }
                """.formatted(orderId, storeCode, storeCode);
    }

    /**
     * A syntactically valid JSON payload that violates the schema: the mandatory
     * "customer" section is missing.
     */
    private String schemaInvalidPayload(String orderId, String storeCode) {
        return """
                {
                  "orderHeader": {
                    "orderId": "%s",
                    "createdAt": "2026-10-14T10:00:00Z",
                    "channel": "ECOMMERCE",
                    "status": "VALIDATED",
                    "store": { "code": "%s", "name": "Store %s" }
                  },
                  "fulfillment": {
                    "type": "HOME_DELIVERY",
                    "slot": { "start": "2026-10-15T10:00:00Z", "end": "2026-10-15T12:00:00Z" }
                  },
                  "lines": [
                    { "lineNumber": 1, "product": { "ean": "1234567890123", "name": "Milk", "productType": "UNIT" }, "requestedQuantity": 2 }
                  ],
                  "summary": { "totalItems": 2, "totalPriceExclTax": 3.0, "totalTax": 0.6, "totalPriceInclTax": 3.6 }
                }
                """.formatted(orderId, storeCode, storeCode);
    }

    // --------------------------------------------------
    // 202 - Accepted
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void testSubmitOrder_ValidPayload_Accepted() throws Exception {
        createStore("ORD_RS1");
        String payload = validPayload("ORD-TEST-001", "ORD_RS1");

        given()
                .body(payload)
                .contentType(ContentType.JSON)
                .when()
                .post("/orders")
                .then()
                .statusCode(202)
                .body(containsString("Order accepted"));

        OrderRequest request = OrderRequest.find("orderNumber", "ORD-TEST-001").firstResult();
        assertNotNull(request, "The OrderRequest must be persisted");
        assertEquals("ORD_RS1", request.storeCode);
        assertEquals(ReservationRecord.ReservationStatus.PENDING, request.reservationRecord.status);
        // the JSON column normalizes whitespace: compare the parsed trees, not the raw strings
        assertEquals(new ObjectMapper().readTree(payload), new ObjectMapper().readTree(request.specification),
                "The raw payload must be stored as the specification");

        List<Event> events = Event.list("label", "Commande Acceptée");
        assertEquals(1, events.size(), "A success event must be emitted");
        Event event = events.get(0);
        assertEquals(Event.UrgencyLevel.LOW, event.urgencyLevel);
        assertEquals("ORD-TEST-001", event.targetEntityCode);
        assertEquals(request.id, event.targetEntityId);
        assertNotNull(event.store);
        assertEquals("ORD_RS1", event.store.code);
    }

    // --------------------------------------------------
    // 400 - Malformed JSON
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void testSubmitOrder_MalformedJson_BadRequest() {
        given()
                .body("{ this is not valid json ")
                .contentType(ContentType.JSON)
                .when()
                .post("/orders")
                .then()
                .statusCode(400)
                .body(containsString("Malformed JSON"));

        assertEquals(0, OrderRequest.count(), "No OrderRequest must be persisted");
        // No event can be emitted: the store is unknown and Event.store is mandatory.
        assertEquals(0, Event.count(), "No event can be recorded without an identifiable store");
    }

    // --------------------------------------------------
    // 400 - Schema violation
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void testSubmitOrder_SchemaViolation_BadRequest() {
        Store store = createStore("ORD_RS2");
        // valid JSON but missing the mandatory "customer" section
        String payload = schemaInvalidPayload("ORD-TEST-002", "ORD_RS2");

        given()
                .body(payload)
                .contentType(ContentType.JSON)
                .when()
                .post("/orders")
                .then()
                .statusCode(400)
                .body(containsString("Schema validation failed"));

        assertEquals(0, OrderRequest.count(), "No OrderRequest must be persisted");

        List<Event> events = Event.list("label", "Commande Rejetée (Schéma Invalide)");
        assertEquals(1, events.size(), "A rejection event must be emitted");
        Event event = events.get(0);
        assertEquals(Event.UrgencyLevel.MEDIUM, event.urgencyLevel);
        assertEquals("ORD-TEST-002", event.targetEntityCode);
        assertEquals(store.id, event.store.id);
        assertTrue(event.description.contains("ORD-TEST-002") || event.description.contains("customer"),
                "The event description must help locate the failure");
    }

    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void testSubmitOrder_SchemaViolation_UnknownStore_BadRequest() {
        // regression test: an unknown store must not turn the 400 into a 500
        // (Event.store is mandatory, so the anomaly event cannot be persisted here)
        String payload = schemaInvalidPayload("ORD-TEST-003", "GHOST_STORE");

        given()
                .body(payload)
                .contentType(ContentType.JSON)
                .when()
                .post("/orders")
                .then()
                .statusCode(400)
                .body(containsString("Schema validation failed"));

        assertEquals(0, OrderRequest.count());
    }

    // --------------------------------------------------
    // 400 - Missing mandatory business fields
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "svc", roles = {"SERVICE_CALLER"})
    void testSubmitOrder_MissingMandatoryFields_BadRequest() {
        Store store = createStore("ORD_RS3");
        // schema-valid (orderId is a string) but empty: rejected at the business-field step
        String payload = validPayload("", "ORD_RS3");

        given()
                .body(payload)
                .contentType(ContentType.JSON)
                .when()
                .post("/orders")
                .then()
                .statusCode(400)
                .body(containsString("Missing mandatory fields"));

        assertEquals(0, OrderRequest.count(), "No OrderRequest must be persisted");

        List<Event> events = Event.list("label", "Commande Rejetée (Données Manquantes)");
        assertEquals(1, events.size(), "A rejection event must be emitted");
        assertEquals(Event.UrgencyLevel.MEDIUM, events.get(0).urgencyLevel);
        assertEquals(store.id, events.get(0).store.id);
    }

    // --------------------------------------------------
    // Security
    // --------------------------------------------------

    @Test
    void testSubmitOrder_Unauthenticated_Unauthorized() {
        given()
                .body(validPayload("ORD-TEST-004", "ORD_RS4"))
                .contentType(ContentType.JSON)
                .when()
                .post("/orders")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "manager", roles = {"MANAGER"})
    void testSubmitOrder_WrongRole_Forbidden() {
        given()
                .body(validPayload("ORD-TEST-005", "ORD_RS5"))
                .contentType(ContentType.JSON)
                .when()
                .post("/orders")
                .then()
                .statusCode(403);
    }
}
