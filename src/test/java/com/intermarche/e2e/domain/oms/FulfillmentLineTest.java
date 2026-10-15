package com.intermarche.e2e.domain.oms;

import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link FulfillmentLine}.
 * Covers persistence (with and without location) and getChecksum sensitivity.
 */
@QuarkusTest
public class FulfillmentLineTest {

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private Product createProduct(String prefix, String ean) {
        Product product = new Product();
        product.ean = ean;
        product.name = "Product " + prefix;
        product.productType = ProductType.UNIT;
        product.temperatureZone = TemperatureZone.AMBIENT;
        product.persist();
        return product;
    }

    private StoreShelfLocation createLocation(Product product, String area, String aisle) {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.product = product;
        loc.facings = 1;
        loc.area = area;
        loc.aisle = aisle;
        loc.unit = "A";
        loc.elevation = "1";
        loc.position = "01";
        loc.persist();
        return loc;
    }

    private FulfillmentLine createLine(Product product, int lineNumber, String quantity) {
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = lineNumber;
        line.product = product;
        line.requestedQuantity = new BigDecimal(quantity);
        line.persist();
        return line;
    }

    // --------------------------------------------------
    // Persistence Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void persist_shouldSucceed_withLocation() {
        Product product = createProduct("FL01", "FL01-EAN1");
        StoreShelfLocation loc = createLocation(product, "AMBIENT", "A01");

        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = 1;
        line.product = product;
        line.requestedQuantity = new BigDecimal("2.500");
        line.location = loc;
        line.persist();

        assertNotNull(line.id);
        assertEquals(1, line.lineNumber);
        assertEquals(new BigDecimal("2.500"), line.requestedQuantity);
        assertEquals(loc.id, line.location.id);
    }

    @Test
    @TestTransaction
    void persist_shouldSucceed_withoutLocation() {
        Product product = createProduct("FL02", "FL02-EAN1");

        FulfillmentLine line = createLine(product, 1, "1.000");

        assertNotNull(line.id);
        assertNull(line.location);
    }

    // --------------------------------------------------
    // Checksum Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenLineNumberChanges() {
        Product product = createProduct("FL03", "FL03-EAN1");
        FulfillmentLine line = createLine(product, 1, "1.000");

        int checksum1 = line.getChecksum();
        line.lineNumber = 2;
        int checksum2 = line.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenQuantityChanges() {
        Product product = createProduct("FL04", "FL04-EAN1");
        FulfillmentLine line = createLine(product, 1, "1.000");

        int checksum1 = line.getChecksum();
        line.requestedQuantity = new BigDecimal("3.000");
        int checksum2 = line.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenProductChanges() {
        Product p1 = createProduct("FL05", "FL05-EAN1");
        Product p2 = createProduct("FL05", "FL05-EAN2");
        FulfillmentLine line = createLine(p1, 1, "1.000");

        int checksum1 = line.getChecksum();
        line.product = p2;
        int checksum2 = line.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenLocationChanges() {
        Product product = createProduct("FL06", "FL06-EAN1");
        FulfillmentLine line = createLine(product, 1, "1.000");

        int checksum1 = line.getChecksum();
        line.location = createLocation(product, "FRESH", "B02");
        int checksum2 = line.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Query Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findById_shouldReturnLine() {
        Product product = createProduct("FL07", "FL07-EAN1");
        FulfillmentLine line = createLine(product, 1, "1.000");

        FulfillmentLine found = FulfillmentLine.findById(line.id);

        assertNotNull(found);
        assertEquals(line.id, found.id);
        assertEquals(product.id, found.product.id);
    }

    @Test
    @TestTransaction
    void findById_shouldReturnNull_whenNotFound() {
        assertNull(FulfillmentLine.findById(999999L));
    }
}
