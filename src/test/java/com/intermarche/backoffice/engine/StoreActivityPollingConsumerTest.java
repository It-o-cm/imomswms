package com.intermarche.backoffice.engine;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.WMSStoreHandle;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StoreActivityPollingConsumer}.
 * The {@link StoreActivityEngine} and the {@link EntityManager} are mocked;
 * Panache statics ({@code WMSStoreHandle.reserveBatch}, {@code findById}) are stubbed.
 */
class StoreActivityPollingConsumerTest {

    private StoreActivityPollingConsumer consumer;
    private StoreActivityEngine storeActivityEngine;
    private EntityManager em;

    @BeforeEach
    void setUp() {
        consumer = new StoreActivityPollingConsumer();
        storeActivityEngine = mock(StoreActivityEngine.class);
        em = mock(EntityManager.class);
        consumer.storeActivityEngine = storeActivityEngine;
        consumer.em = em;
    }

    @Test
    void reserveStores_delegatesWithOrderByClause() {
        try (MockedStatic<WMSStoreHandle> handleMock = mockStatic(WMSStoreHandle.class)) {
            handleMock.when(() -> WMSStoreHandle.reserveBatch(300L, 100, "", "ORDER BY reservation_consideration ASC"))
                    .thenReturn(List.of(3L));

            assertEquals(List.of(3L), consumer.reserveStores());
            handleMock.verify(() -> WMSStoreHandle.reserveBatch(300L, 100, "", "ORDER BY reservation_consideration ASC"));
        }
    }

    @Test
    void pollAndProcessActivities_processesFullBatchThenStops() throws Exception {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.id = 1L;
        handle.store = new Store();
        TypedQuery<WMSStoreHandle> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(WMSStoreHandle.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(handle));

        try (MockedStatic<WMSStoreHandle> handleMock = mockStatic(WMSStoreHandle.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            AtomicInteger calls = new AtomicInteger();
            handleMock.when(() -> WMSStoreHandle.reserveBatch(anyLong(), anyInt(), anyString(), anyString()))
                    .thenAnswer(inv -> calls.incrementAndGet() == 1
                            ? LongStream.rangeClosed(1, 100).boxed().collect(Collectors.toList())
                            : List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(handle);

            consumer.pollAndProcessActivities();

            assertEquals(2, calls.get());
            verify(storeActivityEngine).process(handle.store);
            assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status);
            assertNotNull(handle.lastReservationDate);
            verify(em).merge(handle);
        }
    }

    @Test
    void pollAndProcessActivities_emptyBatch_doesNotProcess() {
        try (MockedStatic<WMSStoreHandle> handleMock = mockStatic(WMSStoreHandle.class)) {
            handleMock.when(() -> WMSStoreHandle.reserveBatch(anyLong(), anyInt(), anyString(), anyString()))
                    .thenReturn(List.of());

            consumer.pollAndProcessActivities();

            verifyNoInteractions(em);
            verifyNoInteractions(storeActivityEngine);
        }
    }

    @Test
    void pollAndProcessActivities_reserveThrows_swallowsException() {
        try (MockedStatic<WMSStoreHandle> handleMock = mockStatic(WMSStoreHandle.class)) {
            handleMock.when(() -> WMSStoreHandle.reserveBatch(anyLong(), anyInt(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("db down"));

            assertDoesNotThrow(() -> consumer.pollAndProcessActivities());
        }
    }

    @Test
    void processStores_fetchesAndProcessesEachHandle() throws Exception {
        WMSStoreHandle h1 = new WMSStoreHandle();
        h1.id = 1L;
        h1.store = new Store();
        WMSStoreHandle h2 = new WMSStoreHandle();
        h2.id = 2L;
        h2.store = new Store();
        TypedQuery<WMSStoreHandle> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(WMSStoreHandle.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(h1, h2));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any()))
                    .thenAnswer(inv -> {
                        Object id = inv.getArgument(0);
                        return id.equals(1L) ? h1 : h2;
                    });

            consumer.processStores(List.of(1L, 2L));

            verify(storeActivityEngine).process(h1.store);
            verify(storeActivityEngine).process(h2.store);
        }
    }

    @Test
    void processSingleStore_nullHandle_doesNothing() {
        assertDoesNotThrow(() -> consumer.processSingleStore(null));

        verifyNoInteractions(storeActivityEngine);
        verifyNoInteractions(em);
    }

    @Test
    void processSingleStore_notFoundAfterRefresh_skipsEngineButResetsReservation() throws Exception {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.id = 3L;
        handle.store = new Store();

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(3L)).thenReturn(null);

            consumer.processSingleStore(handle);

            verify(storeActivityEngine, never()).process(any());
            // Le bloc finally réinitialise quand même la réservation sur le handle d'origine
            assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status);
            assertNotNull(handle.lastReservationDate);
            verify(em).merge(handle);
        }
    }

    @Test
    void processSingleStore_success_resetsReservationToPending() throws Exception {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.id = 1L;
        handle.store = new Store();
        handle.store.id = 9L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(handle);

            consumer.processSingleStore(handle);

            verify(storeActivityEngine).process(handle.store);
            assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status);
            assertNotNull(handle.lastReservationDate);
            verify(em).merge(handle);
        }
    }

    @Test
    void processSingleStore_engineThrows_stillResetsReservation() throws Exception {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.id = 2L;
        handle.store = new Store();
        handle.store.id = 9L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(2L)).thenReturn(handle);
            doThrow(new RuntimeException("boom")).when(storeActivityEngine).process(any(Store.class));

            consumer.processSingleStore(handle);

            assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status);
            assertNotNull(handle.lastReservationDate);
            verify(em).merge(handle);
        }
    }

    @Test
    void createMissingStoreHandles_noStores_exitsImmediately() {
        Query nativeQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getResultList()).thenReturn(List.of());

        consumer.createMissingStoreHandles();

        verify(em, never()).flush();
        verify(em, never()).clear();
    }

    @Test
    void createMissingStoreHandles_createsHandlesUntilNoStoresLeft() {
        Query nativeQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        AtomicInteger calls = new AtomicInteger();
        when(nativeQuery.getResultList())
                .thenAnswer(inv -> calls.incrementAndGet() == 1 ? List.of(11L, 12L) : List.of());

        Store store1 = new Store();
        store1.id = 11L;
        Store store2 = new Store();
        store2.id = 12L;
        when(em.getReference(eq(Store.class), eq(11L))).thenReturn(store1);
        when(em.getReference(eq(Store.class), eq(12L))).thenReturn(store2);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            consumer.createMissingStoreHandles();

            verify(session, times(2)).persist(any(WMSStoreHandle.class));
        }
        verify(em).flush();
        verify(em).clear();
    }

    @Test
    void createMissingStoreHandles_handleCreationFails_continuesBatch() {
        StoreActivityPollingConsumer spy = spy(consumer);
        doReturn(List.of(11L)).doReturn(List.of()).when(spy).findStoreIdsWithoutHandle(anyInt());
        doReturn(false).when(spy).createHandleForStore(any());

        spy.createMissingStoreHandles();

        verify(spy).createHandleForStore(11L);
        verify(em).flush();
        verify(em).clear();
    }

    @Test
    void findStoreIdsWithoutHandle_returnsStoreIds() {
        Query nativeQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getResultList()).thenReturn(List.of(5L, 6L));

        assertEquals(List.of(5L, 6L), consumer.findStoreIdsWithoutHandle(100));
        verify(nativeQuery).setParameter(1, 100);
    }

    @Test
    void createHandleForStore_success_persistsHandle() {
        Store store = new Store();
        store.id = 21L;
        when(em.getReference(eq(Store.class), eq(21L))).thenReturn(store);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            assertTrue(consumer.createHandleForStore(21L));

            verify(session).persist(any(WMSStoreHandle.class));
        }
    }

    @Test
    void createHandleForStore_persistThrows_returnsFalse() {
        Store store = new Store();
        store.id = 22L;
        when(em.getReference(eq(Store.class), eq(22L))).thenReturn(store);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            doThrow(new RuntimeException("constraint violation")).when(session).persist(any(WMSStoreHandle.class));

            assertFalse(consumer.createHandleForStore(22L));
        }
    }

    @Test
    void pollAndProcess_createsHandlesThenProcessesActivities() {
        Query nativeQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getResultList()).thenReturn(List.of());

        try (MockedStatic<WMSStoreHandle> handleMock = mockStatic(WMSStoreHandle.class)) {
            handleMock.when(() -> WMSStoreHandle.reserveBatch(anyLong(), anyInt(), anyString(), anyString()))
                    .thenReturn(List.of());

            consumer.pollAndProcess();

            handleMock.verify(() -> WMSStoreHandle.reserveBatch(anyLong(), anyInt(), anyString(), anyString()));
        }
    }
}
