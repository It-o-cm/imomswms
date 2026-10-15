package com.intermarche.backoffice.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Tote} business logic.
 * Focuses on {@link Tote#isUsageAllowed(TemperatureZone)} validation rules.
 */
public class ToteTest {

    // --------------------------------------------------
    // isUsageAllowed - STANDARD Tote
    // --------------------------------------------------

    @Test
    void isUsageAllowed_standardTote_shouldAllowAmbient() {
        Tote tote = createTote(Tote.ToteType.STANDARD);
        assertTrue(tote.isUsageAllowed(TemperatureZone.AMBIENT));
    }

    @Test
    void isUsageAllowed_standardTote_shouldAllowHousehold() {
        Tote tote = createTote(Tote.ToteType.STANDARD);
        assertTrue(tote.isUsageAllowed(TemperatureZone.HOUSEHOLD));
    }

    @Test
    void isUsageAllowed_standardTote_shouldRejectChilled() {
        Tote tote = createTote(Tote.ToteType.STANDARD);
        assertFalse(tote.isUsageAllowed(TemperatureZone.CHILLED));
    }

    @Test
    void isUsageAllowed_standardTote_shouldRejectFrozen() {
        Tote tote = createTote(Tote.ToteType.STANDARD);
        assertFalse(tote.isUsageAllowed(TemperatureZone.FROZEN));
    }

    // --------------------------------------------------
    // isUsageAllowed - INSULATED Tote
    // --------------------------------------------------

    @Test
    void isUsageAllowed_insulatedTote_shouldAllowChilled() {
        Tote tote = createTote(Tote.ToteType.INSULATED);
        assertTrue(tote.isUsageAllowed(TemperatureZone.CHILLED));
    }

    @Test
    void isUsageAllowed_insulatedTote_shouldAllowFrozen() {
        Tote tote = createTote(Tote.ToteType.INSULATED);
        assertTrue(tote.isUsageAllowed(TemperatureZone.FROZEN));
    }

    @Test
    void isUsageAllowed_insulatedTote_shouldRejectAmbient() {
        Tote tote = createTote(Tote.ToteType.INSULATED);
        assertFalse(tote.isUsageAllowed(TemperatureZone.AMBIENT));
    }

    @Test
    void isUsageAllowed_insulatedTote_shouldRejectHousehold() {
        Tote tote = createTote(Tote.ToteType.INSULATED);
        assertFalse(tote.isUsageAllowed(TemperatureZone.HOUSEHOLD));
    }

    // --------------------------------------------------
    // isUsageAllowed - Null ToteType (edge case)
    // --------------------------------------------------

    @Test
    void isUsageAllowed_nullToteType_shouldRejectAll() {
        Tote tote = new Tote();
        tote.toteType = null;

        assertFalse(tote.isUsageAllowed(TemperatureZone.AMBIENT));
        assertFalse(tote.isUsageAllowed(TemperatureZone.CHILLED));
        assertFalse(tote.isUsageAllowed(TemperatureZone.FROZEN));
        assertFalse(tote.isUsageAllowed(TemperatureZone.HOUSEHOLD));
    }

    // --------------------------------------------------
    // getChecksum Tests
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenCodeChanges() {
        Tote tote = createTote(Tote.ToteType.STANDARD);
        tote.code = "TOTE-001";
        tote.store = createStore();
        int checksum1 = tote.getChecksum();

        tote.code = "TOTE-002";
        int checksum2 = tote.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenToteTypeChanges() {
        Tote tote = createTote(Tote.ToteType.STANDARD);
        tote.code = "TOTE-001";
        tote.store = createStore();
        int checksum1 = tote.getChecksum();

        tote.toteType = Tote.ToteType.INSULATED;
        int checksum2 = tote.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private Tote createTote(Tote.ToteType type) {
        Tote tote = new Tote();
        tote.toteType = type;
        return tote;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    @Test
    void findByStore_shouldReturnTotesOfStore() {
        Store store = createStore();
        Tote tote = createTote(Tote.ToteType.STANDARD);
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("store"), any(Object[].class)))
                    .thenReturn(List.of(tote));

            List<Tote> result = Tote.findByStore(store);

            assertEquals(1, result.size());
            assertSame(tote, result.get(0));
        }
    }

    private Store createStore() {
        Store store = new Store();
        store.id = 1L;
        store.code = "S001";
        store.name = "Test Store";
        return store;
    }
}
