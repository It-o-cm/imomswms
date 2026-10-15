package com.intermarche.backoffice.domain;

import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Event}.
 * Pure unit tests — no database, no Quarkus.
 */
class EventTest {

    // --------------------------------------------------
    // UrgencyLevel enum
    // --------------------------------------------------

    @Test
    void urgencyLevel_hasFourValues() {
        assertEquals(4, Event.UrgencyLevel.values().length);
    }

    @Test
    void urgencyLevel_valueOf() {
        assertEquals(Event.UrgencyLevel.LOW, Event.UrgencyLevel.valueOf("LOW"));
        assertEquals(Event.UrgencyLevel.MEDIUM, Event.UrgencyLevel.valueOf("MEDIUM"));
        assertEquals(Event.UrgencyLevel.HIGH, Event.UrgencyLevel.valueOf("HIGH"));
        assertEquals(Event.UrgencyLevel.CRITICAL, Event.UrgencyLevel.valueOf("CRITICAL"));
    }

    // --------------------------------------------------
    // Constructors
    // --------------------------------------------------

    @Test
    void defaultConstructor_fieldsNull() {
        Event event = new Event();
        assertNull(event.label);
        assertNull(event.description);
        assertNull(event.urgencyLevel);
        assertNull(event.store);
        assertNull(event.targetEntityType);
        assertNull(event.targetEntityId);
        assertNull(event.targetEntityCode);
    }

    @Test
    void convenienceConstructor_setsAllFields() {
        Store store = new Store();
        Event event = new Event("Order Delay", "Late by 10 min", Event.UrgencyLevel.HIGH,
                store, Order.class, 42L, "ORD-1050");

        assertEquals("Order Delay", event.label);
        assertEquals("Late by 10 min", event.description);
        assertEquals(Event.UrgencyLevel.HIGH, event.urgencyLevel);
        assertSame(store, event.store);
        assertEquals(42L, event.targetEntityId);
        assertEquals("ORD-1050", event.targetEntityCode);
    }

    @Test
    void convenienceConstructor_usesClassNameForTargetType() {
        Event event = new Event("label", "desc", Event.UrgencyLevel.LOW,
                new Store(), Order.class, 1L, "CODE");

        assertEquals("com.intermarche.backoffice.domain.order.Order", event.targetEntityType);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_nullStore_noException() {
        Event event = new Event("label", "desc", Event.UrgencyLevel.LOW, null, Order.class, 1L, "CODE");
        assertDoesNotThrow(event::getChecksum);
    }

    @Test
    void getChecksum_changesWithLabel() {
        Event event = createEvent();
        int checksum1 = event.getChecksum();

        event.label = "Other label";
        assertNotEquals(checksum1, event.getChecksum());
    }

    @Test
    void getChecksum_changesWithUrgencyLevel() {
        Event event = createEvent();
        int checksum1 = event.getChecksum();

        event.urgencyLevel = Event.UrgencyLevel.CRITICAL;
        assertNotEquals(checksum1, event.getChecksum());
    }

    @Test
    void getChecksum_changesWithStoreId() {
        Event event = createEvent();
        int checksum1 = event.getChecksum();

        event.store.id = 99L;
        assertNotEquals(checksum1, event.getChecksum());
    }

    @Test
    void getChecksum_changesWithTargetEntityCode() {
        Event event = createEvent();
        int checksum1 = event.getChecksum();

        event.targetEntityCode = "ORD-9999";
        assertNotEquals(checksum1, event.getChecksum());
    }

    @Test
    void getChecksum_changesWithTargetEntityId() {
        Event event = createEvent();
        int checksum1 = event.getChecksum();

        event.targetEntityId = 777L;
        assertNotEquals(checksum1, event.getChecksum());
    }

    // --------------------------------------------------
    // findByTarget / findCriticalByStore (Panache queries)
    // --------------------------------------------------

    @Test
    void findByTarget_queriesByTypeAndIdOrderedByCreationDateDesc() {
        Event event = createEvent();
        @SuppressWarnings("unchecked")
        PanacheQuery<Event> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(event));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(
                    eq("targetEntityType = ?1 and targetEntityId = ?2 ORDER BY createdAt DESC"), any(Object[].class)))
                    .thenReturn(query);

            List<Event> result = Event.findByTarget(Order.class, 42L);

            assertEquals(List.of(event), result);
            panacheMock.verify(() -> PanacheEntityBase.find(
                    eq("targetEntityType = ?1 and targetEntityId = ?2 ORDER BY createdAt DESC"),
                    aryEq(new Object[]{Order.class.getName(), 42L})));
        }
    }

    @Test
    void findCriticalByStore_listsCriticalEventsForStore() {
        Store store = new Store();
        store.id = 1L;
        Event event = createEvent();

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("store = ?1 and urgencyLevel = ?2"), any(Object[].class)))
                    .thenReturn(List.of(event));

            List<Event> result = Event.findCriticalByStore(store);

            assertEquals(List.of(event), result);
            panacheMock.verify(() -> PanacheEntityBase.list(
                    eq("store = ?1 and urgencyLevel = ?2"),
                    aryEq(new Object[]{store, Event.UrgencyLevel.CRITICAL})));
        }
    }

    // --------------------------------------------------
    // getTargetEntity / getTargetEntityAs
    // --------------------------------------------------

    @Test
    void getTargetEntity_resolvesClassAndFindsById() {
        Event event = createEvent();
        Order order = new Order();
        order.id = 42L;
        EntityManager em = mock(EntityManager.class);
        when(em.find(Order.class, 42L)).thenReturn(order);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);

            Object target = event.getTargetEntity();

            assertSame(order, target);
        }
    }

    @Test
    void getTargetEntity_entityNotFound_returnsNull() {
        Event event = createEvent();
        EntityManager em = mock(EntityManager.class);
        when(em.find(Order.class, 42L)).thenReturn(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);

            assertNull(event.getTargetEntity());
        }
    }

    @Test
    void getTargetEntity_unknownClass_throwsIllegalState() {
        Event event = createEvent();
        event.id = 7L;
        event.targetEntityType = "com.doesnotexist.UnknownEntity";

        IllegalStateException ex = assertThrows(IllegalStateException.class, event::getTargetEntity);
        assertTrue(ex.getMessage().contains("com.doesnotexist.UnknownEntity"));
        assertTrue(ex.getMessage().contains("7"));
        assertInstanceOf(ClassNotFoundException.class, ex.getCause());
    }

    @Test
    void getTargetEntityAs_nullEntity_returnsNull() {
        Event event = createEvent();
        EntityManager em = mock(EntityManager.class);
        when(em.find(Order.class, 42L)).thenReturn(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);

            assertNull(event.getTargetEntityAs(Order.class));
        }
    }

    @Test
    void getTargetEntityAs_matchingType_returnsCastEntity() {
        Event event = createEvent();
        Order order = new Order();
        order.id = 42L;
        EntityManager em = mock(EntityManager.class);
        when(em.find(Order.class, 42L)).thenReturn(order);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);

            Order result = event.getTargetEntityAs(Order.class);

            assertSame(order, result);
        }
    }

    @Test
    void getTargetEntityAs_typeMismatch_throwsIllegalState() {
        Event event = createEvent();
        event.id = 9L;
        Store store = new Store();
        store.id = 42L;
        EntityManager em = mock(EntityManager.class);
        doReturn(store).when(em).find(Order.class, 42L);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(PanacheEntityBase::getEntityManager).thenReturn(em);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> event.getTargetEntityAs(Order.class));
            assertTrue(ex.getMessage().contains(Order.class.getName()));
            assertTrue(ex.getMessage().contains(Store.class.getName()));
        }
    }

    // --------------------------------------------------
    // publishInCurrentTx
    // --------------------------------------------------

    @Test
    void publishInCurrentTx_buildsAndPersistsEvent() {
        Store store = new Store();
        store.id = 1L;
        Order target = new Order();
        target.id = 42L;

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            Event event = Event.publishInCurrentTx("Order Delay", "Late by 10 min", Event.UrgencyLevel.HIGH,
                    store, target, "ORD-1050");

            assertEquals("Order Delay", event.label);
            assertEquals("Late by 10 min", event.description);
            assertEquals(Event.UrgencyLevel.HIGH, event.urgencyLevel);
            assertSame(store, event.store);
            assertEquals(Order.class.getName(), event.targetEntityType);
            assertEquals(42L, event.targetEntityId);
            assertEquals("ORD-1050", event.targetEntityCode);
            verify(session).persist(event);
        }
    }

    @Test
    void publishInCurrentTx_nonBaseEntityTarget_throwsIllegalArgument() {
        Store store = new Store();
        store.id = 1L;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Event.publishInCurrentTx("label", "desc", Event.UrgencyLevel.LOW,
                        store, new Object(), "CODE"));
        assertTrue(ex.getMessage().contains("BaseEntity"));
    }

    // --------------------------------------------------
    // publishInNewTx
    // --------------------------------------------------

    @Test
    void publishInNewTx_nullStoreId_throwsIllegalArgument() {
        Store store = new Store();
        store.id = null;
        Order target = new Order();
        target.id = 42L;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Event.publishInNewTx("label", "desc", Event.UrgencyLevel.LOW, store, target, "CODE"));
        assertTrue(ex.getMessage().contains("Store"));
    }

    @Test
    void publishInNewTx_nullTargetId_throwsIllegalArgument() {
        Store store = new Store();
        store.id = 1L;
        Order target = new Order();
        target.id = null;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Event.publishInNewTx("label", "desc", Event.UrgencyLevel.LOW, store, target, "CODE"));
        assertTrue(ex.getMessage().contains("Target entity"));
    }

    @Test
    void publishInNewTx_success_persistsEventInIndependentTransaction() throws Exception {
        Store store = new Store();
        store.id = 1L;
        Store managedStore = new Store();
        managedStore.id = 1L;
        Order target = new Order();
        target.id = 42L;

        TransactionRunnerOptions options = mock(TransactionRunnerOptions.class);
        when(options.call(any(Callable.class))).thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<QuarkusTransaction> txMock = mockStatic(QuarkusTransaction.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(eq(1L))).thenReturn(managedStore);
            txMock.when(QuarkusTransaction::requiringNew).thenReturn(options);

            Event event = Event.publishInNewTx("Order Delay", "Late", Event.UrgencyLevel.CRITICAL,
                    store, target, "ORD-1050");

            assertNotNull(event);
            assertEquals("Order Delay", event.label);
            assertEquals("Late", event.description);
            assertEquals(Event.UrgencyLevel.CRITICAL, event.urgencyLevel);
            assertSame(managedStore, event.store);
            assertEquals(Order.class.getName(), event.targetEntityType);
            assertEquals(42L, event.targetEntityId);
            assertEquals("ORD-1050", event.targetEntityCode);
            verify(session).persist(event);
            verify(session).flush();
        }
    }

    @Test
    void publishInNewTx_storeNotFoundInNewTransaction_returnsNull() throws Exception {
        Store store = new Store();
        store.id = 1L;
        Order target = new Order();
        target.id = 42L;

        TransactionRunnerOptions options = mock(TransactionRunnerOptions.class);
        when(options.call(any(Callable.class))).thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<QuarkusTransaction> txMock = mockStatic(QuarkusTransaction.class)) {
            PanacheTestSupport.mockArcSession(arcMock);
            // The store cannot be refetched inside the new transaction
            panacheMock.when(() -> PanacheEntityBase.findById(eq(1L))).thenReturn(null);
            txMock.when(QuarkusTransaction::requiringNew).thenReturn(options);

            Event event = Event.publishInNewTx("label", "desc", Event.UrgencyLevel.LOW, store, target, "CODE");

            assertNull(event);
        }
    }

    @Test
    void publishInNewTx_transactionFailure_returnsNull() {
        Store store = new Store();
        store.id = 1L;
        Order target = new Order();
        target.id = 42L;

        TransactionRunnerOptions options = mock(TransactionRunnerOptions.class);
        when(options.call(any(Callable.class))).thenThrow(new RuntimeException("Transaction infrastructure failure"));

        try (MockedStatic<QuarkusTransaction> txMock = mockStatic(QuarkusTransaction.class)) {
            txMock.when(QuarkusTransaction::requiringNew).thenReturn(options);

            Event event = Event.publishInNewTx("label", "desc", Event.UrgencyLevel.LOW, store, target, "CODE");

            assertNull(event);
        }
    }

    private Event createEvent() {
        Store store = new Store();
        store.id = 1L;
        return new Event("Order Delay", "Late", Event.UrgencyLevel.MEDIUM, store, Order.class, 42L, "ORD-1050");
    }
}
