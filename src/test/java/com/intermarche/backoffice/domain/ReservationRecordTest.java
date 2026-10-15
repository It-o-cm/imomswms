package com.intermarche.backoffice.domain;

import com.intermarche.backoffice.domain.util.DateTimeProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReservationRecord}.
 * Tests the lease management, status transitions, SQL condition generation,
 * and value object equality.
 */
public class ReservationRecordTest {

    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // startLease Tests
    // --------------------------------------------------

    @Test
    void startLease_shouldSetStatusToProcessing() {
        ReservationRecord record = new ReservationRecord();
        assertEquals(ReservationRecord.ReservationStatus.PENDING, record.status);

        record.startLease(60);

        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, record.status);
    }

    @Test
    void startLease_shouldSetLeasingToNowPlusDuration() {
        LocalDateTime fixedNow = LocalDateTime.of(2026, 1, 15, 10, 0, 0);
        DateTimeProvider.setFixedDateTime(fixedNow);

        ReservationRecord record = new ReservationRecord();
        record.startLease(120);

        Instant expectedLeasing = fixedNow.atZone(ZoneId.systemDefault()).toInstant().plusSeconds(120);
        assertEquals(expectedLeasing, record.leasing);
    }

    @Test
    void startLease_shouldOverwritePreviousLease() {
        LocalDateTime firstTime = LocalDateTime.of(2026, 1, 15, 10, 0, 0);
        DateTimeProvider.setFixedDateTime(firstTime);

        ReservationRecord record = new ReservationRecord();
        record.startLease(60);
        Instant firstLeasing = record.leasing;

        LocalDateTime secondTime = LocalDateTime.of(2026, 1, 15, 10, 5, 0);
        DateTimeProvider.setFixedDateTime(secondTime);
        record.startLease(60);

        assertNotEquals(firstLeasing, record.leasing);
        Instant expectedSecond = secondTime.atZone(ZoneId.systemDefault()).toInstant().plusSeconds(60);
        assertEquals(expectedSecond, record.leasing);
    }

    // --------------------------------------------------
    // isLeaseExpired Tests
    // --------------------------------------------------

    @Test
    void isLeaseExpired_shouldReturnFalse_whenStatusIsPending() {
        ReservationRecord record = new ReservationRecord();
        record.status = ReservationRecord.ReservationStatus.PENDING;
        record.leasing = Instant.now().minusSeconds(3600); // 1 hour ago

        assertFalse(record.isLeaseExpired());
    }

    @Test
    void isLeaseExpired_shouldReturnFalse_whenLeasingIsNull() {
        ReservationRecord record = new ReservationRecord();
        record.status = ReservationRecord.ReservationStatus.PROCESSING;
        record.leasing = null;

        assertFalse(record.isLeaseExpired());
    }

    @Test
    void isLeaseExpired_shouldReturnFalse_whenLeaseIsInFuture() {
        ReservationRecord record = new ReservationRecord();
        record.status = ReservationRecord.ReservationStatus.PROCESSING;
        record.leasing = Instant.now().plusSeconds(3600); // 1 hour in future

        assertFalse(record.isLeaseExpired());
    }

    @Test
    void isLeaseExpired_shouldReturnTrue_whenLeaseIsInPast() {
        ReservationRecord record = new ReservationRecord();
        record.status = ReservationRecord.ReservationStatus.PROCESSING;
        record.leasing = Instant.now().minusSeconds(1); // 1 second ago

        assertTrue(record.isLeaseExpired());
    }

    @Test
    void isLeaseExpired_shouldReturnFalse_whenStatusIsDone() {
        ReservationRecord record = new ReservationRecord();
        record.status = ReservationRecord.ReservationStatus.DONE;
        record.leasing = Instant.now().minusSeconds(3600);

        assertFalse(record.isLeaseExpired());
    }

    // --------------------------------------------------
    // getReservableSqlCondition Tests
    // --------------------------------------------------

    @Test
    void getReservableSqlCondition_shouldIncludePendingStatus() {
        String sql = ReservationRecord.getReservableSqlCondition("status", "leasing", "consideration");

        assertTrue(sql.contains("status = 'PENDING'"));
    }

    @Test
    void getReservableSqlCondition_shouldIncludeExpiredProcessingStatus() {
        String sql = ReservationRecord.getReservableSqlCondition("status", "leasing", "consideration");

        assertTrue(sql.contains("status = 'PROCESSING'"));
        assertTrue(sql.contains("leasing IS NOT NULL"));
        assertTrue(sql.contains("leasing < CURRENT_TIMESTAMP"));
    }

    @Test
    void getReservableSqlCondition_shouldIncludeConsiderationClause() {
        String sql = ReservationRecord.getReservableSqlCondition("status", "leasing", "consideration");

        assertTrue(sql.contains("consideration IS NULL"));
        assertTrue(sql.contains("consideration <= CURRENT_TIMESTAMP"));
    }

    @Test
    void getReservableSqlCondition_shouldCombineClausesWithAnd() {
        String sql = ReservationRecord.getReservableSqlCondition("my_status", "my_leasing", "my_consideration");

        assertTrue(sql.contains(" AND "));
        assertTrue(sql.contains("my_status"));
        assertTrue(sql.contains("my_leasing"));
        assertTrue(sql.contains("my_consideration"));
    }

    // --------------------------------------------------
    // Constructor Tests
    // --------------------------------------------------

    @Test
    void defaultConstructor_shouldSetStatusToPending() {
        ReservationRecord record = new ReservationRecord();

        assertEquals(ReservationRecord.ReservationStatus.PENDING, record.status);
        assertNull(record.leasing);
        assertNull(record.consideration);
    }

    @Test
    void parameterizedConstructor_shouldSetStatusAndLeasing() {
        Instant leasing = Instant.now().plusSeconds(60);
        ReservationRecord record = new ReservationRecord(ReservationRecord.ReservationStatus.PROCESSING, leasing);

        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, record.status);
        assertEquals(leasing, record.leasing);
    }

    // --------------------------------------------------
    // equals / hashCode Tests
    // --------------------------------------------------

    @Test
    void equals_shouldReturnTrue_forSameStatusAndLeasing() {
        Instant leasing = Instant.now();
        ReservationRecord r1 = new ReservationRecord(ReservationRecord.ReservationStatus.PROCESSING, leasing);
        ReservationRecord r2 = new ReservationRecord(ReservationRecord.ReservationStatus.PROCESSING, leasing);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void equals_shouldReturnFalse_forDifferentStatus() {
        Instant leasing = Instant.now();
        ReservationRecord r1 = new ReservationRecord(ReservationRecord.ReservationStatus.PENDING, leasing);
        ReservationRecord r2 = new ReservationRecord(ReservationRecord.ReservationStatus.PROCESSING, leasing);

        assertNotEquals(r1, r2);
    }

    @Test
    void equals_shouldReturnFalse_forDifferentLeasing() {
        ReservationRecord r1 = new ReservationRecord(ReservationRecord.ReservationStatus.PROCESSING, Instant.now());
        ReservationRecord r2 = new ReservationRecord(ReservationRecord.ReservationStatus.PROCESSING, Instant.now().plusSeconds(60));

        assertNotEquals(r1, r2);
    }

    @Test
    void equals_shouldReturnTrue_forSameInstance() {
        ReservationRecord record = new ReservationRecord();
        assertTrue(record.equals(record));
    }

    @Test
    void equals_shouldReturnFalse_forNull() {
        ReservationRecord record = new ReservationRecord();
        assertNotEquals(record, null);
    }

    @Test
    void equals_shouldReturnFalse_forDifferentClass() {
        ReservationRecord record = new ReservationRecord();
        assertNotEquals(record, "not a reservation record");
    }

    // --------------------------------------------------
    // reserveBatch Tests
    // --------------------------------------------------

    /**
     * Dummy entity with a public Long id and an embedded record.
     */
    public static class DummyEntity {
        public Long id;
        public ReservationRecord record = new ReservationRecord();
    }

    /**
     * Dummy entity whose record accessor returns null.
     */
    public static class NullRecordEntity {
        public Long id;
        public ReservationRecord record = null;
    }

    /**
     * Dummy entity whose id is not a Long.
     */
    public static class StringIdEntity {
        public String id;
        public ReservationRecord record = new ReservationRecord();
    }

    /**
     * Dummy entity without any id field.
     */
    public static class NoIdEntity {
        public ReservationRecord record = new ReservationRecord();
    }

    private EntityManager mockEntityManagerReturning(List<?> entities) {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString(), any(Class.class))).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(entities);
        return em;
    }

    @Test
    void reserveBatch_shouldReserveEntitiesAndApplyLease() {
        DummyEntity entity = new DummyEntity();
        entity.id = 42L;
        EntityManager em = mockEntityManagerReturning(List.of(entity));

        List<Long> reserved = ReservationRecord.reserveBatch(
                em, DummyEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, "store_id = 1", "ORDER BY id ASC");

        assertEquals(List.of(42L), reserved);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, entity.record.status);
        assertNotNull(entity.record.leasing);
        assertNotNull(entity.record.consideration);
    }

    @Test
    void reserveBatch_shouldAppendExtraWhereAndOrderByToSql() {
        DummyEntity entity = new DummyEntity();
        entity.id = 1L;
        EntityManager em = mockEntityManagerReturning(List.of(entity));

        ReservationRecord.reserveBatch(
                em, DummyEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, "store_id = 1", "ORDER BY id ASC");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sqlCaptor.capture(), eq(DummyEntity.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.startsWith("SELECT e.* FROM my_table e WHERE "));
        assertTrue(sql.contains(" AND store_id = 1"));
        assertTrue(sql.contains(" ORDER BY id ASC"));
        assertTrue(sql.endsWith(" LIMIT ?1 FOR UPDATE SKIP LOCKED"));
    }

    @Test
    void reserveBatch_shouldOmitExtraWhereAndOrderBy_whenNull() {
        DummyEntity entity = new DummyEntity();
        entity.id = 1L;
        EntityManager em = mockEntityManagerReturning(List.of(entity));

        ReservationRecord.reserveBatch(
                em, DummyEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sqlCaptor.capture(), eq(DummyEntity.class));
        String sql = sqlCaptor.getValue();
        assertFalse(sql.contains("ORDER BY"));
        assertTrue(sql.endsWith(" LIMIT ?1 FOR UPDATE SKIP LOCKED"));
    }

    @Test
    void reserveBatch_shouldOmitExtraWhereAndOrderBy_whenBlank() {
        DummyEntity entity = new DummyEntity();
        entity.id = 1L;
        EntityManager em = mockEntityManagerReturning(List.of(entity));

        ReservationRecord.reserveBatch(
                em, DummyEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, "   ", "  ");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sqlCaptor.capture(), eq(DummyEntity.class));
        String sql = sqlCaptor.getValue();
        assertFalse(sql.contains("ORDER BY"));
        assertTrue(sql.endsWith(" LIMIT ?1 FOR UPDATE SKIP LOCKED"));
    }

    @Test
    void reserveBatch_shouldReturnEmptyList_whenNoEntitiesFound() {
        EntityManager em = mockEntityManagerReturning(List.of());

        List<Long> reserved = ReservationRecord.reserveBatch(
                em, DummyEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, "", "");

        assertTrue(reserved.isEmpty());
    }

    @Test
    void reserveBatch_shouldSkipEntity_whenRecordIsNull() {
        NullRecordEntity entity = new NullRecordEntity();
        entity.id = 7L;
        EntityManager em = mockEntityManagerReturning(List.of(entity));

        List<Long> reserved = ReservationRecord.reserveBatch(
                em, NullRecordEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, "", "");

        assertTrue(reserved.isEmpty());
    }

    @Test
    void reserveBatch_shouldSkipEntity_whenIdIsNotLong() {
        StringIdEntity entity = new StringIdEntity();
        entity.id = "ABC";
        EntityManager em = mockEntityManagerReturning(List.of(entity));

        List<Long> reserved = ReservationRecord.reserveBatch(
                em, StringIdEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, "", "");

        assertTrue(reserved.isEmpty());
        // The lease is still applied even if the id cannot be collected
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, entity.record.status);
    }

    @Test
    void reserveBatch_shouldThrowRuntimeException_whenIdFieldIsMissing() {
        NoIdEntity entity = new NoIdEntity();
        EntityManager em = mockEntityManagerReturning(List.of(entity));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> ReservationRecord.reserveBatch(
                em, NoIdEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, "", ""));

        assertEquals("Failed to extract ID from entity", ex.getMessage());
    }

    @Test
    void reserveBatch_tenArgOverload_shouldDelegateWithEmptyOrderBy() {
        DummyEntity entity = new DummyEntity();
        entity.id = 3L;
        EntityManager em = mockEntityManagerReturning(List.of(entity));

        List<Long> reserved = ReservationRecord.reserveBatch(
                em, DummyEntity.class, "my_table", "status", "leasing", "consideration",
                e -> e.record, 60L, 10, "store_id = 2");

        assertEquals(List.of(3L), reserved);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sqlCaptor.capture(), eq(DummyEntity.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains(" AND store_id = 2"));
        assertFalse(sql.contains("ORDER BY"));
    }

    // --------------------------------------------------
    // ReservationStatus Enum Tests
    // --------------------------------------------------

    @Test
    void reservationStatus_shouldHaveAllExpectedValues() {
        assertNotNull(ReservationRecord.ReservationStatus.valueOf("PENDING"));
        assertNotNull(ReservationRecord.ReservationStatus.valueOf("PROCESSING"));
        assertNotNull(ReservationRecord.ReservationStatus.valueOf("BLOCKED"));
        assertNotNull(ReservationRecord.ReservationStatus.valueOf("DONE"));
        assertNotNull(ReservationRecord.ReservationStatus.valueOf("FAILED"));
        assertNotNull(ReservationRecord.ReservationStatus.valueOf("CLOSED"));
        assertEquals(6, ReservationRecord.ReservationStatus.values().length);
    }
}
