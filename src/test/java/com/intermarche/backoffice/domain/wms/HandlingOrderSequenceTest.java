package com.intermarche.backoffice.domain.wms;

import com.intermarche.backoffice.domain.store.Store;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HandlingOrderSequence} and its composite key {@link HandlingOrderSequence.SequenceId}.
 * Pure unit tests — no database, no Quarkus.
 */
class HandlingOrderSequenceTest {

    private final LocalDate date = LocalDate.of(2026, 10, 14);

    // --------------------------------------------------
    // SequenceId
    // --------------------------------------------------

    @Test
    void sequenceId_defaultConstructor_fieldsNull() {
        HandlingOrderSequence.SequenceId id = new HandlingOrderSequence.SequenceId();
        assertNull(id.store);
        assertNull(id.label);
        assertNull(id.date);
    }

    @Test
    void sequenceId_parameterizedConstructor_setsFields() {
        HandlingOrderSequence.SequenceId id = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        assertEquals(1L, id.store);
        assertEquals("PAK", id.label);
        assertEquals(date, id.date);
    }

    @Test
    void sequenceId_equals_sameValues() {
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        assertEquals(id1, id2);
    }

    @Test
    void sequenceId_equals_reflexive() {
        HandlingOrderSequence.SequenceId id = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        assertEquals(id, id);
    }

    @Test
    void sequenceId_notEquals_differentStore() {
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(2L, "PAK", date);
        assertNotEquals(id1, id2);
    }

    @Test
    void sequenceId_notEquals_differentLabel() {
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(1L, "GRP", date);
        assertNotEquals(id1, id2);
    }

    @Test
    void sequenceId_notEquals_differentDate() {
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(1L, "PAK", date.plusDays(1));
        assertNotEquals(id1, id2);
    }

    @Test
    void sequenceId_notEquals_null() {
        HandlingOrderSequence.SequenceId id = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        assertNotEquals(id, null);
    }

    @Test
    void sequenceId_notEquals_otherType() {
        HandlingOrderSequence.SequenceId id = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        assertNotEquals(id, "not-a-sequence-id");
    }

    @Test
    void sequenceId_hashCode_consistentWithEquals() {
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    void sequenceId_hashCode_stableAcrossCalls() {
        HandlingOrderSequence.SequenceId id = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        assertEquals(id.hashCode(), id.hashCode());
    }

    // --------------------------------------------------
    // Entity
    // --------------------------------------------------

    @Test
    void entity_defaultConstructor_nextValIsOne() {
        HandlingOrderSequence sequence = new HandlingOrderSequence();
        assertEquals(1L, sequence.nextVal);
        assertNull(sequence.store);
        assertNull(sequence.label);
        assertNull(sequence.date);
    }

    @Test
    void entity_parameterizedConstructor_setsFieldsAndNextValOne() {
        Store store = new Store();
        HandlingOrderSequence sequence = new HandlingOrderSequence(store, "PAK", date);

        assertSame(store, sequence.store);
        assertEquals("PAK", sequence.label);
        assertEquals(date, sequence.date);
        assertEquals(1L, sequence.nextVal);
    }

    // --------------------------------------------------
    // getNextCode — argument validation (no DB involved)
    // --------------------------------------------------

    @Test
    void getNextCode_nullStore_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> HandlingOrderSequence.getNextCode(null, "PAK"));
    }

    @Test
    void getNextCode_nullPrefix_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> HandlingOrderSequence.getNextCode(new Store(), null));
    }

    @Test
    void getNextCode_nullStoreAndPrefix_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> HandlingOrderSequence.getNextCode(null, null));
    }
}
