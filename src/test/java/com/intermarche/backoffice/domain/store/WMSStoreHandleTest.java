package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.ReservationRecord;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link WMSStoreHandle} business logic.
 * Focuses on {@link WMSStoreHandle#startProcessing(long)} and checksum sensitivity.
 */
public class WMSStoreHandleTest {

    // --------------------------------------------------
    // startProcessing
    // --------------------------------------------------

    @Test
    void startProcessing_shouldCreateReservationRecord_whenNull() {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.reservationRecord = null;

        handle.startProcessing(60);

        assertNotNull(handle.reservationRecord);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, handle.reservationRecord.status);
    }

    @Test
    void startProcessing_shouldSetLeaseInTheFuture() {
        WMSStoreHandle handle = new WMSStoreHandle();

        handle.startProcessing(3600);

        assertNotNull(handle.reservationRecord.leasing);
        assertTrue(handle.reservationRecord.leasing.isAfter(java.time.Instant.now()));
    }

    @Test
    void startProcessing_shouldUpdateLastReservationDate() {
        WMSStoreHandle handle = new WMSStoreHandle();
        assertNull(handle.lastReservationDate);

        LocalDateTime before = LocalDateTime.now();
        handle.startProcessing(60);
        LocalDateTime after = LocalDateTime.now();

        assertNotNull(handle.lastReservationDate);
        assertFalse(handle.lastReservationDate.isBefore(before));
        assertFalse(handle.lastReservationDate.isAfter(after));
    }

    @Test
    void startProcessing_shouldReuseExistingReservationRecord() {
        WMSStoreHandle handle = new WMSStoreHandle();
        ReservationRecord existing = new ReservationRecord();
        handle.reservationRecord = existing;

        handle.startProcessing(60);

        assertSame(existing, handle.reservationRecord);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, existing.status);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenLastReservationDateChanges() {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.lastReservationDate = LocalDateTime.of(2026, 1, 1, 10, 0);
        int checksum1 = handle.getChecksum();

        handle.lastReservationDate = LocalDateTime.of(2026, 1, 2, 10, 0);
        assertNotEquals(checksum1, handle.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenStoreIdChanges() {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.store = createStore(1L);
        int checksum1 = handle.getChecksum();

        handle.store = createStore(2L);
        assertNotEquals(checksum1, handle.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenStoreIsNull() {
        WMSStoreHandle handle = new WMSStoreHandle();
        handle.store = null;
        assertDoesNotThrow(handle::getChecksum);
    }

    private Store createStore(Long id) {
        Store store = new Store();
        store.id = id;
        store.code = "S" + id;
        return store;
    }

    // --------------------------------------------------
    // reserveBatch
    // --------------------------------------------------

    @Test
    void reserveBatch_threeArg_shouldDelegateToFourArg_withEmptyOrderBy() {
        try (MockedStatic<WMSStoreHandle> mocked = mockStatic(WMSStoreHandle.class)) {
            mocked.when(() -> WMSStoreHandle.reserveBatch(anyLong(), anyInt(), anyString(), anyString()))
                    .thenReturn(List.of(9L));
            mocked.when(() -> WMSStoreHandle.reserveBatch(anyLong(), anyInt(), anyString()))
                    .thenCallRealMethod();

            List<Long> result = WMSStoreHandle.reserveBatch(60L, 10, "store_id = 1");

            assertEquals(List.of(9L), result);
            mocked.verify(() -> WMSStoreHandle.reserveBatch(60L, 10, "store_id = 1", ""));
        }
    }
}
