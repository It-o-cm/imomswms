package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.persistence.PersistenceException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link GraphQLTrait} interface.
 * Tests the default execute method behavior for success and various exception scenarios.
 */
public class GraphQLTraitTest {

    // Create an anonymous implementation to test the default method
    private final GraphQLTrait trait = new GraphQLTrait() {
        @Override
        public SecurityIdentity getIdentity() {
            return null;
        }
    };

    // --------------------------------------------------
    // Success Scenario
    // --------------------------------------------------

    /**
     * Tests successful execution of the supplier.
     */
    @Test
    void testExecute_Success() throws GraphQLException {
        String result = trait.execute(() -> "Success", GraphQLTraitTest.class, "testOperation");
        assertEquals("Success", result);
    }

    // --------------------------------------------------
    // Exception Scenarios
    // --------------------------------------------------

    /**
     * Tests that {@link AlreadyExistsException} is caught and wrapped in {@link GraphQLException}.
     * <p>
     * Validates:
     * - Exception message is preserved in the GraphQLException message.
     * - The original exception is set as the cause.
     */
    @Test
    void testExecute_AlreadyExistsException() {
        String errorMessage = "Resource already exists";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            trait.execute(() -> {
                throw new AlreadyExistsException(errorMessage);
            }, GraphQLTraitTest.class, "createResource");
        });

        assertTrue(ex.getMessage().contains(errorMessage));
        assertTrue(ex.getCause() instanceof AlreadyExistsException);
    }

    /**
     * Tests that {@link PersistenceException} is caught and wrapped in {@link GraphQLException}.
     * <p>
     * Validates:
     * - A generic database error message is returned (not exposing raw DB errors).
     * - The original exception is set as the cause.
     */
    @Test
    void testExecute_PersistenceException() {
        PersistenceException cause = new PersistenceException("Constraint violation");

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            trait.execute(() -> {
                throw cause;
            }, GraphQLTraitTest.class, "saveResource");
        });

        assertTrue(ex.getMessage().contains("Database error while performing saveResource"));
        assertEquals(cause, ex.getCause());
    }

    /**
     * Tests that {@link NoSuchElementException} is re-thrown directly without wrapping.
     * <p>
     * Validates:
     * - The exception is NOT wrapped in a GraphQLException.
     */
    @Test
    void testExecute_NoSuchElementException() {
        String errorMessage = "Item not found";
        NoSuchElementException cause = new NoSuchElementException(errorMessage);

        // Expect NoSuchElementException directly, not GraphQLException
        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> {
            trait.execute(() -> {
                throw cause;
            }, GraphQLTraitTest.class, "getResource");
        });

        assertEquals(errorMessage, ex.getMessage());
    }

    /**
     * Tests that generic {@link Exception} is caught and wrapped in {@link GraphQLException}.
     * <p>
     * Validates:
     * - A generic error message is returned.
     * - The original exception is set as the cause.
     */
    @Test
    void testExecute_GenericException() {
        RuntimeException cause = new RuntimeException("Unexpected failure");

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            trait.execute(() -> {
                throw cause;
            }, GraphQLTraitTest.class, "processResource");
        });

        assertTrue(ex.getMessage().contains("An error occurred during processResource"));
        assertEquals(cause, ex.getCause());
    }

    /**
     * Tests the constructor with a message only.
     */
    @Test
    void testConstructor_Message() {
        String message = "Entity already exists";
        AlreadyExistsException ex = new AlreadyExistsException(message);

        assertEquals(message, ex.getMessage());
        assertNull(ex.getCause());
    }

    /**
     * Tests the constructor with a message and a cause.
     */
    @Test
    void testConstructor_MessageAndCause() {
        String message = "Entity already exists";
        Throwable cause = new RuntimeException("Underlying DB error");

        AlreadyExistsException ex = new AlreadyExistsException(message, cause);

        assertEquals(message, ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    // --------------------------------------------------
    // Read scope
    // --------------------------------------------------

    /**
     * Builds a trait whose identity has the given roles and whose employee belongs to the given store.
     *
     * @param admin whether the user is an administrator
     * @param serviceCaller whether the user is an integration service
     * @param own store of the employee
     * @return the trait
     */
    private GraphQLTrait scoped(boolean admin, boolean serviceCaller, Store own) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole("ADMIN")).thenReturn(admin);
        when(identity.hasRole("SERVICE_CALLER")).thenReturn(serviceCaller);
        return new GraphQLTrait() {
            @Override
            public SecurityIdentity getIdentity() {
                return identity;
            }

            @Override
            public Store getStoreContext() {
                if (own == null) throw new SecurityException("User not linked to a store and lacks global privileges.");
                return own;
            }
        };
    }

    /**
     * Builds a store with an identifier.
     *
     * @param id identifier
     * @return the store
     */
    private static Store store(long id) {
        Store store = new Store();
        store.id = id;
        return store;
    }

    /**
     * Administrators and integration services read every store, even when linked to one; a manager reads their store.
     */
    @Test
    void readScope_isGlobalForAdministratorsAndServices_andTheOwnStoreForAManager() {
        Store own = store(1);
        assertNull(scoped(true, false, own).readScope());
        assertNull(scoped(false, true, own).readScope());
        assertSame(own, scoped(false, false, own).readScope());
        assertThrows(SecurityException.class, () -> scoped(false, false, null).readScope());
    }

    /**
     * A store addressed explicitly must exist and be readable by the user.
     */
    @Test
    void checkReadable_acceptsTheOwnStoreOrGlobalAccess_andRefusesAnotherOrUnknownStore() {
        Store own = store(1);
        assertDoesNotThrow(() -> scoped(false, false, own).checkReadable(store(1)));
        assertDoesNotThrow(() -> scoped(true, false, own).checkReadable(store(2)));
        assertThrows(SecurityException.class, () -> scoped(false, false, own).checkReadable(store(2)));
        assertThrows(NoSuchElementException.class, () -> scoped(true, false, own).checkReadable(null));
    }
}
