package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.domain.executing.GroupingExecution;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GroupingOrder} business logic.
 */
public class GroupingOrderTest {

    @Test
    void getPrefix_shouldReturnGRP() {
        GroupingOrder order = new GroupingOrder();
        assertEquals("GRP", order.getPrefix());
    }

    // --------------------------------------------------
    // createExecution
    // --------------------------------------------------

    @Test
    void createExecution_shouldThrowIllegalState_whenOrderHasNoLots() {
        GroupingOrder order = new GroupingOrder();
        Employee employee = new Employee();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> order.createExecution(employee));
        assertTrue(ex.getMessage().contains("no Lots or LotLines"));
    }

    @Test
    void createExecution_shouldReturnGroupingExecution_whenValidationPasses() {
        GroupingOrder order = new GroupingOrder();
        LotLine line = new LotLine();
        line.id = 10L;
        Lot lot = new Lot();
        lot.lines = new HashSet<>(Set.of(line));
        order.lots = new HashSet<>(Set.of(lot));
        Employee employee = new Employee();

        LoadedTote loadedTote = new LoadedTote();
        loadedTote.id = 5L;
        loadedTote.tote = new com.intermarche.backoffice.domain.store.Tote();
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

            HandlingExecution execution = order.createExecution(employee);

            assertTrue(execution instanceof GroupingExecution);
            assertSame(order, execution.handlingOrder);
            assertSame(employee, execution.employee);
            assertTrue(((GroupingExecution) execution).loadedTotes.contains(loadedTote));
        }
    }
}
