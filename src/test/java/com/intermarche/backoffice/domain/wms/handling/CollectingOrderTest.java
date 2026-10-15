package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.Lot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CollectingOrder} business logic.
 * Uses the concrete subclass {@link ProductCollectingOrder}.
 * Focuses on {@link CollectingOrder#addLot(Lot)} and {@link CollectingOrder#createExecution(Employee)}.
 */
public class CollectingOrderTest {

    @Test
    void addLot_shouldAddLotAndSetStatusToPlannedToBePacked() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        Lot lot = new Lot();
        lot.status = Lot.LotStatus.PENDING;

        order.addLot(lot);

        assertTrue(order.lots.contains(lot));
        assertEquals(Lot.LotStatus.PLANNED_TO_BE_PACKED, lot.status);
    }

    @Test
    void createExecution_shouldReturnPickingExecutionLinkedToOrderAndEmployee() {
        ProductCollectingOrder order = new ProductCollectingOrder();
        // No lots: the PickingExecution constructor skips the database access
        Employee employee = new Employee();
        employee.badgeId = "BADGE-01";

        PickingExecution execution = (PickingExecution) order.createExecution(employee);

        assertNotNull(execution);
        assertSame(order, execution.handlingOrder);
        assertSame(employee, execution.employee);
    }
}
