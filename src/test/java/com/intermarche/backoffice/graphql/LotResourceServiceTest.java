package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LotResourceService}.
 * Covers the input validation and the pure (non-database) paths of the
 * lot / lot-line synchronization logic.
 */
class LotResourceServiceTest {

    private final LotResourceService service = new LotResourceService();

    private PackingOrder orderWithLot(Long lotId) {
        PackingOrder order = new PackingOrder();
        order.id = 1L;
        Lot lot = new Lot();
        lot.id = lotId;
        lot.status = Lot.LotStatus.PENDING;
        order.lots.add(lot);
        return order;
    }

    private LotResourceService.LotUpdateRecord lotUpdate(Long id, Lot.LotStatus status) {
        var record = new LotResourceService.LotUpdateRecord();
        record.id = id;
        record.status = status;
        return record;
    }

    // --------------------------------------------------
    // synchronizeLots - guards
    // --------------------------------------------------

    @Test
    void synchronizeLots_nullInput_noOp() {
        PackingOrder order = orderWithLot(1L);

        service.synchronizeLots(order, null);

        assertEquals(Lot.LotStatus.PENDING, order.lots.iterator().next().status);
    }

    @Test
    void synchronizeLots_emptyInput_noOp() {
        PackingOrder order = orderWithLot(1L);

        service.synchronizeLots(order, List.of());

        assertEquals(Lot.LotStatus.PENDING, order.lots.iterator().next().status);
    }

    @Test
    void synchronizeLots_nullLotId_throwsIAE() {
        PackingOrder order = orderWithLot(1L);

        assertThrows(IllegalArgumentException.class,
                () -> service.synchronizeLots(order, List.of(lotUpdate(null, null))));
    }

    @Test
    void synchronizeLots_unknownLotId_throwsNoSuchElement() {
        PackingOrder order = orderWithLot(1L);

        NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                () -> service.synchronizeLots(order, List.of(lotUpdate(999L, null))));

        assertTrue(ex.getMessage().contains("999"));
    }

    // --------------------------------------------------
    // synchronizeLots - status update
    // --------------------------------------------------

    @Test
    void synchronizeLots_updatesStatus() {
        PackingOrder order = orderWithLot(1L);

        service.synchronizeLots(order, List.of(lotUpdate(1L, Lot.LotStatus.COLLECTED)));

        assertEquals(Lot.LotStatus.COLLECTED, order.lots.iterator().next().status);
    }

    @Test
    void synchronizeLots_nullStatus_keepsCurrentStatus() {
        PackingOrder order = orderWithLot(1L);

        service.synchronizeLots(order, List.of(lotUpdate(1L, null)));

        assertEquals(Lot.LotStatus.PENDING, order.lots.iterator().next().status);
    }

    @Test
    void synchronizeLots_withLines_delegatesToLineSync() {
        PackingOrder order = orderWithLot(1L);
        // The lot has no lines: any line id is unknown -> NoSuchElementException from the delegation
        var lineRecord = new LotResourceService.LotLineRecord();
        lineRecord.id = 999L;
        var lotRecord = lotUpdate(1L, null);
        lotRecord.lines = List.of(lineRecord);

        assertThrows(NoSuchElementException.class,
                () -> service.synchronizeLots(order, List.of(lotRecord)));
    }

    @Test
    void synchronizeLots_emptyLines_doesNotDelegate() {
        PackingOrder order = orderWithLot(1L);
        var lotRecord = lotUpdate(1L, Lot.LotStatus.PACKED);
        lotRecord.lines = List.of();

        service.synchronizeLots(order, List.of(lotRecord));

        assertEquals(Lot.LotStatus.PACKED, order.lots.iterator().next().status);
    }

    @Test
    void synchronizeLots_lotWithNullId_isSkippedInFilter() {
        PackingOrder order = orderWithLot(1L);
        Lot matchingLot = order.lots.iterator().next();
        Lot nullIdLot = new Lot();
        nullIdLot.id = null;
        nullIdLot.status = Lot.LotStatus.PENDING;
        // LinkedHashSet so the null-id lot is evaluated first by the filter
        // (findFirst short-circuits once the matching lot is found)
        order.lots = new LinkedHashSet<>(List.of(nullIdLot, matchingLot));

        service.synchronizeLots(order, List.of(lotUpdate(1L, Lot.LotStatus.COLLECTED)));

        assertEquals(Lot.LotStatus.COLLECTED, matchingLot.status);
        assertEquals(Lot.LotStatus.PENDING, nullIdLot.status);
    }

    @Test
    void synchronizeLots_withLines_delegationCompletes() {
        PackingOrder order = orderWithLot(1L);
        Lot lot = order.lots.iterator().next();
        LotLine existing = lineWithId(1L);
        lot.lines.add(existing);
        StoreShelfLocation location = new StoreShelfLocation();

        var lineRecord = new LotResourceService.LotLineRecord();
        lineRecord.id = 1L;
        lineRecord.quantity = new BigDecimal("7");
        lineRecord.locationId = 10L;
        var lotRecord = lotUpdate(1L, null);
        lotRecord.lines = List.of(lineRecord);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class)) {
            stubFindById(panacheMock, Map.of(10L, location));

            service.synchronizeLots(order, List.of(lotRecord));

            assertEquals(new BigDecimal("7"), existing.quantity);
            assertSame(location, existing.location);
        }
    }

    // --------------------------------------------------
    // synchronizeLotLines - pure paths
    // --------------------------------------------------

    @Test
    void synchronizeLotLines_unknownLineId_throwsNoSuchElement() {
        Lot lot = new Lot();
        lot.id = 5L;
        // lot.lines is empty: no database access is required to reach the failure
        var lineRecord = new LotResourceService.LotLineRecord();
        lineRecord.id = 999L;

        NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                () -> service.synchronizeLotLines(lot, List.of(lineRecord)));

        assertTrue(ex.getMessage().contains("999"));
    }

    @Test
    void synchronizeLotLines_emptyLotLines_removesNothingAndThrowsOnUnknownId() {
        Lot lot = new Lot();
        lot.id = 5L;
        var lineRecord = new LotResourceService.LotLineRecord();
        lineRecord.id = 1L;

        assertThrows(NoSuchElementException.class,
                () -> service.synchronizeLotLines(lot, List.of(lineRecord)));

        assertTrue(lot.lines.isEmpty());
    }

    // --------------------------------------------------
    // Input records
    // --------------------------------------------------

    @Test
    void lotUpdateRecord_holdsValues() {
        var record = new LotResourceService.LotUpdateRecord();
        record.id = 1L;
        record.status = Lot.LotStatus.PLANNED_TO_BE_COLLECTED;
        var line = new LotResourceService.LotLineRecord();
        record.lines = List.of(line);

        assertEquals(1L, record.id);
        assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, record.status);
        assertEquals(1, record.lines.size());
    }

    @Test
    void lotLineRecord_holdsValues() {
        var record = new LotResourceService.LotLineRecord();
        record.id = 1L;
        record.quantity = new BigDecimal("3.5");
        record.productId = 2L;
        record.fulfillmentLineId = 3L;
        record.locationId = 4L;

        assertEquals(1L, record.id);
        assertEquals(new BigDecimal("3.5"), record.quantity);
        assertEquals(2L, record.productId);
        assertEquals(3L, record.fulfillmentLineId);
        assertEquals(4L, record.locationId);
    }

    // --------------------------------------------------
    // synchronizeLotLines - full flow (mocked statics)
    // --------------------------------------------------

    @Test
    void synchronizeLotLines_removesOrphanLines() {
        Lot lot = lotWithStore();
        LotLine kept = lineWithId(1L);
        LotLine orphan = lineWithId(2L);
        lot.lines.add(kept);
        lot.lines.add(orphan);
        StoreShelfLocation location = new StoreShelfLocation();

        var record = new LotResourceService.LotLineRecord();
        record.id = 1L;
        record.locationId = 10L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(10L, location));

            service.synchronizeLotLines(lot, List.of(record));

            assertEquals(1, lot.lines.size());
            assertTrue(lot.lines.contains(kept));
            assertSame(location, kept.location);
        }
    }

    @Test
    void synchronizeLotLines_updatesExistingLineQuantityAndLocation() {
        Lot lot = lotWithStore();
        LotLine existing = lineWithId(1L);
        lot.lines.add(existing);
        StoreShelfLocation location = new StoreShelfLocation();

        var record = new LotResourceService.LotLineRecord();
        record.id = 1L;
        record.quantity = new BigDecimal("5");
        record.locationId = 10L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(10L, location));

            service.synchronizeLotLines(lot, List.of(record));

            assertEquals(new BigDecimal("5"), existing.quantity);
            assertSame(location, existing.location);
        }
    }

    @Test
    void synchronizeLotLines_createsNewLineWhenIdIsNull() {
        Lot lot = lotWithStore();
        Product product = new Product();
        product.id = 2L;
        product.ean = "EAN-1";
        StoreShelfLocation location = new StoreShelfLocation();

        var record = new LotResourceService.LotLineRecord();
        record.id = null;
        record.productId = 2L;
        record.locationId = 10L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(2L, product, 10L, location));

            service.synchronizeLotLines(lot, List.of(record));

            assertEquals(1, lot.lines.size());
            LotLine created = lot.lines.iterator().next();
            assertSame(product, created.product);
            assertSame(location, created.location);
            assertEquals(BigDecimal.ZERO, created.quantity);
        }
    }

    @Test
    void synchronizeLotLines_productNotFound_throwsNoSuchElement() {
        Lot lot = lotWithStore();
        StoreShelfLocation location = new StoreShelfLocation();

        var record = new LotResourceService.LotLineRecord();
        record.id = null;
        record.productId = 99L;
        record.locationId = 10L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(10L, location));

            NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                    () -> service.synchronizeLotLines(lot, List.of(record)));
            assertTrue(ex.getMessage().contains("Product not found"));
        }
    }

    @Test
    void synchronizeLotLines_fulfillmentLineNotFound_throwsNoSuchElement() {
        Lot lot = lotWithStore();
        Product product = new Product();
        product.id = 2L;
        StoreShelfLocation location = new StoreShelfLocation();

        var record = new LotResourceService.LotLineRecord();
        record.id = null;
        record.productId = 2L;
        record.fulfillmentLineId = 99L;
        record.locationId = 10L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(2L, product, 10L, location));

            NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                    () -> service.synchronizeLotLines(lot, List.of(record)));
            assertTrue(ex.getMessage().contains("FulfillmentLine not found"));
        }
    }

    @Test
    void synchronizeLotLines_fulfillmentLineFound_assignsIt() {
        Lot lot = lotWithStore();
        Product product = new Product();
        product.id = 2L;
        FulfillmentLine fulfillmentLine = new FulfillmentLine();
        fulfillmentLine.id = 99L;
        StoreShelfLocation location = new StoreShelfLocation();

        var record = new LotResourceService.LotLineRecord();
        record.id = null;
        record.productId = 2L;
        record.fulfillmentLineId = 99L;
        record.locationId = 10L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(2L, product, 99L, fulfillmentLine, 10L, location));

            service.synchronizeLotLines(lot, List.of(record));

            LotLine created = lot.lines.iterator().next();
            assertSame(fulfillmentLine, created.fulfillmentLine);
            assertSame(product, created.product);
            assertSame(location, created.location);
        }
    }

    @Test
    void synchronizeLotLines_locationIdProvided_usesItWithoutFallback() {
        Lot lot = lotWithStore();
        Product product = new Product();
        product.id = 2L;
        StoreShelfLocation location = new StoreShelfLocation();

        var record = new LotResourceService.LotLineRecord();
        record.id = null;
        record.productId = 2L;
        record.locationId = 10L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(2L, product, 10L, location));

            service.synchronizeLotLines(lot, List.of(record));

            LotLine created = lot.lines.iterator().next();
            assertSame(location, created.location);
            shelfLocationMock.verifyNoInteractions();
        }
    }

    @Test
    void synchronizeLotLines_fallbackLocationFound_assignsClosestLocation() {
        Lot lot = lotWithStore();
        Product product = new Product();
        product.id = 2L;
        StoreShelfLocation closest = new StoreShelfLocation();
        closest.area = "EPICERIE";
        closest.aisle = "A05";

        var record = new LotResourceService.LotLineRecord();
        record.id = null;
        record.productId = 2L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(2L, product));
            baseLocationMock.when(() -> BaseLocation.findCommonSpecification(any()))
                    .thenReturn(new BaseLocation.LocationSpecification(lot.store, lot.chunk.building, "EPICERIE", "A05"));
            shelfLocationMock.when(() -> ShelfLocation.findClosestForProduct(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(closest));

            service.synchronizeLotLines(lot, List.of(record));

            LotLine created = lot.lines.iterator().next();
            assertSame(closest, created.location);
            shelfLocationMock.verify(() -> ShelfLocation.findClosestForProduct(
                    eq(lot.store), eq(product), eq(lot.chunk.building), eq("EPICERIE"), eq("A05")));
        }
    }

    @Test
    void synchronizeLotLines_fallbackLocationNotFound_throwsNoSuchElement() {
        Lot lot = lotWithStore();
        Product product = new Product();
        product.id = 2L;

        var record = new LotResourceService.LotLineRecord();
        record.id = null;
        record.productId = 2L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(2L, product));
            shelfLocationMock.when(() -> ShelfLocation.findClosestForProduct(any(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                    () -> service.synchronizeLotLines(lot, List.of(record)));
            assertTrue(ex.getMessage().contains("No fallback location found"));
        }
    }

    @Test
    void synchronizeLotLines_emptyLotLines_passesNullAreaAndAisleToFallback() {
        Lot lot = lotWithStore();
        Product product = new Product();
        product.id = 2L;
        StoreShelfLocation closest = new StoreShelfLocation();

        var record = new LotResourceService.LotLineRecord();
        record.id = null;
        record.productId = 2L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<BaseLocation> baseLocationMock = mockStatic(BaseLocation.class);
             MockedStatic<ShelfLocation> shelfLocationMock = mockStatic(ShelfLocation.class)) {
            stubFindById(panacheMock, Map.of(2L, product));
            // Default mockStatic answer: findCommonSpecification returns null -> area/aisle stay null
            shelfLocationMock.when(() -> ShelfLocation.findClosestForProduct(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(closest));

            service.synchronizeLotLines(lot, List.of(record));

            shelfLocationMock.verify(() -> ShelfLocation.findClosestForProduct(
                    eq(lot.store), eq(product), eq(lot.chunk.building), isNull(), isNull()));
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static Lot lotWithStore() {
        Lot lot = new Lot();
        lot.id = 5L;
        lot.status = Lot.LotStatus.PENDING;
        Store store = new Store();
        store.id = 1L;
        store.code = "S001";
        lot.store = store;
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.id = 3L;
        StoreBuilding building = new StoreBuilding();
        building.id = 10L;
        building.buildingCode = "BLD-10";
        chunk.building = building;
        lot.chunk = chunk;
        return lot;
    }

    private static LotLine lineWithId(Long id) {
        LotLine line = new LotLine();
        line.id = id;
        return line;
    }

    private static void stubFindById(MockedStatic<PanacheEntityBase> panacheMock, Map<Long, Object> entitiesById) {
        Map<Long, Object> entities = new HashMap<>(entitiesById);
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> entities.get(invocation.getArgument(0)));
    }
}
