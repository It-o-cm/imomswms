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

    /**
     * Prepares the fixtures of each test.
     */
    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        arcMock = mockStatic(Arc.class);
        session = PanacheTestSupport.mockArcSession(arcMock);
        eventMock = mockStatic(Event.class);
    }

    /**
     * Releases the static mocks opened for the test.
     */
    @AfterEach
    void tearDown() {
        panacheMock.close();
        arcMock.close();
        eventMock.close();
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds resource.
     *
     * @return the built value
     */
    private OrderResource newResource() {
        OrderResource resource = new OrderResource();
        resource.objectMapper = new ObjectMapper();
        resource.identity = identity(true);
        return resource;
    }

    /**
     * Builds the identity of the integration account "svc".
     *
     * @param admin whether it is also an administrator
     * @return the identity
     */
    private static io.quarkus.security.identity.SecurityIdentity identity(boolean admin) {
        io.quarkus.security.identity.SecurityIdentity identity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        java.security.Principal principal = () -> "svc";
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("ADMIN")).thenReturn(admin);
        when(identity.hasRole("SERVICE_CALLER")).thenReturn(true);
        return identity;
    }

    /**
     * Stubs the active grants of "svc".
     *
     * @param stores the granted stores
     */
    private void stubGrants(Store... stores) {
        java.util.List<com.intermarche.backoffice.domain.commerce.CommerceStoreGrant> grants = new java.util.ArrayList<>();
        for (Store granted : stores) {
            var grant = new com.intermarche.backoffice.domain.commerce.CommerceStoreGrant();
            grant.store = granted;
            grant.principal = "svc";
            grants.add(grant);
        }
        panacheMock.when(() -> PanacheEntityBase.list(eq("principal = ?1 and active = true"), any(Object[].class)))
                .thenReturn(grants);
    }

    /**
     * Builds resource with real schema.
     *
     * @return the built value
     */
    private OrderResource newResourceWithRealSchema() {
        OrderResource resource = newResource();
        resource.initSchema();
        return resource;
    }

    /**
     * Builds a store fixture.
     *
     * @param code the code
     * @return the store
     */
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

    /**
     * Checks init schema: initializes json schema.
     */
    @Test
    void initSchema_initializesJsonSchema() {
        OrderResource resource = newResource();

        resource.initSchema();

        assertNotNull(resource.jsonSchema);
    }

    /**
     * Checks init schema: factory failure, throws runtime exception.
     */
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

    /**
     * Checks submit order: malformed json, returns 400 and publishes anomaly event.
     */
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

    /**
     * Checks submit order: null payload, returns 400 and publishes anomaly event.
     */
    @Test
    void submitOrder_nullPayload_returns400AndPublishesAnomalyEvent() {
        OrderResource resource = newResourceWithRealSchema();

        Response response = resource.submitOrder(null);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Malformed JSON payload"));
        eventMock.verify(() -> Event.publishInNewTx(
                eq("Format JSON Invalide Intercepté"), anyString(), eq(Event.UrgencyLevel.HIGH),
                isNull(), any(OrderRequest.class), eq("JSON_INVALID")));
    }

    /**
     * Checks submit order: event publication failure, still returns 400.
     */
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

    /**
     * Checks submit order: schema invalid, returns 400 and publishes rejection event.
     */
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

    /**
     * Checks submit order: empty order id, returns 400 and falls back to inconnu.
     */
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

    /**
     * Checks submit order: missing store code, store is null and request rejected.
     */
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

    /**
     * Checks submit order: empty store code, looks up store and falls back to inconnu.
     */
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

    /**
     * Checks submit order: valid payload, returns 202 and persists order request.
     */
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

    /**
     * Checks submit order: persist failure, returns 500 and publishes error event.
     */
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

    // --------------------------------------------------
    // submitOrder: store grants of the integration account
    // --------------------------------------------------

    /**
     * Checks submit order: service granted on the store, is accepted.
     */
    @Test
    void submitOrder_serviceGrantedOnTheStore_isAccepted() {
        Store store = store("STORE-1");
        stubStoreLookup(store);
        stubGrants(store);
        OrderResource resource = newResourceWithRealSchema();
        resource.identity = identity(false);

        Response response = resource.submitOrder(VALID_PAYLOAD);

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), response.getStatus());
    }

    /**
     * Checks submit order: service granted on another store, is refused without saving.
     */
    @Test
    void submitOrder_serviceGrantedOnAnotherStore_isRefusedWithoutSaving() {
        stubStoreLookup(store("STORE-1"));
        Store other = store("STORE-2");
        other.id = 2L;
        stubGrants(other);
        OrderResource resource = newResourceWithRealSchema();
        resource.identity = identity(false);

        Response response = resource.submitOrder(VALID_PAYLOAD);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        verify(session, never()).persist(any());
    }

    /**
     * Checks submit order: service without grant, is refused.
     */
    @Test
    void submitOrder_serviceWithoutGrant_isRefused() {
        stubStoreLookup(store("STORE-1"));
        stubGrants();
        OrderResource resource = newResourceWithRealSchema();
        resource.identity = identity(false);

        Response response = resource.submitOrder(VALID_PAYLOAD);

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
    }
}
