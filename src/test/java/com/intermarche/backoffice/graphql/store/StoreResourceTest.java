package com.intermarche.backoffice.graphql.store;

import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link StoreResource}.
 */
class StoreResourceTest {

    @Test
    void getIdentity_returnsInjectedIdentity() {
        StoreResource resource = new StoreResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }
}
