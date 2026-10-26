package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link PlanZoneVertex} business logic.
 * Focuses on {@link PlanZoneVertex#getChecksum()} sensitivity to its fields.
 */
public class PlanZoneVertexTest {

    @Test
    void getChecksum_shouldChangeWhenPositionChanges() {
        PlanZoneVertex vertex = createVertex();
        vertex.position = 0;
        int checksum1 = vertex.getChecksum();

        vertex.position = 1;
        int checksum2 = vertex.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenXChanges() {
        PlanZoneVertex vertex = createVertex();
        vertex.x = 10.0;
        int checksum1 = vertex.getChecksum();

        vertex.x = 20.0;
        int checksum2 = vertex.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenYChanges() {
        PlanZoneVertex vertex = createVertex();
        vertex.y = 10.0;
        int checksum1 = vertex.getChecksum();

        vertex.y = 20.0;
        int checksum2 = vertex.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldNotFailWhenFieldsAreNull() {
        PlanZoneVertex vertex = new PlanZoneVertex();
        assertDoesNotThrow(vertex::getChecksum);
    }

    private PlanZoneVertex createVertex() {
        PlanZoneVertex vertex = new PlanZoneVertex();
        vertex.position = 0;
        vertex.x = 1.0;
        vertex.y = 2.0;
        return vertex;
    }
}
