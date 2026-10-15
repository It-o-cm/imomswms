package com.intermarche.backoffice.graphql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AlreadyExistsException}.
 */
class AlreadyExistsExceptionTest {

    @Test
    void messageConstructor_setsMessage() {
        AlreadyExistsException exception = new AlreadyExistsException("Duplicate code");
        assertEquals("Duplicate code", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void messageAndCauseConstructor_setsBoth() {
        RuntimeException cause = new RuntimeException("root cause");
        AlreadyExistsException exception = new AlreadyExistsException("Duplicate code", cause);

        assertEquals("Duplicate code", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void isRuntimeException() {
        assertTrue(new AlreadyExistsException("test") instanceof RuntimeException);
    }

    @Test
    void canBeThrownAndCaught() {
        AlreadyExistsException thrown = assertThrows(AlreadyExistsException.class,
                () -> {
                    throw new AlreadyExistsException("conflict");
                });
        assertEquals("conflict", thrown.getMessage());
    }
}
