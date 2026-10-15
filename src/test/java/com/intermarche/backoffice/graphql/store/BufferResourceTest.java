package com.intermarche.backoffice.graphql.store;

import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link BufferResource}.
 */
class BufferResourceTest {

    @Test
    void getIdentity_returnsInjectedIdentity() {
        BufferResource resource = new BufferResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }
}
