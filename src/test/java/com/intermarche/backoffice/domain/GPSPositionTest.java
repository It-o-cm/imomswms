package com.intermarche.backoffice.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link GPSPosition} value object.
 * Focuses on constructors, equals/hashCode contract and checksum.
 */
public class GPSPositionTest {

    // --------------------------------------------------
    // Constructors
    // --------------------------------------------------

    @Test
    void defaultConstructor_shouldLeaveCoordinatesNull() {
        GPSPosition position = new GPSPosition();
        assertNull(position.latitude);
        assertNull(position.longitude);
    }

    @Test
    void parameterizedConstructor_shouldSetCoordinates() {
        GPSPosition position = new GPSPosition(45.75, 4.85);
        assertEquals(45.75, position.latitude);
        assertEquals(4.85, position.longitude);
    }

    // --------------------------------------------------
    // equals / hashCode
    // --------------------------------------------------

    @Test
    void equals_shouldReturnTrue_forSameCoordinates() {
        GPSPosition a = new GPSPosition(45.75, 4.85);
        GPSPosition b = new GPSPosition(45.75, 4.85);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_shouldReturnFalse_forDifferentCoordinates() {
        GPSPosition a = new GPSPosition(45.75, 4.85);
        GPSPosition b = new GPSPosition(45.75, 4.86);

        assertNotEquals(a, b);
    }

    @Test
    void equals_shouldReturnFalse_forNullAndOtherTypes() {
        GPSPosition a = new GPSPosition(45.75, 4.85);

        assertNotEquals(null, a);
        assertNotEquals("not a position", a);
    }

    @Test
    void equals_shouldReturnFalse_whenComparedToNull() {
        GPSPosition a = new GPSPosition(45.75, 4.85);

        assertFalse(a.equals(null));
    }

    @Test
    void equals_shouldReturnFalse_whenComparedToOtherType() {
        GPSPosition a = new GPSPosition(45.75, 4.85);

        assertFalse(a.equals("not a position"));
        assertFalse(a.equals(45.75));
    }

    @Test
    void equals_shouldReturnFalse_forDifferentLatitude() {
        GPSPosition a = new GPSPosition(45.75, 4.85);
        GPSPosition b = new GPSPosition(46.75, 4.85);

        assertNotEquals(a, b);
    }

    @Test
    void equals_shouldBeReflexive() {
        GPSPosition a = new GPSPosition(45.75, 4.85);
        assertEquals(a, a);
    }

    @Test
    void equals_shouldHandleNullCoordinates() {
        GPSPosition a = new GPSPosition(null, null);
        GPSPosition b = new GPSPosition(null, null);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenLatitudeChanges() {
        GPSPosition position = new GPSPosition(45.75, 4.85);
        int checksum1 = position.getChecksum();

        position.latitude = 46.0;
        assertNotEquals(checksum1, position.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenLongitudeChanges() {
        GPSPosition position = new GPSPosition(45.75, 4.85);
        int checksum1 = position.getChecksum();

        position.longitude = 5.0;
        assertNotEquals(checksum1, position.getChecksum());
    }

    @Test
    void getChecksum_shouldEqualHashCode() {
        GPSPosition position = new GPSPosition(45.75, 4.85);
        assertEquals(position.hashCode(), position.getChecksum());
    }
}
