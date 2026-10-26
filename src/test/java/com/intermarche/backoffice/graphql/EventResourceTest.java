package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.graphql.api.Context;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EventResource}.
 * Covers the urgency-level validation branches, the store re-assignment branch of the
 * update mutation and the dynamic query builder of the date-range/store-code queries.
 */
class EventResourceTest {

    private static final Long STORE_ID = 7L;
    private static final String BADGE_ID = "BADGE-001";

    private MockedStatic<PanacheEntityBase> panacheMock;
    private MockedStatic<Arc> arcMock;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        arcMock = mockStatic(Arc.class);
        PanacheTestSupport.mockArcSession(arcMock);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
        arcMock.close();
    }

    private EventResource newResource(boolean admin) {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(BADGE_ID);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("ADMIN")).thenReturn(admin);
        when(identity.hasRole("SERVICE_CALLER")).thenReturn(false);

        EventResource resource = new EventResource();
        resource.identity = identity;
        return resource;
    }

    private Context contextWithSelection(boolean store) {
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains("store")).thenReturn(store);
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(selectionSet);
        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
        return context;
    }

    private Store store(String code) {
        Store store = new Store();
        store.id = STORE_ID;
        store.code = code;
        return store;
    }

    private void stubBadgeLookup(Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        panacheMock.when(() -> PanacheEntityBase.find(startsWith("SELECT e FROM Employee"), any(Object[].class)))
                .thenReturn(query);
    }

    private void stubStoreFindByCode(Store result) {
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                .thenReturn(query);
    }

    /**
     * Records the arguments of the last Event.list(...) call.
     */
    private static class ListCall {
        String jpql;
        Object[] params = new Object[0];
    }

    private ListCall stubEventList(List<Event> result) {
        ListCall call = new ListCall();
        panacheMock.when(() -> PanacheEntityBase.list(anyString()))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    call.jpql = (String) args[0];
                    call.params = new Object[0];
                    return result;
                });
        panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    call.jpql = (String) args[0];
                    call.params = args.length > 1 && args[1] instanceof Object[]
                            ? (Object[]) args[1]
                            : Arrays.copyOfRange(args, 1, args.length);
                    return result;
                });
        return call;
    }

    @Test
    void getIdentity_returnsInjectedIdentity() {
        EventResource resource = new EventResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    // --------------------------------------------------
    // createEvent
    // --------------------------------------------------

    @Test
    void createEvent_nullUrgencyLevel_throwsGraphQLException() {
        stubStoreFindByCode(store("S1"));

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "event";
        input.storeCode = "S1";
        input.urgencyLevel = null;

        EventResource resource = newResource(true);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.createEvent(input));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void createEvent_blankUrgencyLevel_throwsGraphQLException() {
        stubStoreFindByCode(store("S1"));

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "event";
        input.storeCode = "S1";
        input.urgencyLevel = "   ";

        EventResource resource = newResource(true);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.createEvent(input));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void createEvent_validInput_createsEvent() throws Exception {
        Store store = store("S1");
        stubStoreFindByCode(store);

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.label = "event";
        input.description = "desc";
        input.storeCode = "S1";
        input.urgencyLevel = "HIGH";
        input.targetEntityType = "com.intermarche.backoffice.domain.order.Order";
        input.targetEntityId = 42L;
        input.targetEntityCode = "ORD-1";

        Event result = newResource(true).createEvent(input);

        assertEquals("event", result.label);
        assertEquals(Event.UrgencyLevel.HIGH, result.urgencyLevel);
        assertSame(store, result.store);
        assertEquals("ORD-1", result.targetEntityCode);
    }

    // --------------------------------------------------
    // updateEvent
    // --------------------------------------------------

    @Test
    void updateEvent_withUrgencyLevel_updatesIt() throws Exception {
        Event event = new Event();
        event.id = 1L;
        event.label = "old";
        event.urgencyLevel = Event.UrgencyLevel.LOW;
        event.store = store("S1");
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(event);

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.urgencyLevel = "CRITICAL";

        Event result = newResource(true).updateEvent(1L, input);

        assertSame(event, result);
        assertEquals(Event.UrgencyLevel.CRITICAL, event.urgencyLevel);
    }

    @Test
    void updateEvent_blankUrgencyLevel_keepsExisting() throws Exception {
        Event event = new Event();
        event.id = 1L;
        event.label = "old";
        event.urgencyLevel = Event.UrgencyLevel.LOW;
        event.store = store("S1");
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(event);

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.urgencyLevel = "   ";

        Event result = newResource(true).updateEvent(1L, input);

        assertSame(event, result);
        assertEquals(Event.UrgencyLevel.LOW, event.urgencyLevel);
    }

    @Test
    void updateEvent_withDifferentStoreCode_reassignsStore() throws Exception {
        Event event = new Event();
        event.id = 1L;
        event.label = "old";
        event.urgencyLevel = Event.UrgencyLevel.LOW;
        event.store = store("S1");
        Store newStore = store("S2");
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(event);
        stubStoreFindByCode(newStore);

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.storeCode = "S2";

        Event result = newResource(true).updateEvent(1L, input);

        assertSame(event, result);
        assertSame(newStore, event.store);
    }

    @Test
    void updateEvent_withSameStoreCode_keepsStore() throws Exception {
        Event event = new Event();
        event.id = 1L;
        event.label = "old";
        event.urgencyLevel = Event.UrgencyLevel.LOW;
        event.store = store("S1");
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(event);

        EventResource.EventRecord input = new EventResource.EventRecord();
        input.storeCode = "S1";

        Event result = newResource(true).updateEvent(1L, input);

        assertSame(event, result);
        assertEquals("S1", event.store.code);
        panacheMock.verify(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)), never());
    }

    // --------------------------------------------------
    // eventsByDateRange
    // --------------------------------------------------

    @Test
    void eventsByDateRange_withStoreContextAndWantsStore_fetchesStoreAndFilters() throws Exception {
        Store store = store("S1");
        Employee employee = new Employee();
        employee.store = store;
        stubBadgeLookup(employee);
        PanacheQuery<Event> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(new Event()));
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> !jpql.startsWith("SELECT e FROM Employee")), any(Object[].class)))
                .thenReturn(query);

        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 14, 18, 0);

        List<Event> result = newResource(false)
                .eventsByDateRange(start, end, contextWithSelection(true));

        assertEquals(1, result.size());
        panacheMock.verify(() -> PanacheEntityBase.find(
                argThat(jpql -> jpql.contains("JOIN FETCH e.store s")
                        && jpql.contains("AND s.id = ?3")),
                any(Object[].class)));
    }

    @Test
    void eventsByDateRange_withoutStoreContextAndNoSelection_noStoreJoin() throws Exception {
        stubBadgeLookup(null);
        PanacheQuery<Event> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of());
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> !jpql.startsWith("SELECT e FROM Employee")), any(Object[].class)))
                .thenReturn(query);

        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 14, 18, 0);

        List<Event> result = newResource(true)
                .eventsByDateRange(start, end, contextWithSelection(false));

        assertNotNull(result);
        panacheMock.verify(() -> PanacheEntityBase.find(
                argThat(jpql -> !jpql.contains("e.store") && jpql.contains("e.createdAt >= ?1")),
                any(Object[].class)));
    }

    // --------------------------------------------------
    // eventsByStoreCode
    // --------------------------------------------------

    @Test
    void eventsByStoreCode_forcesStoreJoinAndAppliesWhereClause() throws Exception {
        stubStoreFindByCode(store("S1"));
        ListCall call = stubEventList(List.of(new Event()));

        List<Event> result = newResource(true).eventsByStoreCode("S1", contextWithSelection(false));

        assertEquals(1, result.size());
        assertTrue(call.jpql.contains("join e.store s "));
        assertTrue(call.jpql.contains("where s.code = ?1"));
        assertArrayEquals(new Object[]{"S1"}, call.params);
    }

    @Test
    void eventsByStoreCode_withStoreSelection_fetchesStore() throws Exception {
        stubStoreFindByCode(store("S1"));
        ListCall call = stubEventList(List.of());

        newResource(true).eventsByStoreCode("S1", contextWithSelection(true));

        assertTrue(call.jpql.contains("join fetch e.store s "));
        assertTrue(call.jpql.contains("where s.code = ?1"));
    }

    /**
     * An unknown store code gives no event, without querying the events.
     */
    @Test
    void eventsByStoreCode_unknownStore_returnsEmptyWithoutQueryingEvents() throws Exception {
        stubStoreFindByCode(null);
        ListCall call = stubEventList(List.of(new Event()));

        assertTrue(newResource(false).eventsByStoreCode("UNKNOWN", contextWithSelection(false)).isEmpty());
        assertNull(call.jpql);
    }

    /**
     * A manager reads the events of their own store.
     */
    @Test
    void eventsByStoreCode_managerOfThatStore_readsItsEvents() throws Exception {
        Store own = store("S1");
        Employee employee = new Employee();
        employee.store = own;
        stubBadgeLookup(employee);
        stubStoreFindByCode(own);
        ListCall call = stubEventList(List.of(new Event()));

        assertEquals(1, newResource(false).eventsByStoreCode("S1", contextWithSelection(false)).size());
        assertArrayEquals(new Object[]{"S1"}, call.params);
    }

    /**
     * A manager cannot read the events of another store: nothing is queried.
     */
    @Test
    void eventsByStoreCode_managerOfAnotherStore_isRefused() {
        Store own = store("S1");
        Employee employee = new Employee();
        employee.store = own;
        stubBadgeLookup(employee);
        Store other = new Store();
        other.id = STORE_ID + 1;
        other.code = "S2";
        stubStoreFindByCode(other);
        ListCall call = stubEventList(List.of(new Event()));

        assertThrows(GraphQLException.class, () -> newResource(false).eventsByStoreCode("S2", contextWithSelection(false)));
        assertNull(call.jpql);
    }

    /**
     * A manager linked to no store is refused.
     */
    @Test
    void eventsByStoreCode_managerWithoutStore_isRefused() {
        stubBadgeLookup(null);
        stubStoreFindByCode(store("S1"));

        assertThrows(GraphQLException.class, () -> newResource(false).eventsByStoreCode("S1", contextWithSelection(false)));
    }

    // --------------------------------------------------
    // buildOptimizedQuery (via reflection — forceStoreJoin=false is unreachable via public API)
    // --------------------------------------------------

    private String invokeBuildOptimizedQuery(Context context, String whereClause, boolean forceStoreJoin) throws Exception {
        Method method = EventResource.class.getDeclaredMethod(
                "buildOptimizedQuery", Context.class, String.class, boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(newResource(true), context, whereClause, forceStoreJoin);
    }

    @Test
    void buildOptimizedQuery_noForceJoinNoStoreSelection_buildsPlainQueryWithoutWhere() throws Exception {
        String jpql = invokeBuildOptimizedQuery(contextWithSelection(false), null, false);

        assertTrue(jpql.startsWith("select distinct e from Event e "));
        assertFalse(jpql.contains("e.store"));
        assertFalse(jpql.contains("where"));
    }

    @Test
    void buildOptimizedQuery_noForceJoinWantsStore_fetchesStoreWithWhere() throws Exception {
        String jpql = invokeBuildOptimizedQuery(contextWithSelection(true), "e.label = ?1", false);

        assertTrue(jpql.contains("join fetch e.store s "));
        assertTrue(jpql.contains("where e.label = ?1"));
    }
}
