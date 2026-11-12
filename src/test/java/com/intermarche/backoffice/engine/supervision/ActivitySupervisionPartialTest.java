package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;

/**
 * Partial failure: when the handling diagnostic is unavailable, the activity supervision still answers with the other
 * domains and states explicitly what is not analyzed, instead of a false "all good".
 */
@QuarkusTest
@TestTransaction
@TestSecurity(user = "act-partial", roles = {"ADMIN"})
class ActivitySupervisionPartialTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2031, 4, 2, 9, 0);

    @Inject ActivitySupervisionService activity;
    @InjectSpy HandlingSupervisionService handling;
    @Inject EntityManager em;

    private LocalDateTime previousClock;

    /**
     * Freezes the clock.
     */
    @BeforeEach
    void freeze() {
        previousClock = DateTimeProvider.getFixedDateTime();
        DateTimeProvider.setFixedDateTime(NOW);
    }

    /**
     * Restores the clock.
     */
    @AfterEach
    void restore() {
        if (previousClock == null) DateTimeProvider.clear();
        else DateTimeProvider.setFixedDateTime(previousClock);
    }

    /**
     * The handling failure becomes an explicit stale situation and source; the commitments are still analyzed.
     */
    @Test
    void handlingFailureIsAPartialErrorNotAFalseAllGood() {
        var store = new Store();
        store.code = "ACTPAR";
        store.name = "Partiel";
        store.persist();
        var shop = new StoreBuilding();
        shop.buildingCode = "ACTPAR-SHOP";
        shop.persist();
        store.buildings.add(shop);
        var order = new Order();
        order.orderId = "APA-0001";
        order.orderDate = NOW.minusHours(2);
        order.channel = "DRIVE";
        order.status = "VALIDATED";
        order.store = store;
        order.persist();
        var fulfillment = new Fulfillment();
        fulfillment.order = order;
        fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.FAILED;
        var chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        chunk.label = "Default";
        chunk.deliveryDate = NOW.plusHours(3);
        chunk.building = shop;
        fulfillment.addChunk(chunk);
        fulfillment.persist();
        em.flush();
        doThrow(new IllegalStateException("configuration de manutention illisible")).when(handling).analyze(any(Store.class), any(), any(), anySet(), anySet());
        var report = activity.analyze(store, null);
        var source = report.sources().stream().filter(s -> s.code().equals("HANDLING")).findFirst().orElseThrow();
        assertEquals("UNAVAILABLE", source.status());
        assertTrue(source.message().contains("configuration de manutention illisible"), source.message());
        var stale = report.situations().stream().filter(s -> s.id().equals("HANDLING_UNAVAILABLE")).findFirst().orElseThrow();
        assertEquals(ActivityModel.Certainty.STALE, stale.certainty());
        assertEquals("MEDIUM", stale.severity().name());
        var blocked = report.kpis().stream().filter(k -> k.code().equals("BLOCKED_OPERATIONS")).findFirst().orElseThrow();
        assertFalse(blocked.available());
        assertNull(blocked.value());
        assertTrue(report.situations().stream().anyMatch(s -> s.id().equals("ROUTING") && s.commitments().get(0).id().equals(order.id)),
                "the other domains are still analyzed");
    }
}
