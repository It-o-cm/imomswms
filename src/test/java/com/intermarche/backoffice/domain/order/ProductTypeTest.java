package com.intermarche.backoffice.domain.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link ProductType} enum.
 */
public class ProductTypeTest {

    @Test
    void shouldHaveAllExpectedValues() {
        assertEquals(3, ProductType.values().length);
        assertNotNull(ProductType.valueOf("UNIT"));
        assertNotNull(ProductType.valueOf("WEIGHT"));
        assertNotNull(ProductType.valueOf("VOLUME"));
    }

    @Test
    void valueOf_shouldBeCaseSensitive() {
        assertEquals(ProductType.UNIT, ProductType.valueOf("UNIT"));
        assertThrows(IllegalArgumentException.class, () -> ProductType.valueOf("unit"));
    }
}
