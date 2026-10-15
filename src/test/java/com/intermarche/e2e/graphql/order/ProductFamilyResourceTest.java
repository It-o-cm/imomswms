package com.intermarche.e2e.graphql.order;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductFamily;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.graphql.order.ProductFamilyResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ProductFamilyResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class ProductFamilyResourceTest {

    @Inject
    ProductFamilyResource productFamilyResource;

    // --------------------------------------------------
    // Fixture Helpers
    // --------------------------------------------------

    private ProductFamily persistFamily(String code, String description) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.description = description;
        family.persist();
        return family;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllProductFamilies_Empty() {
        List<ProductFamily> families = productFamilyResource.allProductFamilies();
        assertNotNull(families);
        assertTrue(families.isEmpty(), "No family should exist in a fresh test database");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllProductFamilies_ReturnsAll() {
        persistFamily("FAM_P1", "Family P1");
        persistFamily("FAM_P2", "Family P2");

        List<ProductFamily> families = productFamilyResource.allProductFamilies();
        assertEquals(2, families.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductFamily_Success() {
        ProductFamily existing = persistFamily("FAM_P3", "Family P3");

        ProductFamily found = productFamilyResource.productFamily(existing.id);
        assertNotNull(found);
        assertEquals("FAM_P3", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductFamily_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            productFamilyResource.productFamily(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductFamilyByCode_Success() {
        persistFamily("FAM_P4", "Family P4");

        ProductFamily found = productFamilyResource.productFamilyByCode("FAM_P4");
        assertNotNull(found);
        assertEquals("FAM_P4", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testProductFamilyByCode_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            productFamilyResource.productFamilyByCode("FAM_UNKNOWN");
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_Success() throws GraphQLException {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_P5";
        input.description = "Family P5";

        ProductFamily created = productFamilyResource.createProductFamily(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("FAM_P5", created.code);
        assertEquals("Family P5", created.description);
        assertTrue(created.products.isEmpty());
        assertTrue(created.productFamilies.isEmpty());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_WithProductsAndSubFamilies_Success() throws GraphQLException {
        Product product = DomainUtils.createAndPersistProduct("1234567890130", "Apple", ProductType.UNIT);
        ProductFamily child = persistFamily("FAM_P6_CHILD", "Child family");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_P6";
        input.description = "Family P6";
        input.productEans = List.of("1234567890130");
        input.productFamilyCodes = List.of("FAM_P6_CHILD");

        ProductFamily created = productFamilyResource.createProductFamily(input);

        assertNotNull(created.id);
        assertEquals(1, created.products.size());
        assertEquals(product.id, created.products.iterator().next().id);
        assertEquals(1, created.productFamilies.size());
        assertEquals(child.id, created.productFamilies.iterator().next().id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_DuplicateCode() {
        persistFamily("FAM_P7", "Family P7");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_P7"; // duplicate
        input.description = "Another description";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productFamilyResource.createProductFamily(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_DuplicateDescription() {
        persistFamily("FAM_P8", "Family P8");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_P8_BIS";
        input.description = "Family P8"; // duplicate description

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productFamilyResource.createProductFamily(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_UnknownProductEan() {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_P9";
        input.description = "Family P9";
        input.productEans = List.of("0000000000000");

        assertThrows(NoSuchElementException.class, () -> {
            productFamilyResource.createProductFamily(input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateProductFamily_UnknownSubFamilyCode() {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM_P10";
        input.description = "Family P10";
        input.productFamilyCodes = List.of("FAM_UNKNOWN");

        assertThrows(NoSuchElementException.class, () -> {
            productFamilyResource.createProductFamily(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_Success() throws GraphQLException {
        Product productA = DomainUtils.createAndPersistProduct("1234567890131", "Pear", ProductType.UNIT);
        Product productB = DomainUtils.createAndPersistProduct("1234567890132", "Plum", ProductType.UNIT);
        ProductFamily child = persistFamily("FAM_P11_CHILD", "Child family");
        ProductFamily existing = persistFamily("FAM_P11", "Family P11");
        existing.products.add(productA);
        existing.persist();

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.description = "Family P11 updated";
        input.productEans = List.of("1234567890132"); // replacement strategy
        input.productFamilyCodes = List.of("FAM_P11_CHILD");

        ProductFamily updated = productFamilyResource.updateProductFamily(existing.id, input);

        assertEquals("Family P11 updated", updated.description);
        assertEquals(1, updated.products.size(), "Products must be replaced, not appended");
        assertEquals(productB.id, updated.products.iterator().next().id);
        assertEquals(1, updated.productFamilies.size());
        assertEquals(child.id, updated.productFamilies.iterator().next().id);
        assertEquals("FAM_P11", updated.code, "code must not change");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_NotFound() {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.description = "Ghost";

        assertThrows(NoSuchElementException.class, () -> {
            productFamilyResource.updateProductFamily(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_ConflictingDescription() {
        persistFamily("FAM_P12", "Family P12");
        ProductFamily existing = persistFamily("FAM_P13", "Family P13");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.description = "Family P12"; // already used by another family

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productFamilyResource.updateProductFamily(existing.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_UnknownProductEan() {
        ProductFamily existing = persistFamily("FAM_P14", "Family P14");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productEans = List.of("0000000000001");

        assertThrows(NoSuchElementException.class, () -> {
            productFamilyResource.updateProductFamily(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_UnknownSubFamilyCode() {
        ProductFamily existing = persistFamily("FAM_P15", "Family P15");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productFamilyCodes = List.of("FAM_UNKNOWN");

        assertThrows(NoSuchElementException.class, () -> {
            productFamilyResource.updateProductFamily(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateProductFamily_SelfReference_Rejected() {
        ProductFamily existing = persistFamily("FAM_P16", "Family P16");

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productFamilyCodes = List.of("FAM_P16"); // self reference

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            productFamilyResource.updateProductFamily(existing.id, input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("cannot contain itself"));
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteProductFamily_Success() throws GraphQLException {
        ProductFamily existing = persistFamily("FAM_P17", "Family P17");

        boolean result = productFamilyResource.deleteProductFamily(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(ProductFamily.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteProductFamily_NotFound() throws GraphQLException {
        boolean result = productFamilyResource.deleteProductFamily(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testProductFamilyRecord_ToString() {
        ProductFamilyResource.ProductFamilyRecord record = new ProductFamilyResource.ProductFamilyRecord();
        record.code = "FAM_REC";
        record.description = "Record family";
        record.productEans = List.of("EAN1", "EAN2");

        assertEquals("ProductFamilyRecord [code=FAM_REC, description=Record family, productEans=[EAN1, EAN2], productFamilyCodes=null]",
                record.toString());
    }

    @Test
    void testProductFamilyRecord_ToString_NullLists() {
        ProductFamilyResource.ProductFamilyRecord record = new ProductFamilyResource.ProductFamilyRecord();
        record.code = "FAM_REC2";

        assertEquals("ProductFamilyRecord [code=FAM_REC2, description=null, productEans=null, productFamilyCodes=null]",
                record.toString());
    }
}
