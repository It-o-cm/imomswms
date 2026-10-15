package com.intermarche.backoffice.domain.handling;

import com.intermarche.backoffice.domain.executing.GroupingExecution;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HandlingExecution} business logic.
 * Uses the concrete subclass {@link GroupingExecution} (default constructor).
 * Focuses on the {@link HandlingExecution.ExecutionStatus} enum, default status
 * and checksum sensitivity.
 */
public class HandlingExecutionTest {

    // --------------------------------------------------
    // ExecutionStatus Enum
    // --------------------------------------------------

    @Test
    void executionStatus_shouldHaveAllExpectedValues() {
        assertEquals(4, HandlingExecution.ExecutionStatus.values().length);
        assertNotNull(HandlingExecution.ExecutionStatus.valueOf("PENDING"));
        assertNotNull(HandlingExecution.ExecutionStatus.valueOf("IN_PROGRESS"));
        assertNotNull(HandlingExecution.ExecutionStatus.valueOf("DONE"));
        assertNotNull(HandlingExecution.ExecutionStatus.valueOf("CANCELLED"));
    }

    // --------------------------------------------------
    // Defaults
    // --------------------------------------------------

    @Test
    void defaultStatus_shouldBeInProgress() {
        GroupingExecution execution = new GroupingExecution();
        assertEquals(HandlingExecution.ExecutionStatus.IN_PROGRESS, execution.status);
    }

    // --------------------------------------------------
    // getChecksum
    // --------------------------------------------------

    @Test
    void getChecksum_shouldChangeWhenStatusChanges() {
        GroupingExecution execution = new GroupingExecution();
        execution.status = HandlingExecution.ExecutionStatus.IN_PROGRESS;
        int checksum1 = execution.getChecksum();

        execution.status = HandlingExecution.ExecutionStatus.DONE;
        assertNotEquals(checksum1, execution.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenHandlingOrderIdChanges() {
        GroupingExecution execution = new GroupingExecution();
        execution.handlingOrder = createOrder(1L);
        int checksum1 = execution.getChecksum();

        execution.handlingOrder = createOrder(2L);
        assertNotEquals(checksum1, execution.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenEmployeeIdChanges() {
        GroupingExecution execution = new GroupingExecution();
        execution.employee = createEmployee(1L);
        int checksum1 = execution.getChecksum();

        execution.employee = createEmployee(2L);
        assertNotEquals(checksum1, execution.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenRelationsAreNull() {
        GroupingExecution execution = new GroupingExecution();
        execution.handlingOrder = null;
        execution.employee = null;
        assertDoesNotThrow(execution::getChecksum);
    }

    private GroupingOrder createOrder(Long id) {
        GroupingOrder order = new GroupingOrder();
        order.id = id;
        return order;
    }

    private Employee createEmployee(Long id) {
        Employee employee = new Employee();
        employee.id = id;
        return employee;
    }
}
