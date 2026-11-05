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
    private io.quarkus.security.identity.SecurityIdentity identity;

    /**
     * Builds the controller for a manager whose store holds every order asked.
     */
    @BeforeEach
    void setUp() {
        planService = mock(PickingPlanService.class);
        identity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        java.security.Principal principal = () -> "100234";
        when(identity.getPrincipal()).thenReturn(principal);
        when(planService.allInStoreOf(anyList(), eq("100234"))).thenReturn(true);
        controller = new PickingPlanController();
        controller.planService = planService;
        controller.identity = identity;
    }

    /**
     * Checks that orders of another store are refused on both endpoints, without planning anything.
     */
    @Test
    void ordersOfAnotherStore_areRefusedWith403() {
        when(planService.allInStoreOf(anyList(), eq("100234"))).thenReturn(false);

        assertEquals(403, controller.getMergeableOrders(1L, null).getStatus());
        assertEquals(403, controller.getPlannedRoute(List.of(1L, 2L), null).getStatus());
        verify(planService, never()).findMergeableOrders(anyLong(), any());
        verify(planService, never()).generatePickingPlan(anyList(), any());
    }

    /**
     * Checks that an administrator reads the orders of every store, without the store check.
     */
    @Test
    void administrator_readsEveryStore() {
        when(identity.hasRole("ADMIN")).thenReturn(true);
        when(planService.allInStoreOf(anyList(), anyString())).thenReturn(false);

        assertEquals(200, controller.getMergeableOrders(1L, null).getStatus());
    }

    // --------------------------------------------------
    // getMergeableOrders
    // --------------------------------------------------

    /**
     * Checks get mergeable orders: success, returns 200.
     */
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

    /**
     * Checks get mergeable orders: illegal argument, returns 400.
     */
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

    /**
     * Checks get planned route: null order ids, returns 400.
     */
    @Test
    void getPlannedRoute_nullOrderIds_returns400() {
        Response response = controller.getPlannedRoute(null, null);

        assertEquals(400, response.getStatus());
        assertEquals("At least one order ID is required.", response.getEntity());
    }

    /**
     * Checks get planned route: empty order ids, returns 400.
     */
    @Test
    void getPlannedRoute_emptyOrderIds_returns400() {
        Response response = controller.getPlannedRoute(List.of(), null);

        assertEquals(400, response.getStatus());
        assertEquals("At least one order ID is required.", response.getEntity());
    }

    /**
     * Checks get planned route: success, returns 200.
     */
    @Test
    void getPlannedRoute_success_returns200() {
        var plan = new PickingPlanService.BatchPickingPlanResponse();
        when(planService.generatePickingPlan(anyList(), any())).thenReturn(plan);

        Response response = controller.getPlannedRoute(List.of(1L, 2L), "A01");

        assertEquals(200, response.getStatus());
        assertSame(plan, response.getEntity());
    }

    /**
     * Checks get planned route: illegal state, returns 409.
     */
    @Test
    void getPlannedRoute_illegalState_returns409() {
        when(planService.generatePickingPlan(anyList(), any()))
                .thenThrow(new IllegalStateException("Order already reserved"));

        Response response = controller.getPlannedRoute(List.of(1L), null);

        assertEquals(409, response.getStatus());
        assertEquals("Order already reserved", response.getEntity());
    }

    /**
     * Checks get planned route: unexpected exception, returns 500.
     */
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

    /**
     * Checks merged order dto: holds values.
     */
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

    /**
     * Checks batch picking plan response: defaults.
     */
    @Test
    void batchPickingPlanResponse_defaults() {
        var response = new PickingPlanService.BatchPickingPlanResponse();

        assertNotNull(response.buildingPlans);
        assertTrue(response.buildingPlans.isEmpty());
        assertNotNull(response.globalSummary);
    }
}
