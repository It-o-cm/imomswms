package com.intermarche.backoffice.engine.executing;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST Controller for Picking Planning operations.
 * <p>
 * Exposes endpoints to generate optimized routes and find mergeable orders.
 * These operations are read-only and do not modify the state of handling orders.
 * </p>
 */
@Path("/handling-orders/planning")
@Produces(MediaType.APPLICATION_JSON)
public class PickingPlanController {

    @Inject
    PickingPlanService planService;

    /**
     * GET /handling-orders/planning/mergeable/{primaryOrderId}
     * <p>
     * Retrieves a list of orders that satisfy the strict conditions to be merged with the
     * primary order for batch picking planning.
     *
     * @param primaryOrderId The ID of the initial order starting the batch.
     * @param search         An optional query string to filter results by order code or customer name.
     * @return A response containing a list of mergeable order DTOs.
     */
    @GET
    @Path("/mergeable/{primaryOrderId}")
    public Response getMergeableOrders(
            @PathParam("primaryOrderId") Long primaryOrderId,
            @QueryParam("search") String search) {

        try {
            List<PickingPlanService.MergedOrderDTO> mergeableOrders = planService.findMergeableOrders(primaryOrderId, search);
            return Response.ok(mergeableOrders).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * GET /handling-orders/planning/route
     * <p>
     * Generates the optimized picking route for a batch of selected orders.
     * This endpoint is strictly for the planning view and does not create any persistent executions.
     *
     * @param orderIds  A multi-valued list of Order IDs (Primary + Merged).
     * @param startNode The optional starting location code for the pathfinding algorithm.
     * @return A response containing the full Picking Plan response including route, capacity, and metrics.
     */
    @GET
    @Path("/route")
    public Response getPlannedRoute(
            @QueryParam("orderIds") List<Long> orderIds,
            @QueryParam("startNode") String startNode) {

        if (orderIds == null || orderIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("At least one order ID is required.")
                    .build();
        }

        try {
            PickingPlanService.BatchPickingPlanResponse plan = planService.generatePickingPlan(orderIds, startNode);
            return Response.ok(plan).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(e.getMessage())
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to generate picking plan.")
                    .build();
        }
    }
}