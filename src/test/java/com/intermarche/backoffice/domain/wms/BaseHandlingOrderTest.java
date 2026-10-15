package com.intermarche.backoffice.domain.wms;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BaseHandlingOrder} business logic.
 * Uses the concrete subclass {@link PackingOrder}.
 * Focuses on the {@link BaseHandlingOrder.HandlingOrderStatus} enum, default status
 * and checksum sensitivity.
 */
public class BaseHandlingOrderTest {

    // --------------------------------------------------
    // HandlingOrderStatus Enum
    // --------------------------------------------------

    @Test
    void handlingOrderStatus_shouldHaveAllExpectedValues() {
        assertEquals(6, BaseHandlingOrder.HandlingOrderStatus.values().length);
        assertNotNull(BaseHandlingOrder.HandlingOrderStatus.valueOf("OPEN"));
        assertNotNull(BaseHandlingOrder.HandlingOrderStatus.valueOf("SCHEDULED"));
        assertNotNull(BaseHandlingOrder.HandlingOrderStatus.valueOf("PROCESSING"));
        assertNotNull(BaseHandlingOrder.HandlingOrderStatus.valueOf("DONE"));
        assertNotNull(BaseHandlingOrder.HandlingOrderStatus.valueOf("CANCELLED"));
        assertNotNull(BaseHandlingOrder.HandlingOrderStatus.valueOf("FAILED"));
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultStatus_shouldBeOpen() {
        PackingOrder order = new PackingOrder();
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.OPEN, order.status);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenStatusChanges() {
        PackingOrder order = new PackingOrder();
        order.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;
        int checksum1 = order.getChecksum();

        order.status = BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenRequestedStartChanges() {
        PackingOrder order = new PackingOrder();
        order.requestedStart = LocalDateTime.of(2026, 1, 1, 8, 0);
        int checksum1 = order.getChecksum();

        order.requestedStart = LocalDateTime.of(2026, 1, 1, 9, 0);
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenRequestedEndChanges() {
        PackingOrder order = new PackingOrder();
        order.requestedEnd = LocalDateTime.of(2026, 1, 1, 10, 0);
        int checksum1 = order.getChecksum();

        order.requestedEnd = LocalDateTime.of(2026, 1, 1, 11, 0);
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_shouldUseAssigneeBadgeId_notAssigneeId() {
        PackingOrder order = new PackingOrder();
        order.assignee = createEmployee("BADGE-01");
        int checksum1 = order.getChecksum();

        // Same badge, different surrogate id: checksum must stay stable
        order.assignee = createEmployee("BADGE-01");
        order.assignee.id = 999L;
        assertEquals(checksum1, order.getChecksum());

        // Different badge: checksum must change
        order.assignee = createEmployee("BADGE-02");
        assertNotEquals(checksum1, order.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenAssigneeIsNull() {
        PackingOrder order = new PackingOrder();
        order.assignee = null;
        assertDoesNotThrow(order::getChecksum);
    }

    private Employee createEmployee(String badgeId) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        return employee;
    }
}
