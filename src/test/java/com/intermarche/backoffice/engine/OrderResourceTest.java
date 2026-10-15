package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderResource} (REST endpoint receiving retail orders).
 * <p>
 * Covers schema initialization (success and failure), JSON parsing errors, schema
 * validation rejections, mandatory-field validation, store lookup branches,
 * persistence failure handling and the event-publication safety net.
 * Panache/Event statics are mocked: no Quarkus test required.
 */
class OrderResourceTest {

    private static final String VALID_PAYLOAD = """
            {
              "orderHeader": {
                "orderId": "ORD-123",
                "createdAt": "2026-10-14T10:00:00Z",
                "store": { "code": "STORE-1", "id": 1 }
              },
              "customer": {
                "customerId": "CUST-1",
                "firstName": "John",
                "lastName": "Doe",
                "address": { "streetLine1": "1 Main St", "city": "Paris", "postalCode": "75001", "country": "FR" }
              },
              "fulfillment": { "type": "HOME_DELIVERY" },
              "lines": [],
              "summary": { "totalItems": 0 }
            }
            """;

    private MockedStatic<PanacheEntityBase> panacheMock;
    private MockedStatic<Arc> arcMock;
    private MockedStatic<Event> eventMock;
    private Session session;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        arcMock = mockStatic(Arc.class);
        session = PanacheTestSupport.mockArcSession(arcMock);
        eventMock = mockStatic(Event.class);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
        arcMock.close();
        eventMock.close();
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private OrderResource newResource() {
        OrderResource resource = new OrderResource();
        resource.objectMapper = new ObjectMapper();
        return resource;
    }

    private OrderResource newResourceWithRealSchema() {
        OrderResource resource = newResource();
        resource.initSchema();
        return resource;
    }

    private Store store(String code) {
        Store store = new Store();
        store.id = 1L;
        store.code = code;
        return store;
    }

    /**
     * Stubs Store.findByCode (through the Panache find static) to return the given store.
     */
    private void stubStoreLookup(Store store) {
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(store);
        panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                .thenReturn(query);
    }

    // --------------------------------------------------
    // initSchema
    // --------------------------------------------------

    @Test
    void initSchema_initializesJsonSchema() {
        OrderResource resource = newResource();

        resource.initSchema();

        assertNotNull(resource.jsonSchema);
    }

    @Test
    void initSchema_factoryFailure_throwsRuntimeException() {
        OrderResource resource = newResource();

        try (MockedStatic<JsonSchemaFactory> factoryMock = mockStatic(JsonSchemaFactory.class)) {
            factoryMock.when(() -> JsonSchemaFactory.getInstance(any(SpecVersion.VersionFlag.class)))
                    .thenThrow(new IllegalStateException("factory unavailable"));

            RuntimeException exception = assertThrows(RuntimeException.class, resource::initSchema);
            assertEquals("Failed to initialize JSON Schema", exception.getMessage());
            assertInstanceOf(IllegalStateException.class, exception.getCause());
        }
    }

    // --------------------------------------------------
    // submitOrder: JSON parsing
    // --------------------------------------------------

    @Test
    void submitOrder_malformedJson_returns400AndPublishesAnomalyEvent() {
        OrderResource resource = newResourceWithRealSchema();

        Response response = resource.submitOrder("{ this is not valid json");

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Malformed JSON payload"));
        eventMock.verify(() -> Event.publishInNewTx(
                eq("Format JSON Invalide Intercepté"), anyString(), eq(Event.UrgencyLevel.HIGH),
                isNull(), any(OrderRequest.class), eq("JSON_INVALID")));
    }

    @Test
    void submitOrder_eventPublicationFailure_stillReturns400() {
        eventMock.when(() -> Event.publishInNewTx(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("event store down"));
        OrderResource resource = newResourceWithRealSchema();

        Response response = resource.submitOrder("{ this is not valid json");

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    // --------------------------------------------------
    // submitOrder: schema validation
    // --------------------------------------------------

    @Test
    void submitOrder_schemaInvalid_returns400AndPublishesRejectionEvent() {
        stubStoreLookup(store("STORE-1"));
        OrderResource resource = newResourceWithRealSchema();
        // Missing the required "customer" section
        String invalidPayload = """
                {
                  "orderHeader": {
                    "orderId": "ORD-123",
                    "createdAt": "2026-10-14T10:00:00Z",
                    "store": { "code": "STORE-1" }
                  },
                  "fulfillment": {},
                  "lines": [],
                  "summary": {}
                }
                """;

        Response response = resource.submitOrder(invalidPayload);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Schema validation failed"));
        eventMock.verify(() -> Event.publishInNewTx(
                eq("Commande Rejetée (Schéma Invalide)"), anyString(), eq(Event.UrgencyLevel.MEDIUM),
                any(), any(OrderRequest.class), eq("ORD-123")));
    }

    // --------------------------------------------------
    // submitOrder: mandatory fields
    // --------------------------------------------------

    @Test
    void submitOrder_emptyOrderId_returns400AndFallsBackToInconnu() {
        stubStoreLookup(store("STORE-1"));
        OrderResource resource = newResourceWithRealSchema();
        String payload = VALID_PAYLOAD.replace("\"ORD-123\"", "\"\"");

        Response response = resource.submitOrder(payload);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing mandatory fields"));
        eventMock.verify(() -> Event.publishInNewTx(
                eq("Commande Rejetée (Données Manquantes)"), anyString(), eq(Event.UrgencyLevel.MEDIUM),
                any(), any(OrderRequest.class), eq("INCONNU")));
    }

    @Test
    void submitOrder_missingStoreCode_storeIsNullAndRequestRejected() {
        OrderResource resource = newResourceWithRealSchema();
        String payload = VALID_PAYLOAD.replace("\"store\": { \"code\": \"STORE-1\", \"id\": 1 }", "\"store\": {}");

        Response response = resource.submitOrder(payload);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing mandatory fields"));
        // storeCode defaults to "INCONNU": the store lookup must be skipped entirely
        panacheMock.verify(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)), never());
    }

    @Test
    void submitOrder_emptyStoreCode_looksUpStoreAndFallsBackToInconnu() {
        stubStoreLookup(null);
        OrderResource resource = newResourceWithRealSchema();
        String payload = VALID_PAYLOAD.replace("\"code\": \"STORE-1\"", "\"code\": \"\"");

        Response response = resource.submitOrder(payload);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing mandatory fields"));
        panacheMock.verify(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)));
        eventMock.verify(() -> Event.publishInNewTx(
                eq("Commande Rejetée (Données Manquantes)"), anyString(), eq(Event.UrgencyLevel.MEDIUM),
                isNull(), any(OrderRequest.class), eq("ORD-123")));
    }

    // --------------------------------------------------
    // submitOrder: happy path & persistence failure
    // --------------------------------------------------

    @Test
    void submitOrder_validPayload_returns202AndPersistsOrderRequest() {
        Store store = store("STORE-1");
        stubStoreLookup(store);
        OrderResource resource = newResourceWithRealSchema();

        Response response = resource.submitOrder(VALID_PAYLOAD);

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Order accepted for processing"));
        verify(session).persist(any(OrderRequest.class));
        eventMock.verify(() -> Event.publishInCurrentTx(
                eq("Commande Acceptée"), anyString(), eq(Event.UrgencyLevel.LOW),
                eq(store), any(OrderRequest.class), eq("ORD-123")));
    }

    @Test
    void submitOrder_persistFailure_returns500AndPublishesErrorEvent() {
        stubStoreLookup(store("STORE-1"));
        doThrow(new RuntimeException("DB down")).when(session).persist(any());
        OrderResource resource = newResourceWithRealSchema();

        Response response = resource.submitOrder(VALID_PAYLOAD);

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Failed to save order request"));
        eventMock.verify(() -> Event.publishInNewTx(
                eq("Erreur Critique de Persistance"), anyString(), eq(Event.UrgencyLevel.HIGH),
                any(), any(OrderRequest.class), eq("ORD-123")));
    }
}
