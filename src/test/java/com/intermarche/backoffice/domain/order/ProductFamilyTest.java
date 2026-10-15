package com.intermarche.backoffice.domain.order;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProductFamily} business logic.
 * Focuses on flag management ({@code addFlag}/{@code removeFlag}/{@code hasFlag})
 * and checksum sensitivity / stability.
 */
public class ProductFamilyTest {

    // --------------------------------------------------
    // addFlag
    // --------------------------------------------------

    @Test
    void addFlag_shouldAddTokenToEmptyFlags() {
        ProductFamily family = new ProductFamily();
        family.flags = null;

        family.addFlag("ORGANIC");

        assertEquals("ORGANIC", family.flags);
    }

    @Test
    void addFlag_shouldAppendTokenToExistingFlags() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        family.addFlag("SEASONAL");

        assertTrue(family.hasFlag("ORGANIC"));
        assertTrue(family.hasFlag("SEASONAL"));
    }

    @Test
    void addFlag_shouldTrimToken() {
        ProductFamily family = new ProductFamily();
        family.flags = null;

        family.addFlag("  ORGANIC  ");

        assertEquals("ORGANIC", family.flags);
    }

    @Test
    void addFlag_shouldNotDuplicateExistingToken() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        family.addFlag("ORGANIC");

        assertEquals("ORGANIC", family.flags);
    }

    @Test
    void addFlag_shouldIgnoreNullOrBlankToken() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        family.addFlag(null);
        family.addFlag("");
        family.addFlag("   ");

        assertEquals("ORGANIC", family.flags);
    }

    // --------------------------------------------------
    // removeFlag
    // --------------------------------------------------

    @Test
    void removeFlag_shouldRemoveExistingToken() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC,SEASONAL";

        assertTrue(family.removeFlag("ORGANIC"));

        assertFalse(family.hasFlag("ORGANIC"));
        assertTrue(family.hasFlag("SEASONAL"));
    }

    @Test
    void removeFlag_shouldReturnFalse_whenTokenAbsent() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        assertFalse(family.removeFlag("SEASONAL"));
        assertEquals("ORGANIC", family.flags);
    }

    @Test
    void removeFlag_shouldReturnFalse_whenTokenIsNull() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        assertFalse(family.removeFlag(null));
        assertEquals("ORGANIC", family.flags);
    }

    @Test
    void removeFlag_shouldSetFlagsToNull_whenLastTokenRemoved() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        assertTrue(family.removeFlag("ORGANIC"));

        assertNull(family.flags);
    }

    // --------------------------------------------------
    // hasFlag
    // --------------------------------------------------

    @Test
    void hasFlag_shouldReturnTrue_whenTokenPresent() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC, SEASONAL ,FRUIT";

        assertTrue(family.hasFlag("ORGANIC"));
        assertTrue(family.hasFlag("SEASONAL"));
        assertTrue(family.hasFlag("FRUIT"));
    }

    @Test
    void hasFlag_shouldBeCaseSensitive() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        assertFalse(family.hasFlag("organic"));
    }

    @Test
    void hasFlag_shouldTrimQueryToken() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        assertTrue(family.hasFlag("  ORGANIC  "));
    }

    @Test
    void hasFlag_shouldReturnFalse_whenTokenNullOrBlank() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";

        assertFalse(family.hasFlag(null));
        assertFalse(family.hasFlag(""));
        assertFalse(family.hasFlag("   "));
    }

    @Test
    void hasFlag_shouldReturnFalse_whenFlagsIsNull() {
        ProductFamily family = new ProductFamily();
        family.flags = null;

        assertFalse(family.hasFlag("ORGANIC"));
    }

    @Test
    void hasFlag_shouldReturnFalse_whenFlagsIsBlank() {
        ProductFamily family = new ProductFamily();
        family.flags = "   ";

        assertFalse(family.hasFlag("ORGANIC"));
    }

    @Test
    void hasFlag_shouldIgnoreEmptyTokensInFlags() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC,, ,SEASONAL";

        assertTrue(family.hasFlag("ORGANIC"));
        assertTrue(family.hasFlag("SEASONAL"));
        assertFalse(family.hasFlag("GLUTEN_FREE"));
    }

    @Test
    void addFlag_shouldTreatBlankFlagsAsEmptySet() {
        ProductFamily family = new ProductFamily();
        family.flags = "   ";

        family.addFlag("ORGANIC");

        assertEquals("ORGANIC", family.flags);
    }

    @Test
    void removeFlag_shouldReturnFalse_whenFlagsIsBlank() {
        ProductFamily family = new ProductFamily();
        family.flags = "   ";

        assertFalse(family.removeFlag("ORGANIC"));
        assertEquals("   ", family.flags);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenCodeChanges() {
        ProductFamily family = createFamily();
        family.code = "FAM-01";
        int checksum1 = family.getChecksum();

        family.code = "FAM-02";
        assertNotEquals(checksum1, family.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenFlagsChange() {
        ProductFamily family = createFamily();
        family.flags = "ORGANIC";
        int checksum1 = family.getChecksum();

        family.flags = "SEASONAL";
        assertNotEquals(checksum1, family.getChecksum());
    }

    @Test
    void getChecksum_shouldBeStableRegardlessOfProductInsertionOrder() {
        ProductFamily family = createFamily();
        family.products = new HashSet<>(Set.of(createProduct("EAN-B"), createProduct("EAN-A")));
        int checksum1 = family.getChecksum();

        // Rebuild with reversed insertion order: TreeSet must normalize
        ProductFamily other = createFamily();
        other.products = new HashSet<>(Set.of(createProduct("EAN-A"), createProduct("EAN-B")));
        int checksum2 = other.getChecksum();

        assertEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenProductEansChange() {
        ProductFamily family = createFamily();
        family.products = new HashSet<>(Set.of(createProduct("EAN-A")));
        int checksum1 = family.getChecksum();

        family.products = new HashSet<>(Set.of(createProduct("EAN-B")));
        assertNotEquals(checksum1, family.getChecksum());
    }

    private ProductFamily createFamily() {
        ProductFamily family = new ProductFamily();
        family.code = "FAM-01";
        family.description = "Test family";
        family.products = new HashSet<>();
        family.productFamilies = new HashSet<>();
        return family;
    }

    private Product createProduct(String ean) {
        Product product = new Product();
        product.ean = ean;
        return product;
    }

    // --------------------------------------------------
    // findByCode
    // --------------------------------------------------

    @Test
    void findByCode_found_returnsFamily() {
        ProductFamily family = createFamily();
        PanacheQuery<ProductFamily> query = mockFamilyQuery(family, null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(query);

            assertSame(family, ProductFamily.findByCode("FAM-01"));
        }
    }

    @Test
    void findByCode_notFound_returnsNull() {
        PanacheQuery<ProductFamily> query = mockFamilyQuery(null, null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(query);

            assertNull(ProductFamily.findByCode("UNKNOWN"));
        }
    }

    // --------------------------------------------------
    // findAllFamiliesForProduct
    // --------------------------------------------------

    @Test
    void findAllFamiliesForProduct_nullProduct_returnsEmpty() {
        assertTrue(ProductFamily.findAllFamiliesForProduct(null).isEmpty());
    }

    @Test
    void findAllFamiliesForProduct_productWithoutId_returnsEmpty() {
        Product product = createProduct("EAN-1");
        assertTrue(ProductFamily.findAllFamiliesForProduct(product).isEmpty());
    }

    @Test
    void findAllFamiliesForProduct_directParentsAndAncestors() {
        Product product = createProduct("EAN-1");
        product.id = 7L;
        ProductFamily direct = createFamily();
        direct.id = 1L;
        direct.code = "DIRECT";
        ProductFamily ancestor = createFamily();
        ancestor.id = 2L;
        ancestor.code = "ANCESTOR";

        PanacheQuery<ProductFamily> directQuery = mockFamilyQuery(null, List.of(direct));
        PanacheQuery<ProductFamily> parentQuery = mockFamilyQuery(null, null);
        // First parent lookup (for DIRECT) returns ANCESTOR, second (for ANCESTOR) returns empty
        when(parentQuery.list()).thenReturn(List.of(ancestor), List.of());

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(
                            eq("select pf from ProductFamily pf join pf.products p where p.id = ?1"), any(Object[].class)))
                    .thenReturn(directQuery);
            panacheMock.when(() -> PanacheEntityBase.find(
                            eq("select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1"), any(Object[].class)))
                    .thenReturn(parentQuery);

            Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product);

            assertEquals(2, result.size());
            assertTrue(result.contains(direct));
            assertTrue(result.contains(ancestor));
        }
    }

    @Test
    void findAllFamiliesForProduct_cycle_stopsRecursion() {
        Product product = createProduct("EAN-1");
        product.id = 7L;
        ProductFamily family = createFamily();
        family.id = 1L;

        PanacheQuery<ProductFamily> directQuery = mockFamilyQuery(null, List.of(family));
        PanacheQuery<ProductFamily> parentQuery = mockFamilyQuery(null, null);
        // The family is its own parent: recursion must stop instead of looping forever
        when(parentQuery.list()).thenReturn(List.of(family));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(
                            eq("select pf from ProductFamily pf join pf.products p where p.id = ?1"), any(Object[].class)))
                    .thenReturn(directQuery);
            panacheMock.when(() -> PanacheEntityBase.find(
                            eq("select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1"), any(Object[].class)))
                    .thenReturn(parentQuery);

            Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product);

            assertEquals(1, result.size());
            assertTrue(result.contains(family));
        }
    }

    // --------------------------------------------------
    // productHasFlag
    // --------------------------------------------------

    @Test
    void productHasFlag_nullProduct_returnsFalse() {
        assertFalse(ProductFamily.productHasFlag(null, "ORGANIC"));
    }

    @Test
    void productHasFlag_nullOrBlankFlag_returnsFalse() {
        Product product = createProduct("EAN-1");
        product.id = 7L;
        assertFalse(ProductFamily.productHasFlag(product, null));
        assertFalse(ProductFamily.productHasFlag(product, ""));
        assertFalse(ProductFamily.productHasFlag(product, "   "));
    }

    @Test
    void productHasFlag_flagInFamily_returnsTrue() {
        Product product = createProduct("EAN-1");
        product.id = 7L;
        ProductFamily family = createFamily();
        family.id = 1L;
        family.flags = "ORGANIC,SEASONAL";

        PanacheQuery<ProductFamily> directQuery = mockFamilyQuery(null, List.of(family));
        PanacheQuery<ProductFamily> parentQuery = mockFamilyQuery(null, List.of());

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(
                            eq("select pf from ProductFamily pf join pf.products p where p.id = ?1"), any(Object[].class)))
                    .thenReturn(directQuery);
            panacheMock.when(() -> PanacheEntityBase.find(
                            eq("select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1"), any(Object[].class)))
                    .thenReturn(parentQuery);

            assertTrue(ProductFamily.productHasFlag(product, "ORGANIC"));
        }
    }

    @Test
    void productHasFlag_flagNotPresent_returnsFalse() {
        Product product = createProduct("EAN-1");
        product.id = 7L;
        ProductFamily family = createFamily();
        family.id = 1L;
        family.flags = "ORGANIC";

        PanacheQuery<ProductFamily> directQuery = mockFamilyQuery(null, List.of(family));
        PanacheQuery<ProductFamily> parentQuery = mockFamilyQuery(null, List.of());

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(
                            eq("select pf from ProductFamily pf join pf.products p where p.id = ?1"), any(Object[].class)))
                    .thenReturn(directQuery);
            panacheMock.when(() -> PanacheEntityBase.find(
                            eq("select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1"), any(Object[].class)))
                    .thenReturn(parentQuery);

            assertFalse(ProductFamily.productHasFlag(product, "GLUTEN_FREE"));
        }
    }

    // --------------------------------------------------
    // findAncestorsRecursive
    // --------------------------------------------------

    @Test
    void findAncestorsRecursive_alreadyVisited_stopsImmediately() {
        ProductFamily family = createFamily();
        family.id = 1L;
        Set<ProductFamily> ancestors = new HashSet<>();
        ancestors.add(family);

        // Must not query the database at all: the family is already in the set
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            ProductFamily.findAncestorsRecursive(family, ancestors);

            assertEquals(1, ancestors.size());
            panacheMock.verifyNoInteractions();
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static PanacheQuery<ProductFamily> mockFamilyQuery(ProductFamily firstResult, List<ProductFamily> list) {
        PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(firstResult);
        when(query.list()).thenReturn(list != null ? list : List.of());
        return query;
    }
}
