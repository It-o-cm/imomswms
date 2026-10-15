package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.engine.fulfillers.BaseFulfillerFactory;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.enterprise.inject.Instance;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FulfillmentEngine}.
 * Factories, configuration lookup and Panache statics are mocked; no Quarkus, no database.
 */
class FulfillmentEngineTest {

    private FulfillmentEngine engine;
    private Instance<BaseFulfillerFactory> factories;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        engine = new FulfillmentEngine();
        factories = mock(Instance.class);
        engine.factories = factories;
        engine.objectMapper = new ObjectMapper();
        engine.factoryMap = new ConcurrentHashMap<>();
    }

    private Store newStore(Long id) {
        Store store = new Store();
        store.id = id;
        store.code = "STORE-" + id;
        return store;
    }

    private Configuration newConfiguration(String specification) {
        Configuration configuration = new Configuration();
        configuration.specification = specification;
        return configuration;
    }

    private BaseFulfillerFactory newFactory(String name, BaseFulfillerFactory.BaseFulfiller fulfiller) {
        BaseFulfillerFactory factory = mock(BaseFulfillerFactory.class);
        when(factory.getName()).thenReturn(name);
        try {
            when(factory.build(any(JsonNode.class))).thenReturn(fulfiller);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return factory;
    }

    @Test
    @SuppressWarnings("unchecked")
    void initializeFactories_registersAllFactories() {
        BaseFulfillerFactory factoryA = mock(BaseFulfillerFactory.class);
        when(factoryA.getName()).thenReturn("A");
        BaseFulfillerFactory factoryB = mock(BaseFulfillerFactory.class);
        when(factoryB.getName()).thenReturn("B");
        when(factories.iterator()).thenReturn(List.of(factoryA, factoryB).iterator());

        engine.initializeFactories();

        assertEquals(2, engine.factoryMap.size());
        assertSame(factoryA, engine.factoryMap.get("A"));
        assertSame(factoryB, engine.factoryMap.get("B"));
    }

    @Test
    void getFulfillersForStore_nullStore_returnsEmpty() {
        assertTrue(engine.getFulfillersForStore(null).isEmpty());
    }

    @Test
    void getFulfillersForStore_nullStoreId_returnsEmpty() {
        assertTrue(engine.getFulfillersForStore(new Store()).isEmpty());
    }

    @Test
    void getFulfillersForStore_storeNotFound_returnsEmpty() {
        Store store = newStore(1L);
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(null);

            assertTrue(engine.getFulfillersForStore(store).isEmpty());
        }
    }

    @Test
    void getFulfillersForStore_noConfiguration_throwsIllegalState() {
        Store store = newStore(1L);
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(null);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> engine.getFulfillersForStore(store));
            assertTrue(ex.getMessage().contains("No Fulfiller Configuration found"));
        }
    }

    @Test
    void getFulfillersForStore_success_buildsAndCaches() {
        Store store = newStore(1L);
        BaseFulfillerFactory.BaseFulfiller fulfiller = mock(BaseFulfillerFactory.BaseFulfiller.class);
        engine.factoryMap.put("F1", newFactory("F1", fulfiller));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("[{\"type\":\"F1\"}]"));

            List<BaseFulfillerFactory.BaseFulfiller> first = engine.getFulfillersForStore(store);
            List<BaseFulfillerFactory.BaseFulfiller> second = engine.getFulfillersForStore(store);

            assertEquals(1, first.size());
            assertSame(fulfiller, first.get(0));
            assertSame(first, second);
            configurationMock.verify(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()), times(1));
        }
    }

    @Test
    void getFulfillersForStore_elementMissingType_throwsIllegalArgument() {
        Store store = newStore(1L);
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("[{}]"));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.getFulfillersForStore(store));
            assertTrue(ex.getMessage().contains("missing 'type' field"));
        }
    }

    @Test
    void getFulfillersForStore_factoryNotFound_throwsIllegalArgument() {
        Store store = newStore(1L);
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("[{\"type\":\"UNKNOWN\"}]"));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.getFulfillersForStore(store));
            assertTrue(ex.getMessage().contains("not found in registry"));
        }
    }

    @Test
    void getFulfillersForStore_specNotArray_throwsIllegalArgument() {
        Store store = newStore(1L);
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("{\"type\":\"F1\"}"));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> engine.getFulfillersForStore(store));
            assertTrue(ex.getMessage().contains("not a valid JSON array"));
        }
    }

    @Test
    void getFulfillersForStore_buildThrows_wrapsInRuntimeException() {
        Store store = newStore(1L);
        BaseFulfillerFactory factory = mock(BaseFulfillerFactory.class);
        when(factory.getName()).thenReturn("F1");
        try {
            when(factory.build(any(JsonNode.class))).thenThrow(new RuntimeException("bad spec"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        engine.factoryMap.put("F1", factory);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("[{\"type\":\"F1\"}]"));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> engine.getFulfillersForStore(store));
            // The inner "Error building fulfiller" RuntimeException is re-wrapped by the outer catch
            assertTrue(ex.getMessage().contains("Error parsing configuration"));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("Error building fulfiller F1"));
        }
    }

    @Test
    void getFulfillersForStore_invalidJson_wrapsInRuntimeException() {
        Store store = newStore(1L);
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("{invalid json"));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> engine.getFulfillersForStore(store));
            assertTrue(ex.getMessage().contains("Error parsing configuration"));
        }
    }

    @Test
    void evictCacheForStore_removesEntry() {
        Store store = newStore(1L);
        engine.factoryMap.put("F1", newFactory("F1", mock(BaseFulfillerFactory.BaseFulfiller.class)));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("[{\"type\":\"F1\"}]"));

            engine.getFulfillersForStore(store);
            engine.evictCacheForStore(1L);
            engine.getFulfillersForStore(store);

            configurationMock.verify(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()), times(2));
        }
    }

    @Test
    void evictAll_clearsCache() {
        Store store = newStore(1L);
        engine.factoryMap.put("F1", newFactory("F1", mock(BaseFulfillerFactory.BaseFulfiller.class)));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("[{\"type\":\"F1\"}]"));

            engine.getFulfillersForStore(store);
            engine.evictAll();
            engine.getFulfillersForStore(store);

            configurationMock.verify(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()), times(2));
        }
    }

    private Fulfillment newFulfillmentWithChunk(String chunkType) {
        Store store = newStore(1L);
        Order order = new Order();
        order.store = store;
        order.customer = new Customer();
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 42L;
        fulfillment.order = order;
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.id = 100L;
        chunk.type = chunkType;
        FulfillmentLine line = new FulfillmentLine();
        line.id = 200L;
        line.product = new Product();
        line.requestedQuantity = BigDecimal.ONE;
        chunk.lines.add(line);
        fulfillment.chunks.add(chunk);
        return fulfillment;
    }

    @Test
    void process_appliesFulfillersAndMarksDoneWhenAllChunksReady() throws Exception {
        Fulfillment fulfillment = newFulfillmentWithChunk(FulfillmentChunk.READY);
        BaseFulfillerFactory.BaseFulfiller fulfiller = mock(BaseFulfillerFactory.BaseFulfiller.class);
        engine.factoryMap.put("F1", newFactory("F1", fulfiller));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(fulfillment.order.store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("[{\"type\":\"F1\"}]"));
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            engine.process(fulfillment);

            verify(fulfiller).process(fulfillment);
            assertEquals(ReservationRecord.ReservationStatus.DONE, fulfillment.reservationRecord.status);
            verify(session).persist(any(Lot.class));
            verify(session).persist(any(LotLine.class));
        }
    }

    @Test
    void process_notAllChunksReady_keepsPendingAndEmitsEvent() throws Exception {
        Fulfillment fulfillment = newFulfillmentWithChunk(FulfillmentChunk.PENDING);
        BaseFulfillerFactory.BaseFulfiller fulfiller = mock(BaseFulfillerFactory.BaseFulfiller.class);
        engine.factoryMap.put("F1", newFactory("F1", fulfiller));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(fulfillment.order.store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(newConfiguration("[{\"type\":\"F1\"}]"));

            engine.process(fulfillment);

            verify(fulfiller).process(fulfillment);
            assertEquals(ReservationRecord.ReservationStatus.PENDING, fulfillment.reservationRecord.status);
            eventMock.verify(() -> Event.publishInCurrentTx(
                    eq("Fulfillment Incomplet (Non READY)"),
                    anyString(),
                    eq(Event.UrgencyLevel.HIGH),
                    eq(fulfillment.order.store),
                    eq(fulfillment),
                    eq("FULFILLMENT-42")));
        }
    }

    @Test
    void process_getFulfillersThrows_publishesCriticalEventAndRethrows() {
        Fulfillment fulfillment = newFulfillmentWithChunk(FulfillmentChunk.READY);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Configuration> configurationMock = mockStatic(Configuration.class);
             MockedStatic<Event> eventMock = mockStatic(Event.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(1L)).thenReturn(fulfillment.order.store);
            configurationMock.when(() -> Configuration.findHighestPriorityForStore(any(Store.class), anyString()))
                    .thenReturn(null);

            assertThrows(IllegalStateException.class, () -> engine.process(fulfillment));

            eventMock.verify(() -> Event.publishInNewTx(
                    eq("Erreur Critique de Fulfillment"),
                    anyString(),
                    eq(Event.UrgencyLevel.CRITICAL),
                    eq(fulfillment.order.store),
                    eq(fulfillment),
                    eq("FULFILLMENT-42")));
        }
    }
}
