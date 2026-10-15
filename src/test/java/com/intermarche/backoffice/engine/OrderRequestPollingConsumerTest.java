package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.order.OrderRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderRequestPollingConsumer#processSingleRequest}.
 * The {@link OrderRecorder} is mocked; tests assert the reservation status
 * transitions (DONE / FAILED) of the processed {@link OrderRequest}.
 */
class OrderRequestPollingConsumerTest {

    private OrderRequestPollingConsumer consumer;
    private OrderRecorder orderRecorder;

    @BeforeEach
    void setUp() {
        consumer = new OrderRequestPollingConsumer();
        consumer.objectMapper = new ObjectMapper();
        orderRecorder = mock(OrderRecorder.class);
        consumer.orderRecorder = orderRecorder;
    }

    @Test
    void processSingleRequest_invalidJson_marksFailed() {
        OrderRequest request = new OrderRequest("ORD-1", "STORE-1", "{invalid json");

        consumer.processSingleRequest(request);

        assertEquals(ReservationRecord.ReservationStatus.FAILED, request.reservationRecord.status);
        verifyNoInteractions(orderRecorder);
    }

    @Test
    void processSingleRequest_success_marksDone() throws Exception {
        OrderRequest request = new OrderRequest("ORD-1", "STORE-1", "{\"orderHeader\":{\"orderId\":\"ORD-1\"}}");
        doNothing().when(orderRecorder).persistOrderAndFulfillmentFromJson(any(JsonNode.class));

        consumer.processSingleRequest(request);

        assertEquals(ReservationRecord.ReservationStatus.DONE, request.reservationRecord.status);
        verify(orderRecorder).persistOrderAndFulfillmentFromJson(any(JsonNode.class));
    }

    @Test
    void processSingleRequest_recorderThrows_marksFailed() throws Exception {
        OrderRequest request = new OrderRequest("ORD-1", "STORE-1", "{\"orderHeader\":{}}");
        doThrow(new RuntimeException("persistence failure"))
                .when(orderRecorder).persistOrderAndFulfillmentFromJson(any(JsonNode.class));

        consumer.processSingleRequest(request);

        assertEquals(ReservationRecord.ReservationStatus.FAILED, request.reservationRecord.status);
    }

    @Test
    void orderRequest_defaultConstructor_initializesReservationRecord() {
        OrderRequest request = new OrderRequest();

        assertNotNull(request.reservationRecord);
        assertEquals(ReservationRecord.ReservationStatus.PENDING, request.reservationRecord.status);
    }

    @Test
    void orderRequest_convenienceConstructor_setsFieldsAndReservationRecord() {
        OrderRequest request = new OrderRequest("ORD-9", "STORE-9", "{}");

        assertEquals("ORD-9", request.orderNumber);
        assertEquals("STORE-9", request.storeCode);
        assertEquals("{}", request.specification);
        assertNotNull(request.reservationRecord);
        assertEquals(ReservationRecord.ReservationStatus.PENDING, request.reservationRecord.status);
    }

    @Test
    void reserveBatch_delegatesToOrderRequest() {
        try (MockedStatic<OrderRequest> orderRequestMock = mockStatic(OrderRequest.class)) {
            orderRequestMock.when(() -> OrderRequest.reserveBatch(300L, 10, ""))
                    .thenReturn(List.of(1L, 2L));

            assertEquals(List.of(1L, 2L), consumer.reserveBatch());
            orderRequestMock.verify(() -> OrderRequest.reserveBatch(300L, 10, ""));
        }
    }

    @Test
    void processBatch_fetchesRequestsAndProcessesEach() {
        EntityManager em = mock(EntityManager.class);
        consumer.em = em;
        TypedQuery<OrderRequest> query = mock(TypedQuery.class);
        OrderRequest valid = new OrderRequest("ORD-1", "STORE-1", "{\"orderHeader\":{}}");
        OrderRequest invalid = new OrderRequest("ORD-2", "STORE-1", "{invalid json");
        when(em.createQuery(anyString(), eq(OrderRequest.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(valid, invalid));

        consumer.processBatch(List.of(1L, 2L));

        assertEquals(ReservationRecord.ReservationStatus.DONE, valid.reservationRecord.status);
        assertEquals(ReservationRecord.ReservationStatus.FAILED, invalid.reservationRecord.status);
        verify(orderRecorder).persistOrderAndFulfillmentFromJson(any(JsonNode.class));
    }

    @Test
    void pollAndProcess_processesUntilNoMoreTasks() {
        EntityManager em = mock(EntityManager.class);
        consumer.em = em;
        TypedQuery<OrderRequest> query = mock(TypedQuery.class);
        OrderRequest request = new OrderRequest("ORD-1", "STORE-1", "{\"orderHeader\":{}}");
        when(em.createQuery(anyString(), eq(OrderRequest.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(request));

        try (MockedStatic<OrderRequest> orderRequestMock = mockStatic(OrderRequest.class)) {
            AtomicInteger calls = new AtomicInteger();
            orderRequestMock.when(() -> OrderRequest.reserveBatch(anyLong(), anyInt(), anyString()))
                    .thenAnswer(inv -> calls.incrementAndGet() == 1 ? List.of(1L) : List.of());

            consumer.pollAndProcess();

            assertEquals(2, calls.get());
            assertEquals(ReservationRecord.ReservationStatus.DONE, request.reservationRecord.status);
        }
    }

    @Test
    void pollAndProcess_noTasks_doesNotProcess() {
        EntityManager em = mock(EntityManager.class);
        consumer.em = em;

        try (MockedStatic<OrderRequest> orderRequestMock = mockStatic(OrderRequest.class)) {
            orderRequestMock.when(() -> OrderRequest.reserveBatch(anyLong(), anyInt(), anyString()))
                    .thenReturn(List.of());

            consumer.pollAndProcess();

            verifyNoInteractions(em);
            verifyNoInteractions(orderRecorder);
        }
    }

    @Test
    void pollAndProcess_reserveThrows_swallowsException() {
        try (MockedStatic<OrderRequest> orderRequestMock = mockStatic(OrderRequest.class)) {
            orderRequestMock.when(() -> OrderRequest.reserveBatch(anyLong(), anyInt(), anyString()))
                    .thenThrow(new RuntimeException("db down"));

            assertDoesNotThrow(() -> consumer.pollAndProcess());
        }
    }
}
