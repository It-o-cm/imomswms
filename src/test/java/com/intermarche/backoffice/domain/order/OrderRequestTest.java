package com.intermarche.backoffice.domain.order;

import com.intermarche.backoffice.domain.ReservationRecord;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderRequest} business logic.
 * Focuses on constructors, {@link OrderRequest#startProcessing(String, String, String)}
 * and checksum sensitivity.
 */
public class OrderRequestTest {

    // --------------------------------------------------
    // Constructors
    // --------------------------------------------------

    @Test
    void defaultConstructor_shouldInitializeReservationRecord() {
        OrderRequest request = new OrderRequest();

        assertNotNull(request.reservationRecord);
        assertEquals(ReservationRecord.ReservationStatus.PENDING, request.reservationRecord.status);
    }

    @Test
    void convenienceConstructor_shouldSetFieldsAndInitializeReservationRecord() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{\"items\":[]}");

        assertEquals("ORD-001", request.orderNumber);
        assertEquals("S001", request.storeCode);
        assertEquals("{\"items\":[]}", request.specification);
        assertNotNull(request.reservationRecord);
        assertEquals(ReservationRecord.ReservationStatus.PENDING, request.reservationRecord.status);
    }

    // --------------------------------------------------
    // startProcessing
    // --------------------------------------------------

    @Test
    void startProcessing_shouldSetStatusToProcessing() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{}");

        request.startProcessing("worker-1", "host-1", "pid-1");

        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, request.reservationRecord.status);
    }

    @Test
    void startProcessing_shouldCreateReservationRecord_whenNull() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{}");
        request.reservationRecord = null;

        request.startProcessing("worker-1", "host-1", "pid-1");

        assertNotNull(request.reservationRecord);
        assertEquals(ReservationRecord.ReservationStatus.PROCESSING, request.reservationRecord.status);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenOrderNumberChanges() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{}");
        int checksum1 = request.getChecksum();

        request.orderNumber = "ORD-002";
        assertNotEquals(checksum1, request.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenStoreCodeChanges() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{}");
        int checksum1 = request.getChecksum();

        request.storeCode = "S002";
        assertNotEquals(checksum1, request.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenSpecificationChanges() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{\"a\":1}");
        int checksum1 = request.getChecksum();

        request.specification = "{\"a\":2}";
        assertNotEquals(checksum1, request.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenReservationStatusChanges() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{}");
        int checksum1 = request.getChecksum();

        request.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
        assertNotEquals(checksum1, request.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenReservationRecordIsNull() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{}");
        request.reservationRecord = null;

        assertDoesNotThrow(request::getChecksum);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    @Test
    void findPendingByStore_shouldReturnPendingRequests() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{}");
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(
                            eq("storeCode = ?1 and reservationRecord.status = ?2"), any(Object[].class)))
                    .thenReturn(List.of(request));

            List<OrderRequest> result = OrderRequest.findPendingByStore("S001");

            assertEquals(1, result.size());
            assertSame(request, result.get(0));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserveBatch_shouldDelegateToReservationRecord_withRecordAccessor() {
        OrderRequest request = new OrderRequest("ORD-001", "S001", "{}");
        try (MockedStatic<Panache> panacheMock = mockStatic(Panache.class);
             MockedStatic<ReservationRecord> recordMock = mockStatic(ReservationRecord.class)) {
            EntityManager em = mock(EntityManager.class);
            panacheMock.when(Panache::getEntityManager).thenReturn(em);
            recordMock.when(() -> ReservationRecord.reserveBatch(
                            any(EntityManager.class), eq(OrderRequest.class),
                            eq("order_requests"), eq("reservation_status"), eq("reservation_leasing"),
                            eq("reservation_consideration"), any(Function.class),
                            eq(60L), eq(10), eq("store_id = 1")))
                    .thenReturn(List.of(7L));

            List<Long> result = OrderRequest.reserveBatch(60L, 10, "store_id = 1");

            assertEquals(List.of(7L), result);
            // The accessor passed to ReservationRecord extracts the embedded record
            ArgumentCaptor<Function<OrderRequest, ReservationRecord>> captor = ArgumentCaptor.forClass(Function.class);
            recordMock.verify(() -> ReservationRecord.reserveBatch(
                    any(EntityManager.class), eq(OrderRequest.class),
                    eq("order_requests"), eq("reservation_status"), eq("reservation_leasing"),
                    eq("reservation_consideration"), captor.capture(),
                    eq(60L), eq(10), eq("store_id = 1")));
            assertSame(request.reservationRecord, captor.getValue().apply(request));
        }
    }
}
