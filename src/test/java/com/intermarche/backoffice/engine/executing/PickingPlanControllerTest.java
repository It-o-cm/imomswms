package com.intermarche.backoffice.engine.executing;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PickingPlanController}.
 * The {@link PickingPlanService} is mocked; tests assert the HTTP status codes
 * produced by the planning endpoints.
 */
class PickingPlanControllerTest {

    private PickingPlanService planService;
    private PickingPlanController controller;

    @BeforeEach
    void setUp() {
        planService = mock(PickingPlanService.class);
        controller = new PickingPlanController();
        controller.planService = planService;
    }

    // --------------------------------------------------
    // getMergeableOrders
    // --------------------------------------------------

    @Test
    void getMergeableOrders_success_returns200() {
        var dto = new PickingPlanService.MergedOrderDTO();
        dto.id = 2L;
        dto.code = "ORD-2";
        when(planService.findMergeableOrders(1L, "search")).thenReturn(List.of(dto));

        Response response = controller.getMergeableOrders(1L, "search");

        assertEquals(200, response.getStatus());
        assertInstanceOf(List.class, response.getEntity());
        assertEquals(1, ((List<?>) response.getEntity()).size());
    }

    @Test
    void getMergeableOrders_illegalArgument_returns400() {
        when(planService.findMergeableOrders(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("Order not found"));

        Response response = controller.getMergeableOrders(99L, null);

        assertEquals(400, response.getStatus());
        assertEquals("Order not found", response.getEntity());
    }

    // --------------------------------------------------
    // getPlannedRoute
    // --------------------------------------------------

    @Test
    void getPlannedRoute_nullOrderIds_returns400() {
        Response response = controller.getPlannedRoute(null, null);

        assertEquals(400, response.getStatus());
        assertEquals("At least one order ID is required.", response.getEntity());
    }

    @Test
    void getPlannedRoute_emptyOrderIds_returns400() {
        Response response = controller.getPlannedRoute(List.of(), null);

        assertEquals(400, response.getStatus());
        assertEquals("At least one order ID is required.", response.getEntity());
    }

    @Test
    void getPlannedRoute_success_returns200() {
        var plan = new PickingPlanService.BatchPickingPlanResponse();
        when(planService.generatePickingPlan(anyList(), any())).thenReturn(plan);

        Response response = controller.getPlannedRoute(List.of(1L, 2L), "A01");

        assertEquals(200, response.getStatus());
        assertSame(plan, response.getEntity());
    }

    @Test
    void getPlannedRoute_illegalState_returns409() {
        when(planService.generatePickingPlan(anyList(), any()))
                .thenThrow(new IllegalStateException("Order already reserved"));

        Response response = controller.getPlannedRoute(List.of(1L), null);

        assertEquals(409, response.getStatus());
        assertEquals("Order already reserved", response.getEntity());
    }

    @Test
    void getPlannedRoute_unexpectedException_returns500() {
        when(planService.generatePickingPlan(anyList(), any()))
                .thenThrow(new RuntimeException("boom"));

        Response response = controller.getPlannedRoute(List.of(1L), null);

        assertEquals(500, response.getStatus());
        assertEquals("Failed to generate picking plan.", response.getEntity());
    }

    // --------------------------------------------------
    // DTOs
    // --------------------------------------------------

    @Test
    void mergedOrderDTO_holdsValues() {
        var dto = new PickingPlanService.MergedOrderDTO();
        dto.id = 2L;
        dto.code = "ORD-2";
        dto.customerName = "Jane Smith";
        dto.totalItems = 5;

        assertEquals(2L, dto.id);
        assertEquals("ORD-2", dto.code);
        assertEquals("Jane Smith", dto.customerName);
        assertEquals(5, dto.totalItems);
    }

    @Test
    void batchPickingPlanResponse_defaults() {
        var response = new PickingPlanService.BatchPickingPlanResponse();

        assertNotNull(response.buildingPlans);
        assertTrue(response.buildingPlans.isEmpty());
        assertNotNull(response.globalSummary);
    }
}
