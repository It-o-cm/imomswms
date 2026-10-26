package com.intermarche.e2e.graphql;

import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.EventResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.graphql.api.Context;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link EventResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 * <p>
 * The GraphQL {@link Context} is mocked: the selection set controls which fetch joins
 * the resource appends to its JPQL queries.
 */
@QuarkusTest
@TestTransaction
public class EventResourceTest {

    @Inject
    EventResource eventResource;

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    /**
     * Builds a mocked GraphQL context whose selection set contains exactly the given fields.
     */
    private Context mockContext(String... selectedFields) {
        Set<String> selected = Set.of(selectedFields);
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains(anyString())).thenAnswer(inv -> selected.contains(inv.getArgument(0)));
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(selectionSet);
        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
        return context;
    }

    private Store createStore(String code) {
        return DomainUtils.createAndPersistStore(code, 48.0, 2.0);
    }

    private Employee createEmployee(String badgeId, Store store) {
        Employee employee = new Employee();
        employee.firstName = "Emp";
        employee.lastName = badgeId;
        employee.badgeId = badgeId;
        employee.email = badgeId + "@test.com";
        employee.password = BCrypt.hashpw("secret", BCrypt.gensalt()); // BCrypt hashes are 60 chars (@Size(min=60))
        employee.role = Employee.EmployeeRole.MANAGER;
        employee.store = store;
        employee.persist();
        return employee;
    }

    private Event persistEvent(String label, Store store, String targetCode) {
        Event event = new Event(label, "Description of " + label, Event.UrgencyLevel.HIGH,
                store, OrderRequest.class, 1L, targetCode);
        event.persist();
        return event;
    }

    // --------------------------------------------------
    // Query Tests: eventsByDateRange (MANAGER/ADMIN/SERVICE_CALLER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testEventsByDateRange_Global_InRange() throws GraphQLException {
        Store store = createStore("STORE_EV1");
        persistEvent("EVT_1", store, "CODE_1");

        List<Event> inRange = eventResource.eventsByDateRange(
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), mockContext());
        assertEquals(1, inRange.size(), "Event created now must match the [now-1h, now+1h] range");

        List<Event> outOfRange = eventResource.eventsByDateRange(
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1), mockContext());
        assertTrue(outOfRange.isEmpty(), "Event created now must not match a past range");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testEventsByDateRange_ScopedToStore_Manager() throws GraphQLException {
        Store store = createStore("STORE_EV2");
        Store otherStore = createStore("STORE_EV2_OTHER");
        createEmployee("testUser", store);
        persistEvent("EVT_2", store, "CODE_2");
        persistEvent("EVT_3", otherStore, "CODE_3");

        List<Event> events = eventResource.eventsByDateRange(
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), mockContext());
        assertEquals(1, events.size(), "MANAGER must only see events of his store");
        assertEquals("EVT_2", events.get(0).label);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testEventsByDateRange_StoreSelection_FetchJoinsStore() throws GraphQLException {
        Store store = createStore("STORE_EV3");
        persistEvent("EVT_4", store, "CODE_4");
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Event> events = eventResource.eventsByDateRange(
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), mockContext("store"));
        assertEquals(1, events.size());
        assertTrue(Hibernate.isInitialized(events.get(0).store), "store must be fetch-joined when selected");
        assertEquals("STORE_EV3", events.get(0).store.code);
    }

    // --------------------------------------------------
    // Query Tests: eventsByStoreCode (MANAGER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testEventsByStoreCode_Success() throws GraphQLException {
        Store store = createStore("STORE_EV4");
        persistEvent("EVT_5", store, "CODE_5");
        persistEvent("EVT_6", store, "CODE_6");

        List<Event> events = eventResource.eventsByStoreCode("STORE_EV4", mockContext());
        assertEquals(2, events.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testEventsByStoreCode_UnknownStore_ReturnsEmpty() throws GraphQLException {
        List<Event> events = eventResource.eventsByStoreCode("STORE_UNKNOWN", mockContext());
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testEventsByStoreCode_StoreSelection_FetchJoinsStore() throws GraphQLException {
        Store store = createStore("STORE_EV5");
        persistEvent("EVT_7", store, "CODE_7");
        Panache.getEntityManager().flush();
        Panache.getEntityManager().clear();

        List<Event> events = eventResource.eventsByStoreCode("STORE_EV5", mockContext("store"));
        assertEquals(1, events.size());
        assertTrue(Hibernate.isInitialized(events.get(0).store), "store must be fetch-joined when selected");
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEvent_Success() throws GraphQLException {
        Store store = createStore("STORE_EV6");

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "Order Delay";
        input.description = "The order is late";
        input.urgencyLevel = "HIGH";
        input.storeCode = "STORE_EV6";
        input.targetEntityType = "com.intermarche.backoffice.domain.order.OrderRequest";
        input.targetEntityId = 42L;
        input.targetEntityCode = "ORD-42";

        Event created = eventResource.createEvent(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("Order Delay", created.label);
        assertEquals("The order is late", created.description);
        assertEquals(Event.UrgencyLevel.HIGH, created.urgencyLevel, "urgencyLevel must be mapped from the input string");
        assertEquals(store.id, created.store.id);
        assertEquals("com.intermarche.backoffice.domain.order.OrderRequest", created.targetEntityType);
        assertEquals(42L, created.targetEntityId);
        assertEquals("ORD-42", created.targetEntityCode);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEvent_InvalidUrgencyLevel() {
        Store store = createStore("STORE_EV7");

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "Bad urgency";
        input.urgencyLevel = "WRONG";
        input.storeCode = "STORE_EV7";
        input.targetEntityType = "com.intermarche.backoffice.domain.order.OrderRequest";
        input.targetEntityId = 1L;
        input.targetEntityCode = "ORD-1";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            eventResource.createEvent(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Invalid urgency level"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEvent_NullUrgencyLevel() {
        Store store = createStore("STORE_EV8");

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "No urgency";
        input.urgencyLevel = null; // mandatory (@NotNull on the entity)
        input.storeCode = "STORE_EV8";
        input.targetEntityType = "com.intermarche.backoffice.domain.order.OrderRequest";
        input.targetEntityId = 1L;
        input.targetEntityCode = "ORD-1";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            eventResource.createEvent(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Urgency level is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateEvent_StoreNotFound() {
        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "Ghost store";
        input.urgencyLevel = "LOW";
        input.storeCode = "STORE_UNKNOWN";
        input.targetEntityType = "com.intermarche.backoffice.domain.order.OrderRequest";
        input.targetEntityId = 1L;
        input.targetEntityCode = "ORD-1";

        assertThrows(NoSuchElementException.class, () -> {
            eventResource.createEvent(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEvent_Success() throws GraphQLException {
        Store store = createStore("STORE_EV9");
        Event existing = persistEvent("EVT_8", store, "CODE_8");

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "EVT_8 updated";
        input.description = "New description";
        input.urgencyLevel = "CRITICAL";
        input.targetEntityType = "com.intermarche.backoffice.domain.oms.FulfillmentChunk";
        input.targetEntityId = 99L;
        input.targetEntityCode = "CHUNK-99";

        Event updated = eventResource.updateEvent(existing.id, input);

        assertEquals("EVT_8 updated", updated.label);
        assertEquals("New description", updated.description);
        assertEquals(Event.UrgencyLevel.CRITICAL, updated.urgencyLevel);
        assertEquals("com.intermarche.backoffice.domain.oms.FulfillmentChunk", updated.targetEntityType);
        assertEquals(99L, updated.targetEntityId);
        assertEquals("CHUNK-99", updated.targetEntityCode);
        assertEquals(store.id, updated.store.id, "store must remain unchanged");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEvent_ChangeStore() throws GraphQLException {
        Store store = createStore("STORE_EV10");
        Store newStore = createStore("STORE_EV10_BIS");
        Event existing = persistEvent("EVT_9", store, "CODE_9");

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.storeCode = "STORE_EV10_BIS";

        Event updated = eventResource.updateEvent(existing.id, input);
        assertEquals(newStore.id, updated.store.id, "store must be re-assigned");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEvent_StoreNotFound() {
        Store store = createStore("STORE_EV11");
        Event existing = persistEvent("EVT_10", store, "CODE_10");

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.storeCode = "STORE_UNKNOWN";

        assertThrows(NoSuchElementException.class, () -> {
            eventResource.updateEvent(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEvent_InvalidUrgencyLevel() {
        Store store = createStore("STORE_EV12");
        Event existing = persistEvent("EVT_11", store, "CODE_11");

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.urgencyLevel = "WRONG";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            eventResource.updateEvent(existing.id, input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Invalid urgency level"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateEvent_NotFound() {
        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "Ghost";

        assertThrows(NoSuchElementException.class, () -> {
            eventResource.updateEvent(9999L, input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteEvent_Success() throws GraphQLException {
        Store store = createStore("STORE_EV13");
        Event existing = persistEvent("EVT_12", store, "CODE_12");

        boolean result = eventResource.deleteEvent(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(Event.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteEvent_NotFound() throws GraphQLException {
        boolean result = eventResource.deleteEvent(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }
}
