package com.intermarche.e2e.domain.wms;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link HandlingOrderSequence}.
 * Tests the business code generation logic (DDMM-PREFIX-#### format).
 */
@QuarkusTest
public class HandlingOrderSequenceTest {

    @Inject
    EntityManager em;

    private Store createStore(String code) {
        Store store = new Store();
        store.code = code;
        store.name = "Store " + code;
        store.address = new Adresse();
        store.address.streetLine1 = "1 rue de Test";
        store.address.city = "Paris";
        store.address.postalCode = "75001";
        store.address.country = "France";
        store.persist();
        return store;
    }

    @Test
    @TestTransaction
    void getNextCode_shouldGenerateFirstCode_whenNoSequenceExists() {
        Store store = createStore("SEQ-TEST-1");

        String code = HandlingOrderSequence.getNextCode(store, "PAK");

        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMM"));
        assertEquals(datePart + "-PAK-0001", code);
    }

    @Test
    @TestTransaction
    void getNextCode_shouldIncrementSequence_whenCalledMultipleTimes() {
        Store store = createStore("SEQ-TEST-2");

        String code1 = HandlingOrderSequence.getNextCode(store, "GRP");
        String code2 = HandlingOrderSequence.getNextCode(store, "GRP");
        String code3 = HandlingOrderSequence.getNextCode(store, "GRP");

        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMM"));
        assertEquals(datePart + "-GRP-0001", code1);
        assertEquals(datePart + "-GRP-0002", code2);
        assertEquals(datePart + "-GRP-0003", code3);
    }

    @Test
    @TestTransaction
    void getNextCode_shouldUseSeparateSequencesForDifferentPrefixes() {
        Store store = createStore("SEQ-TEST-3");

        String pakCode = HandlingOrderSequence.getNextCode(store, "PAK");
        String grpCode = HandlingOrderSequence.getNextCode(store, "GRP");

        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMM"));
        assertEquals(datePart + "-PAK-0001", pakCode);
        assertEquals(datePart + "-GRP-0001", grpCode);
    }

    @Test
    @TestTransaction
    void getNextCode_shouldUseSeparateSequencesForDifferentStores() {
        Store store1 = createStore("SEQ-TEST-4A");
        Store store2 = createStore("SEQ-TEST-4B");

        String code1 = HandlingOrderSequence.getNextCode(store1, "PAK");
        String code2 = HandlingOrderSequence.getNextCode(store2, "PAK");

        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMM"));
        assertEquals(datePart + "-PAK-0001", code1);
        assertEquals(datePart + "-PAK-0001", code2);
    }

    @Test
    @TestTransaction
    void getNextCode_shouldUppercasePrefix() {
        Store store = createStore("SEQ-TEST-5");

        String code = HandlingOrderSequence.getNextCode(store, "pak");

        assertTrue(code.contains("-PAK-"));
    }

    @Test
    @TestTransaction
    void getNextCode_shouldPadWithZeros() {
        Store store = createStore("SEQ-TEST-6");

        // Generate 10 codes to test padding
        String lastCode = null;
        for (int i = 0; i < 10; i++) {
            lastCode = HandlingOrderSequence.getNextCode(store, "TST");
        }

        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMM"));
        assertEquals(datePart + "-TST-0010", lastCode);
    }

    @Test
    @TestTransaction
    void getNextCode_shouldThrowException_whenStoreIsNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            HandlingOrderSequence.getNextCode(null, "PAK");
        });
    }

    @Test
    @TestTransaction
    void getNextCode_shouldThrowException_whenPrefixIsNull() {
        Store store = createStore("SEQ-TEST-7");

        assertThrows(IllegalArgumentException.class, () -> {
            HandlingOrderSequence.getNextCode(store, null);
        });
    }

    // --------------------------------------------------
    // SequenceId Tests
    // --------------------------------------------------

    @Test
    void sequenceId_equals_shouldWorkCorrectly() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);

        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    void sequenceId_equals_shouldReturnFalse_forDifferentStore() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(2L, "PAK", date);

        assertNotEquals(id1, id2);
    }

    @Test
    void sequenceId_equals_shouldReturnFalse_forDifferentLabel() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", date);
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(1L, "GRP", date);

        assertNotEquals(id1, id2);
    }

    @Test
    void sequenceId_equals_shouldReturnFalse_forDifferentDate() {
        HandlingOrderSequence.SequenceId id1 = new HandlingOrderSequence.SequenceId(1L, "PAK", LocalDate.of(2026, 1, 15));
        HandlingOrderSequence.SequenceId id2 = new HandlingOrderSequence.SequenceId(1L, "PAK", LocalDate.of(2026, 1, 16));

        assertNotEquals(id1, id2);
    }
}
