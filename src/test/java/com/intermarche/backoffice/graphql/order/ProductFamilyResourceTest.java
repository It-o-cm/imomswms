package com.intermarche.backoffice.graphql.order;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductFamily;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProductFamilyResource}.
 * Covers the identity accessor, child linking branches of the create/update mutations
 * and the input record toString.
 */
class ProductFamilyResourceTest {

    private MockedStatic<PanacheEntityBase> panacheMock;
    private MockedStatic<Arc> arcMock;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
        arcMock = mockStatic(Arc.class);
        PanacheTestSupport.mockArcSession(arcMock);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
        arcMock.close();
    }

    private ProductFamilyResource newResource() {
        ProductFamilyResource resource = new ProductFamilyResource();
        resource.identity = mock(SecurityIdentity.class);
        return resource;
    }

    private Product product(String ean) {
        Product product = new Product();
        product.id = 10L;
        product.ean = ean;
        return product;
    }

    private ProductFamily childFamily(String code) {
        ProductFamily child = new ProductFamily();
        child.id = 20L;
        child.code = code;
        return child;
    }

    @Test
    void getIdentity_returnsInjectedIdentity() {
        ProductFamilyResource resource = new ProductFamilyResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    @Test
    void createProductFamily_withEansAndCodes_linksChildren() throws Exception {
        Product product = product("EAN-1");
        ProductFamily child = childFamily("FAM-CHILD");
        panacheMock.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class)))
                .thenReturn(0L);
        panacheMock.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(0);
                    PanacheQuery<?> queryMock = mock(PanacheQuery.class);
                    if (query.equals("ean")) {
                        when(queryMock.firstResult()).thenReturn(product);
                    } else {
                        when(queryMock.firstResult()).thenReturn(child);
                    }
                    return queryMock;
                });

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM-1";
        input.description = "Family 1";
        input.productEans = List.of("EAN-1");
        input.productFamilyCodes = List.of("FAM-CHILD");

        ProductFamily result = newResource().createProductFamily(input);

        assertEquals("FAM-1", result.code);
        assertTrue(result.products.contains(product));
        assertTrue(result.productFamilies.contains(child));
    }

    @Test
    void createProductFamily_withoutChildren_skipsLinking() throws Exception {
        panacheMock.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class)))
                .thenReturn(0L);

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM-1";
        input.description = "Family 1";
        input.productEans = null;
        input.productFamilyCodes = null;

        ProductFamily result = newResource().createProductFamily(input);

        assertEquals("FAM-1", result.code);
        assertTrue(result.products.isEmpty());
        assertTrue(result.productFamilies.isEmpty());
    }

    @Test
    void createProductFamily_emptyEansAndCodes_skipsLinking() throws Exception {
        panacheMock.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class)))
                .thenReturn(0L);

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = "FAM-1";
        input.description = "Family 1";
        input.productEans = List.of();
        input.productFamilyCodes = List.of();

        ProductFamily result = newResource().createProductFamily(input);

        assertEquals("FAM-1", result.code);
        assertTrue(result.products.isEmpty());
        assertTrue(result.productFamilies.isEmpty());
    }

    @Test
    void updateProductFamily_sameDescription_skipsUniquenessCheck() throws Exception {
        ProductFamily family = new ProductFamily();
        family.id = 1L;
        family.code = "FAM-1";
        family.description = "same description";
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(family);

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.description = "same description";

        ProductFamily result = newResource().updateProductFamily(1L, input);

        assertSame(family, result);
        assertEquals("same description", family.description);
        // No uniqueness count query should have been issued
        panacheMock.verify(() -> PanacheEntityBase.count(anyString(), any(Object[].class)), never());
    }

    @Test
    void updateProductFamily_newDescription_updatesDescription() throws Exception {
        ProductFamily family = new ProductFamily();
        family.id = 1L;
        family.code = "FAM-1";
        family.description = "old description";
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(family);
        panacheMock.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class)))
                .thenReturn(0L);

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.description = "new description";

        ProductFamily result = newResource().updateProductFamily(1L, input);

        assertSame(family, result);
        assertEquals("new description", family.description);
    }

    @Test
    void updateProductFamily_withProductFamilyCodes_replacesChildren() throws Exception {
        ProductFamily family = new ProductFamily();
        family.id = 1L;
        family.code = "FAM-1";
        family.description = "desc";
        ProductFamily oldChild = childFamily("FAM-OLD");
        oldChild.id = 20L;
        family.productFamilies.add(oldChild);
        ProductFamily newChild = childFamily("FAM-NEW");
        newChild.id = 21L;
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(family);
        panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                .thenAnswer(invocation -> {
                    PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
                    when(query.firstResult()).thenReturn(newChild);
                    return query;
                });

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productFamilyCodes = List.of("FAM-NEW");

        ProductFamily result = newResource().updateProductFamily(1L, input);

        assertSame(family, result);
        assertFalse(family.productFamilies.contains(oldChild));
        assertTrue(family.productFamilies.contains(newChild));
    }

    @Test
    void updateProductFamily_withProductEans_replacesProducts() throws Exception {
        ProductFamily family = new ProductFamily();
        family.id = 1L;
        family.code = "FAM-1";
        family.description = "desc";
        Product product = product("EAN-1");
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(family);
        panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class)))
                .thenAnswer(invocation -> {
                    PanacheQuery<Product> query = mock(PanacheQuery.class);
                    when(query.firstResult()).thenReturn(product);
                    return query;
                });

        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.productEans = List.of("EAN-1");

        ProductFamily result = newResource().updateProductFamily(1L, input);

        assertSame(family, result);
        assertTrue(family.products.contains(product));
    }

    @Test
    void productFamilyRecord_toString_withFamilies_formatsList() {
        ProductFamilyResource.ProductFamilyRecord record = new ProductFamilyResource.ProductFamilyRecord();
        record.code = "FAM-1";
        record.description = "desc";
        record.productEans = List.of("EAN-1", "EAN-2");
        record.productFamilyCodes = List.of("FAM-A", "FAM-B");

        String text = record.toString();

        assertTrue(text.contains("[EAN-1, EAN-2]"));
        assertTrue(text.contains("[FAM-A, FAM-B]"));
    }

    @Test
    void productFamilyRecord_toString_nullLists_formatsNull() {
        ProductFamilyResource.ProductFamilyRecord record = new ProductFamilyResource.ProductFamilyRecord();
        record.code = "FAM-1";
        record.description = "desc";
        record.productEans = null;
        record.productFamilyCodes = null;

        String text = record.toString();

        assertTrue(text.contains("productEans=null"));
        assertTrue(text.contains("productFamilyCodes=null"));
    }
}
