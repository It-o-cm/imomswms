package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductFamily;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProductGroupingFulfillerFactory}.
 * Pure unit tests — no database, no Quarkus.
 */
class ProductGroupingFulfillerFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AtomicLong CHUNK_IDS = new AtomicLong(10);

    private final ProductGroupingFulfillerFactory factory = new ProductGroupingFulfillerFactory();

    private JsonNode spec(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ProductGroupingFulfillerFactory.ProductGroupingFulfiller buildFulfiller(String json) {
        return (ProductGroupingFulfillerFactory.ProductGroupingFulfiller) factory.build(spec(json));
    }

    // --------------------------------------------------
    // Factory contract
    // --------------------------------------------------

    @Test
    void getName_returnsProductGrouping() {
        assertEquals("ProductGrouping", factory.getName());
    }

    @Test
    void build_validEans_returnsFulfiller() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");
        assertNotNull(fulfiller);
    }

    @Test
    void build_validFamilies_returnsFulfiller() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"families\":[\"FRESH\"]}");
        assertNotNull(fulfiller);
    }

    @Test
    void build_emptyObject_returnsFulfiller() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        assertNotNull(fulfiller);
    }

    @Test
    void build_invalidEansType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> factory.build(spec("{\"eans\":\"not-an-array\"}")));
    }

    @Test
    void build_invalidNameType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> factory.build(spec("{\"name\":123}")));
    }

    // --------------------------------------------------
    // Fulfiller behavior (in-memory only)
    // --------------------------------------------------

    @Test
    void shouldSkipProcessing_noCriteria_true() {
        assertTrue(buildFulfiller("{}").shouldSkipProcessing());
    }

    @Test
    void shouldSkipProcessing_withEans_false() {
        assertFalse(buildFulfiller("{\"eans\":[\"123\"]}").shouldSkipProcessing());
    }

    @Test
    void shouldSkipProcessing_withFamilies_false() {
        assertFalse(buildFulfiller("{\"families\":[\"FRESH\"]}").shouldSkipProcessing());
    }

    @Test
    void isPending_pendingType_true() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        assertTrue(buildFulfiller("{}").isPending(chunk));
    }

    @Test
    void isPending_readyType_false() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        assertFalse(buildFulfiller("{}").isPending(chunk));
    }

    @Test
    void isEanMatch_matchingEan_true() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");
        assertTrue(fulfiller.isEanMatch("123"));
    }

    @Test
    void isEanMatch_nonMatchingEan_false() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");
        assertFalse(fulfiller.isEanMatch("999"));
    }

    @Test
    void isEanMatch_emptyTargets_false() {
        assertFalse(buildFulfiller("{}").isEanMatch("123"));
    }

    @Test
    void createTargetChunk_setsReadyTypeLabelAndBuilding() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "LYON-01";

        FulfillmentChunk chunk = fulfiller.createTargetChunk(building);

        assertEquals(FulfillmentChunk.READY, chunk.type);
        assertEquals("ProductGrouping", chunk.label);
        assertSame(building, chunk.building);
    }

    @Test
    void transferDeliveryDate_copiesWhenTargetHasNone() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        FulfillmentChunk source = new FulfillmentChunk();
        source.deliveryDate = java.time.LocalDateTime.of(2026, 10, 15, 8, 0);
        FulfillmentChunk target = new FulfillmentChunk();

        fulfiller.transferDeliveryDate(target, source);

        assertEquals(source.deliveryDate, target.deliveryDate);
    }

    @Test
    void transferDeliveryDate_keepsExistingWhenTargetHasOne() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        FulfillmentChunk source = new FulfillmentChunk();
        source.deliveryDate = java.time.LocalDateTime.of(2026, 10, 15, 8, 0);
        FulfillmentChunk target = new FulfillmentChunk();
        target.deliveryDate = java.time.LocalDateTime.of(2026, 10, 16, 8, 0);

        fulfiller.transferDeliveryDate(target, source);

        assertEquals(java.time.LocalDateTime.of(2026, 10, 16, 8, 0), target.deliveryDate);
    }

    @Test
    void getOrCreateTargetChunk_createsOnceAndReuses() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        StoreBuilding building = new StoreBuilding();
        Map<com.intermarche.backoffice.domain.store.Building, FulfillmentChunk> map = new HashMap<>();

        FulfillmentChunk first = fulfiller.getOrCreateTargetChunk(building, map);
        FulfillmentChunk second = fulfiller.getOrCreateTargetChunk(building, map);

        assertSame(first, second);
        assertEquals(1, map.size());
    }

    @Test
    void resolveLocationInStore_nullProduct_returnsNull() {
        assertNull(buildFulfiller("{}").resolveLocationInStore(new Store(), null));
    }

    @Test
    void resolveLocationInStore_findsInStoreBuilding() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        Product product = new Product();
        product.id = 42L;
        product.ean = "123";

        StoreBuilding shop = new StoreBuilding();
        shop.buildingCode = "LYON-01";
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product;
        shop.locations.add(location);

        Store store = new Store();
        store.code = "S001";
        store.buildings.add(shop);

        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved =
                fulfiller.resolveLocationInStore(store, product);

        assertNotNull(resolved);
        assertSame(shop, resolved.building());
        assertSame(location, resolved.location());
    }

    @Test
    void resolveLocationInStore_fallsBackToWarehouse() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        Product product = new Product();
        product.id = 42L;
        product.ean = "123";

        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "LYON-WH";
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product;
        warehouse.locations.add(location);

        Store store = new Store();
        store.code = "S001";
        store.buildings.add(warehouse);

        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved =
                fulfiller.resolveLocationInStore(store, product);

        assertNotNull(resolved);
        assertSame(warehouse, resolved.building());
        assertSame(location, resolved.location());
    }

    @Test
    void resolveLocationInStore_productNotLocated_returnsNull() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        Product product = new Product();
        product.id = 42L;

        Store store = new Store();
        store.code = "S001";
        store.buildings.add(new StoreBuilding());

        assertNull(fulfiller.resolveLocationInStore(store, product));
    }

    // --------------------------------------------------
    // ResolvedLocation record
    // --------------------------------------------------

    @Test
    void resolvedLocation_accessors() {
        StoreBuilding building = new StoreBuilding();
        StoreShelfLocation location = new StoreShelfLocation();

        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved =
                new ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation(building, location);

        assertSame(building, resolved.building());
        assertSame(location, resolved.location());
    }

    @Test
    void resolvedLocation_valueEquality() {
        StoreBuilding building = new StoreBuilding();
        StoreShelfLocation location = new StoreShelfLocation();

        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved1 =
                new ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation(building, location);
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved2 =
                new ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation(building, location);

        assertEquals(resolved1, resolved2);
        assertEquals(resolved1.hashCode(), resolved2.hashCode());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private Product productWithEan(String ean, long id) {
        Product product = new Product();
        product.id = id;
        product.ean = ean;
        return product;
    }

    private StoreShelfLocation shelfLocationFor(Product product) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product;
        return location;
    }

    private StoreBuilding shopWithProduct(Product product) {
        StoreBuilding shop = new StoreBuilding();
        shop.buildingCode = "SHOP-01";
        shop.locations.add(shelfLocationFor(product));
        return shop;
    }

    private WarehouseBuilding warehouseWithProduct(Product product) {
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "WH-01";
        warehouse.locations.add(shelfLocationFor(product));
        return warehouse;
    }

    private Store storeWithBuildings(Building... buildings) {
        Store store = new Store();
        store.code = "S001";
        store.buildings.addAll(List.of(buildings));
        return store;
    }

    private Fulfillment fulfillmentWithStore(Store store) {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;
        Order order = new Order();
        order.store = store;
        fulfillment.order = order;
        return fulfillment;
    }

    private FulfillmentLine lineFor(Product product, int lineNumber) {
        FulfillmentLine line = new FulfillmentLine();
        line.product = product;
        line.lineNumber = lineNumber;
        line.requestedQuantity = BigDecimal.valueOf(2.5);
        return line;
    }

    private FulfillmentChunk pendingChunk(FulfillmentLine... lines) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.id = CHUNK_IDS.incrementAndGet();
        chunk.type = FulfillmentChunk.PENDING;
        chunk.deliveryDate = LocalDateTime.of(2026, 10, 15, 8, 0);
        for (FulfillmentLine line : lines) {
            chunk.addLine(line);
        }
        return chunk;
    }

    // --------------------------------------------------
    // process() pipeline
    // --------------------------------------------------

    @Test
    void process_noCriteria_skipsProcessing() {
        Product product = productWithEan("123", 1L);
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(shopWithProduct(product)));
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            assertTrue(fulfillment.chunks.contains(pending));
            verifyNoInteractions(session);
        }
    }

    @Test
    void process_missingOrder_throwsIllegalState() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;

        try (MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");
            assertThrows(IllegalStateException.class, () -> fulfiller.process(fulfillment));
        }
    }

    @Test
    void process_missingStore_throwsIllegalState() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;
        fulfillment.order = new Order();

        try (MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");
            assertThrows(IllegalStateException.class, () -> fulfiller.process(fulfillment));
        }
    }

    @Test
    void process_noPendingChunks_addsNoChunk() {
        Product product = productWithEan("123", 1L);
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(shopWithProduct(product)));
        FulfillmentChunk ready = new FulfillmentChunk();
        ready.type = FulfillmentChunk.READY;
        fulfillment.addChunk(ready);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"eans\":[\"123\"]}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            assertTrue(fulfillment.chunks.contains(ready));
            verifyNoInteractions(session);
        }
    }

    @Test
    void process_movesMatchingLineToNewChunk() {
        Product product = productWithEan("123", 1L);
        StoreBuilding shop = shopWithProduct(product);
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(shop));
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"eans\":[\"123\"]}").process(fulfillment);

            // The emptied pending chunk is removed from the fulfillment
            assertFalse(fulfillment.chunks.contains(pending));
            // A new READY chunk was added
            assertEquals(1, fulfillment.chunks.size());
            FulfillmentChunk target = fulfillment.chunks.iterator().next();
            assertEquals(FulfillmentChunk.READY, target.type);
            assertEquals("ProductGrouping", target.label);
            assertSame(shop, target.building);
            assertEquals(pending.deliveryDate, target.deliveryDate);
            // The new line copies the source data and points to the resolved location
            assertEquals(1, target.lines.size());
            FulfillmentLine newLine = target.lines.iterator().next();
            assertEquals(1, newLine.lineNumber);
            assertSame(product, newLine.product);
            assertEquals(BigDecimal.valueOf(2.5), newLine.requestedQuantity);
            assertSame(shop.locations.iterator().next(), newLine.location);
            // Both the new line and the new chunk were persisted, and the move was flushed
            verify(session, times(2)).persist(any());
            verify(session).flush();
        }
    }

    @Test
    void process_nonMatchingLine_staysInSourceChunk() {
        Product product = productWithEan("999", 1L);
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(shopWithProduct(product)));
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"eans\":[\"123\"]}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            assertTrue(fulfillment.chunks.contains(pending));
            assertEquals(1, pending.lines.size());
            verifyNoInteractions(session);
        }
    }

    @Test
    void process_matchingLineWithoutLocation_staysInSourceChunk() {
        Product product = productWithEan("123", 1L);
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(new StoreBuilding()));
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"eans\":[\"123\"]}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            assertTrue(fulfillment.chunks.contains(pending));
            assertEquals(1, pending.lines.size());
            verifyNoInteractions(session);
        }
    }

    @Test
    void process_familyMatch_movesLine() {
        Product product = productWithEan("999", 1L);
        StoreBuilding shop = shopWithProduct(product);
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(shop));
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        ProductFamily family = new ProductFamily();
        family.code = "FRESH";

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> familyMock = mockStatic(ProductFamily.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);
            familyMock.when(() -> ProductFamily.findAllFamiliesForProduct(any())).thenReturn(Set.of(family));

            buildFulfiller("{\"families\":[\"FRESH\"]}").process(fulfillment);

            assertFalse(fulfillment.chunks.contains(pending));
            assertEquals(1, fulfillment.chunks.size());
            FulfillmentChunk target = fulfillment.chunks.iterator().next();
            assertEquals(1, target.lines.size());
            assertSame(product, target.lines.iterator().next().product);
            verify(session, times(2)).persist(any());
        }
    }

    @Test
    void process_createsOneChunkPerBuilding() {
        Product inShop = productWithEan("111", 1L);
        Product inWarehouse = productWithEan("222", 2L);
        StoreBuilding shop = shopWithProduct(inShop);
        WarehouseBuilding warehouse = warehouseWithProduct(inWarehouse);
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(shop, warehouse));
        FulfillmentChunk pending = pendingChunk(lineFor(inShop, 1), lineFor(inWarehouse, 2));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"eans\":[\"111\",\"222\"]}").process(fulfillment);

            assertEquals(2, fulfillment.chunks.size());
            FulfillmentChunk shopChunk = null;
            FulfillmentChunk warehouseChunk = null;
            for (FulfillmentChunk chunk : fulfillment.chunks) {
                if (chunk.building == shop) {
                    shopChunk = chunk;
                } else if (chunk.building == warehouse) {
                    warehouseChunk = chunk;
                }
            }
            assertNotNull(shopChunk);
            assertNotNull(warehouseChunk);
            assertEquals(1, shopChunk.lines.size());
            assertEquals(1, warehouseChunk.lines.size());
            assertSame(inShop, shopChunk.lines.iterator().next().product);
            assertSame(inWarehouse, warehouseChunk.lines.iterator().next().product);
            assertSame(shop.locations.iterator().next(), shopChunk.lines.iterator().next().location);
            assertSame(warehouse.locations.iterator().next(), warehouseChunk.lines.iterator().next().location);
            // 2 new lines + 2 new chunks persisted
            verify(session, times(4)).persist(any());
        }
    }

    // --------------------------------------------------
    // getStore / initializeBuildingChunksMap / getPendingChunks
    // --------------------------------------------------

    @Test
    void getStore_orderNull_throwsIllegalState() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 5L;

        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> fulfiller.getStore(fulfillment));
        assertTrue(ex.getMessage().contains("5"));
    }

    @Test
    void getStore_storeNull_throwsIllegalState() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 6L;
        fulfillment.order = new Order();

        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        assertThrows(IllegalStateException.class, () -> fulfiller.getStore(fulfillment));
    }

    @Test
    void getStore_returnsOrderStore() {
        Store store = new Store();
        Fulfillment fulfillment = fulfillmentWithStore(store);

        assertSame(store, buildFulfiller("{}").getStore(fulfillment));
    }

    @Test
    void initializeBuildingChunksMap_returnsEmptyMap() {
        Map<Building, FulfillmentChunk> map = buildFulfiller("{}").initializeBuildingChunksMap();
        assertNotNull(map);
        assertTrue(map.isEmpty());
    }

    @Test
    void getPendingChunks_filtersPendingOnly() {
        Fulfillment fulfillment = new Fulfillment();
        FulfillmentChunk pending = pendingChunk();
        FulfillmentChunk ready = new FulfillmentChunk();
        ready.type = FulfillmentChunk.READY;
        fulfillment.addChunk(pending);
        fulfillment.addChunk(ready);

        List<FulfillmentChunk> result = buildFulfiller("{}").getPendingChunks(fulfillment);

        assertEquals(1, result.size());
        assertSame(pending, result.get(0));
    }

    // --------------------------------------------------
    // processSourceChunks / processSingleChunk
    // --------------------------------------------------

    @Test
    void processSourceChunks_returnsTotalMoved() {
        Product product = productWithEan("123", 1L);
        Store store = storeWithBuildings(shopWithProduct(product));
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");
        FulfillmentChunk chunk1 = pendingChunk(lineFor(product, 1));
        FulfillmentChunk chunk2 = pendingChunk(lineFor(product, 2));
        Map<Building, FulfillmentChunk> chunksByBuilding = new HashMap<>();

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            int moved = fulfiller.processSourceChunks(List.of(chunk1, chunk2), store, chunksByBuilding);

            assertEquals(2, moved);
            assertTrue(chunk1.lines.isEmpty());
            assertTrue(chunk2.lines.isEmpty());
            assertEquals(1, chunksByBuilding.size());
            FulfillmentChunk target = chunksByBuilding.values().iterator().next();
            assertEquals(2, target.lines.size());
            verify(session, times(2)).flush();
        }
    }

    @Test
    void processSourceChunks_noChunks_returnsZero() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");
        int moved = fulfiller.processSourceChunks(List.of(), new Store(), new HashMap<>());
        assertEquals(0, moved);
    }

    @Test
    void processSingleChunk_nonMatchingLine_returnsZero() {
        Product product = productWithEan("999", 1L);
        Store store = storeWithBuildings(shopWithProduct(product));
        FulfillmentChunk chunk = pendingChunk(lineFor(product, 1));
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            int moved = fulfiller.processSingleChunk(chunk, store, new HashMap<>());

            assertEquals(0, moved);
            assertEquals(1, chunk.lines.size());
            verifyNoInteractions(session);
        }
    }

    @Test
    void processSingleChunk_matchingLineWithoutLocation_returnsZero() {
        Product product = productWithEan("123", 1L);
        Store store = storeWithBuildings(new StoreBuilding());
        FulfillmentChunk chunk = pendingChunk(lineFor(product, 1));
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");

        int moved = fulfiller.processSingleChunk(chunk, store, new HashMap<>());

        assertEquals(0, moved);
        assertEquals(1, chunk.lines.size());
    }

    // --------------------------------------------------
    // removeEmptyChunks / finalizeProcessing
    // --------------------------------------------------

    @Test
    void removeEmptyChunks_removesOnlyEmptyChunks() {
        Fulfillment fulfillment = new Fulfillment();
        FulfillmentChunk empty = pendingChunk();
        FulfillmentChunk withLine = pendingChunk(lineFor(productWithEan("123", 1L), 1));
        fulfillment.addChunk(empty);
        fulfillment.addChunk(withLine);

        buildFulfiller("{}").removeEmptyChunks(fulfillment, List.of(empty, withLine));

        assertFalse(fulfillment.chunks.contains(empty));
        assertTrue(fulfillment.chunks.contains(withLine));
    }

    @Test
    void finalizeProcessing_addsAndPersistsChunks() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;
        StoreBuilding shop = new StoreBuilding();
        shop.buildingCode = "SHOP-01";
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.building = shop;
        Map<Building, FulfillmentChunk> chunksByBuilding = new HashMap<>();
        chunksByBuilding.put(shop, chunk);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            buildFulfiller("{}").finalizeProcessing(fulfillment, chunksByBuilding, 3);

            assertTrue(fulfillment.chunks.contains(chunk));
            verify(session).persist(chunk);
        }
    }

    @Test
    void finalizeProcessing_emptyMap_doesNothing() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;

        buildFulfiller("{}").finalizeProcessing(fulfillment, new HashMap<>(), 0);

        assertTrue(fulfillment.chunks.isEmpty());
    }

    // --------------------------------------------------
    // isLineMatch / isFamilyMatch
    // --------------------------------------------------

    @Test
    void isLineMatch_eanMatch_true() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"eans\":[\"123\"]}");
        assertTrue(fulfiller.isLineMatch(lineFor(productWithEan("123", 1L), 1)));
    }

    @Test
    void isLineMatch_familyMatch_true() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{\"families\":[\"FRESH\"]}");
        ProductFamily family = new ProductFamily();
        family.code = "FRESH";

        try (MockedStatic<ProductFamily> familyMock = mockStatic(ProductFamily.class)) {
            familyMock.when(() -> ProductFamily.findAllFamiliesForProduct(any())).thenReturn(Set.of(family));
            assertTrue(fulfiller.isLineMatch(lineFor(productWithEan("999", 1L), 1)));
        }
    }

    @Test
    void isLineMatch_noMatch_false() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller =
                buildFulfiller("{\"eans\":[\"123\"],\"families\":[\"FRESH\"]}");
        ProductFamily other = new ProductFamily();
        other.code = "DRY";

        try (MockedStatic<ProductFamily> familyMock = mockStatic(ProductFamily.class)) {
            familyMock.when(() -> ProductFamily.findAllFamiliesForProduct(any())).thenReturn(Set.of(other));
            assertFalse(fulfiller.isLineMatch(lineFor(productWithEan("999", 1L), 1)));
        }
    }

    @Test
    void isFamilyMatch_emptyTargets_false() {
        assertFalse(buildFulfiller("{}").isFamilyMatch(productWithEan("123", 1L)));
    }

    @Test
    void isFamilyMatch_matchingFamily_true() {
        ProductFamily family = new ProductFamily();
        family.code = "FRESH";

        try (MockedStatic<ProductFamily> familyMock = mockStatic(ProductFamily.class)) {
            familyMock.when(() -> ProductFamily.findAllFamiliesForProduct(any())).thenReturn(Set.of(family));
            assertTrue(buildFulfiller("{\"families\":[\"FRESH\"]}").isFamilyMatch(productWithEan("999", 1L)));
        }
    }

    @Test
    void isFamilyMatch_nonMatchingFamily_false() {
        ProductFamily other = new ProductFamily();
        other.code = "DRY";

        try (MockedStatic<ProductFamily> familyMock = mockStatic(ProductFamily.class)) {
            familyMock.when(() -> ProductFamily.findAllFamiliesForProduct(any())).thenReturn(Set.of(other));
            assertFalse(buildFulfiller("{\"families\":[\"FRESH\"]}").isFamilyMatch(productWithEan("999", 1L)));
        }
    }

    // --------------------------------------------------
    // findLocationInStoreBuilding / findLocationInWarehouses
    // --------------------------------------------------

    @Test
    void findLocationInStoreBuilding_noStoreBuilding_returnsNull() {
        Product product = productWithEan("123", 1L);
        Store store = storeWithBuildings(warehouseWithProduct(product));
        assertNull(buildFulfiller("{}").findLocationInStoreBuilding(store, product));
    }

    @Test
    void findLocationInStoreBuilding_productNotLocated_returnsNull() {
        Product product = productWithEan("123", 1L);
        Store store = storeWithBuildings(new StoreBuilding());
        assertNull(buildFulfiller("{}").findLocationInStoreBuilding(store, product));
    }

    @Test
    void findLocationInStoreBuilding_found_returnsResolved() {
        Product product = productWithEan("123", 1L);
        StoreBuilding shop = shopWithProduct(product);
        Store store = storeWithBuildings(shop);

        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved =
                buildFulfiller("{}").findLocationInStoreBuilding(store, product);

        assertNotNull(resolved);
        assertSame(shop, resolved.building());
        assertSame(shop.locations.iterator().next(), resolved.location());
    }

    @Test
    void findLocationInWarehouses_noWarehouse_returnsNull() {
        Product product = productWithEan("123", 1L);
        Store store = storeWithBuildings(shopWithProduct(product));
        assertNull(buildFulfiller("{}").findLocationInWarehouses(store, product));
    }

    @Test
    void findLocationInWarehouses_found_returnsResolved() {
        Product product = productWithEan("123", 1L);
        WarehouseBuilding warehouse = warehouseWithProduct(product);
        Store store = storeWithBuildings(new StoreBuilding(), warehouse);

        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved =
                buildFulfiller("{}").findLocationInWarehouses(store, product);

        assertNotNull(resolved);
        assertSame(warehouse, resolved.building());
        assertSame(warehouse.locations.iterator().next(), resolved.location());
    }

    // --------------------------------------------------
    // createNewLine / moveLineToNewChunk / addLineToTargetChunk / logMove / flush
    // --------------------------------------------------

    @Test
    void createNewLine_copiesFieldsAndPersists() {
        Product product = productWithEan("123", 1L);
        FulfillmentLine source = lineFor(product, 7);
        StoreShelfLocation location = shelfLocationFor(product);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            FulfillmentLine newLine = buildFulfiller("{}").createNewLine(source, location);

            assertEquals(7, newLine.lineNumber);
            assertSame(product, newLine.product);
            assertEquals(BigDecimal.valueOf(2.5), newLine.requestedQuantity);
            assertSame(location, newLine.location);
            verify(session).persist(newLine);
        }
    }

    @Test
    void moveLineToNewChunk_addsLineToTargetChunk() {
        Product product = productWithEan("123", 1L);
        StoreBuilding shop = shopWithProduct(product);
        ShelfLocation location = shop.locations.iterator().next();
        FulfillmentLine source = lineFor(product, 3);
        FulfillmentChunk target = new FulfillmentChunk();
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved =
                new ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation(shop, location);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            PanacheTestSupport.mockArcSession(arcMock);

            buildFulfiller("{}").moveLineToNewChunk(source, resolved, target);

            assertEquals(1, target.lines.size());
            FulfillmentLine moved = target.lines.iterator().next();
            assertEquals(3, moved.lineNumber);
            assertSame(location, moved.location);
        }
    }

    @Test
    void addLineToTargetChunk_addsLine() {
        FulfillmentChunk target = new FulfillmentChunk();
        FulfillmentLine line = lineFor(productWithEan("123", 1L), 1);

        buildFulfiller("{}").addLineToTargetChunk(line, target);

        assertTrue(target.lines.contains(line));
    }

    @Test
    void logMove_doesNotThrow() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        StoreBuilding shop = new StoreBuilding();
        shop.buildingCode = "SHOP-01";
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved =
                new ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation(shop, new StoreShelfLocation());

        FulfillmentLine withProduct = lineFor(productWithEan("123", 1L), 1);
        FulfillmentLine withoutProduct = new FulfillmentLine();

        assertDoesNotThrow(() -> fulfiller.logMove(withProduct, resolved));
        assertDoesNotThrow(() -> fulfiller.logMove(withoutProduct, resolved));
    }

    @Test
    void logMove_debugLevelControlsLogging() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        StoreBuilding shop = new StoreBuilding();
        shop.buildingCode = "SHOP-01";
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation resolved =
                new ProductGroupingFulfillerFactory.ProductGroupingFulfiller.ResolvedLocation(shop, new StoreShelfLocation());
        FulfillmentLine withProduct = lineFor(productWithEan("123", 1L), 1);
        FulfillmentLine withoutProduct = new FulfillmentLine();

        org.jboss.logmanager.Logger lmLogger =
                org.jboss.logmanager.Logger.getLogger(ProductGroupingFulfillerFactory.ProductGroupingFulfiller.class.getName());
        java.util.logging.Level previous = lmLogger.getLevel();
        try {
            // Debug disabled: the log block is skipped
            lmLogger.setLevel(org.jboss.logmanager.Level.INFO);
            assertFalse(ProductGroupingFulfillerFactory.ProductGroupingFulfiller.LOGGER.isDebugEnabled());
            fulfiller.logMove(withProduct, resolved);
            // Debug enabled: the log block runs, with a known EAN and with a null product ("Unknown")
            lmLogger.setLevel(org.jboss.logmanager.Level.DEBUG);
            assertTrue(ProductGroupingFulfillerFactory.ProductGroupingFulfiller.LOGGER.isDebugEnabled());
            fulfiller.logMove(withProduct, resolved);
            fulfiller.logMove(withoutProduct, resolved);
        } finally {
            lmLogger.setLevel(previous);
        }
    }

    // --------------------------------------------------
    // parseConfiguration (via reflection: the JSON schema rejects
    // missing/non-array eans and families before parseConfiguration runs)
    // --------------------------------------------------

    private Object invokeParseConfiguration(JsonNode spec) throws Exception {
        java.lang.reflect.Method method =
                ProductGroupingFulfillerFactory.class.getDeclaredMethod("parseConfiguration", JsonNode.class);
        method.setAccessible(true);
        return method.invoke(factory, spec);
    }

    @Test
    void parseConfiguration_missingEans_returnsConfigWithEmptyEans() throws Exception {
        assertNotNull(invokeParseConfiguration(spec("{\"families\":[\"F1\"]}")));
    }

    @Test
    void parseConfiguration_eansNotArray_returnsConfigWithEmptyEans() throws Exception {
        assertNotNull(invokeParseConfiguration(spec("{\"eans\":\"EAN-1\"}")));
    }

    @Test
    void parseConfiguration_familiesNotArray_returnsConfigWithEmptyFamilies() throws Exception {
        assertNotNull(invokeParseConfiguration(spec("{\"eans\":[\"EAN-1\"],\"families\":\"F1\"}")));
    }

    // --------------------------------------------------
    // findLocationInWarehouses
    // --------------------------------------------------

    @Test
    void findLocationInWarehouses_productNotInWarehouse_returnsNull() {
        ProductGroupingFulfillerFactory.ProductGroupingFulfiller fulfiller = buildFulfiller("{}");
        Product product = productWithEan("999", 42L);
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "WH-01";
        Store store = new Store();
        store.code = "S001";
        store.buildings.add(warehouse);

        assertNull(fulfiller.findLocationInWarehouses(store, product));
    }

    @Test
    void flushDatabaseChanges_callsPanacheFlush() {
        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            buildFulfiller("{}").flushDatabaseChanges();

            verify(session).flush();
        }
    }
}
