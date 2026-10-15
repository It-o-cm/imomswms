package com.intermarche.backoffice.domain.oms;

import com.intermarche.backoffice.domain.store.StoreBuilding;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FulfillmentChunk}.
 * Pure unit tests — no database, no Quarkus.
 */
class FulfillmentChunkTest {

    // --------------------------------------------------
    // Constants
    // --------------------------------------------------

    @Test
    void pendingConstant_isPending() {
        assertEquals("PENDING", FulfillmentChunk.PENDING);
    }

    @Test
    void readyConstant_isReady() {
        assertEquals("READY", FulfillmentChunk.READY);
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_buildingIsNull() {
        assertNull(new FulfillmentChunk().building);
    }

    @Test
    void defaultConstructor_fulfillmentIsNull() {
        assertNull(new FulfillmentChunk().fulfillment);
    }

    @Test
    void defaultConstructor_linesInitializedEmpty() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        assertNotNull(chunk.lines);
        assertTrue(chunk.lines.isEmpty());
    }

    // --------------------------------------------------
    // addLine
    // --------------------------------------------------

    @Test
    void addLine_addsToLines() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = 1;

        chunk.addLine(line);

        assertEquals(1, chunk.lines.size());
        assertTrue(chunk.lines.contains(line));
    }

    // --------------------------------------------------
    // findCommonSpecification
    // --------------------------------------------------

    @Test
    void findCommonSpecification_nullChunk_throwsNPE() {
        // chunk.lines est déréférencé sans garde
        assertThrows(NullPointerException.class, () -> FulfillmentChunk.findCommonSpecification(null));
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_nullBuilding_noException() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.building = null;
        assertDoesNotThrow(chunk::getChecksum);
    }

    @Test
    void getChecksum_changesWithType() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        int checksum1 = chunk.getChecksum();

        chunk.type = FulfillmentChunk.READY;
        assertNotEquals(checksum1, chunk.getChecksum());
    }

    @Test
    void getChecksum_changesWithLabel() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        int checksum1 = chunk.getChecksum();

        chunk.label = "ProductGrouping";
        assertNotEquals(checksum1, chunk.getChecksum());
    }

    @Test
    void getChecksum_changesWithDeliveryDate() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        int checksum1 = chunk.getChecksum();

        chunk.deliveryDate = LocalDateTime.of(2026, 10, 15, 8, 0);
        assertNotEquals(checksum1, chunk.getChecksum());
    }

    @Test
    void getChecksum_changesWithBuildingId() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        StoreBuilding building = new StoreBuilding();
        building.id = 1L;
        chunk.building = building;
        int checksum1 = chunk.getChecksum();

        building.id = 2L;
        assertNotEquals(checksum1, chunk.getChecksum());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        FulfillmentChunk chunk1 = new FulfillmentChunk();
        chunk1.type = FulfillmentChunk.PENDING;
        chunk1.label = "Default";
        chunk1.deliveryDate = LocalDateTime.of(2026, 10, 15, 8, 0);

        FulfillmentChunk chunk2 = new FulfillmentChunk();
        chunk2.type = FulfillmentChunk.PENDING;
        chunk2.label = "Default";
        chunk2.deliveryDate = LocalDateTime.of(2026, 10, 15, 8, 0);

        assertEquals(chunk1.getChecksum(), chunk2.getChecksum());
    }

    @Test
    void getChecksum_nullFields_noException() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        assertDoesNotThrow(chunk::getChecksum);
    }
}
