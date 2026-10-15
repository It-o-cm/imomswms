package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.TemperatureZone;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HandlingOrderController}.
 * The {@link HandlingOrderService} is mocked; tests assert the HTTP status codes
 * and response entities produced by the thin REST wrapper for every endpoint.
 */
class HandlingOrderControllerTest {

    private HandlingOrderService service;
    private HandlingOrderController controller;

    @BeforeEach
    void setUp() {
        service = mock(HandlingOrderService.class);
        controller = new HandlingOrderController();
        controller.handlingOrderService = service;
    }

    // --------------------------------------------------
    // Listing & Reservation
    // --------------------------------------------------

    @Test
    void listAvailable_returns200WithList() {
        var summary = new HandlingOrderService.HandlingOrderSummaryDTO();
        summary.id = 1L;
        summary.type = "PackingOrder";
        when(service.listAvailableOrders()).thenReturn(List.of(summary));

        Response response = controller.listAvailable();

        assertEquals(200, response.getStatus());
        assertInstanceOf(List.class, response.getEntity());
        assertEquals(1, ((List<?>) response.getEntity()).size());
    }

    @Test
    void reserveOrder_found_returns201() {
        var dto = new HandlingOrderService.HandlingExecutionDTO(10L, "PickingExecution", 1L, "BADGE-1", "John Doe", LocalDateTime.now());
        when(service.reserveOrder(1L, "BADGE-1")).thenReturn(dto);

        var request = new HandlingOrderController.ReserveRequest();
        request.badgeId = "BADGE-1";
        Response response = controller.reserveOrder(1L, request);

        assertEquals(201, response.getStatus());
        assertSame(dto, response.getEntity());
    }

    @Test
    void reserveOrder_nullRequest_returns400() {
        Response response = controller.reserveOrder(1L, null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void reserveOrder_notFound_returns404() {
        when(service.reserveOrder(99L, "BADGE-1")).thenReturn(null);

        var request = new HandlingOrderController.ReserveRequest();
        request.badgeId = "BADGE-1";
        Response response = controller.reserveOrder(99L, request);

        assertEquals(404, response.getStatus());
        assertEquals("Order or Employee not found", response.getEntity());
    }

    @Test
    void cancelReservation_returns204() {
        doNothing().when(service).cancelReservation(1L);

        Response response = controller.cancelReservation(1L);

        assertEquals(204, response.getStatus());
        verify(service).cancelReservation(1L);
    }

    // --------------------------------------------------
    // Batch Execution Management
    // --------------------------------------------------

    @Test
    void startBatchExecutions_success_returns200() {
        var batchResponse = new HandlingOrderService.BatchStartResponse(Map.of(1L, 10L), new HandlingOrderService.PickingExecutionStateDTO());
        when(service.startBatchExecutions(anyList(), anyString())).thenReturn(batchResponse);

        var request = new HandlingOrderController.BatchStartRequest();
        request.orderIds = List.of(1L);
        Response response = controller.startBatchExecutions(request);

        assertEquals(200, response.getStatus());
        assertSame(batchResponse, response.getEntity());
    }

    @Test
    void startBatchExecutions_nullRequest_returns400() {
        Response response = controller.startBatchExecutions(null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void startBatchExecutions_illegalArgument_returns400() {
        when(service.startBatchExecutions(anyList(), anyString()))
                .thenThrow(new IllegalArgumentException("No orders"));

        var request = new HandlingOrderController.BatchStartRequest();
        request.orderIds = List.of(1L);
        Response response = controller.startBatchExecutions(request);

        assertEquals(400, response.getStatus());
        assertEquals("No orders", response.getEntity());
    }

    @Test
    void getBatchState_success_returns200() {
        var state = new HandlingOrderService.PickingExecutionStateDTO();
        when(service.getBatchState(anyList())).thenReturn(state);

        Response response = controller.getBatchState(List.of(10L, 11L));

        assertEquals(200, response.getStatus());
        assertSame(state, response.getEntity());
    }

    @Test
    void getBatchState_nullIds_delegatesToService() {
        var state = new HandlingOrderService.PickingExecutionStateDTO();
        when(service.getBatchState(isNull())).thenReturn(state);

        Response response = controller.getBatchState(null);

        assertEquals(200, response.getStatus());
        assertSame(state, response.getEntity());
        verify(service).getBatchState(null);
    }

    @Test
    void getBatchState_exception_returns500() {
        when(service.getBatchState(anyList())).thenThrow(new RuntimeException("boom"));

        Response response = controller.getBatchState(List.of(10L));

        assertEquals(500, response.getStatus());
        assertEquals("boom", response.getEntity());
    }

    // --------------------------------------------------
    // Picking Execution Management
    // --------------------------------------------------

    @Test
    void substituteProduct_success_returns204() {
        doNothing().when(service).substituteProduct(1L, 2L, 3L);

        var request = new HandlingOrderController.SubstituteProductRequest();
        request.newProductId = 3L;
        Response response = controller.substituteProduct(1L, 2L, request);

        assertEquals(204, response.getStatus());
        verify(service).substituteProduct(1L, 2L, 3L);
    }

    @Test
    void substituteProduct_nullRequest_returns400() {
        Response response = controller.substituteProduct(1L, 2L, null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void substituteProduct_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("bad item")).when(service).substituteProduct(anyLong(), anyLong(), anyLong());

        var request = new HandlingOrderController.SubstituteProductRequest();
        request.newProductId = 3L;
        Response response = controller.substituteProduct(1L, 2L, request);

        assertEquals(400, response.getStatus());
        assertEquals("bad item", response.getEntity());
    }

    @Test
    void addTote_success_returns201() {
        doNothing().when(service).addTote(1L, "TOTE-1", TemperatureZone.AMBIENT);

        var request = new HandlingOrderController.AddToteRequest();
        request.toteCode = "TOTE-1";
        request.usage = TemperatureZone.AMBIENT;
        Response response = controller.addTote(1L, request);

        assertEquals(201, response.getStatus());
        verify(service).addTote(1L, "TOTE-1", TemperatureZone.AMBIENT);
    }

    @Test
    void addTote_nullRequest_returns400() {
        Response response = controller.addTote(1L, null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void addTote_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("unknown tote")).when(service).addTote(anyLong(), anyString(), any());

        var request = new HandlingOrderController.AddToteRequest();
        request.toteCode = "TOTE-X";
        Response response = controller.addTote(1L, request);

        assertEquals(400, response.getStatus());
        assertEquals("unknown tote", response.getEntity());
    }

    @Test
    void removeEmptyTote_success_returns204() {
        doNothing().when(service).removeEmptyTote(1L, "TOTE-1");

        Response response = controller.removeEmptyTote(1L, "TOTE-1");

        assertEquals(204, response.getStatus());
        verify(service).removeEmptyTote(1L, "TOTE-1");
    }

    @Test
    void removeEmptyTote_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("tote not empty")).when(service).removeEmptyTote(anyLong(), anyString());

        Response response = controller.removeEmptyTote(1L, "TOTE-1");

        assertEquals(400, response.getStatus());
        assertEquals("tote not empty", response.getEntity());
    }

    @Test
    void addItemToTote_success_returns204() {
        doNothing().when(service).addItemToTote(1L, "TOTE-1", 2L, new BigDecimal("3"), 4L);

        var request = new HandlingOrderController.AddItemRequest();
        request.pickedItemId = 2L;
        request.quantity = new BigDecimal("3");
        request.productId = 4L;
        Response response = controller.addItemToTote(1L, "TOTE-1", request);

        assertEquals(204, response.getStatus());
        verify(service).addItemToTote(1L, "TOTE-1", 2L, new BigDecimal("3"), 4L);
    }

    @Test
    void addItemToTote_nullRequest_returns400() {
        Response response = controller.addItemToTote(1L, "TOTE-1", null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void addItemToTote_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("bad item")).when(service).addItemToTote(anyLong(), anyString(), anyLong(), any(), anyLong());

        var request = new HandlingOrderController.AddItemRequest();
        request.pickedItemId = 2L;
        request.quantity = new BigDecimal("3");
        request.productId = 4L;
        Response response = controller.addItemToTote(1L, "TOTE-1", request);

        assertEquals(400, response.getStatus());
    }

    @Test
    void moveItem_success_returns204() {
        doNothing().when(service).moveItem(1L, "TOTE-A", "TOTE-B", 2L);

        var request = new HandlingOrderController.MoveItemRequest();
        request.sourceToteCode = "TOTE-A";
        request.targetToteCode = "TOTE-B";
        request.pickedItemId = 2L;
        Response response = controller.moveItem(1L, request);

        assertEquals(204, response.getStatus());
        verify(service).moveItem(1L, "TOTE-A", "TOTE-B", 2L);
    }

    @Test
    void moveItem_nullRequest_returns400() {
        Response response = controller.moveItem(1L, null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void moveItem_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("same tote")).when(service).moveItem(anyLong(), anyString(), anyString(), anyLong());

        var request = new HandlingOrderController.MoveItemRequest();
        request.sourceToteCode = "TOTE-A";
        request.targetToteCode = "TOTE-A";
        request.pickedItemId = 2L;
        Response response = controller.moveItem(1L, request);

        assertEquals(400, response.getStatus());
    }

    @Test
    void removeItemFromTote_success_returns204() {
        doNothing().when(service).removeItemFromTote(1L, "TOTE-1", 2L);

        Response response = controller.removeItemFromTote(1L, "TOTE-1", 2L);

        assertEquals(204, response.getStatus());
        verify(service).removeItemFromTote(1L, "TOTE-1", 2L);
    }

    @Test
    void removeItemFromTote_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("not in tote")).when(service).removeItemFromTote(anyLong(), anyString(), anyLong());

        Response response = controller.removeItemFromTote(1L, "TOTE-1", 2L);

        assertEquals(400, response.getStatus());
    }

    @Test
    void cancelItem_success_returns204() {
        doNothing().when(service).cancelPickedItem(1L, 2L);

        Response response = controller.cancelItem(1L, 2L);

        assertEquals(204, response.getStatus());
        verify(service).cancelPickedItem(1L, 2L);
    }

    @Test
    void cancelItem_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("already cancelled")).when(service).cancelPickedItem(anyLong(), anyLong());

        Response response = controller.cancelItem(1L, 2L);

        assertEquals(400, response.getStatus());
    }

    @Test
    void concludeExecution_success_returns204() {
        doNothing().when(service).concludePickingExecution(1L, 5L);

        var request = new HandlingOrderController.ConcludeRequest();
        request.bufferId = 5L;
        Response response = controller.concludeExecution(1L, request);

        assertEquals(204, response.getStatus());
        verify(service).concludePickingExecution(1L, 5L);
    }

    @Test
    void concludeExecution_nullRequest_returns400() {
        Response response = controller.concludeExecution(1L, null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void concludeExecution_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("bad buffer")).when(service).concludePickingExecution(anyLong(), anyLong());

        var request = new HandlingOrderController.ConcludeRequest();
        request.bufferId = 5L;
        Response response = controller.concludeExecution(1L, request);

        assertEquals(400, response.getStatus());
    }

    @Test
    void concludeExecution_illegalState_returns400() {
        doThrow(new IllegalStateException("not ready")).when(service).concludePickingExecution(anyLong(), anyLong());

        var request = new HandlingOrderController.ConcludeRequest();
        request.bufferId = 5L;
        Response response = controller.concludeExecution(1L, request);

        assertEquals(400, response.getStatus());
        assertEquals("not ready", response.getEntity());
    }

    // --------------------------------------------------
    // Grouping Execution Management
    // --------------------------------------------------

    @Test
    void moveToteToDestination_success_returns204() {
        doNothing().when(service).moveToteToDestination(1L, "TOTE-1");

        Response response = controller.moveToteToDestination(1L, "TOTE-1");

        assertEquals(204, response.getStatus());
        verify(service).moveToteToDestination(1L, "TOTE-1");
    }

    @Test
    void moveToteToDestination_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("unknown tote")).when(service).moveToteToDestination(anyLong(), anyString());

        Response response = controller.moveToteToDestination(1L, "TOTE-1");

        assertEquals(400, response.getStatus());
    }

    @Test
    void concludeGroupingExecution_success_returns204() {
        doNothing().when(service).concludeGroupingExecution(1L);

        Response response = controller.concludeGroupingExecution(1L);

        assertEquals(204, response.getStatus());
        verify(service).concludeGroupingExecution(1L);
    }

    @Test
    void concludeGroupingExecution_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("totes remaining")).when(service).concludeGroupingExecution(anyLong());

        Response response = controller.concludeGroupingExecution(1L);

        assertEquals(400, response.getStatus());
    }

    // --------------------------------------------------
    // Bulk Execution Validation
    // --------------------------------------------------

    @Test
    void validateReport_success_returns204() {
        doNothing().when(service).saveAndConcludeExecution(anyLong(), any());

        var request = new HandlingOrderService.ExecutionReportRequest();
        request.bufferId = 5L;
        Response response = controller.validateReport(1L, request);

        assertEquals(204, response.getStatus());
        verify(service).saveAndConcludeExecution(1L, request);
    }

    @Test
    void validateReport_nullRequest_returns400() {
        Response response = controller.validateReport(1L, null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void validateReport_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("bad report")).when(service).saveAndConcludeExecution(anyLong(), any());

        var request = new HandlingOrderService.ExecutionReportRequest();
        Response response = controller.validateReport(1L, request);

        assertEquals(400, response.getStatus());
        assertEquals("bad report", response.getEntity());
    }

    @Test
    void validateReport_unexpectedException_returns500() {
        doThrow(new RuntimeException("db down")).when(service).saveAndConcludeExecution(anyLong(), any());

        var request = new HandlingOrderService.ExecutionReportRequest();
        Response response = controller.validateReport(1L, request);

        assertEquals(500, response.getStatus());
        assertEquals("Validation failed", response.getEntity());
    }

    @Test
    void createAndValidateBatch_success_returns204() {
        doNothing().when(service).createAndConcludeBatch(any());

        var request = new HandlingOrderService.BatchValidationRequest();
        request.orderIds = List.of(1L);
        Response response = controller.createAndValidateBatch(request);

        assertEquals(204, response.getStatus());
        verify(service).createAndConcludeBatch(request);
    }

    @Test
    void createAndValidateBatch_nullRequest_returns400() {
        Response response = controller.createAndValidateBatch(null);

        assertEquals(400, response.getStatus());
        assertEquals("Request body is required", response.getEntity());
        verifyNoInteractions(service);
    }

    @Test
    void createAndValidateBatch_illegalArgument_returns400() {
        doThrow(new IllegalArgumentException("bad batch")).when(service).createAndConcludeBatch(any());

        var request = new HandlingOrderService.BatchValidationRequest();
        Response response = controller.createAndValidateBatch(request);

        assertEquals(400, response.getStatus());
        assertEquals("bad batch", response.getEntity());
    }

    @Test
    void createAndValidateBatch_illegalState_returns400() {
        doThrow(new IllegalStateException("conflict")).when(service).createAndConcludeBatch(any());

        var request = new HandlingOrderService.BatchValidationRequest();
        Response response = controller.createAndValidateBatch(request);

        assertEquals(400, response.getStatus());
    }

    @Test
    void createAndValidateBatch_unexpectedException_returns500() {
        doThrow(new RuntimeException("db down")).when(service).createAndConcludeBatch(any());

        var request = new HandlingOrderService.BatchValidationRequest();
        Response response = controller.createAndValidateBatch(request);

        assertEquals(500, response.getStatus());
        assertEquals("Batch validation failed", response.getEntity());
    }

    // --------------------------------------------------
    // DTOs
    // --------------------------------------------------

    @Test
    void handlingExecutionDTO_constructor_setsFields() {
        LocalDateTime now = LocalDateTime.now();
        var dto = new HandlingOrderService.HandlingExecutionDTO(10L, "PickingExecution", 1L, "BADGE-1", "John Doe", now);

        assertEquals(10L, dto.id);
        assertEquals("PickingExecution", dto.type);
        assertEquals(1L, dto.orderId);
        assertEquals("BADGE-1", dto.badgeId);
        assertEquals("John Doe", dto.employeeName);
        assertEquals(now, dto.createdAt);
    }

    @Test
    void batchStartResponse_constructor_setsFields() {
        var state = new HandlingOrderService.PickingExecutionStateDTO();
        var response = new HandlingOrderService.BatchStartResponse(Map.of(1L, 10L), state);

        assertEquals(10L, response.executionMap.get(1L));
        assertSame(state, response.state);
    }

    @Test
    void requestDTOs_holdValues() {
        var reserve = new HandlingOrderController.ReserveRequest();
        reserve.badgeId = "B1";
        assertEquals("B1", reserve.badgeId);

        var batch = new HandlingOrderController.BatchStartRequest();
        batch.orderIds = List.of(1L, 2L);
        assertEquals(List.of(1L, 2L), batch.orderIds);

        var substitute = new HandlingOrderController.SubstituteProductRequest();
        substitute.newProductId = 9L;
        assertEquals(9L, substitute.newProductId);

        var addTote = new HandlingOrderController.AddToteRequest();
        addTote.toteCode = "T1";
        addTote.usage = TemperatureZone.CHILLED;
        assertEquals("T1", addTote.toteCode);
        assertEquals(TemperatureZone.CHILLED, addTote.usage);

        var addItem = new HandlingOrderController.AddItemRequest();
        addItem.pickedItemId = 1L;
        addItem.quantity = new BigDecimal("2");
        addItem.productId = 3L;
        assertEquals(1L, addItem.pickedItemId);
        assertEquals(new BigDecimal("2"), addItem.quantity);
        assertEquals(3L, addItem.productId);

        var move = new HandlingOrderController.MoveItemRequest();
        move.sourceToteCode = "A";
        move.targetToteCode = "B";
        move.pickedItemId = 4L;
        assertEquals("A", move.sourceToteCode);
        assertEquals("B", move.targetToteCode);
        assertEquals(4L, move.pickedItemId);

        var conclude = new HandlingOrderController.ConcludeRequest();
        conclude.bufferId = 7L;
        assertEquals(7L, conclude.bufferId);
    }
}
