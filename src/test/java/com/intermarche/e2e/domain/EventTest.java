package com.intermarche.e2e.domain;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link Event}.
 * Covers event creation, querying by target, critical event filtering,
 * and dynamic entity resolution.
 */
@QuarkusTest
public class EventTest {

    private Store createStore(String prefix) {
        Store store = new Store();
        store.code = prefix + "-S";
        store.name = "Store " + prefix;
        store.address = new Adresse();
        store.address.streetLine1 = "1 rue de Test";
        store.address.city = "Lyon";
        store.address.postalCode = "69001";
        store.address.country = "France";
        store.persist();
        return store;
    }

    private Product createProduct(String prefix, String ean) {
        Product product = new Product();
        product.ean = ean;
        product.name = "Product " + prefix;
        product.productType = ProductType.UNIT;
        product.temperatureZone = TemperatureZone.AMBIENT;
        product.persist();
        return product;
    }

    // --------------------------------------------------
    // publishInCurrentTx Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void publishInCurrentTx_shouldPersistEvent() {
        Store store = createStore("EV01");
        Product product = createProduct("EV01", "EV01-EAN1");

        Event event = Event.publishInCurrentTx(
                "Test Event",
                "A test description",
                Event.UrgencyLevel.MEDIUM,
                store,
                product,
                "PROD-001"
        );

        assertNotNull(event);
        assertNotNull(event.id);
        assertEquals("Test Event", event.label);
        assertEquals("A test description", event.description);
        assertEquals(Event.UrgencyLevel.MEDIUM, event.urgencyLevel);
        assertEquals(store.id, event.store.id);
        assertEquals(Product.class.getName(), event.targetEntityType);
        assertEquals(product.id, event.targetEntityId);
        assertEquals("PROD-001", event.targetEntityCode);
    }

    @Test
    @TestTransaction
    void publishInCurrentTx_shouldSetTargetEntityTypeFromClass() {
        Store store = createStore("EV02");
        Product product = createProduct("EV02", "EV02-EAN1");

        Event event = Event.publishInCurrentTx(
                "Type Test", "desc", Event.UrgencyLevel.LOW,
                store, product, "CODE-1"
        );

        assertEquals("com.intermarche.backoffice.domain.order.Product", event.targetEntityType);
    }

    // --------------------------------------------------
    // findByTarget Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findByTarget_shouldReturnEventsForTarget() {
        Store store = createStore("EV03");
        Product product = createProduct("EV03", "EV03-EAN1");

        Event.publishInCurrentTx("Event 1", "d1", Event.UrgencyLevel.LOW, store, product, "C1");
        Event.publishInCurrentTx("Event 2", "d2", Event.UrgencyLevel.HIGH, store, product, "C2");

        List<Event> events = Event.findByTarget(Product.class, product.id);

        assertEquals(2, events.size());
    }

    @Test
    @TestTransaction
    void findByTarget_shouldReturnEmptyList_whenNoEvents() {
        Store store = createStore("EV04");
        Product product = createProduct("EV04", "EV04-EAN1");

        List<Event> events = Event.findByTarget(Product.class, product.id);

        assertTrue(events.isEmpty());
    }

    @Test
    @TestTransaction
    void findByTarget_shouldNotReturnEventsForOtherTargets() {
        Store store = createStore("EV05");
        Product product1 = createProduct("EV05", "EV05-EAN1");
        Product product2 = createProduct("EV05", "EV05-EAN2");

        Event.publishInCurrentTx("Event for P1", "d", Event.UrgencyLevel.LOW, store, product1, "C1");

        List<Event> eventsForP2 = Event.findByTarget(Product.class, product2.id);
        assertTrue(eventsForP2.isEmpty());

        List<Event> eventsForP1 = Event.findByTarget(Product.class, product1.id);
        assertEquals(1, eventsForP1.size());
    }

    // --------------------------------------------------
    // findCriticalByStore Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void findCriticalByStore_shouldReturnOnlyCriticalEvents() {
        Store store = createStore("EV06");
        Product product = createProduct("EV06", "EV06-EAN1");

        Event.publishInCurrentTx("Low", "d", Event.UrgencyLevel.LOW, store, product, "C1");
        Event.publishInCurrentTx("Critical 1", "d", Event.UrgencyLevel.CRITICAL, store, product, "C2");
        Event.publishInCurrentTx("Medium", "d", Event.UrgencyLevel.MEDIUM, store, product, "C3");
        Event.publishInCurrentTx("Critical 2", "d", Event.UrgencyLevel.CRITICAL, store, product, "C4");

        List<Event> critical = Event.findCriticalByStore(store);

        assertEquals(2, critical.size());
        assertTrue(critical.stream().allMatch(e -> e.urgencyLevel == Event.UrgencyLevel.CRITICAL));
    }

    @Test
    @TestTransaction
    void findCriticalByStore_shouldNotReturnEventsFromOtherStores() {
        Store store1 = createStore("EV07A");
        Store store2 = createStore("EV07B");
        Product product = createProduct("EV07", "EV07-EAN1");

        Event.publishInCurrentTx("Critical S1", "d", Event.UrgencyLevel.CRITICAL, store1, product, "C1");
        Event.publishInCurrentTx("Critical S2", "d", Event.UrgencyLevel.CRITICAL, store2, product, "C2");

        List<Event> criticalS1 = Event.findCriticalByStore(store1);
        assertEquals(1, criticalS1.size());
        assertEquals("Critical S1", criticalS1.get(0).label);
    }

    // --------------------------------------------------
    // getTargetEntity Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getTargetEntity_shouldResolveEntity() {
        Store store = createStore("EV08");
        Product product = createProduct("EV08", "EV08-EAN1");

        Event event = Event.publishInCurrentTx(
                "Test", "d", Event.UrgencyLevel.LOW, store, product, "C1"
        );

        Object resolved = event.getTargetEntity();

        assertNotNull(resolved);
        assertInstanceOf(Product.class, resolved);
        assertEquals(product.id, ((Product) resolved).id);
    }

    @Test
    @TestTransaction
    void getTargetEntityAs_shouldResolveAndCast() {
        Store store = createStore("EV09");
        Product product = createProduct("EV09", "EV09-EAN1");

        Event event = Event.publishInCurrentTx(
                "Test", "d", Event.UrgencyLevel.LOW, store, product, "C1"
        );

        Product resolved = event.getTargetEntityAs(Product.class);

        assertNotNull(resolved);
        assertEquals(product.ean, resolved.ean);
    }

    @Test
    @TestTransaction
    void getTargetEntityAs_shouldThrow_whenTypeMismatch() {
        Store store = createStore("EV10");
        Product product = createProduct("EV10", "EV10-EAN1");

        Event event = Event.publishInCurrentTx(
                "Test", "d", Event.UrgencyLevel.LOW, store, product, "C1"
        );

        assertThrows(IllegalStateException.class,
                () -> event.getTargetEntityAs(Store.class));
    }

    // --------------------------------------------------
    // Constructor Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void constructor_shouldSetAllFields() {
        Store store = createStore("EV11");

        Event event = new Event(
                "My Label",
                "My Description",
                Event.UrgencyLevel.HIGH,
                store,
                Product.class,
                42L,
                "CODE-42"
        );

        assertEquals("My Label", event.label);
        assertEquals("My Description", event.description);
        assertEquals(Event.UrgencyLevel.HIGH, event.urgencyLevel);
        assertEquals(store, event.store);
        assertEquals(Product.class.getName(), event.targetEntityType);
        assertEquals(42L, event.targetEntityId);
        assertEquals("CODE-42", event.targetEntityCode);
    }

    // --------------------------------------------------
    // Enum Tests
    // --------------------------------------------------

    @Test
    void urgencyLevel_shouldHaveExpectedValues() {
        assertEquals(4, Event.UrgencyLevel.values().length);
        assertNotNull(Event.UrgencyLevel.valueOf("LOW"));
        assertNotNull(Event.UrgencyLevel.valueOf("MEDIUM"));
        assertNotNull(Event.UrgencyLevel.valueOf("HIGH"));
        assertNotNull(Event.UrgencyLevel.valueOf("CRITICAL"));
    }

    // --------------------------------------------------
    // Checksum Tests
    // --------------------------------------------------

    @Test
    @TestTransaction
    void getChecksum_shouldChangeWhenLabelChanges() {
        Store store = createStore("EV12");
        Product product = createProduct("EV12", "EV12-EAN1");

        Event event = Event.publishInCurrentTx(
                "Label A", "d", Event.UrgencyLevel.LOW, store, product, "C1"
        );
        int checksum1 = event.getChecksum();

        event.label = "Label B";
        int checksum2 = event.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }
}
