package com.intermarche.backoffice.graphql.store;

import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AisleResource}.
 */
class AisleResourceTest {

    @Test
    void getIdentity_returnsInjectedIdentity() {
        AisleResource resource = new AisleResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }
}
