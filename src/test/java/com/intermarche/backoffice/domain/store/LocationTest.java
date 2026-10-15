package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Location} interface default methods.
 * Pure unit tests — no database, no Quarkus.
 * Uses Mockito mocks instead of anonymous classes to avoid Quarkus classpath scanning issues.
 */
class LocationTest {

    @Test
    @DisplayName("default getAisle returns null")
    void defaultGetAisleReturnsNull() {
        Location location = mock(Location.class, CALLS_REAL_METHODS);
        assertNull(location.getAisle());
    }

    @Test
    @DisplayName("default getArea returns null")
    void defaultGetAreaReturnsNull() {
        Location location = mock(Location.class, CALLS_REAL_METHODS);
        assertNull(location.getArea());
    }

    @Test
    @DisplayName("overridden getAisle returns custom value")
    void overriddenGetAisle() {
        Location location = mock(Location.class);
        when(location.getAisle()).thenReturn("A12");
        assertEquals("A12", location.getAisle());
    }

    @Test
    @DisplayName("overridden getArea returns custom value")
    void overriddenGetArea() {
        Location location = mock(Location.class);
        when(location.getArea()).thenReturn("ZONE-A");
        assertEquals("ZONE-A", location.getArea());
    }
}
