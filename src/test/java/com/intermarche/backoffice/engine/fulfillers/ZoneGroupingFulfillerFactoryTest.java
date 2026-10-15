package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import com.intermarche.backoffice.domain.store.WarehouseShelfLocation;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ZoneGroupingFulfillerFactory}.
 * Tests schema validation and configuration parsing without database access.
 */
class ZoneGroupingFulfillerFactoryTest {

    private final ZoneGroupingFulfillerFactory factory = new ZoneGroupingFulfillerFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void getName_shouldReturnZoneGrouping() {
        assertEquals("ZoneGrouping", factory.getName());
    }

    @Test
    void build_shouldCreateFulfiller_whenOnlyBuildingCodeProvided() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"buildingCode": "LYON-WH"}
                """);
        BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(spec);

        assertNotNull(fulfiller);
        assertInstanceOf(ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller.class, fulfiller);
    }

    @Test
    void build_shouldCreateFulfiller_whenBuildingCodeAndAreaProvided() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"buildingCode": "LYON-WH", "area": "FRESH"}
                """);
        BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(spec);

        assertNotNull(fulfiller);
    }

    @Test
    void build_shouldCreateFulfiller_whenAllFieldsProvided() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"buildingCode": "LYON-WH", "area": "FRESH", "aisle": "A05"}
                """);
        BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(spec);

        assertNotNull(fulfiller);
    }

    @Test
    void build_shouldCreateFulfiller_whenNameProvided() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"name": "Fresh Zone", "buildingCode": "STORE-01"}
                """);
        BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(spec);

        assertNotNull(fulfiller);
    }

    @Test
    void build_shouldThrow_whenBuildingCodeIsMissing() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"area": "FRESH"}
                """);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec));
        assertTrue(ex.getMessage().contains("buildingCode"));
    }

    @Test
    void build_shouldThrow_whenSpecIsEmptyObject() throws Exception {
        JsonNode spec = mapper.readTree("{}");
        assertThrows(IllegalArgumentException.class, () -> factory.build(spec));
    }

    @Test
    void build_shouldThrow_whenSpecIsArray() throws Exception {
        JsonNode spec = mapper.readTree("[]");
        assertThrows(IllegalArgumentException.class, () -> factory.build(spec));
    }

    @Test
    void build_shouldThrow_whenBuildingCodeIsNotString() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"buildingCode": 123}
                """);
        assertThrows(IllegalArgumentException.class, () -> factory.build(spec));
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static final AtomicLong CHUNK_IDS = new AtomicLong(10);

    private ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller buildFulfiller(String json) {
        try {
            return (ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller) factory.build(mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Product productWithEan(String ean, long id) {
        Product product = new Product();
        product.id = id;
        product.ean = ean;
        return product;
    }

    private WarehouseShelfLocation locationFor(Product product, String area, String aisle) {
        WarehouseShelfLocation location = new WarehouseShelfLocation();
        location.product = product;
        location.area = area;
        location.aisle = aisle;
        return location;
    }

    private WarehouseBuilding warehouseWithLocations(String code, ShelfLocation... locations) {
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = code;
        warehouse.locations.addAll(List.of(locations));
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
        // LinkedHashSet keeps insertion order so multi-chunk scenarios are deterministic
        fulfillment.chunks = new LinkedHashSet<>();
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
    // Configuration parsing / validation
    // --------------------------------------------------

    @Test
    void parseConfiguration_allFields_returnsConfig() throws Exception {
        ZoneGroupingFulfillerFactory.ZoneConfig config = factory.parseConfiguration(
                mapper.readTree("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}"));

        assertEquals("WH-01", config.buildingCode());
        assertEquals("FRESH", config.area());
        assertEquals("A05", config.aisle());
    }

    @Test
    void parseConfiguration_onlyBuildingCode_areaAndAisleAreNull() throws Exception {
        ZoneGroupingFulfillerFactory.ZoneConfig config =
                factory.parseConfiguration(mapper.readTree("{\"buildingCode\":\"WH-01\"}"));

        assertEquals("WH-01", config.buildingCode());
        assertNull(config.area());
        assertNull(config.aisle());
    }

    @Test
    void validateConfiguration_validSpec_doesNotThrow() throws Exception {
        assertDoesNotThrow(() -> factory.validateConfiguration(mapper.readTree("{\"buildingCode\":\"WH-01\"}")));
    }

    @Test
    void validateConfiguration_missingBuildingCode_throws() throws Exception {
        JsonNode spec = mapper.readTree("{\"area\":\"FRESH\"}");
        assertThrows(IllegalArgumentException.class, () -> factory.validateConfiguration(spec));
    }

    // --------------------------------------------------
    // Fulfiller: simple methods
    // --------------------------------------------------

    @Test
    void isPending_pendingType_true() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        assertTrue(buildFulfiller("{\"buildingCode\":\"WH-01\"}").isPending(chunk));
    }

    @Test
    void isPending_readyType_false() {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        assertFalse(buildFulfiller("{\"buildingCode\":\"WH-01\"}").isPending(chunk));
    }

    @Test
    void createTargetChunk_setsReadyTypeLabelAndBuilding() {
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "WH-01";

        FulfillmentChunk chunk = buildFulfiller("{\"buildingCode\":\"WH-01\"}").createTargetChunk(warehouse);

        assertEquals(FulfillmentChunk.READY, chunk.type);
        assertEquals("ZoneGrouping", chunk.label);
        assertSame(warehouse, chunk.building);
    }

    @Test
    void getStoreFromFulfillment_orderNull_throwsIllegalState() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 5L;

        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller = buildFulfiller("{\"buildingCode\":\"WH-01\"}");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> fulfiller.getStoreFromFulfillment(fulfillment));
        assertTrue(ex.getMessage().contains("5"));
    }

    @Test
    void getStoreFromFulfillment_storeNull_throwsIllegalState() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 6L;
        fulfillment.order = new Order();

        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller = buildFulfiller("{\"buildingCode\":\"WH-01\"}");
        assertThrows(IllegalStateException.class, () -> fulfiller.getStoreFromFulfillment(fulfillment));
    }

    @Test
    void getStoreFromFulfillment_returnsOrderStore() {
        Store store = new Store();
        Fulfillment fulfillment = fulfillmentWithStore(store);

        assertSame(store, buildFulfiller("{\"buildingCode\":\"WH-01\"}").getStoreFromFulfillment(fulfillment));
    }

    @Test
    void resolveTargetBuilding_found_returnsBuilding() {
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "WH-01";
        Store store = storeWithBuildings(warehouse);

        Building resolved = buildFulfiller("{\"buildingCode\":\"WH-01\"}").resolveTargetBuilding(store);

        assertSame(warehouse, resolved);
    }

    @Test
    void resolveTargetBuilding_notFound_returnsNull() {
        Store store = storeWithBuildings(new WarehouseBuilding());

        assertNull(buildFulfiller("{\"buildingCode\":\"WH-01\"}").resolveTargetBuilding(store));
    }

    @Test
    void resolveTargetBuilding_blankCode_returnsNull() {
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "WH-01";
        Store store = storeWithBuildings(warehouse);

        assertNull(buildFulfiller("{\"buildingCode\":\"  \"}").resolveTargetBuilding(store));
    }

    @Test
    void handleMissingBuilding_doesNotThrow() {
        Store store = new Store();
        store.code = "S001";

        assertDoesNotThrow(() -> buildFulfiller("{\"buildingCode\":\"WH-01\"}").handleMissingBuilding(store));
    }

    // --------------------------------------------------
    // updateDeliveryDateFromSource
    // --------------------------------------------------

    @Test
    void updateDeliveryDateFromSource_targetHasNoDate_copiesFromSource() {
        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller = buildFulfiller("{\"buildingCode\":\"WH-01\"}");
        FulfillmentChunk source = new FulfillmentChunk();
        source.deliveryDate = LocalDateTime.of(2026, 10, 15, 8, 0);
        FulfillmentChunk target = new FulfillmentChunk();

        fulfiller.updateDeliveryDateFromSource(source, target);

        assertEquals(source.deliveryDate, target.deliveryDate);
    }

    @Test
    void updateDeliveryDateFromSource_sameDate_keepsTarget() {
        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller = buildFulfiller("{\"buildingCode\":\"WH-01\"}");
        LocalDateTime date = LocalDateTime.of(2026, 10, 15, 8, 0);
        FulfillmentChunk source = new FulfillmentChunk();
        source.deliveryDate = date;
        FulfillmentChunk target = new FulfillmentChunk();
        target.deliveryDate = date;

        fulfiller.updateDeliveryDateFromSource(source, target);

        assertEquals(date, target.deliveryDate);
    }

    @Test
    void updateDeliveryDateFromSource_conflict_keepsTargetDate() {
        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller = buildFulfiller("{\"buildingCode\":\"WH-01\"}");
        FulfillmentChunk source = new FulfillmentChunk();
        source.deliveryDate = LocalDateTime.of(2026, 10, 15, 8, 0);
        FulfillmentChunk target = new FulfillmentChunk();
        target.deliveryDate = LocalDateTime.of(2026, 10, 16, 9, 30);

        fulfiller.updateDeliveryDateFromSource(source, target);

        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 30), target.deliveryDate);
    }

    @Test
    void updateDeliveryDateFromSource_sourceHasNoDate_targetStaysNull() {
        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller = buildFulfiller("{\"buildingCode\":\"WH-01\"}");
        FulfillmentChunk source = new FulfillmentChunk();
        FulfillmentChunk target = new FulfillmentChunk();

        fulfiller.updateDeliveryDateFromSource(source, target);

        assertNull(target.deliveryDate);
    }

    @Test
    void updateDeliveryDateFromSource_sourceNullDate_targetKeepsDate() {
        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller = buildFulfiller("{\"buildingCode\":\"WH-01\"}");
        FulfillmentChunk source = new FulfillmentChunk();
        FulfillmentChunk target = new FulfillmentChunk();
        target.deliveryDate = LocalDateTime.of(2026, 10, 16, 9, 30);

        fulfiller.updateDeliveryDateFromSource(source, target);

        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 30), target.deliveryDate);
    }

    // --------------------------------------------------
    // removeChunkIfEmpty / finalizeProcessing
    // --------------------------------------------------

    @Test
    void removeChunkIfEmpty_emptyChunk_isRemoved() {
        Fulfillment fulfillment = new Fulfillment();
        FulfillmentChunk empty = pendingChunk();
        fulfillment.addChunk(empty);

        buildFulfiller("{\"buildingCode\":\"WH-01\"}").removeChunkIfEmpty(fulfillment, empty);

        assertFalse(fulfillment.chunks.contains(empty));
    }

    @Test
    void removeChunkIfEmpty_nonEmptyChunk_isKept() {
        Fulfillment fulfillment = new Fulfillment();
        FulfillmentChunk chunk = pendingChunk(lineFor(productWithEan("123", 1L), 1));
        fulfillment.addChunk(chunk);

        buildFulfiller("{\"buildingCode\":\"WH-01\"}").removeChunkIfEmpty(fulfillment, chunk);

        assertTrue(fulfillment.chunks.contains(chunk));
    }

    @Test
    void finalizeProcessing_linesMoved_persistsAndAddsChunk() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;
        WarehouseBuilding warehouse = new WarehouseBuilding();
        warehouse.buildingCode = "WH-01";
        FulfillmentChunk newChunk = new FulfillmentChunk();

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            buildFulfiller("{\"buildingCode\":\"WH-01\"}").finalizeProcessing(fulfillment, newChunk, 2, warehouse);

            assertTrue(fulfillment.chunks.contains(newChunk));
            verify(session).persist(newChunk);
        }
    }

    @Test
    void finalizeProcessing_noLinesMoved_doesNothing() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;
        FulfillmentChunk newChunk = new FulfillmentChunk();

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            buildFulfiller("{\"buildingCode\":\"WH-01\"}")
                    .finalizeProcessing(fulfillment, newChunk, 0, new WarehouseBuilding());

            assertTrue(fulfillment.chunks.isEmpty());
            verifyNoInteractions(session);
        }
    }

    // --------------------------------------------------
    // isLineInTargetZone
    // --------------------------------------------------

    @Test
    void isLineInTargetZone_productNotLocated_returnsFalse() {
        Product product = productWithEan("123", 1L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01");
        FulfillmentLine line = lineFor(product, 1);

        assertFalse(buildFulfiller("{\"buildingCode\":\"WH-01\"}").isLineInTargetZone(line, warehouse));
    }

    @Test
    void isLineInTargetZone_areaMismatch_returnsFalse() {
        Product product = productWithEan("123", 1L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01", locationFor(product, "FROZEN", "A05"));

        assertFalse(buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}")
                .isLineInTargetZone(lineFor(product, 1), warehouse));
    }

    @Test
    void isLineInTargetZone_aisleMismatch_returnsFalse() {
        Product product = productWithEan("123", 1L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01", locationFor(product, "FRESH", "B02"));

        assertFalse(buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}")
                .isLineInTargetZone(lineFor(product, 1), warehouse));
    }

    @Test
    void isLineInTargetZone_match_setsLineLocationAndReturnsTrue() {
        Product product = productWithEan("123", 1L);
        WarehouseShelfLocation location = locationFor(product, "FRESH", "A05");
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01", location);
        FulfillmentLine line = lineFor(product, 1);

        boolean result = buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}")
                .isLineInTargetZone(line, warehouse);

        assertTrue(result);
        assertSame(location, line.location);
    }

    @Test
    void isLineInTargetZone_noAreaNoAisleConfig_anyLocationInBuildingMatches() {
        Product product = productWithEan("123", 1L);
        WarehouseShelfLocation location = locationFor(product, "FROZEN", "B02");
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01", location);
        FulfillmentLine line = lineFor(product, 1);

        boolean result = buildFulfiller("{\"buildingCode\":\"WH-01\"}").isLineInTargetZone(line, warehouse);

        assertTrue(result);
        assertSame(location, line.location);
    }

    // --------------------------------------------------
    // moveMatchingLines
    // --------------------------------------------------

    @Test
    void moveMatchingLines_movesOnlyMatchingLines() {
        Product matching = productWithEan("111", 1L);
        Product other = productWithEan("222", 2L);
        WarehouseShelfLocation location = locationFor(matching, "FRESH", "A05");
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01", location);
        FulfillmentChunk source = pendingChunk(lineFor(matching, 1), lineFor(other, 2));
        FulfillmentChunk target = new FulfillmentChunk();
        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller =
                buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}");

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            int moved = fulfiller.moveMatchingLines(source, target, warehouse);

            assertEquals(1, moved);
            assertEquals(1, source.lines.size());
            assertSame(other, source.lines.iterator().next().product);
            assertEquals(1, target.lines.size());
            FulfillmentLine newLine = target.lines.iterator().next();
            assertEquals(1, newLine.lineNumber);
            assertSame(matching, newLine.product);
            assertEquals(BigDecimal.valueOf(2.5), newLine.requestedQuantity);
            assertSame(location, newLine.location);
            verify(session).persist(newLine);
            verify(session).flush();
        }
    }

    @Test
    void moveMatchingLines_noMatchingLine_returnsZero() {
        Product product = productWithEan("123", 1L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01");
        FulfillmentChunk source = pendingChunk(lineFor(product, 1));
        FulfillmentChunk target = new FulfillmentChunk();

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            int moved = buildFulfiller("{\"buildingCode\":\"WH-01\"}").moveMatchingLines(source, target, warehouse);

            assertEquals(0, moved);
            assertEquals(1, source.lines.size());
            assertTrue(target.lines.isEmpty());
            verifyNoInteractions(session);
        }
    }

    @Test
    void moveMatchingLines_debugLevelControlsLogging() {
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01");
        ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller =
                spy(buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}"));
        // isLineInTargetZone dereferences line.product: stub it to isolate moveMatchingLines
        doReturn(true).when(fulfiller).isLineInTargetZone(any(FulfillmentLine.class), any(Building.class));

        org.jboss.logmanager.Logger lmLogger =
                org.jboss.logmanager.Logger.getLogger(ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller.class.getName());
        java.util.logging.Level previous = lmLogger.getLevel();
        try {
            try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
                Session session = PanacheTestSupport.mockArcSession(arcMock);

                // Debug disabled: the log block is skipped
                lmLogger.setLevel(org.jboss.logmanager.Level.INFO);
                assertFalse(ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller.LOGGER.isDebugEnabled());
                FulfillmentChunk source1 = pendingChunk(lineFor(null, 7));
                FulfillmentChunk target1 = new FulfillmentChunk();
                assertEquals(1, fulfiller.moveMatchingLines(source1, target1, warehouse));
                assertTrue(source1.lines.isEmpty());

                // Debug enabled: the log block runs, with a null product ("Unknown")
                lmLogger.setLevel(org.jboss.logmanager.Level.DEBUG);
                assertTrue(ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller.LOGGER.isDebugEnabled());
                FulfillmentChunk source2 = pendingChunk(lineFor(null, 8));
                FulfillmentChunk target2 = new FulfillmentChunk();
                assertEquals(1, fulfiller.moveMatchingLines(source2, target2, warehouse));
                assertEquals(1, target2.lines.size());
                assertNull(target2.lines.iterator().next().product);
                verify(session, times(2)).persist(any(FulfillmentLine.class));
                verify(session, times(2)).flush();
            }
        } finally {
            lmLogger.setLevel(previous);
        }
    }

    // --------------------------------------------------
    // process() pipeline
    // --------------------------------------------------

    @Test
    void process_missingStore_throwsIllegalState() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.id = 1L;

        try (MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            ZoneGroupingFulfillerFactory.ZoneGroupingFulfiller fulfiller = buildFulfiller("{\"buildingCode\":\"WH-01\"}");
            assertThrows(IllegalStateException.class, () -> fulfiller.process(fulfillment));
        }
    }

    @Test
    void process_targetBuildingNotFound_abortsWithoutChanges() {
        Product product = productWithEan("123", 1L);
        Store store = storeWithBuildings(new WarehouseBuilding());
        Fulfillment fulfillment = fulfillmentWithStore(store);
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"buildingCode\":\"WH-01\"}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            assertTrue(fulfillment.chunks.contains(pending));
            assertEquals(1, pending.lines.size());
            verifyNoInteractions(session);
        }
    }

    @Test
    void process_noPendingChunks_addsNoChunk() {
        Product product = productWithEan("123", 1L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01", locationFor(product, "FRESH", "A05"));
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(warehouse));
        FulfillmentChunk ready = new FulfillmentChunk();
        ready.type = FulfillmentChunk.READY;
        fulfillment.addChunk(ready);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            assertTrue(fulfillment.chunks.contains(ready));
            verifyNoInteractions(session);
        }
    }

    @Test
    void process_movesMatchingLineToNewChunk() {
        Product product = productWithEan("123", 1L);
        WarehouseShelfLocation location = locationFor(product, "FRESH", "A05");
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01", location);
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(warehouse));
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}").process(fulfillment);

            // The emptied pending chunk is removed from the fulfillment
            assertFalse(fulfillment.chunks.contains(pending));
            // A single new READY chunk was added
            assertEquals(1, fulfillment.chunks.size());
            FulfillmentChunk newChunk = fulfillment.chunks.iterator().next();
            assertEquals(FulfillmentChunk.READY, newChunk.type);
            assertEquals("ZoneGrouping", newChunk.label);
            assertSame(warehouse, newChunk.building);
            assertEquals(pending.deliveryDate, newChunk.deliveryDate);
            // The moved line copies the source data and points to the resolved location
            assertEquals(1, newChunk.lines.size());
            FulfillmentLine newLine = newChunk.lines.iterator().next();
            assertEquals(1, newLine.lineNumber);
            assertSame(product, newLine.product);
            assertEquals(BigDecimal.valueOf(2.5), newLine.requestedQuantity);
            assertSame(location, newLine.location);
            // The new line and the new chunk were persisted, one flush per moved line
            verify(session, times(2)).persist(any());
            verify(session).flush();
        }
    }

    @Test
    void process_nonMatchingLine_staysInSourceChunk() {
        Product product = productWithEan("123", 1L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01");
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(warehouse));
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"buildingCode\":\"WH-01\"}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            assertTrue(fulfillment.chunks.contains(pending));
            assertEquals(1, pending.lines.size());
            verifyNoInteractions(session);
        }
    }

    @Test
    void process_areaMismatch_staysInSourceChunk() {
        Product product = productWithEan("123", 1L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01", locationFor(product, "FROZEN", "A05"));
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(warehouse));
        FulfillmentChunk pending = pendingChunk(lineFor(product, 1));
        fulfillment.addChunk(pending);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            assertTrue(fulfillment.chunks.contains(pending));
            verifyNoInteractions(session);
        }
    }

    @Test
    void process_multiplePendingChunks_mergesIntoOneChunk() {
        Product first = productWithEan("111", 1L);
        Product second = productWithEan("222", 2L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01",
                locationFor(first, "FRESH", "A05"), locationFor(second, "FRESH", "A05"));
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(warehouse));
        FulfillmentChunk chunk1 = pendingChunk(lineFor(first, 1));
        FulfillmentChunk chunk2 = pendingChunk(lineFor(second, 2));
        fulfillment.addChunk(chunk1);
        fulfillment.addChunk(chunk2);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            FulfillmentChunk newChunk = fulfillment.chunks.iterator().next();
            assertEquals(2, newChunk.lines.size());
            // 2 new lines + 1 new chunk persisted, one flush per moved line
            verify(session, times(3)).persist(any());
            verify(session, times(2)).flush();
        }
    }

    @Test
    void process_deliveryDateConflict_keepsFirstChunkDate() {
        Product first = productWithEan("111", 1L);
        Product second = productWithEan("222", 2L);
        WarehouseBuilding warehouse = warehouseWithLocations("WH-01",
                locationFor(first, "FRESH", "A05"), locationFor(second, "FRESH", "A05"));
        Fulfillment fulfillment = fulfillmentWithStore(storeWithBuildings(warehouse));
        FulfillmentChunk chunk1 = pendingChunk(lineFor(first, 1));
        chunk1.deliveryDate = LocalDateTime.of(2026, 10, 15, 8, 0);
        FulfillmentChunk chunk2 = pendingChunk(lineFor(second, 2));
        chunk2.deliveryDate = LocalDateTime.of(2026, 10, 16, 9, 30);
        fulfillment.addChunk(chunk1);
        fulfillment.addChunk(chunk2);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> pm = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);
            pm.when(() -> PanacheEntityBase.findById(any())).thenReturn(fulfillment);

            buildFulfiller("{\"buildingCode\":\"WH-01\",\"area\":\"FRESH\",\"aisle\":\"A05\"}").process(fulfillment);

            assertEquals(1, fulfillment.chunks.size());
            FulfillmentChunk newChunk = fulfillment.chunks.iterator().next();
            // LinkedHashSet keeps insertion order: chunk1 is processed first and sets the date
            assertEquals(LocalDateTime.of(2026, 10, 15, 8, 0), newChunk.deliveryDate);
        }
    }
}
