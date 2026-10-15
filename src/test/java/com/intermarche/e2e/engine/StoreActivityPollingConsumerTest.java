package com.intermarche.e2e.engine;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.WMSStoreHandle;
import com.intermarche.backoffice.engine.StoreActivityEngine;
import com.intermarche.backoffice.engine.StoreActivityPollingConsumer;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link StoreActivityPollingConsumer}.
 * <p>
 * The scheduler is disabled ({@link SchedulerDisabledTestProfile}): the consumer is driven
 * manually. The {@link StoreActivityEngine} is mocked to isolate the consumer logic
 * (handle creation, reservation batching, per-store processing, failure handling).
 * <p>
 * Uses {@link TestTransaction} to rollback changes after each test.
 */
@QuarkusTest
@TestProfile(SchedulerDisabledTestProfile.class)
@TestTransaction
public class StoreActivityPollingConsumerTest {

    @Inject
    StoreActivityPollingConsumer consumer;

    @InjectMock
    StoreActivityEngine storeActivityEngine;

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private WMSStoreHandle createHandle(Store store) {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.store = store;
        handle.persist();
        return handle;
    }

    // --------------------------------------------------
    // createMissingStoreHandles
    // --------------------------------------------------

    @Test
    void testCreateMissingStoreHandles_CreatesHandlesForStoresWithoutHandle() {
        Store s1 = DomainUtils.createAndPersistStore("SAP_S1", 48.0, 2.0);
        Store s2 = DomainUtils.createAndPersistStore("SAP_S2", 48.0, 2.0);

        consumer.createMissingStoreHandles();

        // assertions are scoped to the fixtures: the shared test database may hold
        // stores/handles committed by other test classes
        List<WMSStoreHandle> handles = WMSStoreHandle.find("store.id in ?1", List.of(s1.id, s2.id)).list();
        assertEquals(2, handles.size(), "A handle must have been created for each store");
        for (WMSStoreHandle handle : handles) {
            assertNotNull(handle.store);
            assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status);
        }
        assertTrue(handles.stream().anyMatch(h -> h.store.id.equals(s1.id)));
        assertTrue(handles.stream().anyMatch(h -> h.store.id.equals(s2.id)));
    }

    @Test
    void testCreateMissingStoreHandles_DoesNotRecreateExistingHandles() {
        Store withHandle = DomainUtils.createAndPersistStore("SAP_S3", 48.0, 2.0);
        WMSStoreHandle existing = createHandle(withHandle);
        Store withoutHandle = DomainUtils.createAndPersistStore("SAP_S4", 48.0, 2.0);

        consumer.createMissingStoreHandles();

        assertEquals(1, WMSStoreHandle.count("store.id", withHandle.id), "The existing handle must be preserved");
        assertEquals(1, WMSStoreHandle.count("store.id", withoutHandle.id), "A new handle must have been created");
        WMSStoreHandle reloaded = WMSStoreHandle.findById(existing.id);
        assertNotNull(reloaded, "The pre-existing handle must be preserved");
        assertEquals(withHandle.id, reloaded.store.id);
    }

    @Test
    void testCreateMissingStoreHandles_AllStoresHaveHandles_NothingCreated() {
        Store store = DomainUtils.createAndPersistStore("SAP_S5", 48.0, 2.0);
        createHandle(store);

        consumer.createMissingStoreHandles();

        assertEquals(1, WMSStoreHandle.count("store.id", store.id));
    }

    // --------------------------------------------------
    // findStoreIdsWithoutHandle
    // --------------------------------------------------

    @Test
    void testFindStoreIdsWithoutHandle_ReturnsOnlyStoresWithoutHandle() {
        Store withHandle = DomainUtils.createAndPersistStore("SAP_S6", 48.0, 2.0);
        createHandle(withHandle);
        Store withoutHandle = DomainUtils.createAndPersistStore("SAP_S7", 48.0, 2.0);

        List<Long> ids = consumer.findStoreIdsWithoutHandle(100);

        assertTrue(ids.contains(withoutHandle.id), "The store without handle must be returned");
        assertFalse(ids.contains(withHandle.id), "The store with a handle must be excluded");
    }

    @Test
    void testFindStoreIdsWithoutHandle_RespectsLimit() {
        DomainUtils.createAndPersistStore("SAP_S8", 48.0, 2.0);
        DomainUtils.createAndPersistStore("SAP_S9", 48.0, 2.0);
        DomainUtils.createAndPersistStore("SAP_S10", 48.0, 2.0);

        List<Long> ids = consumer.findStoreIdsWithoutHandle(2);

        assertEquals(2, ids.size());
    }

    // --------------------------------------------------
    // createHandleForStore
    // --------------------------------------------------

    @Test
    void testCreateHandleForStore_CreatesAndPersistsHandle() {
        Store store = DomainUtils.createAndPersistStore("SAP_S11", 48.0, 2.0);

        boolean created = consumer.createHandleForStore(store.id);

        assertTrue(created);
        WMSStoreHandle handle = WMSStoreHandle.find("store.id", store.id).firstResult();
        assertNotNull(handle);
        assertEquals(store.id, handle.store.id);
        assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status);
    }

    // --------------------------------------------------
    // processSingleStore
    // --------------------------------------------------

    @Test
    void testProcessSingleStore_EngineSuccess_ResetsReservationToPending() throws Exception {
        Store store = DomainUtils.createAndPersistStore("SAP_S12", 48.0, 2.0);
        WMSStoreHandle handle = createHandle(store);
        // simulate an active reservation
        handle.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
        handle.reservationRecord.leasing = Instant.now().plusSeconds(300);
        doNothing().when(storeActivityEngine).process(any(Store.class));

        consumer.processSingleStore(handle);

        verify(storeActivityEngine, times(1)).process(argThat(s -> s != null && s.id.equals(store.id)));
        assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status,
                "After processing, the reservation must be released (PENDING)");
        assertNotNull(handle.lastReservationDate, "The last reservation date must be updated");
        assertTrue(handle.lastReservationDate.isAfter(LocalDateTime.now().minusMinutes(1)));
    }

    @Test
    void testProcessSingleStore_EngineThrows_StillResetsReservation() throws Exception {
        Store store = DomainUtils.createAndPersistStore("SAP_S13", 48.0, 2.0);
        WMSStoreHandle handle = createHandle(store);
        handle.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
        handle.reservationRecord.leasing = Instant.now().plusSeconds(300);
        doThrow(new RuntimeException("engine failure")).when(storeActivityEngine).process(any(Store.class));

        assertDoesNotThrow(() -> consumer.processSingleStore(handle));

        assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status,
                "Even on failure, the reservation must be released (PENDING)");
        assertNotNull(handle.lastReservationDate, "The last reservation date must be updated even on failure");
    }

    // --------------------------------------------------
    // reserveStores
    // --------------------------------------------------

    @Test
    void testReserveStores_ReservesPendingHandles() {
        Store s1 = DomainUtils.createAndPersistStore("SAP_S14", 48.0, 2.0);
        Store s2 = DomainUtils.createAndPersistStore("SAP_S15", 48.0, 2.0);
        WMSStoreHandle h1 = createHandle(s1);
        WMSStoreHandle h2 = createHandle(s2);

        List<Long> reserved = consumer.reserveStores();

        assertTrue(reserved.contains(h1.id));
        assertTrue(reserved.contains(h2.id));
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, h1.reservationRecord.status);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, h2.reservationRecord.status);
        assertNotNull(h1.reservationRecord.leasing, "A lease expiration must be set");
        assertTrue(h1.reservationRecord.leasing.isAfter(Instant.now()),
                "The lease must expire in the future");
    }

    @Test
    void testReserveStores_DoesNotReserveTwice() {
        Store store = DomainUtils.createAndPersistStore("SAP_S16", 48.0, 2.0);
        WMSStoreHandle handle = createHandle(store);

        List<Long> firstBatch = consumer.reserveStores();
        assertTrue(firstBatch.contains(handle.id));

        List<Long> secondBatch = consumer.reserveStores();
        assertFalse(secondBatch.contains(handle.id), "An already reserved handle must not be reserved again");
    }

    // --------------------------------------------------
    // processStores
    // --------------------------------------------------

    @Test
    void testProcessStores_CallsEngineForEachHandle() throws Exception {
        Store s1 = DomainUtils.createAndPersistStore("SAP_S17", 48.0, 2.0);
        Store s2 = DomainUtils.createAndPersistStore("SAP_S18", 48.0, 2.0);
        WMSStoreHandle h1 = createHandle(s1);
        WMSStoreHandle h2 = createHandle(s2);
        doNothing().when(storeActivityEngine).process(any(Store.class));

        consumer.processStores(List.of(h1.id, h2.id));

        verify(storeActivityEngine, times(1)).process(argThat(s -> s != null && s.id.equals(s1.id)));
        verify(storeActivityEngine, times(1)).process(argThat(s -> s != null && s.id.equals(s2.id)));
    }

    // --------------------------------------------------
    // pollAndProcessActivities / pollAndProcess (full cycles)
    // --------------------------------------------------

    @Test
    void testPollAndProcessActivities_ProcessesPendingHandles() throws Exception {
        Store store = DomainUtils.createAndPersistStore("SAP_S19", 48.0, 2.0);
        WMSStoreHandle handle = createHandle(store);
        doNothing().when(storeActivityEngine).process(any(Store.class));

        consumer.pollAndProcessActivities();

        verify(storeActivityEngine, times(1)).process(argThat(s -> s != null && s.id.equals(store.id)));
        assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status,
                "The handle must have been reserved then released by the processing");
        assertNotNull(handle.lastReservationDate);
    }

    @Test
    void testPollAndProcessActivities_StoreWithoutHandle_IsNotProcessed() throws Exception {
        Store store = DomainUtils.createAndPersistStore("SAP_S20", 48.0, 2.0);

        consumer.pollAndProcessActivities();

        verify(storeActivityEngine, never()).process(argThat(s -> s != null && s.id.equals(store.id)));
    }

    @Test
    void testPollAndProcess_CreatesMissingHandlesAndProcessesThem() throws Exception {
        Store store = DomainUtils.createAndPersistStore("SAP_S21", 48.0, 2.0);
        doNothing().when(storeActivityEngine).process(any(Store.class));

        consumer.pollAndProcess();

        WMSStoreHandle handle = WMSStoreHandle.find("store.id", store.id).firstResult();
        assertNotNull(handle, "A handle must have been created for the store");
        verify(storeActivityEngine, times(1)).process(argThat(s -> s != null && s.id.equals(store.id)));
        assertEquals(ReservationRecord.ReservationStatus.PENDING, handle.reservationRecord.status);
        assertNotNull(handle.lastReservationDate);
    }
}
