package com.intermarche.backoffice.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Buffer} business logic.
 * Focuses on {@link Buffer#getCapacityForZone(TemperatureZone)}, defaults,
 * the {@link Buffer.BufferPurpose} enum and checksum sensitivity.
 */
public class BufferTest {

    // --------------------------------------------------
    // getCapacityForZone
    // --------------------------------------------------

    @Test
    void getCapacityForZone_shouldReturnMatchingCapacity_whenActive() {
        Buffer buffer = createBuffer(true);
        buffer.ambientCapacity = 10;
        buffer.chilledCapacity = 20;
        buffer.frozenCapacity = 30;

        assertEquals(10, buffer.getCapacityForZone(TemperatureZone.AMBIENT));
        assertEquals(20, buffer.getCapacityForZone(TemperatureZone.CHILLED));
        assertEquals(30, buffer.getCapacityForZone(TemperatureZone.FROZEN));
    }

    @Test
    void getCapacityForZone_shouldReturnZeroForHousehold() {
        Buffer buffer = createBuffer(true);
        buffer.ambientCapacity = 10;

        // HOUSEHOLD is not handled by the switch: falls to default
        assertEquals(0, buffer.getCapacityForZone(TemperatureZone.HOUSEHOLD));
    }

    @Test
    void getCapacityForZone_shouldReturnZero_whenInactive() {
        Buffer buffer = createBuffer(false);
        buffer.ambientCapacity = 10;
        buffer.chilledCapacity = 20;
        buffer.frozenCapacity = 30;

        assertEquals(0, buffer.getCapacityForZone(TemperatureZone.AMBIENT));
        assertEquals(0, buffer.getCapacityForZone(TemperatureZone.CHILLED));
        assertEquals(0, buffer.getCapacityForZone(TemperatureZone.FROZEN));
        assertEquals(0, buffer.getCapacityForZone(TemperatureZone.HOUSEHOLD));
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaults_shouldBeStagingAndActive() {
        Buffer buffer = new Buffer();
        assertEquals(Buffer.BufferPurpose.STAGING, buffer.purpose);
        assertTrue(buffer.active);
    }

    // --------------------------------------------------
    // BufferPurpose Enum
    // --------------------------------------------------

    @Test
    void bufferPurpose_shouldHaveAllExpectedValues() {
        assertEquals(3, Buffer.BufferPurpose.values().length);
        assertNotNull(Buffer.BufferPurpose.valueOf("STAGING"));
        assertNotNull(Buffer.BufferPurpose.valueOf("PROCESSING"));
        assertNotNull(Buffer.BufferPurpose.valueOf("RECEIPT"));
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenBufferCodeChanges() {
        Buffer buffer = createBuffer(true);
        buffer.bufferCode = "BUF-01";
        int checksum1 = buffer.getChecksum();

        buffer.bufferCode = "BUF-02";
        assertNotEquals(checksum1, buffer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenPurposeChanges() {
        Buffer buffer = createBuffer(true);
        buffer.purpose = Buffer.BufferPurpose.STAGING;
        int checksum1 = buffer.getChecksum();

        buffer.purpose = Buffer.BufferPurpose.RECEIPT;
        assertNotEquals(checksum1, buffer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenCapacityChanges() {
        Buffer buffer = createBuffer(true);
        buffer.ambientCapacity = 10;
        int checksum1 = buffer.getChecksum();

        buffer.ambientCapacity = 11;
        assertNotEquals(checksum1, buffer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenActiveFlagChanges() {
        Buffer buffer = createBuffer(true);
        int checksum1 = buffer.getChecksum();

        buffer.active = false;
        assertNotEquals(checksum1, buffer.getChecksum());
    }

    @Test
    void getChecksum_shouldUseBuildingCode_notBuildingId() {
        Buffer buffer = createBuffer(true);
        buffer.building = createBuilding("BLD-01");
        int checksum1 = buffer.getChecksum();

        buffer.building = createBuilding("BLD-01");
        buffer.building.id = 999L;
        assertEquals(checksum1, buffer.getChecksum());

        buffer.building = createBuilding("BLD-02");
        assertNotEquals(checksum1, buffer.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenBuildingIsNull() {
        Buffer buffer = createBuffer(true);
        buffer.building = null;
        assertDoesNotThrow(buffer::getChecksum);
    }

    private Buffer createBuffer(boolean active) {
        Buffer buffer = new Buffer();
        buffer.bufferCode = "BUF-01";
        buffer.purpose = Buffer.BufferPurpose.STAGING;
        buffer.active = active;
        buffer.ambientCapacity = 10;
        buffer.chilledCapacity = 5;
        buffer.frozenCapacity = 2;
        return buffer;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    @Test
    void findActive_shouldReturnActiveBuffers() {
        Buffer buffer = createBuffer(true);
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("active"), any(Object[].class)))
                    .thenReturn(List.of(buffer));

            List<Buffer> result = Buffer.findActive();

            assertEquals(1, result.size());
            assertSame(buffer, result.get(0));
        }
    }

    private Building createBuilding(String code) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        return building;
    }
}
