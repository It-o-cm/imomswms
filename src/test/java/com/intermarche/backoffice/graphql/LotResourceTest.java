package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
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

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LotResource}.
 * <p>
 * Covers the identity accessor, the optimized-query builder (chunk/location/lines
 * fetch-join variants driven by the GraphQL selection set), the single-lot and
 * by-chunk queries and the update mutation (store scoping, status update, line
 * synchronization delegation). Panache statics are mocked: no Quarkus test required.
 */
class LotResourceTest {

    private static final Long STORE_ID = 7L;
    private static final Long OTHER_STORE_ID = 8L;
    private static final String BADGE_ID = "BADGE-001";

    private MockedStatic<PanacheEntityBase> panacheMock;
    private MockedStatic<Arc> arcMock;
    private LotResourceService lotService;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        arcMock = mockStatic(Arc.class);
        PanacheTestSupport.mockArcSession(arcMock);
        lotService = mock(LotResourceService.class);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
        arcMock.close();
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private LotResource newResource(boolean admin, boolean serviceCaller) {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(BADGE_ID);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("ADMIN")).thenReturn(admin);
        when(identity.hasRole("SERVICE_CALLER")).thenReturn(serviceCaller);

        LotResource resource = new LotResource();
        resource.identity = identity;
        resource.lotService = lotService;
        return resource;
    }

    /**
     * Builds a GraphQL context whose selection set reports the given fields.
     * When {@code wantsLines} is true, the "lines" field definition is exposed and
     * reports whether "fulfillmentLine" / "product" are selected inside it.
     */
    private Context context(boolean wantsChunk, boolean wantsLocation, boolean wantsLines,
                            boolean linesWantsFulfillmentLine, boolean linesWantsProduct) {
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains("chunk")).thenReturn(wantsChunk);
        when(selectionSet.contains("location")).thenReturn(wantsLocation);
        when(selectionSet.contains("lines")).thenReturn(wantsLines);
        if (wantsLines) {
            SelectedField linesField = mock(SelectedField.class);
            when(linesField.getName()).thenReturn("lines");
            DataFetchingFieldSelectionSet linesSelection = mock(DataFetchingFieldSelectionSet.class);
            when(linesField.getSelectionSet()).thenReturn(linesSelection);
            when(linesSelection.contains("fulfillmentLine")).thenReturn(linesWantsFulfillmentLine);
            when(linesSelection.contains("product")).thenReturn(linesWantsProduct);
            // An unrelated field ensures the anyMatch lambda's name-mismatch branch is exercised
            SelectedField idField = mock(SelectedField.class);
            when(idField.getName()).thenReturn("id");
            when(idField.getSelectionSet()).thenReturn(mock(DataFetchingFieldSelectionSet.class));
            when(selectionSet.getFields()).thenReturn(List.of(idField, linesField));
        } else {
            when(selectionSet.getFields()).thenReturn(List.of());
        }
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(selectionSet);
        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
        return context;
    }

    private Context emptyContext() {
        return context(false, false, false, false, false);
    }

    private Store store(Long id) {
        Store store = new Store();
        store.id = id;
        return store;
    }

    private Employee employee(Store store) {
        Employee employee = new Employee();
        employee.store = store;
        return employee;
    }

    private Lot lot(Long id, Store store) {
        Lot lot = new Lot();
        lot.id = id;
        lot.store = store;
        return lot;
    }

    /**
     * Stubs Employee.findByBadgeId (through the Panache find static) to return the given employee.
     */
    private void stubBadgeLookup(Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        panacheMock.when(() -> PanacheEntityBase.find(startsWith("SELECT e FROM Employee"), any(Object[].class)))
                .thenReturn(query);
    }

    /**
     * Records the arguments of a list(...) call.
     */
    private static class ListCall {
        String jpql;
        Object[] params = new Object[0];
    }

    private static Object[] extractParams(Object[] args) {
        return args.length > 1 && args[1] instanceof Object[]
                ? (Object[]) args[1]
                : Arrays.copyOfRange(args, 1, args.length);
    }

    /**
     * Stubs Lot.list(String) (single-arg overload) and captures the JPQL.
     */
    private ListCall stubLotList(List<Lot> result) {
        ListCall call = new ListCall();
        panacheMock.when(() -> PanacheEntityBase.list(anyString()))
                .thenAnswer(invocation -> {
                    call.jpql = invocation.getArgument(0);
                    return result;
                });
        return call;
    }

    /**
     * Stubs Lot.list(String, Object...) (varargs overload) and captures the arguments.
     */
    private ListCall stubLotListVarargs(List<Lot> result) {
        ListCall call = new ListCall();
        panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    call.jpql = (String) args[0];
                    call.params = extractParams(args);
                    return result;
                });
        return call;
    }

    // --------------------------------------------------
    // getIdentity
    // --------------------------------------------------

    @Test
    void getIdentity_returnsInjectedIdentity() {
        LotResource resource = new LotResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    // --------------------------------------------------
    // allLots
    // --------------------------------------------------

    @Test
    void allLots_emptySelection_buildsPlainQuery() {
        ListCall call = stubLotList(List.of(lot(1L, store(STORE_ID))));

        List<Lot> result = newResource(true, false).allLots(emptyContext());

        assertEquals(1, result.size());
        assertEquals("select distinct l from Lot l ", call.jpql);
    }

    @Test
    void allLots_wantsChunk_fetchesChunk() {
        ListCall call = stubLotList(List.of());

        newResource(true, false).allLots(context(true, false, false, false, false));

        assertEquals("select distinct l from Lot l left join fetch l.chunk c ", call.jpql);
    }

    @Test
    void allLots_wantsLocation_fetchesLocation() {
        ListCall call = stubLotList(List.of());

        newResource(true, false).allLots(context(false, true, false, false, false));

        assertEquals("select distinct l from Lot l left join fetch l.location loc ", call.jpql);
    }

    @Test
    void allLots_wantsLinesWithNestedFields_fetchesLinesFulfillmentLineAndProduct() {
        ListCall call = stubLotList(List.of());

        newResource(true, false).allLots(context(false, false, true, true, true));

        assertEquals("select distinct l from Lot l left join fetch l.lines ll " +
                "left join fetch ll.fulfillmentLine ffl left join fetch ll.product p ", call.jpql);
    }

    @Test
    void allLots_wantsLinesWithoutNestedFields_fetchesLinesOnly() {
        ListCall call = stubLotList(List.of());

        newResource(true, false).allLots(context(false, false, true, false, false));

        assertEquals("select distinct l from Lot l left join fetch l.lines ll ", call.jpql);
    }

    @Test
    void allLots_wantsLinesWithOnlyFulfillmentLine_fetchesFulfillmentLineOnly() {
        ListCall call = stubLotList(List.of());

        newResource(true, false).allLots(context(false, false, true, true, false));

        assertEquals("select distinct l from Lot l left join fetch l.lines ll " +
                "left join fetch ll.fulfillmentLine ffl ", call.jpql);
    }

    @Test
    void allLots_wantsLinesWithOnlyProduct_fetchesProductOnly() {
        ListCall call = stubLotList(List.of());

        newResource(true, false).allLots(context(false, false, true, false, true));

        assertEquals("select distinct l from Lot l left join fetch l.lines ll " +
                "left join fetch ll.product p ", call.jpql);
    }

    // --------------------------------------------------
    // lot
    // --------------------------------------------------

    @Test
    void lot_found_returnsLot() {
        Lot lot = lot(5L, store(STORE_ID));
        ListCall call = new ListCall();
        PanacheQuery<Lot> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(lot);
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> jpql.contains("l.id = ?1")), any(Object[].class)))
                .thenAnswer(invocation -> {
                    call.jpql = (String) invocation.getArguments()[0];
                    return query;
                });

        Lot result = newResource(true, false).lot(5L, emptyContext());

        assertSame(lot, result);
        assertEquals("select distinct l from Lot l where l.id = ?1", call.jpql);
    }

    @Test
    void lot_notFound_throwsNoSuchElement() {
        PanacheQuery<Lot> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        panacheMock.when(() -> PanacheEntityBase.find(
                        argThat(jpql -> jpql.contains("l.id = ?1")), any(Object[].class)))
                .thenReturn(query);

        LotResource resource = newResource(true, false);
        assertThrows(NoSuchElementException.class, () -> resource.lot(5L, emptyContext()));
    }

    // --------------------------------------------------
    // lotsByChunkId
    // --------------------------------------------------

    @Test
    void lotsByChunkId_withoutChunkSelection_joinsChunkForFiltering() {
        ListCall call = stubLotListVarargs(List.of(lot(1L, store(STORE_ID))));

        List<Lot> result = newResource(true, false).lotsByChunkId(9L, emptyContext());

        assertEquals(1, result.size());
        assertEquals("select distinct l from Lot l join l.chunk c where c.id = ?1", call.jpql);
        assertArrayEquals(new Object[]{9L}, call.params);
    }

    @Test
    void lotsByChunkId_withChunkSelection_fetchesChunk() {
        ListCall call = stubLotListVarargs(List.of());

        newResource(true, false).lotsByChunkId(9L, context(true, false, false, false, false));

        assertEquals("select distinct l from Lot l left join fetch l.chunk c where c.id = ?1", call.jpql);
        assertArrayEquals(new Object[]{9L}, call.params);
    }

    // --------------------------------------------------
    // updateLot
    // --------------------------------------------------

    @Test
    void updateLot_notFound_throwsNoSuchElement() {
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(null);

        LotResource.LotRecord input = new LotResource.LotRecord();
        LotResource resource = newResource(false, false);
        assertThrows(NoSuchElementException.class, () -> resource.updateLot(1L, input));
    }

    @Test
    void updateLot_sameStore_updatesStatusAndSynchronizesLines() throws Exception {
        Lot lot = lot(1L, store(STORE_ID));
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(lot);
        stubBadgeLookup(employee(store(STORE_ID)));

        LotResource.LotRecord input = new LotResource.LotRecord();
        input.status = Lot.LotStatus.COLLECTED;
        input.lines = List.of(new LotResourceService.LotLineRecord());

        Lot result = newResource(false, false).updateLot(1L, input);

        assertSame(lot, result);
        assertEquals(Lot.LotStatus.COLLECTED, lot.status);
        verify(lotService).synchronizeLotLines(lot, input.lines);
    }

    @Test
    void updateLot_globalAdmin_skipsStoreCheck() throws Exception {
        Lot lot = lot(1L, store(STORE_ID));
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(lot);
        stubBadgeLookup(null);

        LotResource.LotRecord input = new LotResource.LotRecord();
        input.status = Lot.LotStatus.COLLECTED;

        Lot result = newResource(true, false).updateLot(1L, input);

        assertSame(lot, result);
        assertEquals(Lot.LotStatus.COLLECTED, lot.status);
    }

    @Test
    void updateLot_crossStore_throwsSecurityExceptionWrapped() {
        Lot lot = lot(1L, store(STORE_ID));
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(lot);
        stubBadgeLookup(employee(store(OTHER_STORE_ID)));

        LotResource.LotRecord input = new LotResource.LotRecord();
        input.status = Lot.LotStatus.COLLECTED;

        LotResource resource = newResource(false, false);
        GraphQLException exception = assertThrows(GraphQLException.class, () -> resource.updateLot(1L, input));
        assertInstanceOf(SecurityException.class, exception.getCause());
    }

    @Test
    void updateLot_nullStatusAndLines_leavesLotUntouched() throws Exception {
        Lot lot = lot(1L, store(STORE_ID));
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(lot);
        stubBadgeLookup(employee(store(STORE_ID)));

        LotResource.LotRecord input = new LotResource.LotRecord();

        Lot result = newResource(false, false).updateLot(1L, input);

        assertSame(lot, result);
        assertEquals(Lot.LotStatus.PENDING, lot.status);
        verify(lotService, never()).synchronizeLotLines(any(), any());
    }
}
