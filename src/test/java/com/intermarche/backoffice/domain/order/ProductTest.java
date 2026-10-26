package com.intermarche.backoffice.domain.order;

import com.intermarche.backoffice.domain.store.TemperatureZone;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Product} business logic.
 * Pure unit tests — no database, no Quarkus.
 */
class ProductTest {

    private Product createProduct(ProductType type) {
        Product product = new Product();
        product.ean = "3017620422003";
        product.name = "Pâtes";
        product.productType = type;
        product.temperatureZone = TemperatureZone.AMBIENT;
        return product;
    }

    // --------------------------------------------------
    // standardQuantity
    // --------------------------------------------------

    @Test
    void standardQuantity_unit_returnsQuantityAsIs() {
        Product product = createProduct(ProductType.UNIT);
        BigDecimal result = product.standardQuantity(5.0);
        assertEquals(0, result.compareTo(BigDecimal.valueOf(5.0)));
    }

    @Test
    void standardQuantity_unit_decimalPreserved() {
        Product product = createProduct(ProductType.UNIT);
        BigDecimal result = product.standardQuantity(2.5);
        assertEquals(0, result.compareTo(BigDecimal.valueOf(2.5)));
    }

    @Test
    void standardQuantity_weight_dividesByReferenceWeight() {
        Product product = createProduct(ProductType.WEIGHT);
        product.referenceWeight = new BigDecimal("0.5");

        BigDecimal result = product.standardQuantity(2.0);

        assertEquals(0, result.compareTo(new BigDecimal("4")));
    }

    @Test
    void standardQuantity_weight_nullReferenceWeight_returnsZero() {
        Product product = createProduct(ProductType.WEIGHT);
        product.referenceWeight = null;

        assertEquals(0, product.standardQuantity(2.0).compareTo(BigDecimal.ZERO));
    }

    @Test
    void standardQuantity_weight_zeroReferenceWeight_returnsZero() {
        Product product = createProduct(ProductType.WEIGHT);
        product.referenceWeight = BigDecimal.ZERO;

        assertEquals(0, product.standardQuantity(2.0).compareTo(BigDecimal.ZERO));
    }

    @Test
    void standardQuantity_volume_dividesByReferenceWeight() {
        Product product = createProduct(ProductType.VOLUME);
        product.referenceWeight = new BigDecimal("0.25");

        BigDecimal result = product.standardQuantity(1.0);

        assertEquals(0, result.compareTo(new BigDecimal("4")));
    }

    @Test
    void standardQuantity_weight_roundsHalfUp() {
        Product product = createProduct(ProductType.WEIGHT);
        product.referenceWeight = new BigDecimal("0.3");

        BigDecimal result = product.standardQuantity(1.0);

        assertEquals(0, result.compareTo(new BigDecimal("3.333333")));
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_activeIsTrue() {
        assertTrue(new Product().active);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_changesWithEan() {
        Product product = createProduct(ProductType.UNIT);
        int checksum1 = product.getChecksum();
        product.ean = "9999999999999";
        assertNotEquals(checksum1, product.getChecksum());
    }

    @Test
    void getChecksum_changesWithName() {
        Product product = createProduct(ProductType.UNIT);
        int checksum1 = product.getChecksum();
        product.name = "Riz";
        assertNotEquals(checksum1, product.getChecksum());
    }

    @Test
    void getChecksum_changesWithProductType() {
        Product product = createProduct(ProductType.UNIT);
        int checksum1 = product.getChecksum();
        product.productType = ProductType.WEIGHT;
        assertNotEquals(checksum1, product.getChecksum());
    }

    @Test
    void getChecksum_changesWithTemperatureZone() {
        Product product = createProduct(ProductType.UNIT);
        int checksum1 = product.getChecksum();
        product.temperatureZone = TemperatureZone.FROZEN;
        assertNotEquals(checksum1, product.getChecksum());
    }

    @Test
    void getChecksum_changesWithActive() {
        Product product = createProduct(ProductType.UNIT);
        int checksum1 = product.getChecksum();
        product.active = false;
        assertNotEquals(checksum1, product.getChecksum());
    }

    @Test
    void getChecksum_sameFields_sameValue() {
        assertEquals(createProduct(ProductType.UNIT).getChecksum(), createProduct(ProductType.UNIT).getChecksum());
    }
    @Test
    void checksumDetectsAChangeToFreeTextCharacteristics() {
        Product product=createProduct(ProductType.UNIT);int previous=product.getChecksum();
        product.characteristics="Lait demi-écrémé, bouteille de 1 litre.";
        assertNotEquals(previous,product.getChecksum());
    }
}
