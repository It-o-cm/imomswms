package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.engine.handling.BaseOrderHandlerBuilderFactory;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StoreActivityEngine}.
 * Covers the null/empty guards of the builder cache, the factory registry
 * initialization, the cache eviction helpers, the configuration parsing
 * ({@code buildBuilderList}), the {@code process} orchestration and the
 * late handling order detection.
 */
class StoreActivityEngineTest {

    @Test
    void getOrderHandlerBuildersForStore_nullStore_returnsEmptyList() {
        StoreActivityEngine engine = new StoreActivityEngine();

        List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> builders = engine.getOrderHandlerBuildersForStore(null);

        assertNotNull(builders);
        assertTrue(builders.isEmpty());
    }

    @Test
    void getOrderHandlerBuildersForStore_storeWithoutId_returnsEmptyList() {
        StoreActivityEngine engine = new StoreActivityEngine();
        Store store = new Store();
        store.id = null;

        List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> builders = engine.getOrderHandlerBuildersForStore(store);

        assertNotNull(builders);
        assertTrue(builders.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void initializeFactories_registersFactoriesByName() {
        StoreActivityEngine engine = new StoreActivityEngine();
        Instance<BaseOrderHandlerBuilderFactory> factories = mock(Instance.class);

        BaseOrderHandlerBuilderFactory packing = mock(BaseOrderHandlerBuilderFactory.class);
        BaseOrderHandlerBuilderFactory grouping = mock(BaseOrderHandlerBuilderFactory.class);
        when(packing.getName()).thenReturn("Packing");
        when(grouping.getName()).thenReturn("Grouping");
        when(factories.iterator()).thenReturn(List.of(packing, grouping).iterator());

        engine.factories = factories;
        engine.initializeFactories();

        assertNotNull(engine.factoryMap);
        assertEquals(2, engine.factoryMap.size());
        assertSame(packing, engine.factoryMap.get("Packing"));
        assertSame(grouping, engine.factoryMap.get("Grouping"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void initializeFactories_noFactories_registersEmptyMap() {
        StoreActivityEngine engine = new StoreActivityEngine();
        Instance<BaseOrderHandlerBuilderFactory> factories = mock(Instance.class);
        when(factories.iterator()).thenReturn(List.<BaseOrderHandlerBuilderFactory>of().iterator());

        engine.factories = factories;
        engine.initializeFactories();

        assertNotNull(engine.factoryMap);
        assertTrue(engine.factoryMap.isEmpty());
    }

    @Test
    void evictCacheForStore_doesNotThrow() {
        StoreActivityEngine engine = new StoreActivityEngine();

        // Evicting an unknown store id is a no-op
        assertDoesNotThrow(() -> engine.evictCacheForStore(42L));
        assertDoesNotThrow(() -> engine.evictCacheForStore(999L));
    }

    @Test
    void evictAll_doesNotThrow() {
        StoreActivityEngine engine = new StoreActivityEngine();

        assertDoesNotThrow(engine::evictAll);
    }

    @Test
    void evictAll_thenNullStoreStillReturnsEmpty() {
        StoreActivityEngine engine = new StoreActivityEngine();

        engine.evictAll();

        assertTrue(engine.getOrderHandlerBuildersForStore(null).isEmpty());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static Store storeWithId(Long id, String code) {
        Store store = new Store();
        store.id = id;
        store.code = code;
        return store;
    }

    private static Configuration configWithSpec(String specification) {
        Configuration configuration = new Configuration();
        configuration.specification = specification;
        return configuration;
    }

    private static StoreActivityEngine newEngine(OrderScheduler scheduler) {
        StoreActivityEngine engine = new StoreActivityEngine();
        engine.objectMapper = new ObjectMapper();
        engine.orderScheduler = scheduler;
        engine.factoryMap = new ConcurrentHashMap<>();
        return engine;
    }
    private static BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder mockBuilder() {
        return mock(BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder.class);
    }

    private static BaseHandlingOrder newLateOrder(Long id, String code) {
        BaseHandlingOrder order = mock(BaseHandlingOrder.class);
        order.id = id;
        order.code = code;
        order.requestedEnd = LocalDateTime.now().minusHours(2);
        order.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;
        return order;
    }

    // --------------------------------------------------
    // buildBuilderList (via getOrderHandlerBuildersForStore)
    // --------------------------------------------------

    @Test
    void getOrderHandlerBuildersForStore_noConfiguration_throwsIllegalState() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(null);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> engine.getOrderHandlerBuildersForStore(store));
            assertTrue(ex.getMessage().contains("No Order Handler Builder Configuration found for store S1"));
        }
    }

    @Test
    void getOrderHandlerBuildersForStore_validSpec_buildsBuildersInOrder() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder packingBuilder = mockBuilder();
        BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder groupingBuilder = mockBuilder();
        BaseOrderHandlerBuilderFactory packingFactory = mock(BaseOrderHandlerBuilderFactory.class);
        BaseOrderHandlerBuilderFactory groupingFactory = mock(BaseOrderHandlerBuilderFactory.class);
        when(packingFactory.build(any(JsonNode.class))).thenReturn(packingBuilder);
        when(groupingFactory.build(any(JsonNode.class))).thenReturn(groupingBuilder);
        engine.factoryMap.put("Packing", packingFactory);
        engine.factoryMap.put("Grouping", groupingFactory);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[{\"type\":\"Packing\",\"zone\":\"A\"},{\"type\":\"Grouping\"}]"));

            List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> builders = engine.getOrderHandlerBuildersForStore(store);

            assertEquals(2, builders.size());
            assertSame(packingBuilder, builders.get(0));
            assertSame(groupingBuilder, builders.get(1));
            verify(packingFactory).build(argThat(node -> node != null
                    && "Packing".equals(node.get("type").asText())
                    && "A".equals(node.get("zone").asText())));
            verify(groupingFactory).build(argThat(node -> node != null
                    && "Grouping".equals(node.get("type").asText())));
        }
    }

    @Test
    void getOrderHandlerBuildersForStore_elementMissingType_throwsIllegalArgument() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[{\"zone\":\"A\"}]"));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.getOrderHandlerBuildersForStore(store));
            assertTrue(ex.getMessage().contains("missing 'type' field"));
        }
    }

    @Test
    void getOrderHandlerBuildersForStore_unknownFactory_throwsIllegalArgument() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[{\"type\":\"Unknown\"}]"));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.getOrderHandlerBuildersForStore(store));
            assertTrue(ex.getMessage().contains("Unknown"));
            assertTrue(ex.getMessage().contains("not found in registry"));
        }
    }

    @Test
    void getOrderHandlerBuildersForStore_factoryBuildFails_wrapsInRuntimeException() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        BaseOrderHandlerBuilderFactory factory = mock(BaseOrderHandlerBuilderFactory.class);
        when(factory.build(any(JsonNode.class))).thenThrow(new IllegalStateException("bad spec"));
        engine.factoryMap.put("Packing", factory);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[{\"type\":\"Packing\"}]"));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> engine.getOrderHandlerBuildersForStore(store));
            assertTrue(ex.getMessage().contains("Failed to build order handler builder Packing"));
            assertTrue(ex.getMessage().contains("bad spec"));
            assertInstanceOf(IllegalStateException.class, ex.getCause());
        }
    }

    @Test
    void getOrderHandlerBuildersForStore_specNotAnArray_throwsIllegalArgument() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("{\"type\":\"Packing\"}"));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.getOrderHandlerBuildersForStore(store));
            assertTrue(ex.getMessage().contains("is not a valid JSON array"));
        }
    }

    @Test
    void getOrderHandlerBuildersForStore_invalidJson_wrapsInRuntimeException() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("not-json"));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> engine.getOrderHandlerBuildersForStore(store));
            assertTrue(ex.getMessage().contains("Error parsing configuration for store S1"));
        }
    }

    // --------------------------------------------------
    // Builder cache
    // --------------------------------------------------

    @Test
    void getOrderHandlerBuildersForStore_storeNotFoundInDb_returnsEmptyList() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(null);

            List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> builders = engine.getOrderHandlerBuildersForStore(store);

            assertNotNull(builders);
            assertTrue(builders.isEmpty());
        }
    }

    @Test
    void getOrderHandlerBuildersForStore_cachesResultAcrossCalls() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder builder = mockBuilder();
        BaseOrderHandlerBuilderFactory factory = mock(BaseOrderHandlerBuilderFactory.class);
        when(factory.build(any(JsonNode.class))).thenReturn(builder);
        engine.factoryMap.put("Packing", factory);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[{\"type\":\"Packing\"}]"));

            List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> first = engine.getOrderHandlerBuildersForStore(store);
            List<BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder> second = engine.getOrderHandlerBuildersForStore(store);

            assertSame(first, second);
            assertEquals(1, second.size());
            panacheMock.verify(() -> PanacheEntityBase.findById(any()), times(1));
            configMock.verify(() -> Configuration.findHighestPriorityForStore(any(), anyString()), times(1));
            verify(factory, times(1)).build(any(JsonNode.class));
        }
    }

    @Test
    void getOrderHandlerBuildersForStore_failureIsNotCached_retriesOnNextCall() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(null);

            assertThrows(IllegalStateException.class, () -> engine.getOrderHandlerBuildersForStore(store));
            assertThrows(IllegalStateException.class, () -> engine.getOrderHandlerBuildersForStore(store));

            panacheMock.verify(() -> PanacheEntityBase.findById(any()), times(2));
        }
    }

    @Test
    void evictCacheForStore_forcesRebuildOnNextCall() {
        Store store = storeWithId(1L, "S1");
        StoreActivityEngine engine = newEngine(mock(OrderScheduler.class));

        BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder builder = mockBuilder();
        BaseOrderHandlerBuilderFactory factory = mock(BaseOrderHandlerBuilderFactory.class);
        when(factory.build(any(JsonNode.class))).thenReturn(builder);
        engine.factoryMap.put("Packing", factory);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[{\"type\":\"Packing\"}]"));

            engine.getOrderHandlerBuildersForStore(store);
            engine.evictCacheForStore(1L);
            engine.getOrderHandlerBuildersForStore(store);

            panacheMock.verify(() -> PanacheEntityBase.findById(any()), times(2));
            configMock.verify(() -> Configuration.findHighestPriorityForStore(any(), anyString()), times(2));
        }
    }

    // --------------------------------------------------
    // process
    // --------------------------------------------------

    @Test
    void process_nullStore_doesNothing() {
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);

        assertDoesNotThrow(() -> engine.process(null));

        verify(scheduler, never()).scheduleOpenedOrders(any());
    }

    @Test
    void process_buildersPresent_processesEachBuilderInOrderThenSchedules() throws Exception {
        Store store = storeWithId(1L, "S1");
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);

        BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder builder1 = mockBuilder();
        BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder builder2 = mockBuilder();
        BaseOrderHandlerBuilderFactory factory1 = mock(BaseOrderHandlerBuilderFactory.class);
        BaseOrderHandlerBuilderFactory factory2 = mock(BaseOrderHandlerBuilderFactory.class);
        when(factory1.build(any(JsonNode.class))).thenReturn(builder1);
        when(factory2.build(any(JsonNode.class))).thenReturn(builder2);
        engine.factoryMap.put("Packing", factory1);
        engine.factoryMap.put("Grouping", factory2);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[{\"type\":\"Packing\"},{\"type\":\"Grouping\"}]"));
            panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenAnswer(invocation -> List.of());

            engine.process(store);

            InOrder inOrder = inOrder(builder1, builder2, scheduler);
            inOrder.verify(builder1).process(store);
            inOrder.verify(builder2).process(store);
            inOrder.verify(scheduler).scheduleOpenedOrders(store);
            eventMock.verify(() -> Event.publishInCurrentTx(anyString(), anyString(), any(), any(), any(), anyString()), never());
            eventMock.verify(() -> Event.publishInNewTx(anyString(), anyString(), any(), any(), any(), anyString()), never());
        }
    }

    @Test
    void process_noBuilders_stillSchedulesAndChecksLateOrders() throws Exception {
        Store store = storeWithId(1L, "S1");
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[]"));
            panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenAnswer(invocation -> List.of());

            engine.process(store);

            verify(scheduler).scheduleOpenedOrders(store);
            panacheMock.verify(() -> PanacheEntityBase.list(contains("requestedEnd"), any(Object[].class)), times(1));
            eventMock.verify(() -> Event.publishInCurrentTx(anyString(), anyString(), any(), any(), any(), anyString()), never());
        }
    }

    @Test
    void process_builderThrows_publishesCriticalEventWithFallbackStoreAndRethrows() throws Exception {
        Store store = storeWithId(1L, "S1");
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);

        BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder builder = mockBuilder();
        BaseOrderHandlerBuilderFactory factory = mock(BaseOrderHandlerBuilderFactory.class);
        when(factory.build(any(JsonNode.class))).thenReturn(builder);
        engine.factoryMap.put("Packing", factory);
        doThrow(new RuntimeException("boom")).when(builder).process(store);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[{\"type\":\"Packing\"}]"));

            RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.process(store));
            assertEquals("boom", ex.getMessage());

            ArgumentCaptor<Object> targetCaptor = ArgumentCaptor.forClass(Object.class);
            eventMock.verify(() -> Event.publishInNewTx(
                    eq("Erreur Critique Création Handlers (Store)"),
                    argThat((String description) -> description != null && description.contains("S1") && description.contains("boom")),
                    eq(Event.UrgencyLevel.CRITICAL),
                    eq(store),
                    targetCaptor.capture(),
                    eq("STORE-1")));

            Store fallback = (Store) targetCaptor.getValue();
            assertEquals("S1", fallback.code);
            assertEquals(1L, fallback.id);

            verify(scheduler, never()).scheduleOpenedOrders(any());
        }
    }

    @Test
    void process_noConfiguration_publishesCriticalEventAndRethrows() {
        Store store = storeWithId(1L, "S1");
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(null);

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> engine.process(store));
            assertTrue(ex.getMessage().contains("No Order Handler Builder Configuration found for store S1"));

            eventMock.verify(() -> Event.publishInNewTx(
                    eq("Erreur Critique Création Handlers (Store)"),
                    anyString(),
                    eq(Event.UrgencyLevel.CRITICAL),
                    eq(store),
                    any(Store.class),
                    eq("STORE-1")));
            verify(scheduler, never()).scheduleOpenedOrders(any());
        }
    }

    // --------------------------------------------------
    // Late handling order detection (via process)
    // --------------------------------------------------

    @Test
    void process_lateOrderNotYetNotified_publishesHighUrgencyEvent() throws Exception {
        Store store = storeWithId(1L, "S1");
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);
        BaseHandlingOrder lateOrder = newLateOrder(7L, "HO-1");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[]"));
            panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenAnswer(invocation -> List.of(lateOrder));
            panacheMock.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class)))
                    .thenReturn(0L);

            engine.process(store);

            eventMock.verify(() -> Event.publishInCurrentTx(
                    eq("Retard Handling Order"),
                    argThat((String description) -> description != null && description.contains("HO-1")),
                    eq(Event.UrgencyLevel.HIGH),
                    eq(store),
                    eq(lateOrder),
                    eq("HO-1")));
            panacheMock.verify(() -> PanacheEntityBase.count(eq("targetEntityId = ?1 AND label = ?2"), any(Object[].class)), times(1));
            verify(scheduler).scheduleOpenedOrders(store);
        }
    }

    @Test
    void process_multipleLateOrders_publishesEventForEachNotNotified() throws Exception {
        Store store = storeWithId(1L, "S1");
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);
        BaseHandlingOrder order1 = newLateOrder(7L, "HO-1");
        BaseHandlingOrder order2 = newLateOrder(8L, "HO-2");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[]"));
            panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenAnswer(invocation -> List.of(order1, order2));
            panacheMock.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class)))
                    .thenReturn(0L);

            engine.process(store);

            eventMock.verify(() -> Event.publishInCurrentTx(
                    eq("Retard Handling Order"), anyString(), eq(Event.UrgencyLevel.HIGH), eq(store), eq(order1), eq("HO-1")), times(1));
            eventMock.verify(() -> Event.publishInCurrentTx(
                    eq("Retard Handling Order"), anyString(), eq(Event.UrgencyLevel.HIGH), eq(store), eq(order2), eq("HO-2")), times(1));
        }
    }

    @Test
    void process_lateOrderAlreadyNotified_skipsEvent() throws Exception {
        Store store = storeWithId(1L, "S1");
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);
        BaseHandlingOrder lateOrder = newLateOrder(7L, "HO-1");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[]"));
            panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenAnswer(invocation -> List.of(lateOrder));
            panacheMock.when(() -> PanacheEntityBase.count(anyString(), any(Object[].class)))
                    .thenReturn(1L);

            engine.process(store);

            eventMock.verify(() -> Event.publishInCurrentTx(anyString(), anyString(), any(), any(), any(), anyString()), never());
            panacheMock.verify(() -> PanacheEntityBase.count(anyString(), any(Object[].class)), times(1));
        }
    }

    @Test
    void process_lateCheckFails_swallowsExceptionAndCompletes() throws Exception {
        Store store = storeWithId(1L, "S1");
        OrderScheduler scheduler = mock(OrderScheduler.class);
        StoreActivityEngine engine = newEngine(scheduler);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(store);
            configMock.when(() -> Configuration.findHighestPriorityForStore(any(), anyString()))
                    .thenReturn(configWithSpec("[]"));
            panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenThrow(new RuntimeException("db down"));

            assertDoesNotThrow(() -> engine.process(store));

            verify(scheduler).scheduleOpenedOrders(store);
            eventMock.verify(() -> Event.publishInCurrentTx(anyString(), anyString(), any(), any(), any(), anyString()), never());
            eventMock.verify(() -> Event.publishInNewTx(anyString(), anyString(), any(), any(), any(), anyString()), never());
        }
    }
}
