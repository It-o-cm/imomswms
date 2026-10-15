package com.intermarche.e2e.imports;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductFamily;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.imports.ProductFamilyCsvResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Test class for {@link ProductFamilyCsvResource}.
 * <p>
 * Tests the CSV import endpoint for ProductFamilies, covering creation, updates,
 * checksum-based change detection (including product/sub-family links), the
 * 1-by-1 fallback mechanism and error handling.
 * <p>
 * CSV format (5 columns): code|description|flags|product_eans|family_codes
 */
@QuarkusTest
public class ProductFamilyCsvResourceTest {

    private static final String EAN_1 = "3270190123458";
    private static final String EAN_2 = "3270190123459";

    /**
     * The TransactionManager for manual transaction control in tests.
     */
    @Inject
    TransactionManager tm;

    /**
     * The resource under test, injected to exercise protected methods directly.
     */
    @Inject
    ProductFamilyCsvResource resource;

    /**
     * Cleans the database before each test to ensure isolation.
     */
    @BeforeEach
    void setUp() {
        CsvImportTestHelper.cleanDatabase(tm);
    }

    /**
     * Cleans the database after each test so that no committed row leaks into the next test class.
     */
    @AfterEach
    void tearDown() {
        CsvImportTestHelper.cleanDatabase(tm);
    }

    /**
     * Creates a Product fixture with the given EAN.
     *
     * @param ean  The product EAN (13 characters).
     * @param name The product name.
     */
    private void createProduct(String ean, String name) {
        CsvImportTestHelper.withTransaction(tm, () -> {
            Product p = new Product();
            p.ean = ean;
            p.name = name;
            p.productType = ProductType.UNIT;
            p.temperatureZone = TemperatureZone.AMBIENT;
            p.active = true;
            p.persist();
            return null;
        });
    }

    /**
     * Tests the successful import of a new family with linked products.
     * <p>
     * Verifies that the family is created with correct attributes and products.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportNewFamilyWithProductsSuccess() {
        createProduct(EAN_1, "Product 1");
        createProduct(EAN_2, "Product 2");
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "PF_FRUITS|Fruits|ORGANIC,SEASONAL|" + EAN_1 + "," + EAN_2 + "|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        ProductFamily family = ProductFamily.findByCode("PF_FRUITS");
        assertNotNull(family);
        assertEquals("Fruits", family.description);
        assertEquals("ORGANIC,SEASONAL", family.flags);
        assertEquals(2, family.products.size());
    }

    /**
     * Tests the successful import of a family linked to an existing sub-family.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamilyWithSubFamilySuccess() {
        given()
                .body("code|description|flags|product_eans|family_codes\n" +
                        "PF_LEAF|Leaf family|||")
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|description|flags|product_eans|family_codes\n" +
                        "PF_ROOT|Root family|||PF_LEAF")
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));

        ProductFamily root = ProductFamily.findByCode("PF_ROOT");
        assertNotNull(root);
        assertEquals(1, root.productFamilies.size());
        assertEquals("PF_LEAF", root.productFamilies.iterator().next().code);
    }

    /**
     * Tests that re-importing an identical CSV does not trigger any update.
     * <p>
     * Validates the checksum consistency between the entity and the importer:
     * the checksum covers exactly the CSV-imported fields (code, description,
     * flags, product_eans, family_codes).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testReimportIdenticalCsv_NoUpdate() {
        createProduct(EAN_1, "Product 1");
        createProduct(EAN_2, "Product 2");
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "PF_FRUITS|Fruits|ORGANIC,SEASONAL|" + EAN_1 + "," + EAN_2 + "|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests the update of an existing family when the description changes.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingFamily_ChangedDescription() {
        createProduct(EAN_1, "Product 1");
        given()
                .body("code|description|flags|product_eans|family_codes\n" +
                        "PF_UPD|Old description||" + EAN_1 + "|")
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|description|flags|product_eans|family_codes\n" +
                        "PF_UPD|New description||" + EAN_1 + "|")
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        ProductFamily updated = ProductFamily.findByCode("PF_UPD");
        assertNotNull(updated);
        assertEquals("New description", updated.description);
    }

    /**
     * Tests that changing only the product links is detected as an update.
     * <p>
     * The product_eans column is part of the checksum, so relinking products
     * must be counted as an update.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testUpdateExistingFamily_ChangedProductLinks() {
        createProduct(EAN_1, "Product 1");
        createProduct(EAN_2, "Product 2");
        given()
                .body("code|description|flags|product_eans|family_codes\n" +
                        "PF_LINKS|Links||" + EAN_1 + "," + EAN_2 + "|")
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|description|flags|product_eans|family_codes\n" +
                        "PF_LINKS|Links||" + EAN_1 + "|")
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":1"));

        ProductFamily updated = ProductFamily.findByCode("PF_LINKS");
        assertNotNull(updated);
        assertEquals(1, updated.products.size());
        assertEquals(EAN_1, updated.products.iterator().next().ean);
    }

    /**
     * Tests the error handling when a referenced product EAN does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamily_UnknownProductEan_Error() {
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "PF_BAD|Bad family||9999999999999|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Product EAN '9999999999999' not found."));

        assertNull(ProductFamily.findByCode("PF_BAD"));
    }

    /**
     * Tests the error handling when a referenced sub-family code does not exist.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamily_UnknownSubFamily_Error() {
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "PF_BAD2|Bad family|||GRP_NOPE";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("SubFamily code 'GRP_NOPE' not found."));

        assertNull(ProductFamily.findByCode("PF_BAD2"));
    }

    /**
     * Tests the error handling when a family references itself as a sub-family.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamily_SelfReference_Error() {
        given()
                .body("code|description|flags|product_eans|family_codes\n" +
                        "PF_SELF|Self family|||")
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"));

        given()
                .body("code|description|flags|product_eans|family_codes\n" +
                        "PF_SELF|Self family|||PF_SELF")
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("Family 'PF_SELF' cannot contain itself."));

        ProductFamily family = ProductFamily.findByCode("PF_SELF");
        assertNotNull(family);
        assertTrue(family.productFamilies.isEmpty());
    }

    /**
     * Tests the 1-by-1 fallback mechanism with a mix of valid and invalid lines.
     * <p>
     * The invalid line (unknown EAN) rolls the chunk back; the fallback must
     * still create the valid line. This validates that the fallback context
     * performs a fresh product lookup.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamilies_FallbackMixedLines() {
        createProduct(EAN_1, "Product 1");
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "PF_OK|Good family||" + EAN_1 + "|\n" +
                "PF_BAD|Bad family||9999999999999|";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":1"))
                .body(containsString("\"updatedCount\":0"))
                .body(containsString("\"errors\""))
                .body(containsString("9999999999999"));

        assertNotNull(ProductFamily.findByCode("PF_OK"));
        assertNull(ProductFamily.findByCode("PF_BAD"));
    }

    /**
     * Tests the chunk processing guard when the parsed line list is empty.
     */
    @Test
    void testProcessChunkWithFallback_EmptyList() {
        Map<String, Object> result = resource.processChunkWithFallback(List.of(), Set.of(), new int[2], new ArrayList<>());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Tests the processing logic when the input CSV contains no data lines (only header).
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamilies_EmptyInput() {
        String csvContent = "code|description|flags|product_eans|family_codes\n";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("\"updatedCount\":0"))
                .body(Matchers.not(containsString("\"errors\"")));
    }

    /**
     * Tests that a line with fewer columns than expected is ignored with an error.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void testImportFamilies_TooFewColumns() {
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "PF_FRUITS|Fruits|ORGANIC";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(200)
                .body(containsString("\"createdCount\":0"))
                .body(containsString("not enough columns"));

        assertNull(ProductFamily.findByCode("PF_FRUITS"));
    }

    /**
     * Tests the security configuration to ensure access is denied for non-admin users.
     */
    @Test
    void testSecurity_AccessDeniedForNonAdmin() {
        String csvContent = "code|description|flags|product_eans|family_codes\n" +
                "PF_FRUITS|Fruits|||";

        given()
                .body(csvContent)
                .contentType(ContentType.TEXT)
                .when()
                .post("/product-families/import")
                .then()
                .statusCode(401);
    }
}
