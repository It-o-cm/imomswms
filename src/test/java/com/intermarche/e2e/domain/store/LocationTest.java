package com.intermarche.e2e.domain.store;

import com.intermarche.backoffice.domain.store.Location;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link Location} interface default methods.
 * <p>
 * Note: the default methods are exercised through a Mockito mock with
 * {@link Answers#CALLS_REAL_METHODS}. A hand-written (even anonymous) implementation
 * must be avoided here: test classes are part of the Jandex index in @QuarkusTest,
 * and SmallRye GraphQL would try to register any {@code Location} implementation
 * found in the index as a schema type (an anonymous class would be named "1" and
 * break the schema build).
 */
public class LocationTest {

    @Test
    void defaultMethods_shouldReturnNull() {
        Location location = mock(Location.class, Answers.CALLS_REAL_METHODS);

        assertNull(location.getAisle());
        assertNull(location.getArea());
    }

    @Test
    void shelfLocation_shouldOverrideDefaults() {
        StoreShelfLocation location = new StoreShelfLocation();
        location.area = "EPICERIE";
        location.aisle = "A05";

        assertEquals("A05", location.getAisle());
        assertEquals("EPICERIE", location.getArea());
    }
}
