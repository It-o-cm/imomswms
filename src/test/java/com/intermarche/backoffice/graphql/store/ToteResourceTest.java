package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Tote;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ToteResource}.
 * Covers the identity accessor and the volume blank-check branch of the update mutation.
 */
class ToteResourceTest {

    private MockedStatic<PanacheEntityBase> panacheMock;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
    }

    private ToteResource newResource() {
        ToteResource resource = new ToteResource();
        resource.identity = mock(SecurityIdentity.class);
        return resource;
    }

    @Test
    void getIdentity_returnsInjectedIdentity() {
        ToteResource resource = new ToteResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    @Test
    void updateTote_blankVolume_keepsExistingVolume() throws Exception {
        Tote tote = new Tote();
        tote.id = 1L;
        tote.code = "TOTE-1";
        tote.volume = new BigDecimal("45.5");
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(tote);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.volume = "   ";

        Tote result = newResource().updateTote(1L, input);

        assertSame(tote, result);
        assertEquals(new BigDecimal("45.5"), tote.volume);
    }

    @Test
    void updateTote_validVolume_updatesVolume() throws Exception {
        Tote tote = new Tote();
        tote.id = 1L;
        tote.code = "TOTE-1";
        tote.volume = new BigDecimal("45.5");
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(tote);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.volume = "12.75";

        Tote result = newResource().updateTote(1L, input);

        assertSame(tote, result);
        assertEquals(new BigDecimal("12.75"), tote.volume);
    }

    @Test
    void updateTote_nullVolume_keepsExistingVolume() throws Exception {
        Tote tote = new Tote();
        tote.id = 1L;
        tote.code = "TOTE-1";
        tote.volume = new BigDecimal("45.5");
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(tote);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.volume = null;

        Tote result = newResource().updateTote(1L, input);

        assertSame(tote, result);
        assertEquals(new BigDecimal("45.5"), tote.volume);
    }
}
