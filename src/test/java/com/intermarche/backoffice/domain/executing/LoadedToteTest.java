package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LoadedTote} business logic.
 * Pure unit tests — no database, no Quarkus.
 */
class LoadedToteTest {

    // --------------------------------------------------
    // Status enum
    // --------------------------------------------------

    @Test
    void status_hasTwoValues() {
        assertEquals(2, LoadedTote.Status.values().length);
    }

    @Test
    void status_valueOf() {
        assertEquals(LoadedTote.Status.IN_USE, LoadedTote.Status.valueOf("IN_USE"));
        assertEquals(LoadedTote.Status.DELIVERED, LoadedTote.Status.valueOf("DELIVERED"));
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultConstructor_statusIsInUse() {
        assertEquals(LoadedTote.Status.IN_USE, new LoadedTote().status);
    }

    @Test
    void defaultConstructor_itemsInitializedEmpty() {
        LoadedTote tote = new LoadedTote();
        assertNotNull(tote.items);
        assertTrue(tote.items.isEmpty());
    }

    // --------------------------------------------------
    // useAnEmptyTote — guard clause
    // --------------------------------------------------

    @Test
    void useAnEmptyTote_nullTote_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LoadedTote.useAnEmptyTote(null));
        assertTrue(ex.getMessage().contains("Physical Tote cannot be null"));
    }

    // --------------------------------------------------
    // addItem — bidirectional relationship
    // --------------------------------------------------

    @Test
    void addItem_nullItem_throwsIllegalArgument() {
        LoadedTote loadedTote = new LoadedTote();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loadedTote.addItem(null));
        assertTrue(ex.getMessage().contains("PickedItem cannot be null"));
    }

    @Test
    void addItem_addsToItems() {
        LoadedTote loadedTote = new LoadedTote();
        PickedItem item = new PickedItem();

        loadedTote.addItem(item);

        assertEquals(1, loadedTote.items.size());
        assertTrue(loadedTote.items.contains(item));
    }

    @Test
    void addItem_setsBackReference() {
        LoadedTote loadedTote = new LoadedTote();
        PickedItem item = new PickedItem();

        loadedTote.addItem(item);

        assertSame(loadedTote, item.loadedTote);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_nullToteAndBuffer_noException() {
        LoadedTote loadedTote = new LoadedTote();
        loadedTote.tote = null;
        loadedTote.buffer = null;
        assertDoesNotThrow(loadedTote::getChecksum);
    }

    @Test
    void getChecksum_changesWithToteId() {
        LoadedTote loadedTote = new LoadedTote();
        Tote tote = new Tote();
        tote.id = 1L;
        loadedTote.tote = tote;
        int checksum1 = loadedTote.getChecksum();

        tote.id = 2L;
        assertNotEquals(checksum1, loadedTote.getChecksum());
    }

    @Test
    void getChecksum_changesWithBufferId() {
        LoadedTote loadedTote = new LoadedTote();
        Buffer buffer = new Buffer();
        buffer.id = 3L;
        loadedTote.buffer = buffer;
        int checksum1 = loadedTote.getChecksum();

        buffer.id = 4L;
        assertNotEquals(checksum1, loadedTote.getChecksum());
    }

    @Test
    void getChecksum_changesWithStatus() {
        LoadedTote loadedTote = new LoadedTote();
        int checksum1 = loadedTote.getChecksum();

        loadedTote.status = LoadedTote.Status.DELIVERED;
        assertNotEquals(checksum1, loadedTote.getChecksum());
    }

    @Test
    void getChecksum_changesWithStorageType() {
        LoadedTote loadedTote = new LoadedTote();
        loadedTote.storageType = TemperatureZone.AMBIENT;
        int checksum1 = loadedTote.getChecksum();

        loadedTote.storageType = TemperatureZone.FROZEN;
        assertNotEquals(checksum1, loadedTote.getChecksum());
    }
}
