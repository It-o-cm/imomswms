package com.intermarche.e2e.domain.executing;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LoadedTote}.
 * Tests the exclusivity rule: a physical Tote can only have one active LoadedTote at a time.
 */
@QuarkusTest
public class LoadedToteTest {

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

    private Tote createTote(Store store, String code) {
        Tote tote = new Tote();
        tote.code = code;
        tote.volume = new BigDecimal("45.000");
        tote.toteType = Tote.ToteType.STANDARD;
        tote.store = store;
        tote.persist();
        return tote;
    }

    // --------------------------------------------------
    // useAnEmptyTote Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void useAnEmptyTote_shouldCreateLoadedTote_whenToteIsAvailable() {
        Store store = createStore("LT-S1");
        Tote tote = createTote(store, "TOTE-LT-001");

        LoadedTote loadedTote = LoadedTote.useAnEmptyTote(tote);
        loadedTote.storageType = TemperatureZone.AMBIENT;
        loadedTote.persist();

        assertNotNull(loadedTote);
        assertNotNull(loadedTote.id);
        assertEquals(LoadedTote.Status.IN_USE, loadedTote.status);
        assertEquals(tote.id, loadedTote.tote.id);
    }

    @Test
    @TestTransaction
    void useAnEmptyTote_shouldThrow_whenToteIsAlreadyInUse() {
        Store store = createStore("LT-S1B");
        Tote tote = createTote(store, "TOTE-LT-001B");

        LoadedTote first = LoadedTote.useAnEmptyTote(tote);
        first.storageType = TemperatureZone.AMBIENT;
        first.persist();
        em.flush();

        // Second attempt on the same tote while first is still IN_USE
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LoadedTote.useAnEmptyTote(tote));
        assertTrue(ex.getMessage().contains(tote.code));
    }

    @Test
    @TestTransaction
    void useAnEmptyTote_shouldAllowNewSession_whenPreviousIsDelivered() {
        Store store = createStore("LT-S2");
        Tote tote = createTote(store, "TOTE-LT-002");

        // First session
        LoadedTote first = LoadedTote.useAnEmptyTote(tote);
        first.storageType = TemperatureZone.AMBIENT;
        assertNotNull(first);

        // Mark first session as delivered
        first.status = LoadedTote.Status.DELIVERED;
        first.persist();
        em.flush();
        em.clear();

        // Second session should be allowed
        Tote refetchedTote = Tote.findById(tote.id);
        LoadedTote second = LoadedTote.useAnEmptyTote(refetchedTote);
        second.storageType = TemperatureZone.AMBIENT;
        second.persist();

        assertNotNull(second);
        assertNotEquals(first.id, second.id);
        assertEquals(LoadedTote.Status.IN_USE, second.status);
    }

    @Test
    @TestTransaction
    void useAnEmptyTote_shouldAllowMultipleSessions_whenPreviousAreDelivered() {
        Store store = createStore("LT-S3");
        Tote tote = createTote(store, "TOTE-LT-003");

        // Create and deliver 3 sessions
        for (int i = 0; i < 3; i++) {
            LoadedTote lt = LoadedTote.useAnEmptyTote(tote);
            lt.storageType = TemperatureZone.AMBIENT;
            lt.status = LoadedTote.Status.DELIVERED;
            lt.persist();
            em.flush();
            em.clear();
            tote = Tote.findById(tote.id);
        }

        // 4th session should still work
        LoadedTote fourth = LoadedTote.useAnEmptyTote(tote);
        fourth.storageType = TemperatureZone.AMBIENT;
        fourth.persist();
        assertNotNull(fourth);
        assertEquals(LoadedTote.Status.IN_USE, fourth.status);
    }

    // --------------------------------------------------
    // addItem Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void addItem_shouldSetBidirectionalRelationship() {
        Store store = createStore("LT-S4");
        Tote tote = createTote(store, "TOTE-LT-004");
        LoadedTote loadedTote = LoadedTote.useAnEmptyTote(tote);
        loadedTote.storageType = TemperatureZone.AMBIENT;

        PickedItem item = new PickedItem();
        item.quantity = new BigDecimal("2.000");
        item.status = PickedItem.PickStatus.PENDING;

        loadedTote.addItem(item);

        assertTrue(loadedTote.items.contains(item));
        assertEquals(loadedTote, item.loadedTote);
    }

    // --------------------------------------------------
    // getChecksum Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenStatusChanges() {
        Store store = createStore("LT-S5");
        Tote tote = createTote(store, "TOTE-LT-005");
        LoadedTote loadedTote = LoadedTote.useAnEmptyTote(tote);
        loadedTote.storageType = TemperatureZone.AMBIENT;

        int checksum1 = loadedTote.getChecksum();

        loadedTote.status = LoadedTote.Status.DELIVERED;
        int checksum2 = loadedTote.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenStorageTypeChanges() {
        Store store = createStore("LT-S6");
        Tote tote = createTote(store, "TOTE-LT-006");
        LoadedTote loadedTote = LoadedTote.useAnEmptyTote(tote);
        loadedTote.storageType = TemperatureZone.AMBIENT;

        int checksum1 = loadedTote.getChecksum();

        loadedTote.storageType = TemperatureZone.CHILLED;
        int checksum2 = loadedTote.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    // --------------------------------------------------
    // Status Enum Tests
    // --------------------------------------------------

    @Test
    void status_shouldHaveExpectedValues() {
        assertEquals(2, LoadedTote.Status.values().length);
        assertNotNull(LoadedTote.Status.valueOf("IN_USE"));
        assertNotNull(LoadedTote.Status.valueOf("DELIVERED"));
    }

    @Test
    @TestTransaction
    void defaultStatus_shouldBeInUse() {
        Store store = createStore("LT-S7");
        Tote tote = createTote(store, "TOTE-LT-007");

        LoadedTote loadedTote = LoadedTote.useAnEmptyTote(tote);

        assertEquals(LoadedTote.Status.IN_USE, loadedTote.status);
    }
}
