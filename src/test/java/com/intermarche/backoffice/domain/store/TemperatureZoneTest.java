package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link TemperatureZone} enum.
 */
public class TemperatureZoneTest {

    @Test
    void shouldHaveAllExpectedValues() {
        assertEquals(4, TemperatureZone.values().length);
        assertNotNull(TemperatureZone.valueOf("AMBIENT"));
        assertNotNull(TemperatureZone.valueOf("CHILLED"));
        assertNotNull(TemperatureZone.valueOf("FROZEN"));
        assertNotNull(TemperatureZone.valueOf("HOUSEHOLD"));
    }

    @Test
    void valueOf_shouldBeCaseSensitive() {
        assertEquals(TemperatureZone.AMBIENT, TemperatureZone.valueOf("AMBIENT"));
        assertThrows(IllegalArgumentException.class, () -> TemperatureZone.valueOf("ambient"));
    }
}
