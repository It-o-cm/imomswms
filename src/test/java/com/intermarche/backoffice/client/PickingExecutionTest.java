package com.intermarche.backoffice.client;

import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.ZoneCollectingOrder;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PickingExecution}.
 * <p>
 * Covers the constructor (PickedItem creation from the collecting order structure),
 * tote management (add/remove), item picking (full move, split, substitution),
 * item moves between totes, item removal with merge, cancellation, execution
 * conclusion and aisle code extraction.
 */
class PickingExecutionTest {

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static Product product(Long id, String ean) {
        Product product = new Product();
        product.id = id;
        product.ean = ean;
        return product;
    }

    private static LotLine lotLine(Long id, Product product, String qty) {
        LotLine line = new LotLine();
        line.id = id;
        line.product = product;
        line.quantity = new BigDecimal(qty);
        return line;
    }

    private static Lot lotWith(LotLine... lines) {
        Lot lot = new Lot();
        lot.lines = new LinkedHashSet<>(List.of(lines));
        return lot;
    }

    private static ZoneCollectingOrder collectingOrderWith(Lot... lots) {
        ZoneCollectingOrder order = new ZoneCollectingOrder();
        order.lots = new LinkedHashSet<>(List.of(lots));
        return order;
    }

    private static PickedItem pickedItem(LotLine lotLine, Product product, String qty) {
        PickedItem item = new PickedItem();
        item.lotLine = lotLine;
        item.product = product;
        item.quantity = new BigDecimal(qty);
        item.status = PickedItem.PickStatus.PENDING;
        return item;
    }

    private static Tote tote(Tote.ToteType type, String code) {
        Tote tote = new Tote();
        tote.toteType = type;
        tote.code = code;
        return tote;
    }

    private static LoadedTote loadedTote(Tote tote) {
        LoadedTote loadedTote = new LoadedTote();
        loadedTote.tote = tote;
        loadedTote.status = LoadedTote.Status.IN_USE;
        return loadedTote;
    }

    private static PickingExecution executionWith(LoadedTote... totes) {
        PickingExecution execution = new PickingExecution();
        execution.loadedTotes = new LinkedHashSet<>(List.of(totes));
        return execution;
    }

    @SuppressWarnings("unchecked")
    private static PanacheQuery<LoadedTote> stubToteAvailability(Session session, LoadedTote existing) {
        PanacheQuery<LoadedTote> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(existing);
        return query;
    }

    // --------------------------------------------------
    // Constructor
    // --------------------------------------------------

    @Test
    void constructor_noLots_createsNoItems() {
        ZoneCollectingOrder order = collectingOrderWith();
        Employee employee = new Employee();

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            PickingExecution execution = new PickingExecution(order, employee);

            assertSame(order, execution.handlingOrder);
            assertSame(employee, execution.employee);
            assertTrue(execution.itemsToPick.isEmpty());
            // No lot lines: the existing-items query is never executed
            panacheMock.verify(() -> PanacheEntityBase.list(anyString(), any(Object[].class)), never());
            verify(session, never()).persist(any());
        }
    }

    @Test
    void constructor_createsPickedItemsForLotLinesWithoutExistingItems() {
        Product product = product(1L, "EAN-1");
        LotLine line1 = lotLine(10L, product, "5");
        LotLine line2 = lotLine(11L, product, "3");
        ZoneCollectingOrder order = collectingOrderWith(lotWith(line1, line2));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            PickingExecution execution = new PickingExecution(order, new Employee());

            assertEquals(2, execution.itemsToPick.size());
            for (PickedItem item : execution.itemsToPick) {
                assertEquals(PickedItem.PickStatus.PENDING, item.status);
                assertSame(product, item.product);
                assertNull(item.loadedTote);
            }
            assertTrue(execution.itemsToPick.stream().anyMatch(i -> i.lotLine == line1 && i.quantity.compareTo(new BigDecimal("5")) == 0));
            assertTrue(execution.itemsToPick.stream().anyMatch(i -> i.lotLine == line2 && i.quantity.compareTo(new BigDecimal("3")) == 0));
            // One persist per created item
            verify(session, times(2)).persist(any(PickedItem.class));
        }
    }

    @Test
    void constructor_skipsLotLinesThatAlreadyHavePickedItems() {
        Product product = product(1L, "EAN-1");
        LotLine line1 = lotLine(10L, product, "5");
        LotLine line2 = lotLine(11L, product, "3");
        ZoneCollectingOrder order = collectingOrderWith(lotWith(line1, line2));
        // An item already exists for line1
        PickedItem existing = pickedItem(line1, product, "5");

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of(existing));

            PickingExecution execution = new PickingExecution(order, new Employee());

            // Only line2 gets a new item
            assertEquals(1, execution.itemsToPick.size());
            PickedItem created = execution.itemsToPick.iterator().next();
            assertSame(line2, created.lotLine);
            verify(session).persist(created);
        }
    }

    // --------------------------------------------------
    // addTote
    // --------------------------------------------------

    @Test
    void addTote_nullTote_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();

        assertThrows(IllegalArgumentException.class, () -> execution.addTote(null, TemperatureZone.AMBIENT));
    }

    @Test
    void addTote_usageNotAllowedForToteType_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();
        Tote standardTote = tote(Tote.ToteType.STANDARD, "T-1");

        // STANDARD totes do not accept CHILLED
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.addTote(standardTote, TemperatureZone.CHILLED));
        assertTrue(ex.getMessage().contains("CHILLED"));
        assertTrue(ex.getMessage().contains("STANDARD"));
    }

    @Test
    void addTote_validUsage_createsAndPersistsLoadedTote() {
        PickingExecution execution = new PickingExecution();
        Tote standardTote = tote(Tote.ToteType.STANDARD, "T-1");

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            PanacheQuery<LoadedTote> query = stubToteAvailability(session, null);
            panacheMock.when(() -> PanacheEntityBase.find(eq("tote = ?1 and status = ?2"), any(Object[].class)))
                    .thenReturn(query);

            LoadedTote loadedTote = execution.addTote(standardTote, TemperatureZone.AMBIENT);

            assertSame(standardTote, loadedTote.tote);
            assertEquals(TemperatureZone.AMBIENT, loadedTote.storageType);
            assertEquals(LoadedTote.Status.IN_USE, loadedTote.status);
            assertTrue(execution.loadedTotes.contains(loadedTote));
            verify(session).persist(loadedTote);
        }
    }

    @Test
    void addTote_toteAlreadyInUse_throwsIllegalState() {
        PickingExecution execution = new PickingExecution();
        Tote tote = tote(Tote.ToteType.INSULATED, "T-2");
        LoadedTote existing = loadedTote(tote);
        existing.id = 99L;

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            PanacheQuery<LoadedTote> query = stubToteAvailability(session, existing);
            panacheMock.when(() -> PanacheEntityBase.find(eq("tote = ?1 and status = ?2"), any(Object[].class)))
                    .thenReturn(query);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> execution.addTote(tote, TemperatureZone.FROZEN));
            assertTrue(ex.getMessage().contains("T-2"));
            verify(session, never()).persist(any());
        }
    }

    // --------------------------------------------------
    // removeEmptyTote
    // --------------------------------------------------

    @Test
    void removeEmptyTote_nullTote_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();

        assertThrows(IllegalArgumentException.class, () -> execution.removeEmptyTote(null));
    }

    @Test
    void removeEmptyTote_toteNotInExecution_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.removeEmptyTote(tote(Tote.ToteType.STANDARD, "T-9")));
        assertTrue(ex.getMessage().contains("does not belong"));
    }

    @Test
    void removeEmptyTote_toteWithItems_throwsIllegalArgument() {
        Tote tote = tote(Tote.ToteType.STANDARD, "T-1");
        LoadedTote loadedTote = loadedTote(tote);
        loadedTote.items.add(pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1"));
        PickingExecution execution = executionWith(loadedTote);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.removeEmptyTote(tote));
        assertTrue(ex.getMessage().contains("contains items"));
        assertTrue(execution.loadedTotes.contains(loadedTote));
    }

    @Test
    void removeEmptyTote_emptyTote_isRemoved() {
        Tote tote = tote(Tote.ToteType.STANDARD, "T-1");
        LoadedTote loadedTote = loadedTote(tote);
        PickingExecution execution = executionWith(loadedTote);

        execution.removeEmptyTote(tote);

        assertFalse(execution.loadedTotes.contains(loadedTote));
    }

    @Test
    void removeEmptyTote_toteWithNullItems_isRemoved() {
        Tote tote = tote(Tote.ToteType.STANDARD, "T-1");
        LoadedTote loadedTote = loadedTote(tote);
        loadedTote.items = null;
        PickingExecution execution = executionWith(loadedTote);

        execution.removeEmptyTote(tote);

        assertFalse(execution.loadedTotes.contains(loadedTote));
    }

    // --------------------------------------------------
    // addItemToTote
    // --------------------------------------------------

    @Test
    void addItemToTote_nullArguments_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        assertThrows(IllegalArgumentException.class, () -> execution.addItemToTote(null, item, null, null));
        assertThrows(IllegalArgumentException.class, () -> execution.addItemToTote(loadedTote(tote(Tote.ToteType.STANDARD, "T")), null, null, null));
    }

    @Test
    void addItemToTote_toteNotInExecution_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");
        execution.itemsToPick.add(item);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.addItemToTote(loadedTote(tote(Tote.ToteType.STANDARD, "T")), item, null, null));
        assertTrue(ex.getMessage().contains("does not belong"));
    }

    @Test
    void addItemToTote_itemNotInPickList_throwsIllegalArgument() {
        LoadedTote loadedTote = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        PickingExecution execution = executionWith(loadedTote);
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.addItemToTote(loadedTote, item, null, null));
        assertTrue(ex.getMessage().contains("not in the pending list"));
    }

    @Test
    void addItemToTote_fullQuantity_movesWholeItemKeepingOriginalProduct() {
        Product product = product(1L, "EAN-1");
        LotLine line = lotLine(1L, product, "5");
        PickedItem item = pickedItem(line, product, "5");
        LoadedTote loadedTote = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        PickingExecution execution = executionWith(loadedTote);
        execution.itemsToPick.add(item);

        // Null quantity: the full quantity is moved; null product: the original product is kept
        execution.addItemToTote(loadedTote, item, null, null);

        assertFalse(execution.itemsToPick.contains(item));
        assertTrue(loadedTote.items.contains(item));
        assertSame(loadedTote, item.loadedTote);
        assertSame(product, item.product);
        assertEquals(PickedItem.PickStatus.PICKED, item.status);
        assertEquals(0, item.quantity.compareTo(new BigDecimal("5")));
    }

    @Test
    void addItemToTote_toteWithNullItems_initializesItemsSet() {
        Product product = product(1L, "EAN-1");
        LotLine line = lotLine(1L, product, "5");
        PickedItem item = pickedItem(line, product, "5");
        LoadedTote loadedTote = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        loadedTote.items = null;
        PickingExecution execution = executionWith(loadedTote);
        execution.itemsToPick.add(item);

        execution.addItemToTote(loadedTote, item, null, null);

        assertNotNull(loadedTote.items);
        assertTrue(loadedTote.items.contains(item));
        assertSame(loadedTote, item.loadedTote);
    }

    @Test
    void addItemToTote_partialQuantity_splitsItemWithNewProduct() {
        Product original = product(1L, "EAN-1");
        Product substitute = product(2L, "EAN-2");
        LotLine line = lotLine(1L, original, "5");
        PickedItem item = pickedItem(line, original, "5");
        LoadedTote loadedTote = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        PickingExecution execution = executionWith(loadedTote);
        execution.itemsToPick.add(item);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            execution.addItemToTote(loadedTote, item, new BigDecimal("2"), substitute);

            // The original item keeps the remainder in the work list
            assertTrue(execution.itemsToPick.contains(item));
            assertEquals(0, item.quantity.compareTo(new BigDecimal("3")));
            assertSame(original, item.product);
            assertEquals(PickedItem.PickStatus.PENDING, item.status);

            // A new item holds the moved quantity in the tote
            assertEquals(1, loadedTote.items.size());
            PickedItem moved = loadedTote.items.iterator().next();
            assertNotSame(item, moved);
            assertSame(line, moved.lotLine);
            assertSame(substitute, moved.product);
            assertEquals(0, moved.quantity.compareTo(new BigDecimal("2")));
            assertEquals(PickedItem.PickStatus.PICKED, moved.status);
            assertSame(loadedTote, moved.loadedTote);
            verify(session).persist(moved);
        }
    }

    @Test
    void addItemToTote_quantityExceedsItemQuantity_throwsIllegalArgument() {
        Product product = product(1L, "EAN-1");
        PickedItem item = pickedItem(lotLine(1L, product, "5"), product, "5");
        LoadedTote loadedTote = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        PickingExecution execution = executionWith(loadedTote);
        execution.itemsToPick.add(item);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.addItemToTote(loadedTote, item, new BigDecimal("6"), null));
        assertTrue(ex.getMessage().contains("cannot exceed"));
        // The item is untouched
        assertTrue(execution.itemsToPick.contains(item));
        assertTrue(loadedTote.items.isEmpty());
    }

    // --------------------------------------------------
    // moveItemToTote
    // --------------------------------------------------

    @Test
    void moveItemToTote_nullArguments_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        assertThrows(IllegalArgumentException.class, () -> execution.moveItemToTote(null, null, item));
        assertThrows(IllegalArgumentException.class,
                () -> execution.moveItemToTote(null, loadedTote(tote(Tote.ToteType.STANDARD, "T")), null));
    }

    @Test
    void moveItemToTote_targetNotInExecution_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.moveItemToTote(null, loadedTote(tote(Tote.ToteType.STANDARD, "T")), item));
        assertTrue(ex.getMessage().contains("target tote does not belong"));
    }

    @Test
    void moveItemToTote_itemNotLinkedToOrder_throwsIllegalArgument() {
        Product product = product(1L, "EAN-1");
        LotLine line = lotLine(1L, product, "1");
        // The execution is not linked to any collecting order
        PickingExecution execution = executionWith(loadedTote(tote(Tote.ToteType.STANDARD, "T-1")));
        PickedItem item = pickedItem(line, product, "1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.moveItemToTote(null, execution.loadedTotes.iterator().next(), item));
        assertTrue(ex.getMessage().contains("not linked to a LotLine"));
    }

    @Test
    void moveItemToTote_valid_movesItemFromSourceToTarget() {
        Product product = product(1L, "EAN-1");
        LotLine line = lotLine(1L, product, "1");
        ZoneCollectingOrder order = collectingOrderWith(lotWith(line));
        PickedItem item = pickedItem(line, product, "1");
        LoadedTote source = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        source.items.add(item);
        item.loadedTote = source;
        LoadedTote target = loadedTote(tote(Tote.ToteType.STANDARD, "T-2"));
        PickingExecution execution = executionWith(source, target);
        execution.handlingOrder = order;

        execution.moveItemToTote(source, target, item);

        assertFalse(source.items.contains(item));
        assertTrue(target.items.contains(item));
        assertSame(target, item.loadedTote);
    }

    @Test
    void moveItemToTote_nullSourceTote_movesItemToTarget() {
        Product product = product(1L, "EAN-1");
        LotLine line = lotLine(1L, product, "1");
        ZoneCollectingOrder order = collectingOrderWith(lotWith(line));
        PickedItem item = pickedItem(line, product, "1");
        LoadedTote target = loadedTote(tote(Tote.ToteType.STANDARD, "T-2"));
        PickingExecution execution = executionWith(target);
        execution.handlingOrder = order;

        // Null source: the item is simply linked to the target tote
        execution.moveItemToTote(null, target, item);

        assertTrue(target.items.contains(item));
        assertSame(target, item.loadedTote);
    }

    @Test
    void moveItemToTote_sourceToteWithNullItems_skipsRemoval() {
        Product product = product(1L, "EAN-1");
        LotLine line = lotLine(1L, product, "1");
        ZoneCollectingOrder order = collectingOrderWith(lotWith(line));
        PickedItem item = pickedItem(line, product, "1");
        LoadedTote source = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        source.items = null;
        LoadedTote target = loadedTote(tote(Tote.ToteType.STANDARD, "T-2"));
        PickingExecution execution = executionWith(target);
        execution.handlingOrder = order;

        // Null items set on the source: no removal attempted, no NPE
        execution.moveItemToTote(source, target, item);

        assertTrue(target.items.contains(item));
        assertSame(target, item.loadedTote);
    }

    // --------------------------------------------------
    // removeItemFromTote
    // --------------------------------------------------

    @Test
    void removeItemFromTote_nullArguments_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        assertThrows(IllegalArgumentException.class, () -> execution.removeItemFromTote(null, item));
        assertThrows(IllegalArgumentException.class,
                () -> execution.removeItemFromTote(loadedTote(tote(Tote.ToteType.STANDARD, "T")), null));
    }

    @Test
    void removeItemFromTote_toteNotInExecution_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.removeItemFromTote(loadedTote(tote(Tote.ToteType.STANDARD, "T")), item));
        assertTrue(ex.getMessage().contains("does not belong"));
    }

    @Test
    void removeItemFromTote_itemNotInTote_throwsIllegalArgument() {
        LoadedTote loadedTote = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        PickingExecution execution = executionWith(loadedTote);
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.removeItemFromTote(loadedTote, item));
        assertTrue(ex.getMessage().contains("not in the tote"));
    }

    @Test
    void removeItemFromTote_noExistingItemForLotLine_addsBackToWorkList() {
        Product original = product(1L, "EAN-1");
        Product substitute = product(2L, "EAN-2");
        LotLine line = lotLine(1L, original, "2");
        // The item was picked with a substitute product
        PickedItem item = pickedItem(line, substitute, "2");
        item.status = PickedItem.PickStatus.PICKED;
        LoadedTote loadedTote = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        loadedTote.items.add(item);
        item.loadedTote = loadedTote;
        PickingExecution execution = executionWith(loadedTote);

        execution.removeItemFromTote(loadedTote, item);

        assertFalse(loadedTote.items.contains(item));
        assertNull(item.loadedTote);
        // The product is reset to the original LotLine product and the status back to PENDING
        assertSame(original, item.product);
        assertEquals(PickedItem.PickStatus.PENDING, item.status);
        assertTrue(execution.itemsToPick.contains(item));
    }

    @Test
    void removeItemFromTote_existingItemForSameLotLine_mergesQuantitiesAndDeletes() {
        Product product = product(1L, "EAN-1");
        LotLine line = lotLine(1L, product, "5");
        PickedItem inTote = pickedItem(line, product, "2");
        inTote.status = PickedItem.PickStatus.PICKED;
        LoadedTote loadedTote = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        loadedTote.items.add(inTote);
        inTote.loadedTote = loadedTote;
        // An item for the same LotLine already waits in the work list
        PickedItem inWorkList = pickedItem(line, product, "3");
        PickingExecution execution = executionWith(loadedTote);
        execution.itemsToPick.add(inWorkList);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            // delete() removes the entity directly when the session already contains it
            when(session.contains(inTote)).thenReturn(true);

            execution.removeItemFromTote(loadedTote, inTote);

            // Quantities merged into the existing work-list item
            assertEquals(0, inWorkList.quantity.compareTo(new BigDecimal("5")));
            assertEquals(1, execution.itemsToPick.size());
            // The removed item is deleted from the database
            verify(session).remove(inTote);
        }
    }

    // --------------------------------------------------
    // cancelItem
    // --------------------------------------------------

    @Test
    void cancelItem_null_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();

        assertThrows(IllegalArgumentException.class, () -> execution.cancelItem(null));
    }

    @Test
    void cancelItem_notInPickList_throwsIllegalArgument() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> execution.cancelItem(item));
        assertTrue(ex.getMessage().contains("cannot be cancelled"));
    }

    @Test
    void cancelItem_inPickList_setsStatusCanceled() {
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");
        PickingExecution execution = new PickingExecution();
        execution.itemsToPick.add(item);

        execution.cancelItem(item);

        assertEquals(PickedItem.PickStatus.CANCELED, item.status);
        assertTrue(execution.itemsToPick.contains(item));
    }

    // --------------------------------------------------
    // concludeExecution
    // --------------------------------------------------

    @Test
    void concludeExecution_pendingItems_throwsIllegalState() {
        PickedItem pending = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");
        PickingExecution execution = new PickingExecution();
        execution.itemsToPick.add(pending);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> execution.concludeExecution(new Buffer()));
        assertTrue(ex.getMessage().contains("still pending"));
        assertEquals(HandlingExecution.ExecutionStatus.IN_PROGRESS, execution.status);
    }

    @Test
    void concludeExecution_noPendingItems_assignsBufferAndSetsDone() {
        PickedItem canceled = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");
        canceled.status = PickedItem.PickStatus.CANCELED;
        LoadedTote tote1 = loadedTote(tote(Tote.ToteType.STANDARD, "T-1"));
        LoadedTote tote2 = loadedTote(tote(Tote.ToteType.INSULATED, "T-2"));
        PickingExecution execution = executionWith(tote1, tote2);
        execution.itemsToPick.add(canceled);
        Buffer buffer = new Buffer();

        execution.concludeExecution(buffer);

        assertSame(buffer, tote1.buffer);
        assertSame(buffer, tote2.buffer);
        assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
    }

    // --------------------------------------------------
    // getAisleCodesFromItemsToPick
    // --------------------------------------------------

    @Test
    void getAisleCodesFromItemsToPick_returnsUniqueAislesOfShelfLocations() {
        Product product = product(1L, "EAN-1");
        PickedItem item1 = itemAtAisle(product, "A05");
        PickedItem item2 = itemAtAisle(product, "A05");
        PickedItem item3 = itemAtAisle(product, "B02");
        // Item with a null aisle: filtered out
        PickedItem itemNullAisle = itemAtAisle(product, null);
        // Item with a non-shelf location: filtered out
        PickedItem itemNonShelf = pickedItem(lotLine(4L, product, "1"), product, "1");
        FulfillmentLine fl = new FulfillmentLine();
        fl.location = mock(BaseLocation.class);
        itemNonShelf.lotLine.fulfillmentLine = fl;

        PickingExecution execution = new PickingExecution();
        execution.itemsToPick = new LinkedHashSet<>(List.of(item1, item2, item3, itemNullAisle, itemNonShelf));

        Set<String> aisles = execution.getAisleCodesFromItemsToPick();

        assertEquals(Set.of("A05", "B02"), aisles);
    }

    @Test
    void getAisleCodesFromItemsToPick_emptyList_returnsEmptySet() {
        PickingExecution execution = new PickingExecution();

        assertTrue(execution.getAisleCodesFromItemsToPick().isEmpty());
    }

    private static PickedItem itemAtAisle(Product product, String aisle) {
        LotLine line = lotLine(1L, product, "1");
        StoreShelfLocation location = new StoreShelfLocation();
        location.aisle = aisle;
        FulfillmentLine fl = new FulfillmentLine();
        fl.location = location;
        line.fulfillmentLine = fl;
        return pickedItem(line, product, "1");
    }

    // --------------------------------------------------
    // addItemToPick / deleteItemToPick
    // --------------------------------------------------

    @Test
    void addItemToPick_addsItemToWorkList() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");

        execution.addItemToPick(item);

        assertTrue(execution.itemsToPick.contains(item));
    }

    @Test
    void deleteItemToPick_removesItemAndReturnsPresence() {
        PickingExecution execution = new PickingExecution();
        PickedItem item = pickedItem(lotLine(1L, product(1L, "EAN-1"), "1"), product(1L, "EAN-1"), "1");
        execution.itemsToPick.add(item);

        assertTrue(execution.deleteItemToPick(item));
        assertFalse(execution.itemsToPick.contains(item));
        assertFalse(execution.deleteItemToPick(item));
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_changesWhenTotesAreAdded() {
        PickingExecution execution = new PickingExecution();
        int checksum = execution.getChecksum();

        execution.loadedTotes.add(loadedTote(tote(Tote.ToteType.STANDARD, "T-1")));

        assertNotEquals(checksum, execution.getChecksum());
    }
}
