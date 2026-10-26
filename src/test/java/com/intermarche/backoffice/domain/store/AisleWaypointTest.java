package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link AisleWaypoint} business logic.
 * Focuses on {@link AisleWaypoint#getChecksum()} sensitivity to its fields.
 */
public class AisleWaypointTest {

    @Test
    void getChecksum_shouldChangeWhenPositionChanges() {
        AisleWaypoint waypoint = createWaypoint();
        waypoint.position = 0;
        int checksum1 = waypoint.getChecksum();

        waypoint.position = 1;
        int checksum2 = waypoint.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenXChanges() {
        AisleWaypoint waypoint = createWaypoint();
        waypoint.x = 10.0;
        int checksum1 = waypoint.getChecksum();

        waypoint.x = 20.0;
        int checksum2 = waypoint.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenYChanges() {
        AisleWaypoint waypoint = createWaypoint();
        waypoint.y = 10.0;
        int checksum1 = waypoint.getChecksum();

        waypoint.y = 20.0;
        int checksum2 = waypoint.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldNotFailWhenFieldsAreNull() {
        AisleWaypoint waypoint = new AisleWaypoint();
        assertDoesNotThrow(waypoint::getChecksum);
    }

    private AisleWaypoint createWaypoint() {
        AisleWaypoint waypoint = new AisleWaypoint();
        waypoint.position = 0;
        waypoint.x = 1.0;
        waypoint.y = 2.0;
        return waypoint;
    }
}
