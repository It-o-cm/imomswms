package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultFulfillerFactory.DefaultFulfiller}.
 * <p>
 * Covers store resolution, target building resolution (configured codes vs default
 * warehouses-then-stores order), line distribution across buildings, target chunk
 * creation, delivery date propagation, source chunk cleanup and line copying.
 */
class DefaultFulfillerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultFulfillerFactory factory = new DefaultFulfillerFactory();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private DefaultFulfillerFactory.DefaultFulfiller buildFulfiller(String json) throws Exception {
        return (DefaultFulfillerFactory.DefaultFulfiller) factory.build(mapper.readTree(json));
    }

    private static Product product(Long id, String ean) {
        Product product = new Product();
        product.id = id;
        product.ean = ean;
        return product;
    }

    private static StoreShelfLocation locationFor(Product product) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product;
        return location;
    }

    private static WarehouseBuilding warehouse(String code, StoreShelfLocation... locations) {
        WarehouseBuilding building = new WarehouseBuilding();
        building.buildingCode = code;
        building.locations = new LinkedHashSet<>(List.of(locations));
        return building;
    }

    private static StoreBuilding shop(String code, StoreShelfLocation... locations) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        building.locations = new LinkedHashSet<>(List.of(locations));
        return building;
    }

    private static Store storeWith(Building... buildings) {
        Store store = new Store();
        store.id = 1L;
        store.code = "S1";
        store.buildings = new LinkedHashSet<>(List.of(buildings));
        return store;
    }

    private static FulfillmentLine line(Product product, int lineNumber, String qty) {
        FulfillmentLine line = new FulfillmentLine();
        line.product = product;
        line.lineNumber = lineNumber;
        line.requestedQuantity = new BigDecimal(qty);
        return line;
    }

    private static FulfillmentChunk chunk(String type, LocalDateTime deliveryDate, FulfillmentLine... lines) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = type;
        chunk.deliveryDate = deliveryDate;
        chunk.lines = new LinkedHashSet<>(List.of(lines));
        return chunk;
    }

    private static Fulfillment fulfillmentWith(Long id, Store store, FulfillmentChunk... chunks) {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = id;
        Order order = new Order();
        order.store = store;
        fulfillment.order = order;
        fulfillment.chunks = new LinkedHashSet<>(List.of(chunks));
        return fulfillment;
    }

    private static List<Object> capturePersisted(Session session, int expectedTimes) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(session, times(expectedTimes)).persist(captor.capture());
        return captor.getAllValues();
    }

    private static List<FulfillmentChunk> persistedChunks(List<Object> persisted) {
        return persisted.stream().filter(FulfillmentChunk.class::isInstance).map(FulfillmentChunk.class::cast).collect(Collectors.toList());
    }

    private static List<FulfillmentLine> persistedLines(List<Object> persisted) {
        return persisted.stream().filter(FulfillmentLine.class::isInstance).map(FulfillmentLine.class::cast).collect(Collectors.toList());
    }

    // --------------------------------------------------
    // getStore
    // --------------------------------------------------

    @Test
    void getStore_missingOrder_throwsIllegalState() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 42L;
        fulfillment.order = null;

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> fulfiller.getStore(fulfillment));
        assertTrue(ex.getMessage().contains("42"));
    }

    @Test
    void getStore_missingStore_throwsIllegalState() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 43L;
        fulfillment.order = new Order();
        fulfillment.order.store = null;

        assertThrows(IllegalStateException.class, () -> fulfiller.getStore(fulfillment));
    }

    @Test
    void getStore_returnsOrderStore() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        Store store = storeWith();
        Fulfillment fulfillment = fulfillmentWith(1L, store);

        assertSame(store, fulfiller.getStore(fulfillment));
    }

    // --------------------------------------------------
    // resolveTargetBuildings / resolveBuildingsByCodes / resolveDefaultBuildings
    // --------------------------------------------------

    @Test
    void resolveTargetBuildings_withConfiguredCodes_usesConfiguredOrder() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{\"buildingCodes\":[\"ST01\",\"WH01\"]}");
        StoreBuilding shop = shop("ST01");
        WarehouseBuilding warehouse = warehouse("WH01");
        Store store = storeWith(warehouse, shop);

        List<Building> resolved = fulfiller.resolveTargetBuildings(store);

        assertEquals(List.of(shop, warehouse), resolved);
    }

    @Test
    void resolveTargetBuildings_withoutCodes_defaultsToWarehousesThenStoresSorted() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        StoreBuilding st02 = shop("ST02");
        StoreBuilding st01 = shop("ST01");
        WarehouseBuilding wh02 = warehouse("WH02");
        WarehouseBuilding wh01 = warehouse("WH01");
        Store store = storeWith(st02, wh02, st01, wh01);

        List<Building> resolved = fulfiller.resolveTargetBuildings(store);

        assertEquals(List.of(wh01, wh02, st01, st02), resolved);
    }

    @Test
    void resolveBuildingsByCodes_unknownCodeIsSkipped() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        WarehouseBuilding wh01 = warehouse("WH01");
        Store store = storeWith(wh01);

        List<Building> resolved = fulfiller.resolveBuildingsByCodes(store, List.of("UNKNOWN", "WH01"));

        assertEquals(List.of(wh01), resolved);
    }

    @Test
    void resolveDefaultBuildings_sortsWarehousesFirstThenStores() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        StoreBuilding st02 = shop("ST02");
        StoreBuilding st01 = shop("ST01");
        WarehouseBuilding wh02 = warehouse("WH02");
        WarehouseBuilding wh01 = warehouse("WH01");
        Store store = storeWith(st01, wh02, st02, wh01);

        List<Building> resolved = fulfiller.resolveDefaultBuildings(store);

        assertEquals(List.of(wh01, wh02, st01, st02), resolved);
    }

    @Test
    void resolveTargetBuildings_nullConfiguredCodes_defaultsToWarehousesThenStores() {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller =
                new DefaultFulfillerFactory.DefaultFulfiller(new DefaultFulfillerFactory.DefaultFulfillerConfig(null));
        StoreBuilding shop = shop("ST01");
        WarehouseBuilding warehouse = warehouse("WH01");
        Store store = storeWith(shop, warehouse);

        List<Building> resolved = fulfiller.resolveTargetBuildings(store);

        assertEquals(List.of(warehouse, shop), resolved);
    }

    @Test
    void resolveDefaultBuildings_unknownBuildingType_isSkipped() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        Building unknown = mock(Building.class);
        WarehouseBuilding wh01 = warehouse("WH01");
        Store store = storeWith(unknown, wh01);

        List<Building> resolved = fulfiller.resolveDefaultBuildings(store);

        assertEquals(List.of(wh01), resolved);
    }

    // --------------------------------------------------
    // isPending
    // --------------------------------------------------

    @Test
    void isPending_trueOnlyForPendingType() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");

        assertTrue(fulfiller.isPending(chunk(FulfillmentChunk.PENDING, null)));
        assertFalse(fulfiller.isPending(chunk(FulfillmentChunk.READY, null)));
    }

    // --------------------------------------------------
    // findLocationInBuildings
    // --------------------------------------------------

    @Test
    void findLocationInBuildings_nullProduct_returnsNull() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        WarehouseBuilding wh01 = warehouse("WH01", locationFor(product(1L, "EAN-1")));

        assertNull(fulfiller.findLocationInBuildings(null, List.of(wh01)));
    }

    @Test
    void findLocationInBuildings_productNotInAnyBuilding_returnsNull() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        WarehouseBuilding wh01 = warehouse("WH01", locationFor(product(1L, "EAN-1")));

        assertNull(fulfiller.findLocationInBuildings(product(99L, "EAN-99"), List.of(wh01)));
    }

    @Test
    void findLocationInBuildings_returnsFirstBuildingContainingProduct() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        Product product = product(1L, "EAN-1");
        StoreShelfLocation locWh = locationFor(product);
        StoreShelfLocation locSt = locationFor(product);
        WarehouseBuilding wh01 = warehouse("WH01", locWh);
        StoreBuilding st01 = shop("ST01", locSt);

        DefaultFulfillerFactory.DefaultFulfiller.ResolvedLocation resolved =
                fulfiller.findLocationInBuildings(product, List.of(st01, wh01));

        assertNotNull(resolved);
        assertSame(st01, resolved.building());
        assertSame(locSt, resolved.location());
    }

    // --------------------------------------------------
    // findOrCreateTargetChunk
    // --------------------------------------------------

    @Test
    void findOrCreateTargetChunk_createsReadyChunkWithDefaults() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        WarehouseBuilding wh01 = warehouse("WH01");
        Map<Building, FulfillmentChunk> map = new HashMap<>();

        FulfillmentChunk created = fulfiller.findOrCreateTargetChunk(map, wh01);

        assertEquals(FulfillmentChunk.READY, created.type);
        assertSame(wh01, created.building);
        assertEquals("Default", created.label);
        assertSame(created, map.get(wh01));
    }

    @Test
    void findOrCreateTargetChunk_returnsExistingChunk() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        WarehouseBuilding wh01 = warehouse("WH01");
        Map<Building, FulfillmentChunk> map = new HashMap<>();
        FulfillmentChunk existing = chunk(FulfillmentChunk.READY, null);
        map.put(wh01, existing);

        assertSame(existing, fulfiller.findOrCreateTargetChunk(map, wh01));
    }

    // --------------------------------------------------
    // removeChunkIfEmpty
    // --------------------------------------------------

    @Test
    void removeChunkIfEmpty_removesChunkWhenNoLinesLeft() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        FulfillmentChunk empty = chunk(FulfillmentChunk.PENDING, null);
        Fulfillment fulfillment = fulfillmentWith(1L, storeWith(), empty);

        fulfiller.removeChunkIfEmpty(fulfillment, empty);

        assertFalse(fulfillment.chunks.contains(empty));
    }

    @Test
    void removeChunkIfEmpty_keepsChunkWhenLinesRemain() throws Exception {
        DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
        FulfillmentChunk notEmpty = chunk(FulfillmentChunk.PENDING, null, line(product(1L, "EAN-1"), 1, "1"));
        Fulfillment fulfillment = fulfillmentWith(1L, storeWith(), notEmpty);

        fulfiller.removeChunkIfEmpty(fulfillment, notEmpty);

        assertTrue(fulfillment.chunks.contains(notEmpty));
    }

    // --------------------------------------------------
    // createNewLine
    // --------------------------------------------------

    @Test
    void createNewLine_copiesFieldsAndPersists() throws Exception {
        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");

            Product product = product(7L, "EAN-7");
            StoreShelfLocation location = locationFor(product);
            FulfillmentLine source = line(product, 3, "12.5");

            FulfillmentLine created = fulfiller.createNewLine(source, location);

            assertEquals(3, created.lineNumber);
            assertSame(product, created.product);
            assertEquals(new BigDecimal("12.5"), created.requestedQuantity);
            assertSame(location, created.location);
            verify(session).persist(created);
        }
    }

    // --------------------------------------------------
    // logLineMoved
    // --------------------------------------------------

    @Test
    void logLineMoved_debugDisabledAndEnabled_coversBothBranches() throws Exception {
        org.jboss.logmanager.Logger lmLogger =
                org.jboss.logmanager.Logger.getLogger(DefaultFulfillerFactory.DefaultFulfiller.class.getName());
        java.util.logging.Level previous = lmLogger.getLevel();
        try {
            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
            // Debug disabled: the log statement is skipped
            lmLogger.setLevel(org.jboss.logmanager.Level.INFO);
            assertFalse(DefaultFulfillerFactory.DefaultFulfiller.LOGGER.isDebugEnabled());
            fulfiller.logLineMoved(line(product(1L, "EAN-1"), 1, "1"), "WH01");
            // Debug enabled: the log statement runs, with a known EAN and with a null product ("Unknown")
            lmLogger.setLevel(org.jboss.logmanager.Level.DEBUG);
            assertTrue(DefaultFulfillerFactory.DefaultFulfiller.LOGGER.isDebugEnabled());
            fulfiller.logLineMoved(line(product(1L, "EAN-1"), 1, "1"), "WH01");
            fulfiller.logLineMoved(line(null, 2, "1"), "WH01");
        } finally {
            lmLogger.setLevel(previous);
        }
    }

    // --------------------------------------------------
    // process - full flow
    // --------------------------------------------------

    @Test
    void process_missingStore_throwsIllegalState() throws Exception {
        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            Fulfillment fulfillment = new Fulfillment();
            fulfillment.id = 100L;
            fulfillment.order = null;
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");

            assertThrows(IllegalStateException.class, () -> fulfiller.process(fulfillment));
            verify(session, never()).persist(any());
        }
    }

    @Test
    void process_noPendingChunks_nothingPersisted() throws Exception {
        Product product = product(1L, "EAN-1");
        WarehouseBuilding wh01 = warehouse("WH01", locationFor(product));
        Store store = storeWith(wh01);
        FulfillmentChunk readyChunk = chunk(FulfillmentChunk.READY, null, line(product, 1, "1"));
        Fulfillment fulfillment = fulfillmentWith(100L, store, readyChunk);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
            fulfiller.process(fulfillment);

            verify(session, never()).persist(any());
            verify(session, never()).flush();
            assertTrue(fulfillment.chunks.contains(readyChunk));
        }
    }

    @Test
    void process_pendingChunk_lineMovedToWarehouse() throws Exception {
        Product product = product(1L, "EAN-1");
        StoreShelfLocation location = locationFor(product);
        WarehouseBuilding wh01 = warehouse("WH01", location);
        StoreBuilding st01 = shop("ST01");
        Store store = storeWith(st01, wh01);
        LocalDateTime deliveryDate = LocalDateTime.of(2026, 10, 14, 10, 0);
        FulfillmentLine sourceLine = line(product, 5, "3");
        FulfillmentChunk sourceChunk = chunk(FulfillmentChunk.PENDING, deliveryDate, sourceLine);
        Fulfillment fulfillment = fulfillmentWith(100L, store, sourceChunk);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
            fulfiller.process(fulfillment);

            // The source chunk is emptied and removed from the fulfillment
            assertFalse(fulfillment.chunks.contains(sourceChunk));
            assertTrue(sourceChunk.lines.isEmpty());

            // One new line + one new chunk persisted
            List<Object> persisted = capturePersisted(session, 2);
            List<FulfillmentChunk> chunks = persistedChunks(persisted);
            List<FulfillmentLine> lines = persistedLines(persisted);
            assertEquals(1, chunks.size());
            assertEquals(1, lines.size());

            FulfillmentChunk targetChunk = chunks.get(0);
            assertEquals(FulfillmentChunk.READY, targetChunk.type);
            assertSame(wh01, targetChunk.building);
            assertEquals("Default", targetChunk.label);
            assertEquals(deliveryDate, targetChunk.deliveryDate);
            assertEquals(1, targetChunk.lines.size());
            // The new chunk is attached to the fulfillment
            assertTrue(fulfillment.chunks.contains(targetChunk));

            FulfillmentLine newLine = lines.get(0);
            assertEquals(5, newLine.lineNumber);
            assertSame(product, newLine.product);
            assertEquals(new BigDecimal("3"), newLine.requestedQuantity);
            assertSame(location, newLine.location);
            assertTrue(targetChunk.lines.contains(newLine));

            // One flush per moved line
            verify(session).flush();
        }
    }

    @Test
    void process_lineWithoutLocation_staysInSourceChunk() throws Exception {
        Product product = product(1L, "EAN-1");
        WarehouseBuilding wh01 = warehouse("WH01"); // no location for the product
        Store store = storeWith(wh01);
        FulfillmentChunk sourceChunk = chunk(FulfillmentChunk.PENDING, null, line(product, 1, "1"));
        Fulfillment fulfillment = fulfillmentWith(100L, store, sourceChunk);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
            fulfiller.process(fulfillment);

            // The line could not be moved: nothing persisted, no flush, chunk kept
            verify(session, never()).persist(any());
            verify(session, never()).flush();
            assertTrue(fulfillment.chunks.contains(sourceChunk));
            assertEquals(1, sourceChunk.lines.size());
        }
    }

    @Test
    void process_lineWithNullProduct_notMoved() throws Exception {
        WarehouseBuilding wh01 = warehouse("WH01", locationFor(product(1L, "EAN-1")));
        Store store = storeWith(wh01);
        FulfillmentChunk sourceChunk = chunk(FulfillmentChunk.PENDING, null, line(null, 1, "1"));
        Fulfillment fulfillment = fulfillmentWith(100L, store, sourceChunk);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
            fulfiller.process(fulfillment);

            verify(session, never()).persist(any());
            assertTrue(fulfillment.chunks.contains(sourceChunk));
            assertEquals(1, sourceChunk.lines.size());
        }
    }

    @Test
    void process_twoLinesSameBuilding_shareSingleTargetChunk() throws Exception {
        Product product = product(1L, "EAN-1");
        WarehouseBuilding wh01 = warehouse("WH01", locationFor(product));
        Store store = storeWith(wh01);
        LocalDateTime deliveryDate = LocalDateTime.of(2026, 10, 14, 10, 0);
        FulfillmentChunk sourceChunk = chunk(FulfillmentChunk.PENDING, deliveryDate,
                line(product, 1, "1"), line(product, 2, "2"));
        Fulfillment fulfillment = fulfillmentWith(100L, store, sourceChunk);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
            fulfiller.process(fulfillment);

            // 2 new lines + 1 shared target chunk
            List<Object> persisted = capturePersisted(session, 3);
            List<FulfillmentChunk> chunks = persistedChunks(persisted);
            assertEquals(1, chunks.size());
            assertEquals(2, chunks.get(0).lines.size());
            assertEquals(2, persistedLines(persisted).size());
            // One flush per moved line
            verify(session, times(2)).flush();
        }
    }

    @Test
    void process_configuredBuildingCodes_productResolvedInConfiguredOrder() throws Exception {
        Product product = product(1L, "EAN-1");
        WarehouseBuilding wh01 = warehouse("WH01", locationFor(product));
        StoreBuilding st01 = shop("ST01", locationFor(product));
        Store store = storeWith(wh01, st01);
        FulfillmentChunk sourceChunk = chunk(FulfillmentChunk.PENDING, null, line(product, 1, "1"));
        Fulfillment fulfillment = fulfillmentWith(100L, store, sourceChunk);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            // ST01 is configured first: the product is resolved there even if WH01 also has it
            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{\"buildingCodes\":[\"ST01\",\"WH01\"]}");
            fulfiller.process(fulfillment);

            List<FulfillmentChunk> chunks = persistedChunks(capturePersisted(session, 2));
            assertEquals(1, chunks.size());
            assertSame(st01, chunks.get(0).building);
        }
    }

    @Test
    void process_twoPendingChunks_deliveryDatePropagatedToExistingTargetChunk() throws Exception {
        Product product = product(1L, "EAN-1");
        WarehouseBuilding wh01 = warehouse("WH01", locationFor(product));
        Store store = storeWith(wh01);
        // First chunk has no delivery date, second one does: the shared target chunk
        // is created without a date, then updated when the second chunk is processed
        FulfillmentChunk chunkWithoutDate = chunk(FulfillmentChunk.PENDING, null, line(product, 1, "1"));
        LocalDateTime deliveryDate = LocalDateTime.of(2026, 10, 14, 10, 0);
        FulfillmentChunk chunkWithDate = chunk(FulfillmentChunk.PENDING, deliveryDate, line(product, 2, "1"));
        Fulfillment fulfillment = fulfillmentWith(100L, store, chunkWithoutDate, chunkWithDate);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
            fulfiller.process(fulfillment);

            // 2 new lines + 1 shared target chunk
            List<FulfillmentChunk> chunks = persistedChunks(capturePersisted(session, 3));
            assertEquals(1, chunks.size());
            FulfillmentChunk targetChunk = chunks.get(0);
            assertEquals(deliveryDate, targetChunk.deliveryDate);
            assertEquals(2, targetChunk.lines.size());
        }
    }

    @Test
    void process_chunkWithRemainingLines_notRemoved() throws Exception {
        Product resolvable = product(1L, "EAN-1");
        Product unresolvable = product(2L, "EAN-2");
        WarehouseBuilding wh01 = warehouse("WH01", locationFor(resolvable));
        Store store = storeWith(wh01);
        FulfillmentChunk sourceChunk = chunk(FulfillmentChunk.PENDING, null,
                line(resolvable, 1, "1"), line(unresolvable, 2, "1"));
        Fulfillment fulfillment = fulfillmentWith(100L, store, sourceChunk);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            DefaultFulfillerFactory.DefaultFulfiller fulfiller = buildFulfiller("{}");
            fulfiller.process(fulfillment);

            // One line moved (1 line + 1 chunk persisted), the other stays: the source chunk is kept
            capturePersisted(session, 2);
            assertTrue(fulfillment.chunks.contains(sourceChunk));
            assertEquals(1, sourceChunk.lines.size());
            assertTrue(sourceChunk.lines.stream().anyMatch(l -> l.product == unresolvable));
        }
    }
}
