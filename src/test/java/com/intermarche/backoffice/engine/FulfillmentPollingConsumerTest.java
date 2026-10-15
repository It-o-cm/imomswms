package com.intermarche.backoffice.engine;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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
 * Unit tests for {@link FulfillmentPollingConsumer}.
 * The {@link FulfillmentEngine} and the {@link EntityManager} are mocked;
 * Panache statics ({@code Fulfillment.reserveBatch}, {@code findById}) are stubbed.
 */
class FulfillmentPollingConsumerTest {

    private FulfillmentPollingConsumer consumer;
    private EntityManager em;
    private FulfillmentEngine fulfillmentEngine;

    @BeforeEach
    void setUp() {
        consumer = new FulfillmentPollingConsumer();
        em = mock(EntityManager.class);
        fulfillmentEngine = mock(FulfillmentEngine.class);
        consumer.em = em;
        consumer.fulfillmentEngine = fulfillmentEngine;
    }

    @Test
    void reserveBatch_delegatesToFulfillment() {
        try (MockedStatic<Fulfillment> fulfillmentMock = mockStatic(Fulfillment.class)) {
            fulfillmentMock.when(() -> Fulfillment.reserveBatch(300L, 10, ""))
                    .thenReturn(List.of(7L));

            assertEquals(List.of(7L), consumer.reserveBatch());
            fulfillmentMock.verify(() -> Fulfillment.reserveBatch(300L, 10, ""));
        }
    }

    @Test
    void processBatch_fetchesAndProcessesEachFulfillment() throws Exception {
        Fulfillment f1 = new Fulfillment();
        f1.id = 1L;
        Fulfillment f2 = new Fulfillment();
        f2.id = 2L;
        TypedQuery<Fulfillment> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Fulfillment.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(f1, f2));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any()))
                    .thenAnswer(inv -> {
                        Object id = inv.getArgument(0);
                        return id.equals(1L) ? f1 : f2;
                    });

            consumer.processBatch(List.of(1L, 2L));

            verify(fulfillmentEngine).process(f1);
            verify(fulfillmentEngine).process(f2);
        }
    }

    @Test
    void processSingleFulfillment_nullFulfillment_doesNothing() {
        assertDoesNotThrow(() -> consumer.processSingleFulfillment(null));

        verifyNoInteractions(fulfillmentEngine);
        verifyNoInteractions(em);
    }

    @Test
    void processSingleFulfillment_notFoundAfterRefresh_skips() throws Exception {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 8L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(8L)).thenReturn(null);

            consumer.processSingleFulfillment(fulfillment);

            verifyNoInteractions(fulfillmentEngine);
            verify(em, never()).merge(any());
        }
    }

    @Test
    void processSingleFulfillment_success_processesViaEngine() throws Exception {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 5L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(5L)).thenReturn(fulfillment);

            consumer.processSingleFulfillment(fulfillment);

            verify(fulfillmentEngine).process(fulfillment);
            assertNotEquals(ReservationRecord.ReservationStatus.FAILED, fulfillment.reservationRecord.status);
        }
    }

    @Test
    void processSingleFulfillment_engineThrows_marksFailedAndMerges() throws Exception {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 6L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(6L)).thenReturn(fulfillment);
            doThrow(new RuntimeException("boom")).when(fulfillmentEngine).process(any(Fulfillment.class));

            consumer.processSingleFulfillment(fulfillment);

            assertEquals(ReservationRecord.ReservationStatus.FAILED, fulfillment.reservationRecord.status);
            verify(em).merge(fulfillment);
        }
    }

    @Test
    void pollAndProcess_processesFullBatchThenStops() throws Exception {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;
        TypedQuery<Fulfillment> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Fulfillment.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(fulfillment));

        try (MockedStatic<Fulfillment> fulfillmentMock = mockStatic(Fulfillment.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            AtomicInteger calls = new AtomicInteger();
            fulfillmentMock.when(() -> Fulfillment.reserveBatch(anyLong(), anyInt(), anyString()))
                    .thenAnswer(inv -> calls.incrementAndGet() == 1
                            ? LongStream.rangeClosed(1, 10).boxed().collect(Collectors.toList())
                            : List.of());
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            consumer.pollAndProcess();

            assertEquals(2, calls.get());
            verify(fulfillmentEngine).process(fulfillment);
        }
    }

    @Test
    void pollAndProcess_emptyBatch_doesNotProcess() {
        try (MockedStatic<Fulfillment> fulfillmentMock = mockStatic(Fulfillment.class)) {
            fulfillmentMock.when(() -> Fulfillment.reserveBatch(anyLong(), anyInt(), anyString()))
                    .thenReturn(List.of());

            consumer.pollAndProcess();

            verifyNoInteractions(em);
            verifyNoInteractions(fulfillmentEngine);
        }
    }

    @Test
    void pollAndProcess_reserveThrows_swallowsException() {
        try (MockedStatic<Fulfillment> fulfillmentMock = mockStatic(Fulfillment.class)) {
            fulfillmentMock.when(() -> Fulfillment.reserveBatch(anyLong(), anyInt(), anyString()))
                    .thenThrow(new RuntimeException("db down"));

            assertDoesNotThrow(() -> consumer.pollAndProcess());
        }
    }
}
