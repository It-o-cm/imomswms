package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link GroupingExecution} business logic.
 * Focuses on the guard clauses of {@code moveToteToDestination} / {@code concludeExecution}
 * and on the integrity validation performed by the constructor.
 */
public class GroupingExecutionTest {

    // --------------------------------------------------
    // moveToteToDestination guard clauses
    // --------------------------------------------------

    @Test
    void moveToteToDestination_shouldThrowIllegalArgument_whenToteIsNull() {
        GroupingExecution execution = new GroupingExecution();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> execution.moveToteToDestination(null));
        assertTrue(ex.getMessage().contains("Physical Tote cannot be null"));
    }

    @Test
    void moveToteToDestination_shouldThrowIllegalState_whenNoHandlingOrder() {
        GroupingExecution execution = new GroupingExecution();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> execution.moveToteToDestination(new Tote()));
        assertTrue(ex.getMessage().contains("No GroupingOrder associated"));
    }

    @Test
    void moveToteToDestination_shouldThrowIllegalState_whenHandlingOrderIsNotGroupingOrder() {
        GroupingExecution execution = new GroupingExecution();
        execution.handlingOrder = mock(BaseHandlingOrder.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> execution.moveToteToDestination(new Tote()));
        assertTrue(ex.getMessage().contains("No GroupingOrder associated"));
    }

    // --------------------------------------------------
    // concludeExecution guard clauses
    // --------------------------------------------------

    @Test
    void concludeExecution_shouldThrowIllegalState_whenNoHandlingOrder() {
        GroupingExecution execution = new GroupingExecution();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                execution::concludeExecution);
        assertTrue(ex.getMessage().contains("No GroupingOrder associated"));
    }

    @Test
    void concludeExecution_shouldThrowIllegalState_whenHandlingOrderIsNotGroupingOrder() {
        GroupingExecution execution = new GroupingExecution();
        execution.handlingOrder = mock(BaseHandlingOrder.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                execution::concludeExecution);
        assertTrue(ex.getMessage().contains("No GroupingOrder associated"));
    }

    @Test
    void concludeExecution_shouldThrowIllegalState_whenBufferIsNull() {
        GroupingExecution execution = new GroupingExecution();
        GroupingOrder order = new GroupingOrder();
        order.buffer = null;
        execution.handlingOrder = order;

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                execution::concludeExecution);
        assertTrue(ex.getMessage().contains("does not specify a destination Buffer"));
    }

    @Test
    void concludeExecution_shouldMarkOrderDone_whenLoadedTotesIsNull() {
        GroupingExecution execution = new GroupingExecution();
        GroupingOrder order = new GroupingOrder();
        order.buffer = new Buffer();
        execution.handlingOrder = order;
        execution.loadedTotes = null;

        execution.concludeExecution();

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.DONE, order.status);
    }

    @Test
    void concludeExecution_shouldMarkOrderDone_whenLoadedTotesIsEmpty() {
        GroupingExecution execution = new GroupingExecution();
        GroupingOrder order = new GroupingOrder();
        order.buffer = new Buffer();
        execution.handlingOrder = order;
        execution.loadedTotes = new HashSet<>();

        execution.concludeExecution();

        assertEquals(BaseHandlingOrder.HandlingOrderStatus.DONE, order.status);
    }

    // --------------------------------------------------
    // Constructor validation
    // --------------------------------------------------

    @Test
    void constructor_shouldThrowIllegalState_whenLotsIsNull() {
        GroupingOrder order = new GroupingOrder();
        order.lots = null;

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new GroupingExecution(order, new Employee()));
        assertTrue(ex.getMessage().contains("no Lots or LotLines"));
    }

    @Test
    void constructor_shouldThrowIllegalState_whenLotLinesIsNull() {
        GroupingOrder order = new GroupingOrder();
        Lot lot = new Lot();
        lot.lines = null;
        order.lots = new HashSet<>(Set.of(lot));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new GroupingExecution(order, new Employee()));
        assertTrue(ex.getMessage().contains("no Lots or LotLines"));
    }

    @Test
    void constructor_shouldThrowIllegalState_whenNoActiveLoadedTotesFound() {
        GroupingOrder order = new GroupingOrder();
        LotLine line = new LotLine();
        line.id = 10L;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));
        order.lots = new HashSet<>(Set.of(lot));

        LoadedTote loadedTote = new LoadedTote();
        loadedTote.id = 5L;
        loadedTote.tote = new Tote();
        loadedTote.status = LoadedTote.Status.IN_USE;
        PickedItem item = new PickedItem();
        item.id = 1L;
        item.lotLine = line;
        item.status = PickedItem.PickStatus.PICKED;
        item.loadedTote = loadedTote;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of(item));
            // No active LoadedTote found for the involved IDs
            panacheMock.when(() -> PanacheEntityBase.list(eq("id in ?1 and status = ?2"), any(Object[].class)))
                    .thenReturn(List.of());

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new GroupingExecution(order, new Employee()));
            assertTrue(ex.getMessage().contains("No IN_USE LoadedTotes"));
        }
    }

    @Test
    void constructor_shouldThrowIllegalState_whenPickedItemsCoverageMismatch() {
        GroupingOrder order = new GroupingOrder();
        LotLine line = new LotLine();
        line.id = 10L;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));
        order.lots = new HashSet<>(Set.of(lot));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            // No PickedItem found although one LotLine is expected
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new GroupingExecution(order, new Employee()));
            assertTrue(ex.getMessage().contains("Missing PickedItems"));
        }
    }

    @Test
    void constructor_shouldThrowIllegalState_whenItemStatusIsInvalid() {
        GroupingOrder order = new GroupingOrder();
        LotLine line = new LotLine();
        line.id = 10L;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));
        order.lots = new HashSet<>(Set.of(lot));

        PickedItem item = new PickedItem();
        item.id = 1L;
        item.lotLine = line;
        item.status = PickedItem.PickStatus.PENDING; // not PICKED nor CANCELED

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of(item));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new GroupingExecution(order, new Employee()));
            assertTrue(ex.getMessage().contains("has status"));
        }
    }

    @Test
    void constructor_shouldThrowIllegalState_whenItemHasNoLoadedTote() {
        GroupingOrder order = new GroupingOrder();
        LotLine line = new LotLine();
        line.id = 10L;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));
        order.lots = new HashSet<>(Set.of(lot));

        PickedItem item = new PickedItem();
        item.id = 1L;
        item.lotLine = line;
        item.status = PickedItem.PickStatus.PICKED;
        item.loadedTote = null;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of(item));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new GroupingExecution(order, new Employee()));
            assertTrue(ex.getMessage().contains("not assigned to a LoadedTote"));
        }
    }

    @Test
    void constructor_shouldThrowIllegalState_whenLoadedToteIsNotInUse() {
        GroupingOrder order = new GroupingOrder();
        LotLine line = new LotLine();
        line.id = 10L;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));
        order.lots = new HashSet<>(Set.of(lot));

        LoadedTote loadedTote = new LoadedTote();
        loadedTote.id = 5L;
        loadedTote.tote = new Tote();
        loadedTote.status = LoadedTote.Status.DELIVERED; // not IN_USE
        PickedItem item = new PickedItem();
        item.id = 1L;
        item.lotLine = line;
        item.status = PickedItem.PickStatus.PICKED;
        item.loadedTote = loadedTote;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of(item));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new GroupingExecution(order, new Employee()));
            assertTrue(ex.getMessage().contains("Expected IN_USE"));
        }
    }

    @Test
    void constructor_shouldThrowIllegalState_whenTotePurityIsViolated() {
        GroupingOrder order = new GroupingOrder();
        LotLine line = new LotLine();
        line.id = 10L;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));
        order.lots = new HashSet<>(Set.of(lot));

        Tote physicalTote = new Tote();
        physicalTote.id = 3L;
        LoadedTote loadedTote = new LoadedTote();
        loadedTote.id = 5L;
        loadedTote.tote = physicalTote;
        loadedTote.status = LoadedTote.Status.IN_USE;
        PickedItem item = new PickedItem();
        item.id = 1L;
        item.lotLine = line;
        item.status = PickedItem.PickStatus.PICKED;
        item.loadedTote = loadedTote;

        // A foreign item (belonging to another LotLine) is found in the physical tote
        LotLine foreignLine = new LotLine();
        foreignLine.id = 99L;
        PickedItem foreignItem = new PickedItem();
        foreignItem.id = 2L;
        foreignItem.lotLine = foreignLine;
        foreignItem.loadedTote = loadedTote;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of(item));
            panacheMock.when(() -> PanacheEntityBase.list(eq("id in ?1 and status = ?2"), any(Object[].class)))
                    .thenReturn(List.of(loadedTote));
            panacheMock.when(() -> PanacheEntityBase.list(startsWith("SELECT pi FROM PickedItem"), any(Object[].class)))
                    .thenReturn(List.of(foreignItem));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new GroupingExecution(order, new Employee()));
            assertTrue(ex.getMessage().contains("Tote purity violated"));
        }
    }

    @Test
    void constructor_shouldCreateExecution_whenValidationPasses() {
        GroupingOrder order = new GroupingOrder();
        LotLine line = new LotLine();
        line.id = 10L;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));
        order.lots = new HashSet<>(Set.of(lot));

        Tote physicalTote = new Tote();
        physicalTote.id = 3L;
        LoadedTote loadedTote = new LoadedTote();
        loadedTote.id = 5L;
        loadedTote.tote = physicalTote;
        loadedTote.status = LoadedTote.Status.IN_USE;
        PickedItem item = new PickedItem();
        item.id = 1L;
        item.lotLine = line;
        item.status = PickedItem.PickStatus.PICKED;
        item.loadedTote = loadedTote;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("lotLine.id in ?1"), any(Object[].class)))
                    .thenReturn(List.of(item));
            panacheMock.when(() -> PanacheEntityBase.list(eq("id in ?1 and status = ?2"), any(Object[].class)))
                    .thenReturn(List.of(loadedTote));
            panacheMock.when(() -> PanacheEntityBase.list(startsWith("SELECT pi FROM PickedItem"), any(Object[].class)))
                    .thenReturn(List.of(item));

            GroupingExecution execution = new GroupingExecution(order, new Employee());

            assertSame(order, execution.handlingOrder);
            assertTrue(execution.loadedTotes.contains(loadedTote));
        }
    }
}
