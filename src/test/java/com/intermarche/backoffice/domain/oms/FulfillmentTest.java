package com.intermarche.backoffice.domain.oms;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.order.Order;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Fulfillment}.
 * Pure unit tests — no database, no Quarkus.
 */
class FulfillmentTest {

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_chunksInitializedEmpty() {
        Fulfillment fulfillment = new Fulfillment();
        assertNotNull(fulfillment.chunks);
        assertTrue(fulfillment.chunks.isEmpty());
    }

    @Test
    void defaultConstructor_reservationRecordNotNull() {
        assertNotNull(new Fulfillment().reservationRecord);
    }

    @Test
    void defaultConstructor_reservationStatusIsPending() {
        Fulfillment fulfillment = new Fulfillment();
        assertEquals(ReservationRecord.ReservationStatus.PENDING, fulfillment.reservationRecord.status);
    }

    // --------------------------------------------------
    // addChunk
    // --------------------------------------------------

    @Test
    void addChunk_addsToChunks() {
        Fulfillment fulfillment = new Fulfillment();
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;

        fulfillment.addChunk(chunk);

        assertEquals(1, fulfillment.chunks.size());
        assertTrue(fulfillment.chunks.contains(chunk));
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_nullOrder_noException() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = null;
        assertDoesNotThrow(fulfillment::getChecksum);
    }

    @Test
    void getChecksum_changesWithOrderId() {
        Fulfillment fulfillment = new Fulfillment();
        Order order = new Order();
        order.id = 1L;
        fulfillment.order = order;
        int checksum1 = fulfillment.getChecksum();

        order.id = 2L;
        assertNotEquals(checksum1, fulfillment.getChecksum());
    }

    @Test
    void getChecksum_changesWithReservationStatus() {
        Fulfillment fulfillment = new Fulfillment();
        int checksum1 = fulfillment.getChecksum();

        fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.PROCESSING;
        assertNotEquals(checksum1, fulfillment.getChecksum());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        assertEquals(new Fulfillment().getChecksum(), new Fulfillment().getChecksum());
    }
}
