package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.TemperatureZone;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST Controller for managing Handling Orders Execution.
 * <p>
 * Exposes endpoints to list available orders, reserve orders, manage the picking
 * process (totes, items), and conclude executions.
 * </p>
 */
@Path("/handling-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HandlingOrderController {

    @Inject
    HandlingOrderService handlingOrderService;

    // --------------------------------------------------
    // Listing & Reservation
    // --------------------------------------------------

    /**
     * GET /handling-orders/available
     * Lists all orders without an execution.
     *
     * @return A response containing a list of available order summaries.
     */
    @GET
    @Path("/available")
    public Response listAvailable() {
        var dtoList = handlingOrderService.listAvailableOrders();
        return Response.ok(dtoList).build();
    }

    /**
     * Reserves a specific handling order for an employee.
     *
     * @param id      The ID of the order to reserve.
     * @param request The request body containing the employee's badge ID.
     * @return A response containing the created execution DTO, or 404 if not found.
     */
    @POST
    @Path("/{id}/reserve")
    public Response reserveOrder(@PathParam("id") Long id, ReserveRequest request) {
        HandlingOrderService.HandlingExecutionDTO dto = handlingOrderService.reserveOrder(id, request.badgeId);
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Order or Employee not found").build();
        }
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    /**
     * Cancels the reservation of a specific handling order.
     *
     * @param id The ID of the order whose reservation should be cancelled.
     * @return A no-content response indicating success.
     */
    @DELETE
    @Path("/{id}/reserve")
    public Response cancelReservation(@PathParam("id") Long id) {
        handlingOrderService.cancelReservation(id);
        return Response.noContent().build();
    }

    // --------------------------------------------------
    // Batch Execution Management
    // --------------------------------------------------

    /**
     * POST /handling-orders/executions/batch
     * Initiates a batch picking session.
     *
     * @param request The request body containing the list of order IDs.
     * @return A response containing the batch start response.
     */
    @POST
    @Path("/executions/batch")
    public Response startBatchExecutions(BatchStartRequest request) {
        try {
            String badgeId = "CURRENT_USER_BADGE";
            HandlingOrderService.BatchStartResponse response = handlingOrderService.startBatchExecutions(request.orderIds, badgeId);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * GET /handling-orders/executions/batch
     * Retrieves the current state of a batch of executions.
     *
     * @param ids List of execution IDs.
     * @return A response containing the unified picking execution state.
     */
    @GET
    @Path("/executions/batch")
    public Response getBatchState(@QueryParam("ids") List<Long> ids) {
        try {
            HandlingOrderService.PickingExecutionStateDTO state = handlingOrderService.getBatchState(ids);
            return Response.ok(state).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    /**
     * PUT /handling-orders/{executionId}/items/{itemId}/product
     * Substitutes the product of a specific picked item.
     *
     * @param executionId The ID of the execution context.
     * @param itemId      The ID of the picked item to update.
     * @param request     The request body containing the new product ID.
     * @return A no-content response on success.
     */
    @PUT
    @Path("/{executionId}/items/{itemId}/product")
    public Response substituteProduct(
            @PathParam("executionId") Long executionId,
            @PathParam("itemId") Long itemId,
            SubstituteProductRequest request) {
        try {
            handlingOrderService.substituteProduct(executionId, itemId, request.newProductId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    // --------------------------------------------------
    // Picking Execution Management
    // --------------------------------------------------

    /**
     * POST /handling-orders/{executionId}/totes
     * Attaches an existing Tote to the picking execution.
     *
     * @param executionId The ID of the picking execution.
     * @param request     The request body containing tote code and usage.
     * @return 201 Created if successful, 400 if validation fails.
     */
    @POST
    @Path("/{executionId}/totes")
    public Response addTote(@PathParam("executionId") Long executionId, AddToteRequest request) {
        try {
            handlingOrderService.addTote(executionId, request.toteCode, request.usage);
            return Response.status(Response.Status.CREATED).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * DELETE /handling-orders/{executionId}/totes/{toteCode}
     * Detaches a Tote from the picking execution.
     *
     * @param executionId The ID of the picking execution.
     * @param toteCode    The code of the tote to detach.
     * @return 204 No Content if successful, 400 if validation fails.
     */
    @DELETE
    @Path("/{executionId}/totes/{toteCode}")
    public Response removeEmptyTote(@PathParam("executionId") Long executionId, @PathParam("toteCode") String toteCode) {
        try {
            handlingOrderService.removeEmptyTote(executionId, toteCode);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * POST /handling-orders/{executionId}/totes/{toteCode}/items
     * Moves or adds an existing picked item into a specific tote.
     *
     * @param executionId The ID of the picking execution.
     * @param toteCode    The code of the destination tote.
     * @param request     The request body containing item ID, quantity, and optional product ID.
     * @return 204 No Content if successful, 400 if validation fails.
     */
    @POST
    @Path("/{executionId}/totes/{toteCode}/items")
    public Response addItemToTote(@PathParam("executionId") Long executionId,
                                  @PathParam("toteCode") String toteCode,
                                  AddItemRequest request) {
        try {
            handlingOrderService.addItemToTote(executionId, toteCode, request.pickedItemId, request.quantity, request.productId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * POST /handling-orders/{executionId}/items/move
     * Moves an existing picked item between totes.
     *
     * @param executionId The ID of the picking execution.
     * @param request     The request body containing source, target, and item IDs.
     * @return 204 No Content if successful, 400 if validation fails.
     */
    @POST
    @Path("/{executionId}/items/move")
    public Response moveItem(@PathParam("executionId") Long executionId, MoveItemRequest request) {
        try {
            handlingOrderService.moveItem(executionId, request.sourceToteCode, request.targetToteCode, request.pickedItemId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * DELETE /handling-orders/{executionId}/totes/{toteCode}/items/{itemId}
     * Removes a picked item from a tote (returns it to the work list).
     *
     * @param executionId The ID of the picking execution.
     * @param toteCode    The code of the tote.
     * @param itemId      The ID of the picked item.
     * @return 204 No Content if successful, 400 if validation fails.
     */
    @DELETE
    @Path("/{executionId}/totes/{toteCode}/items/{itemId}")
    public Response removeItemFromTote(@PathParam("executionId") Long executionId,
                                       @PathParam("toteCode") String toteCode,
                                       @PathParam("itemId") Long itemId) {
        try {
            handlingOrderService.removeItemFromTote(executionId, toteCode, itemId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * POST /handling-orders/{executionId}/items/{itemId}/cancel
     * Cancels a picked item.
     *
     * @param executionId The ID of the picking execution.
     * @param itemId      The ID of the picked item to cancel.
     * @return 204 No Content if successful, 400 if validation fails.
     */
    @POST
    @Path("/{executionId}/items/{itemId}/cancel")
    public Response cancelItem(@PathParam("executionId") Long executionId, @PathParam("itemId") Long itemId) {
        try {
            handlingOrderService.cancelPickedItem(executionId, itemId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * POST /handling-orders/{executionId}/conclude
     * Concludes the picking execution.
     *
     * @param executionId The ID of the picking execution.
     * @param request     The request body containing the target buffer ID.
     * @return 204 No Content if successful, 400 if validation fails.
     */
    @POST
    @Path("/{executionId}/conclude")
    public Response concludeExecution(@PathParam("executionId") Long executionId, ConcludeRequest request) {
        try {
            handlingOrderService.concludePickingExecution(executionId, request.bufferId);
            return Response.noContent().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    // --------------------------------------------------
    // Grouping Execution Management
    // --------------------------------------------------

    /**
     * POST /handling-orders/{executionId}/totes/{toteCode}/move-to-destination
     * Moves a physical Tote to the destination buffer.
     *
     * @param executionId The ID of the grouping execution.
     * @param toteCode    The code of the tote to move.
     * @return 204 No Content if successful, 400 if validation fails.
     */
    @POST
    @Path("/{executionId}/totes/{toteCode}/move-to-destination")
    public Response moveToteToDestination(@PathParam("executionId") Long executionId,
                                          @PathParam("toteCode") String toteCode) {
        try {
            handlingOrderService.moveToteToDestination(executionId, toteCode);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * POST /handling-orders/{executionId}/conclude-grouping
     * Concludes the grouping execution.
     *
     * @param executionId The ID of the grouping execution.
     * @return 204 No Content if successful, 400 if validation fails.
     */
    @POST
    @Path("/{executionId}/conclude-grouping")
    public Response concludeGroupingExecution(@PathParam("executionId") Long executionId) {
        try {
            handlingOrderService.concludeGroupingExecution(executionId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    // --------------------------------------------------
    // Bulk Execution Validation Endpoint
    // --------------------------------------------------

    /**
     * POST /handling-orders/{executionId}/validate-report
     * Saves the entire execution state (totes, allocations) and concludes the process.
     *
     * @param executionId The ID of the execution.
     * @param request     The request body containing the final state.
     * @return 204 No Content on success.
     */
    @POST
    @Path("/{executionId}/validate-report")
    public Response validateReport(
            @PathParam("executionId") Long executionId,
            HandlingOrderService.ExecutionReportRequest request) {
        try {
            handlingOrderService.saveAndConcludeExecution(executionId, request);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Validation failed").build();
        }
    }

    /**
     * POST /handling-orders/executions/batch-validate
     * Creates executions and saves state in one transaction.
     *
     * @param request The request body.
     * @return 204 No Content on success.
     */
    @POST
    @Path("/executions/batch-validate")
    public Response createAndValidateBatch(HandlingOrderService.BatchValidationRequest request) {
        try {
            handlingOrderService.createAndConcludeBatch(request);
            return Response.noContent().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Batch validation failed").build();
        }
    }

    // --------------------------------------------------
    // DTOs (Request Bodies)
    // ---------------------------------------------------------

    /** Request body for reserving an order. */
    public static class ReserveRequest {
        /** The badge ID of the employee taking the order. */
        public String badgeId;
    }

    /** Request body for starting a batch execution. */
    public static class BatchStartRequest {
        /** The list of order IDs to include in the batch. */
        public List<Long> orderIds;
    }

    /** Request body for substituting a product. */
    public static class SubstituteProductRequest {
        /** The ID of the new product to assign. */
        public Long newProductId;
    }

    /** Request body for adding a tote to an execution. */
    public static class AddToteRequest {
        /** The business code of the tote. */
        public String toteCode;
        /** The intended temperature zone usage for the tote. */
        public TemperatureZone usage;
    }

    /** Request body for adding an item to a tote. */
    public static class AddItemRequest {
        /** The ID of the picked item. */
        public Long pickedItemId;
        /** The quantity to move (null for full quantity). */
        public BigDecimal quantity;
        /** The ID of the product for substitution (null to keep original). */
        public Long productId;
    }

    /** Request body for moving an item between totes. */
    public static class MoveItemRequest {
        /** The code of the source tote. */
        public String sourceToteCode;
        /** The code of the target tote. */
        public String targetToteCode;
        /** The ID of the picked item to move. */
        public Long pickedItemId;
    }

    /** Request body for concluding an execution. */
    public static class ConcludeRequest {
        /** The ID of the buffer where totes should be staged. */
        public Long bufferId;
    }
}