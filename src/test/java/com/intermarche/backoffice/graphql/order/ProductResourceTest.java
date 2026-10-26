package com.intermarche.backoffice.graphql.order;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProductResource}.
 * Covers the identity accessor and the temperatureZone mapping branches
 * of the create and update mutations.
 */
class ProductResourceTest {

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

    private ProductResource newResource() {
        ProductResource resource = new ProductResource();
        resource.identity = mock(SecurityIdentity.class);
        return resource;
    }

    private void stubNoExistingProduct() {
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        panacheMock.when(() -> PanacheEntityBase.find(eq("ean"), any(Object[].class)))
                .thenReturn(query);
        panacheMock.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class)))
                .thenReturn(0L);
    }

    @Test
    void getIdentity_returnsInjectedIdentity() {
        ProductResource resource = new ProductResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    @Test
    void createProduct_withTemperatureZone_setsZone() throws Exception {
        stubNoExistingProduct();

        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "EAN-1";
        input.name = "Product 1";
        input.temperatureZone = "CHILLED";
        input.characteristics="Texte libre de caractéristiques. ".repeat(30);

        Product result = newResource().createProduct(input);

        assertEquals("EAN-1", result.ean);
        assertEquals(input.characteristics,result.characteristics);
        assertEquals(TemperatureZone.CHILLED, result.temperatureZone);
    }

    @Test
    void createProduct_withoutTemperatureZone_defaultsToAmbient() throws Exception {
        stubNoExistingProduct();

        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = "EAN-1";
        input.name = "Product 1";
        input.temperatureZone = null;

        Product result = newResource().createProduct(input);

        assertEquals(TemperatureZone.AMBIENT, result.temperatureZone);
    }

    @Test
    void updateProduct_withTemperatureZone_updatesZone() throws Exception {
        Product product = new Product();
        product.id = 5L;
        product.ean = "EAN-1";
        product.name = "Product 1";
        product.temperatureZone = TemperatureZone.AMBIENT;
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(product);

        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.temperatureZone = "FROZEN";
        input.characteristics="Nouvelle description détaillée";

        Product result = newResource().updateProduct(5L, input);

        assertSame(product, result);
        assertEquals(TemperatureZone.FROZEN, product.temperatureZone);
    }

    @Test
    void updateProduct_withoutTemperatureZone_keepsExistingZone() throws Exception {
        Product product = new Product();
        product.id = 5L;
        product.ean = "EAN-1";
        product.name = "Product 1";
        product.temperatureZone = TemperatureZone.CHILLED;
        product.characteristics="À conserver si ce champ est absent de la mise à jour";
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(product);

        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.temperatureZone = null;

        Product result = newResource().updateProduct(5L, input);

        assertSame(product, result);
        assertEquals(TemperatureZone.CHILLED, product.temperatureZone);
        assertEquals("À conserver si ce champ est absent de la mise à jour",product.characteristics);
    }
}
