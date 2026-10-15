package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.TemperatureZone;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the DTOs of {@link HandlingOrderService}.
 * Pure unit tests — no database, no Quarkus.
 */
class HandlingOrderServiceDTOTest {

    @Test
    void toteItemDTO_fields() {
        HandlingOrderService.ToteItemDTO dto = new HandlingOrderService.ToteItemDTO();
        dto.pickedItemId = 1L;
        dto.qty = new BigDecimal("2.5");
        dto.productName = "Lait demi-écrémé";

        assertEquals(1L, dto.pickedItemId);
        assertEquals(new BigDecimal("2.5"), dto.qty);
        assertEquals("Lait demi-écrémé", dto.productName);
    }

    @Test
    void toteReportDTO_fields() {
        HandlingOrderService.ToteItemReportDTO item = new HandlingOrderService.ToteItemReportDTO();
        item.pickedItemId = 3L;
        item.qty = BigDecimal.ONE;

        HandlingOrderService.ToteReportDTO dto = new HandlingOrderService.ToteReportDTO();
        dto.code = "TOTE-01";
        dto.zone = TemperatureZone.CHILLED;
        dto.items = List.of(item);

        assertEquals("TOTE-01", dto.code);
        assertEquals(TemperatureZone.CHILLED, dto.zone);
        assertEquals(1, dto.items.size());
        assertEquals(3L, dto.items.get(0).pickedItemId);
    }

    @Test
    void toteItemReportDTO_fields() {
        HandlingOrderService.ToteItemReportDTO dto = new HandlingOrderService.ToteItemReportDTO();
        dto.pickedItemId = 9L;
        dto.qty = new BigDecimal("0.750");

        assertEquals(9L, dto.pickedItemId);
        assertEquals(new BigDecimal("0.750"), dto.qty);
    }

    @Test
    void orderReportDTO_fields() {
        HandlingOrderService.ToteReportDTO tote = new HandlingOrderService.ToteReportDTO();
        tote.code = "TOTE-02";

        HandlingOrderService.OrderReportDTO dto = new HandlingOrderService.OrderReportDTO();
        dto.orderId = 55L;
        dto.totes = List.of(tote);

        assertEquals(55L, dto.orderId);
        assertEquals(1, dto.totes.size());
        assertEquals("TOTE-02", dto.totes.get(0).code);
    }

    @Test
    void executionReportRequest_fields() {
        HandlingOrderService.ExecutionReportRequest dto = new HandlingOrderService.ExecutionReportRequest();
        dto.bufferId = 12L;
        dto.totes = List.of(new HandlingOrderService.ToteReportDTO());
        dto.remainingItems = List.of(new HandlingOrderService.PickingItemDTO());

        assertEquals(12L, dto.bufferId);
        assertEquals(1, dto.totes.size());
        assertEquals(1, dto.remainingItems.size());
    }

    @Test
    void batchValidationRequest_fields() {
        HandlingOrderService.BatchValidationRequest dto = new HandlingOrderService.BatchValidationRequest();
        dto.orderIds = List.of(1L, 2L);
        dto.bufferId = 7L;
        dto.reports = List.of(new HandlingOrderService.OrderReportDTO());

        assertEquals(List.of(1L, 2L), dto.orderIds);
        assertEquals(7L, dto.bufferId);
        assertEquals(1, dto.reports.size());
    }

    @Test
    void handlingOrderSummaryDTO_fields() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 14, 10, 0);

        HandlingOrderService.HandlingOrderSummaryDTO dto = new HandlingOrderService.HandlingOrderSummaryDTO();
        dto.id = 3L;
        dto.type = "PackingOrder";
        dto.status = "PENDING";
        dto.requestedStart = start;
        dto.requestedEnd = end;

        assertEquals(3L, dto.id);
        assertEquals("PackingOrder", dto.type);
        assertEquals("PENDING", dto.status);
        assertEquals(start, dto.requestedStart);
        assertEquals(end, dto.requestedEnd);
    }

    @Test
    void handlingExecutionDTO_constructorSetsAllFields() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 14, 9, 30);

        HandlingOrderService.HandlingExecutionDTO dto =
                new HandlingOrderService.HandlingExecutionDTO(10L, "PickingExecution", 20L, "AS10", "John Doe", created);

        assertEquals(10L, dto.id);
        assertEquals("PickingExecution", dto.type);
        assertEquals(20L, dto.orderId);
        assertEquals("AS10", dto.badgeId);
        assertEquals("John Doe", dto.employeeName);
        assertEquals(created, dto.createdAt);
    }

    @Test
    void pickingItemDTO_fields() {
        HandlingOrderService.PickingItemDTO dto = new HandlingOrderService.PickingItemDTO();
        dto.uid = 1L;
        dto.executionId = 2L;
        dto.orderId = 3L;
        dto.productName = "Pâtes";
        dto.ean = "3017620422003";
        dto.qtyRequested = new BigDecimal("4");
        dto.qtyAllocated = new BigDecimal("3");
        dto.unitLabel = "pcs";
        dto.picked = true;
        dto.aisle = "A05";
        dto.unit = "B";
        dto.pos = "05";
        dto.temperatureZone = TemperatureZone.AMBIENT;

        assertEquals(1L, dto.uid);
        assertEquals(2L, dto.executionId);
        assertEquals(3L, dto.orderId);
        assertEquals("Pâtes", dto.productName);
        assertEquals("3017620422003", dto.ean);
        assertEquals(new BigDecimal("4"), dto.qtyRequested);
        assertEquals(new BigDecimal("3"), dto.qtyAllocated);
        assertEquals("pcs", dto.unitLabel);
        assertTrue(dto.picked);
        assertEquals("A05", dto.aisle);
        assertEquals("B", dto.unit);
        assertEquals("05", dto.pos);
        assertEquals(TemperatureZone.AMBIENT, dto.temperatureZone);
    }

    @Test
    void loadedToteDTO_fields() {
        HandlingOrderService.LoadedToteDTO dto = new HandlingOrderService.LoadedToteDTO();
        dto.id = 1L;
        dto.code = "TOTE-01";
        dto.type = "INSULATED";
        dto.zone = TemperatureZone.FROZEN;
        dto.orderId = 8L;
        dto.totalWeight = new BigDecimal("12.5");
        dto.totalVolume = new BigDecimal("30.0");
        dto.items = List.of(new HandlingOrderService.ToteItemDTO());

        assertEquals(1L, dto.id);
        assertEquals("TOTE-01", dto.code);
        assertEquals("INSULATED", dto.type);
        assertEquals(TemperatureZone.FROZEN, dto.zone);
        assertEquals(8L, dto.orderId);
        assertEquals(new BigDecimal("12.5"), dto.totalWeight);
        assertEquals(new BigDecimal("30.0"), dto.totalVolume);
        assertEquals(1, dto.items.size());
    }

    @Test
    void executionSummaryDTO_fields() {
        HandlingOrderService.ExecutionSummaryDTO dto = new HandlingOrderService.ExecutionSummaryDTO();
        dto.id = 1L;
        dto.orderId = 2L;
        dto.orderCode = "2710-PAK-0042";
        dto.status = "IN_PROGRESS";

        assertEquals(1L, dto.id);
        assertEquals(2L, dto.orderId);
        assertEquals("2710-PAK-0042", dto.orderCode);
        assertEquals("IN_PROGRESS", dto.status);
    }

    @Test
    void pickingExecutionStateDTO_fields() {
        HandlingOrderService.PickingExecutionStateDTO dto = new HandlingOrderService.PickingExecutionStateDTO();
        dto.executions = List.of(new HandlingOrderService.ExecutionSummaryDTO());
        dto.items = List.of(new HandlingOrderService.PickingItemDTO());
        dto.totes = List.of(new HandlingOrderService.LoadedToteDTO());

        assertEquals(1, dto.executions.size());
        assertEquals(1, dto.items.size());
        assertEquals(1, dto.totes.size());
    }

    @Test
    void batchStartResponse_constructorSetsFields() {
        HandlingOrderService.PickingExecutionStateDTO state = new HandlingOrderService.PickingExecutionStateDTO();
        Map<Long, Long> map = Map.of(1L, 100L);

        HandlingOrderService.BatchStartResponse response = new HandlingOrderService.BatchStartResponse(map, state);

        assertSame(map, response.executionMap);
        assertSame(state, response.state);
        assertEquals(100L, response.executionMap.get(1L));
    }
}
