package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.auth.AuthenticationService;
import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.executing.GroupingExecution;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.BatchStartResponse;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.BatchValidationRequest;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.ExecutionReportRequest;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.HandlingExecutionDTO;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.HandlingOrderSummaryDTO;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.OrderReportDTO;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.PickingExecutionStateDTO;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.PickingItemDTO;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.ToteItemReportDTO;
import com.intermarche.backoffice.engine.executing.HandlingOrderService.ToteReportDTO;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HandlingOrderService}.
 * Pure unit tests — no database, no Quarkus.
 * <p>
 * Panache static finders ({@code find}, {@code findById}, {@code list}) are intercepted via
 * {@code mockStatic(PanacheEntityBase.class)} because they are declared on PanacheEntityBase
 * and inherited by every entity. {@code Employee.findByBadgeId} is declared on {@code Employee}
 * and is intercepted via {@code mockStatic(Employee.class)}.
 * <p>
 * Persistence calls ({@code entity.persist()}, {@code entity.delete()}) are routed to a mock
 * Hibernate {@link Session} via {@link PanacheTestSupport#mockArcSession(MockedStatic)}.
 * Note: {@code entity.delete()} calls {@code session.contains(entity)} first; tests that verify
 * the deletion stub {@code session.contains(entity)} to {@code true} so that
 * {@code session.remove(entity)} is invoked with the entity itself.
 */
class HandlingOrderServiceTest {

    private HandlingOrderService service;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        service = new HandlingOrderService();
        authenticationService = mock(AuthenticationService.class);
        service.authenticationService = authenticationService;
    }

    // --------------------------------------------------
    // Entity factories
    // --------------------------------------------------

    private static Employee employee(Long id, String badgeId) {
        Employee employee = new Employee();
        employee.id = id;
        employee.badgeId = badgeId;
        employee.firstName = "John";
        employee.lastName = "Doe";
        return employee;
    }

    private static Product product(Long id, String name) {
        Product product = new Product();
        product.id = id;
        product.name = name;
        product.ean = "EAN-" + id;
        product.temperatureZone = TemperatureZone.AMBIENT;
        return product;
    }

    private static Tote tote(Long id, String code) {
        Tote tote = new Tote();
        tote.id = id;
        tote.code = code;
        tote.toteType = Tote.ToteType.STANDARD;
        return tote;
    }

    private static Buffer buffer(Long id) {
        Buffer buffer = new Buffer();
        buffer.id = id;
        return buffer;
    }

    private static PickedItem item(Long id, Product product, BigDecimal quantity) {
        PickedItem item = new PickedItem();
        item.id = id;
        item.product = product;
        item.quantity = quantity;
        item.status = PickedItem.PickStatus.PENDING;
        return item;
    }

    private static LoadedTote loadedTote(Long id, Tote tote) {
        LoadedTote loadedTote = new LoadedTote();
        loadedTote.id = id;
        loadedTote.tote = tote;
        loadedTote.status = LoadedTote.Status.IN_USE;
        loadedTote.storageType = TemperatureZone.AMBIENT;
        return loadedTote;
    }

    private static BaseHandlingOrder orderMock(Long id, String code, BaseHandlingOrder.HandlingOrderStatus status) {
        BaseHandlingOrder order = mock(BaseHandlingOrder.class);
        order.id = id;
        order.code = code;
        order.status = status;
        return order;
    }

    private static PickingExecution pickingExecution(Long id, BaseHandlingOrder order) {
        PickingExecution execution = new PickingExecution();
        execution.id = id;
        execution.handlingOrder = order;
        return execution;
    }

    private static PickedItem itemWithAisle(Long id, Product product, BigDecimal quantity, String aisle) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.aisle = aisle;
        location.unit = "U-" + aisle;
        location.position = "P-" + aisle;
        FulfillmentLine line = new FulfillmentLine();
        line.location = location;
        LotLine lotLine = new LotLine();
        lotLine.id = id + 1000;
        lotLine.fulfillmentLine = line;
        lotLine.product = product;
        PickedItem item = item(id, product, quantity);
        item.lotLine = lotLine;
        return item;
    }

    // --------------------------------------------------
    // Panache stubbing helpers
    // --------------------------------------------------

    /**
     * A stubbed {@code find(query, params)} result, keyed by query string and first parameter.
     *
     * @param query      the HQL query string
     * @param firstParam the expected first vararg parameter (null = match any)
     * @param firstResult the entity returned by {@code firstResult()} (null = not found)
     */
    private record FindEntry(String query, Object firstParam, Object firstResult) {
    }

    @SuppressWarnings("unchecked")
    private static PanacheQuery<PanacheEntityBase> firstResultQuery(Object firstResult) {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn((PanacheEntityBase) firstResult);
        return query;
    }

    /**
     * Stubs {@code PanacheEntityBase.find(String, Object...)} so that each entry matches on the
     * query string and (optionally) the first vararg parameter. Unmatched queries return null.
     */
    private static void stubFind(MockedStatic<PanacheEntityBase> panacheMock, List<FindEntry> entries) {
        List<PanacheQuery<PanacheEntityBase>> queries = new ArrayList<>();
        for (FindEntry entry : entries) {
            queries.add(firstResultQuery(entry.firstResult()));
        }
        PanacheQuery<PanacheEntityBase> emptyQuery = firstResultQuery(null);
        panacheMock.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    // Mockito déplie les varargs : getArguments() = [query, param1, param2, ...]
                    Object[] args = invocation.getArguments();
                    String query = (String) args[0];
                    Object firstParam = (args.length > 1) ? args[1] : null;
                    for (int i = 0; i < entries.size(); i++) {
                        FindEntry entry = entries.get(i);
                        if (entry.query().equals(query)
                                && (entry.firstParam() == null || entry.firstParam().equals(firstParam))) {
                            return queries.get(i);
                        }
                    }
                    return emptyQuery;
                });
    }

    /**
     * Stubs {@code PanacheEntityBase.findById(Object)} with a per-id lookup map.
     * Each test uses distinct ids per entity type, so a single map is enough.
     */
    private static void stubFindById(MockedStatic<PanacheEntityBase> panacheMock, Map<Long, Object> byId) {
        panacheMock.when(() -> PanacheEntityBase.findById(any()))
                .thenAnswer(invocation -> byId.get(invocation.getArgument(0)));
    }

    /**
     * Stubs {@code PanacheEntityBase.list(String, Object...)} with a per-query lookup map.
     */
    private static void stubList(MockedStatic<PanacheEntityBase> panacheMock, Map<String, List<?>> byQuery) {
        panacheMock.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> byQuery.getOrDefault(invocation.getArgument(0), List.of()));
    }

    // --------------------------------------------------
    // listAvailableOrders
    // --------------------------------------------------

    @Test
    void listAvailableOrders_noOrders_returnsEmptyList() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of());

            List<HandlingOrderSummaryDTO> result = service.listAvailableOrders();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Test
    void listAvailableOrders_ordersWithoutExecution_mapsToSummaryDTOs() {
        GroupingOrder order1 = new GroupingOrder();
        order1.id = 1L;
        order1.code = "1410-GRP-0001";
        order1.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        order1.requestedStart = LocalDateTime.of(2026, 10, 14, 10, 0);
        order1.requestedEnd = LocalDateTime.of(2026, 10, 14, 12, 0);
        GroupingOrder order2 = new GroupingOrder();
        order2.id = 2L;
        order2.code = "1410-GRP-0002";
        order2.status = BaseHandlingOrder.HandlingOrderStatus.OPEN;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of(
                    "id not in (select he.handlingOrder.id from HandlingExecution he)",
                    List.of(order1, order2)));

            List<HandlingOrderSummaryDTO> result = service.listAvailableOrders();

            assertEquals(2, result.size());
            HandlingOrderSummaryDTO dto1 = result.get(0);
            assertEquals(1L, dto1.id);
            assertEquals("GroupingOrder", dto1.type);
            assertEquals("SCHEDULED", dto1.status);
            assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), dto1.requestedStart);
            assertEquals(LocalDateTime.of(2026, 10, 14, 12, 0), dto1.requestedEnd);
            HandlingOrderSummaryDTO dto2 = result.get(1);
            assertEquals(2L, dto2.id);
            assertEquals("OPEN", dto2.status);
            assertNull(dto2.requestedStart);
            assertNull(dto2.requestedEnd);
        }
    }

    // --------------------------------------------------
    // reserveOrder
    // --------------------------------------------------

    @Test
    void reserveOrder_orderNotFound_throwsISE() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.reserveOrder(1L, "BADGE-1"));
            assertEquals("Order not found", ex.getMessage());
        }
    }

    @Test
    void reserveOrder_orderNotScheduled_throwsISE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.OPEN);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(1L, order));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.reserveOrder(1L, "BADGE-1"));
            assertEquals("Order is not yet scheduled", ex.getMessage());
        }
    }

    @Test
    void reserveOrder_employeeNotFound_throwsISE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Employee> employeeMock = mockStatic(Employee.class)) {
            stubFindById(panacheMock, Map.of(1L, order));
            employeeMock.when(() -> Employee.findByBadgeId("BADGE-1")).thenReturn(null);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.reserveOrder(1L, "BADGE-1"));
            assertEquals("Employee not found", ex.getMessage());
        }
    }

    @Test
    void reserveOrder_validOrder_createsExecutionAndReturnsDTO() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        PickingExecution execution = pickingExecution(100L, order);
        execution.createdAt = LocalDateTime.of(2026, 10, 14, 9, 30);
        when(order.createExecution(employee)).thenReturn(execution);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Employee> employeeMock = mockStatic(Employee.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(1L, order));
            employeeMock.when(() -> Employee.findByBadgeId("BADGE-1")).thenReturn(employee);

            HandlingExecutionDTO dto = service.reserveOrder(1L, "BADGE-1");

            assertEquals(100L, dto.id);
            assertEquals("PickingExecution", dto.type);
            assertEquals(1L, dto.orderId);
            assertEquals("BADGE-1", dto.badgeId);
            assertEquals("John Doe", dto.employeeName);
            assertEquals(LocalDateTime.of(2026, 10, 14, 9, 30), dto.createdAt);
            verify(session).persist(execution);
        }
    }

    // --------------------------------------------------
    // cancelReservation
    // --------------------------------------------------

    @Test
    void cancelReservation_executionExists_deletesIt() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, execution)));
            // delete() appelle session.contains(entity) avant session.remove(entity)
            when(session.contains(execution)).thenReturn(true);

            service.cancelReservation(1L);

            verify(session).remove(execution);
        }
    }

    @Test
    void cancelReservation_noExecution_doesNothing() {
        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            assertDoesNotThrow(() -> service.cancelReservation(1L));

            verify(session, never()).remove(any());
        }
    }

    // --------------------------------------------------
    // startBatchExecutions
    // --------------------------------------------------

    @Test
    void startBatchExecutions_nullOrderIds_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> service.startBatchExecutions(null, "BADGE-1"));
    }

    @Test
    void startBatchExecutions_emptyOrderIds_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> service.startBatchExecutions(List.of(), "BADGE-1"));
    }

    @Test
    void startBatchExecutions_validOrders_createsExecutionsAndReturnsState() {
        BaseHandlingOrder order1 = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        BaseHandlingOrder order2 = orderMock(2L, "ORD-2", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        PickingExecution exec1 = pickingExecution(100L, order1);
        PickingExecution exec2 = pickingExecution(101L, order2);
        when(order1.createExecution(employee)).thenReturn(exec1);
        when(order2.createExecution(employee)).thenReturn(exec2);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Employee> employeeMock = mockStatic(Employee.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(1L, order1, 2L, order2, 100L, exec1, 101L, exec2));
            employeeMock.when(() -> Employee.findByBadgeId("BADGE-1")).thenReturn(employee);

            BatchStartResponse response = service.startBatchExecutions(List.of(1L, 2L), "BADGE-1");

            assertEquals(2, response.executionMap.size());
            assertEquals(100L, response.executionMap.get(1L));
            assertEquals(101L, response.executionMap.get(2L));
            assertNotNull(response.state);
            assertEquals(2, response.state.executions.size());
            assertEquals(100L, response.state.executions.get(0).id);
            assertEquals(1L, response.state.executions.get(0).orderId);
            assertEquals("ORD-1", response.state.executions.get(0).orderCode);
            assertEquals("IN_PROGRESS", response.state.executions.get(0).status);
            assertEquals(101L, response.state.executions.get(1).id);
            assertEquals("ORD-2", response.state.executions.get(1).orderCode);
            assertTrue(response.state.items.isEmpty());
            assertTrue(response.state.totes.isEmpty());
            verify(session).persist(exec1);
            verify(session).persist(exec2);
        }
    }

    // --------------------------------------------------
    // getBatchState
    // --------------------------------------------------

    @Test
    void getBatchState_noExecutions_returnsEmptyState() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of());

            PickingExecutionStateDTO state = service.getBatchState(List.of(100L));

            assertNotNull(state);
            assertTrue(state.executions.isEmpty());
            assertTrue(state.items.isEmpty());
            assertTrue(state.totes.isEmpty());
        }
    }

    @Test
    void getBatchState_nullExecutionIds_returnsEmptyStateWithoutQuery() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PickingExecutionStateDTO state = service.getBatchState(null);

            assertNotNull(state);
            assertTrue(state.executions.isEmpty());
            assertTrue(state.items.isEmpty());
            assertTrue(state.totes.isEmpty());
            panacheMock.verifyNoInteractions();
        }
    }

    @Test
    void getBatchState_emptyExecutionIds_returnsEmptyStateWithoutQuery() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PickingExecutionStateDTO state = service.getBatchState(List.of());

            assertNotNull(state);
            assertTrue(state.executions.isEmpty());
            assertTrue(state.items.isEmpty());
            assertTrue(state.totes.isEmpty());
            panacheMock.verifyNoInteractions();
        }
    }

    @Test
    void getBatchState_executionsWithItemsAndTotes_aggregatesAndSortsByAisle() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);

        Product pasta = product(1L, "Pâtes");
        pasta.referenceWeight = new BigDecimal("0.5");
        pasta.referenceVolume = new BigDecimal("0.001");
        Product milk = product(2L, "Lait");
        Product soap = product(3L, "Savon");
        soap.referenceWeight = new BigDecimal("1.0");
        soap.referenceVolume = new BigDecimal("0.002");

        PickedItem itemA = itemWithAisle(10L, pasta, new BigDecimal("2"), "A02");
        PickedItem itemB = itemWithAisle(11L, milk, new BigDecimal("1"), "A01");
        // Item sans emplacement de rayon : trié en dernier ("ZZZ"), déjà placé dans un bac
        PickedItem itemC = item(12L, soap, new BigDecimal("3"));
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote loadedTote = loadedTote(50L, physicalTote);
        loadedTote.items.add(itemC);
        itemC.loadedTote = loadedTote;

        execution.itemsToPick.add(itemA);
        execution.itemsToPick.add(itemB);
        execution.itemsToPick.add(itemC);
        execution.loadedTotes.add(loadedTote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of("id in ?1", List.of(execution)));

            PickingExecutionStateDTO state = service.getBatchState(List.of(100L));

            // Résumé de l'exécution
            assertEquals(1, state.executions.size());
            assertEquals(100L, state.executions.get(0).id);
            assertEquals(1L, state.executions.get(0).orderId);
            assertEquals("ORD-1", state.executions.get(0).orderCode);
            assertEquals("IN_PROGRESS", state.executions.get(0).status);

            // Items triés par rayon : A01, A02, puis null (ZZZ) en dernier
            assertEquals(3, state.items.size());
            PickingItemDTO dtoB = state.items.get(0);
            assertEquals(11L, dtoB.uid);
            assertEquals(100L, dtoB.executionId);
            assertEquals(1L, dtoB.orderId);
            assertEquals("Lait", dtoB.productName);
            assertEquals("EAN-2", dtoB.ean);
            assertEquals(TemperatureZone.AMBIENT, dtoB.temperatureZone);
            assertEquals(0, dtoB.qtyRequested.compareTo(new BigDecimal("1")));
            assertEquals("pcs", dtoB.unitLabel);
            assertFalse(dtoB.picked);
            assertEquals(0, dtoB.qtyAllocated.compareTo(BigDecimal.ZERO));
            assertEquals("A01", dtoB.aisle);
            assertEquals("U-A01", dtoB.unit);
            assertEquals("P-A01", dtoB.pos);

            PickingItemDTO dtoA = state.items.get(1);
            assertEquals(10L, dtoA.uid);
            assertEquals("Pâtes", dtoA.productName);
            assertEquals("A02", dtoA.aisle);

            PickingItemDTO dtoC = state.items.get(2);
            assertEquals(12L, dtoC.uid);
            assertNull(dtoC.aisle);
            assertTrue(dtoC.picked);
            assertEquals(0, dtoC.qtyAllocated.compareTo(new BigDecimal("3")));

            // Bac avec totaux poids/volume calculés
            assertEquals(1, state.totes.size());
            var toteDto = state.totes.get(0);
            assertEquals(50L, toteDto.id);
            assertEquals("TOTE-1", toteDto.code);
            assertEquals("STANDARD", toteDto.type);
            assertEquals(TemperatureZone.AMBIENT, toteDto.zone);
            assertEquals(1L, toteDto.orderId);
            assertEquals(1, toteDto.items.size());
            assertEquals(12L, toteDto.items.get(0).pickedItemId);
            assertEquals("Savon", toteDto.items.get(0).productName);
            assertEquals(0, toteDto.totalWeight.compareTo(new BigDecimal("3.0")));
            assertEquals(0, toteDto.totalVolume.compareTo(new BigDecimal("0.006")));
        }
    }

    // --------------------------------------------------
    // substituteProduct
    // --------------------------------------------------

    @Test
    void substituteProduct_itemNotFound_throwsIAE() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.substituteProduct(100L, 10L, 20L));
            assertEquals("PickedItem not found", ex.getMessage());
        }
    }

    @Test
    void substituteProduct_productNotFound_throwsIAE() {
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("2"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(10L, item));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.substituteProduct(100L, 10L, 20L));
            assertEquals("Product not found", ex.getMessage());
        }
    }

    @Test
    void substituteProduct_executionNotFound_throwsIAE() {
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("2"));
        Product newProduct = product(20L, "Riz");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(10L, item, 20L, newProduct));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.substituteProduct(100L, 10L, 20L));
            assertEquals("Item does not belong to the specified execution", ex.getMessage());
        }
    }

    @Test
    void substituteProduct_itemNotInExecution_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("2"));
        Product newProduct = product(20L, "Riz");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(10L, item, 20L, newProduct, 100L, execution));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.substituteProduct(100L, 10L, 20L));
            assertEquals("Item does not belong to the specified execution", ex.getMessage());
        }
    }

    @Test
    void substituteProduct_validItem_replacesProduct() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("2"));
        execution.itemsToPick.add(item);
        Product newProduct = product(20L, "Riz");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(10L, item, 20L, newProduct, 100L, execution));

            service.substituteProduct(100L, 10L, 20L);

            assertSame(newProduct, item.product);
        }
    }

    // --------------------------------------------------
    // addTote
    // --------------------------------------------------

    @Test
    void addTote_executionNotFound_throwsIAE() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.addTote(100L, "TOTE-1", TemperatureZone.AMBIENT));
            assertEquals("Execution not found", ex.getMessage());
        }
    }

    @Test
    void addTote_toteNotFound_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(new FindEntry("code", "TOTE-1", null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.addTote(100L, "TOTE-1", TemperatureZone.AMBIENT));
            assertEquals("Tote not found with code: TOTE-1", ex.getMessage());
        }
    }

    @Test
    void addTote_availableTote_createsLoadedToteAndPersists() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("code", "TOTE-1", physicalTote),
                    // useAnEmptyTote vérifie qu'aucun LoadedTote IN_USE n'existe pour ce bac
                    new FindEntry("tote = ?1 and status = ?2", physicalTote, null)));

            service.addTote(100L, "TOTE-1", TemperatureZone.CHILLED);

            assertEquals(1, execution.loadedTotes.size());
            LoadedTote loadedTote = execution.loadedTotes.iterator().next();
            assertSame(physicalTote, loadedTote.tote);
            assertEquals(LoadedTote.Status.IN_USE, loadedTote.status);
            assertEquals(TemperatureZone.CHILLED, loadedTote.storageType);
            verify(session).persist(loadedTote);
        }
    }

    @Test
    void addTote_toteAlreadyInUse_throwsISE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote existing = loadedTote(50L, physicalTote);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("code", "TOTE-1", physicalTote),
                    new FindEntry("tote = ?1 and status = ?2", physicalTote, existing)));

            assertThrows(IllegalStateException.class,
                    () -> service.addTote(100L, "TOTE-1", TemperatureZone.AMBIENT));

            assertTrue(execution.loadedTotes.isEmpty());
            verify(session, never()).persist(any());
        }
    }

    // --------------------------------------------------
    // removeEmptyTote
    // --------------------------------------------------

    @Test
    void removeEmptyTote_executionNotFound_throwsIAE() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.removeEmptyTote(100L, "TOTE-1"));
            assertEquals("Execution not found", ex.getMessage());
        }
    }

    @Test
    void removeEmptyTote_loadedToteNotFound_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.removeEmptyTote(100L, "TOTE-1"));
            assertEquals("LoadedTote not found", ex.getMessage());
        }
    }

    @Test
    void removeEmptyTote_emptyTote_removesAndDeletesIt() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote loadedTote = loadedTote(50L, physicalTote);
        execution.loadedTotes.add(loadedTote);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", loadedTote)));
            when(session.contains(loadedTote)).thenReturn(true);

            service.removeEmptyTote(100L, "TOTE-1");

            assertTrue(execution.loadedTotes.isEmpty());
            verify(session).remove(loadedTote);
        }
    }

    @Test
    void removeEmptyTote_toteWithItems_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote loadedTote = loadedTote(50L, physicalTote);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("2"));
        loadedTote.items.add(item);
        item.loadedTote = loadedTote;
        execution.loadedTotes.add(loadedTote);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", loadedTote)));

            assertThrows(IllegalArgumentException.class,
                    () -> service.removeEmptyTote(100L, "TOTE-1"));

            assertEquals(1, execution.loadedTotes.size());
            verify(session, never()).remove(any());
        }
    }

    // --------------------------------------------------
    // addItemToTote
    // --------------------------------------------------

    @Test
    void addItemToTote_executionNotFound_throwsIAE() {
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote tote = loadedTote(50L, physicalTote);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", tote)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.addItemToTote(100L, "TOTE-1", 10L, new BigDecimal("5"), null));
            assertEquals("Invalid execution, tote, or item", ex.getMessage());
        }
    }

    @Test
    void addItemToTote_toteNotFound_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.addItemToTote(100L, "TOTE-1", 10L, new BigDecimal("5"), null));
            assertEquals("Invalid execution, tote, or item", ex.getMessage());
        }
    }

    @Test
    void addItemToTote_itemNotFound_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote tote = loadedTote(50L, physicalTote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", tote)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.addItemToTote(100L, "TOTE-1", 10L, new BigDecimal("5"), null));
            assertEquals("Invalid execution, tote, or item", ex.getMessage());
        }
    }

    @Test
    void addItemToTote_productNotFound_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote tote = loadedTote(50L, physicalTote);
        execution.loadedTotes.add(tote);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        execution.itemsToPick.add(item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", tote)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.addItemToTote(100L, "TOTE-1", 10L, new BigDecimal("5"), 99L));
            assertEquals("Product not found: 99", ex.getMessage());
        }
    }

    @Test
    void addItemToTote_fullQuantity_movesItemToTote() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote tote = loadedTote(50L, physicalTote);
        execution.loadedTotes.add(tote);
        Product pasta = product(1L, "Pâtes");
        PickedItem item = item(10L, pasta, new BigDecimal("5"));
        execution.itemsToPick.add(item);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", tote)));

            service.addItemToTote(100L, "TOTE-1", 10L, new BigDecimal("5"), null);

            assertSame(tote, item.loadedTote);
            assertTrue(tote.items.contains(item));
            assertFalse(execution.itemsToPick.contains(item));
            assertEquals(PickedItem.PickStatus.PICKED, item.status);
            assertSame(pasta, item.product);
        }
    }

    @Test
    void addItemToTote_withSubstitution_replacesProduct() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote tote = loadedTote(50L, physicalTote);
        execution.loadedTotes.add(tote);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        execution.itemsToPick.add(item);
        Product rice = product(20L, "Riz");

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item, 20L, rice));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", tote)));

            service.addItemToTote(100L, "TOTE-1", 10L, new BigDecimal("5"), 20L);

            assertSame(tote, item.loadedTote);
            assertSame(rice, item.product);
            assertEquals(PickedItem.PickStatus.PICKED, item.status);
        }
    }

    @Test
    void addItemToTote_partialQuantity_splitsItem() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote tote = loadedTote(50L, physicalTote);
        execution.loadedTotes.add(tote);
        Product pasta = product(1L, "Pâtes");
        PickedItem item = item(10L, pasta, new BigDecimal("5"));
        execution.itemsToPick.add(item);

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TOTE-1", tote)));

            service.addItemToTote(100L, "TOTE-1", 10L, new BigDecimal("2"), null);

            // L'item original reste dans la liste de travail avec la quantité restante
            assertTrue(execution.itemsToPick.contains(item));
            assertEquals(0, item.quantity.compareTo(new BigDecimal("3")));
            assertNull(item.loadedTote);
            // Un nouvel item est créé pour la quantité déplacée
            assertEquals(1, tote.items.size());
            PickedItem moved = tote.items.iterator().next();
            assertNotSame(item, moved);
            assertEquals(0, moved.quantity.compareTo(new BigDecimal("2")));
            assertEquals(PickedItem.PickStatus.PICKED, moved.status);
            assertSame(pasta, moved.product);
            assertSame(tote, moved.loadedTote);
            verify(session).persist(moved);
        }
    }

    // --------------------------------------------------
    // moveItem
    // --------------------------------------------------

    private static ProductCollectingOrder collectingOrderWithLotLine(Long orderId, LotLine lotLine) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.id = orderId;
        Lot lot = new Lot();
        lot.id = 50L;
        lot.lines.add(lotLine);
        order.lots.add(lot);
        return order;
    }

    @Test
    void moveItem_executionNotFound_throwsIAE() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.moveItem(100L, null, "TGT", 10L));
            assertEquals("Execution not found", ex.getMessage());
        }
    }

    @Test
    void moveItem_itemNotFound_throwsIAE() {
        LotLine lotLine = new LotLine();
        lotLine.id = 500L;
        ProductCollectingOrder order = collectingOrderWithLotLine(1L, lotLine);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TGT");
        LoadedTote targetTote = loadedTote(50L, physicalTote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TGT", targetTote)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.moveItem(100L, null, "TGT", 10L));
            assertEquals("Item or Target Tote not found", ex.getMessage());
        }
    }

    @Test
    void moveItem_targetToteNotFound_throwsIAE() {
        LotLine lotLine = new LotLine();
        lotLine.id = 500L;
        ProductCollectingOrder order = collectingOrderWithLotLine(1L, lotLine);
        PickingExecution execution = pickingExecution(100L, order);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        item.lotLine = lotLine;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TGT", null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.moveItem(100L, null, "TGT", 10L));
            assertEquals("Item or Target Tote not found", ex.getMessage());
        }
    }

    @Test
    void moveItem_nullSourceTote_movesItem() {
        LotLine lotLine = new LotLine();
        lotLine.id = 500L;
        ProductCollectingOrder order = collectingOrderWithLotLine(1L, lotLine);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TGT");
        LoadedTote targetTote = loadedTote(50L, physicalTote);
        execution.loadedTotes.add(targetTote);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        item.lotLine = lotLine;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TGT", targetTote)));

            service.moveItem(100L, null, "TGT", 10L);

            assertSame(targetTote, item.loadedTote);
            assertTrue(targetTote.items.contains(item));
        }
    }

    @Test
    void moveItem_blankSourceTote_movesItem() {
        LotLine lotLine = new LotLine();
        lotLine.id = 500L;
        ProductCollectingOrder order = collectingOrderWithLotLine(1L, lotLine);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TGT");
        LoadedTote targetTote = loadedTote(50L, physicalTote);
        execution.loadedTotes.add(targetTote);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        item.lotLine = lotLine;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "TGT", targetTote)));

            service.moveItem(100L, "  ", "TGT", 10L);

            assertSame(targetTote, item.loadedTote);
            assertTrue(targetTote.items.contains(item));
        }
    }

    @Test
    void moveItem_withSourceTote_movesItemBetweenTotes() {
        LotLine lotLine = new LotLine();
        lotLine.id = 500L;
        ProductCollectingOrder order = collectingOrderWithLotLine(1L, lotLine);
        PickingExecution execution = pickingExecution(100L, order);
        LoadedTote sourceTote = loadedTote(51L, tote(61L, "SRC"));
        LoadedTote targetTote = loadedTote(50L, tote(60L, "TGT"));
        execution.loadedTotes.add(sourceTote);
        execution.loadedTotes.add(targetTote);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        item.lotLine = lotLine;
        sourceTote.items.add(item);
        item.loadedTote = sourceTote;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1 and status = ?2", "SRC", sourceTote),
                    new FindEntry("tote.code = ?1 and status = ?2", "TGT", targetTote)));

            service.moveItem(100L, "SRC", "TGT", 10L);

            assertTrue(sourceTote.items.isEmpty());
            assertSame(targetTote, item.loadedTote);
            assertTrue(targetTote.items.contains(item));
        }
    }

    // --------------------------------------------------
    // removeItemFromTote
    // --------------------------------------------------

    @Test
    void removeItemFromTote_itemNotFound_throwsIAE() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.removeItemFromTote(100L, "TOTE-1", 10L));
            assertEquals("Item not found", ex.getMessage());
        }
    }

    @Test
    void removeItemFromTote_itemWithoutTote_doesNothing() {
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(10L, item));

            assertDoesNotThrow(() -> service.removeItemFromTote(100L, "TOTE-1", 10L));

            assertNull(item.loadedTote);
        }
    }

    @Test
    void removeItemFromTote_itemInTote_removesItFromTote() {
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote loadedTote = loadedTote(50L, physicalTote);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        loadedTote.items.add(item);
        item.loadedTote = loadedTote;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(10L, item));

            service.removeItemFromTote(100L, "TOTE-1", 10L);

            assertTrue(loadedTote.items.isEmpty());
            assertNull(item.loadedTote);
        }
    }

    // --------------------------------------------------
    // cancelPickedItem
    // --------------------------------------------------

    @Test
    void cancelPickedItem_itemNotFound_throwsIAE() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.cancelPickedItem(100L, 10L));
            assertEquals("Item not found", ex.getMessage());
        }
    }

    @Test
    void cancelPickedItem_validItem_marksCanceled() {
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(10L, item));

            service.cancelPickedItem(100L, 10L);

            assertEquals(PickedItem.PickStatus.CANCELED, item.status);
        }
    }

    // --------------------------------------------------
    // concludePickingExecution
    // --------------------------------------------------

    @Test
    void concludePickingExecution_executionNotFound_throwsIAE() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.concludePickingExecution(100L, 7L));
            assertEquals("Execution or Buffer not found", ex.getMessage());
        }
    }

    @Test
    void concludePickingExecution_bufferNotFound_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.concludePickingExecution(100L, 7L));
            assertEquals("Execution or Buffer not found", ex.getMessage());
        }
    }

    @Test
    void concludePickingExecution_validExecution_assignsBufferAndMarksDone() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        item.status = PickedItem.PickStatus.PICKED;
        execution.itemsToPick.add(item);
        LoadedTote loadedTote = loadedTote(50L, tote(60L, "TOTE-1"));
        execution.loadedTotes.add(loadedTote);
        Buffer buffer = buffer(7L);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 7L, buffer));

            service.concludePickingExecution(100L, 7L);

            assertSame(buffer, loadedTote.buffer);
            assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
        }
    }

    @Test
    void concludePickingExecution_pendingItems_throwsISE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        execution.itemsToPick.add(item);
        Buffer buffer = buffer(7L);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 7L, buffer));

            assertThrows(IllegalStateException.class,
                    () -> service.concludePickingExecution(100L, 7L));

            assertEquals(HandlingExecution.ExecutionStatus.IN_PROGRESS, execution.status);
        }
    }

    // --------------------------------------------------
    // moveToteToDestination
    // --------------------------------------------------

    @Test
    void moveToteToDestination_executionNotFound_doesNothing() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            assertDoesNotThrow(() -> service.moveToteToDestination(100L, "TOTE-1"));
        }
    }

    @Test
    void moveToteToDestination_notGroupingExecution_doesNothing() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));

            assertDoesNotThrow(() -> service.moveToteToDestination(100L, "TOTE-1"));
        }
    }

    @Test
    void moveToteToDestination_toteNotFound_doesNothing() {
        GroupingExecution execution = new GroupingExecution();
        execution.id = 100L;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(new FindEntry("code", "TOTE-1", null)));

            assertDoesNotThrow(() -> service.moveToteToDestination(100L, "TOTE-1"));
        }
    }

    @Test
    void moveToteToDestination_groupingExecution_assignsDestinationBuffer() {
        Buffer destination = buffer(7L);
        GroupingOrder order = new GroupingOrder();
        order.id = 1L;
        order.buffer = destination;
        GroupingExecution execution = new GroupingExecution();
        execution.id = 100L;
        execution.handlingOrder = order;
        Tote physicalTote = tote(60L, "TOTE-1");
        LoadedTote loadedTote = loadedTote(50L, physicalTote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("code", "TOTE-1", physicalTote),
                    new FindEntry("tote = ?1 and status = ?2", physicalTote, loadedTote)));

            service.moveToteToDestination(100L, "TOTE-1");

            assertSame(destination, loadedTote.buffer);
        }
    }

    // --------------------------------------------------
    // concludeGroupingExecution
    // --------------------------------------------------

    @Test
    void concludeGroupingExecution_executionNotFound_doesNothing() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            assertDoesNotThrow(() -> service.concludeGroupingExecution(100L));
        }
    }

    @Test
    void concludeGroupingExecution_notGroupingExecution_doesNothing() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));

            assertDoesNotThrow(() -> service.concludeGroupingExecution(100L));
        }
    }

    @Test
    void concludeGroupingExecution_allTotesAtDestination_marksDone() {
        Buffer destination = buffer(7L);
        GroupingOrder order = new GroupingOrder();
        order.id = 1L;
        order.buffer = destination;
        order.status = BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        GroupingExecution execution = new GroupingExecution();
        execution.id = 100L;
        execution.handlingOrder = order;
        LoadedTote loadedTote = loadedTote(50L, tote(60L, "TOTE-1"));
        loadedTote.buffer = destination;
        execution.loadedTotes.add(loadedTote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));

            service.concludeGroupingExecution(100L);

            assertEquals(BaseHandlingOrder.HandlingOrderStatus.DONE, order.status);
            assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
        }
    }

    @Test
    void concludeGroupingExecution_toteNotAtDestination_throwsISE() {
        Buffer destination = buffer(7L);
        GroupingOrder order = new GroupingOrder();
        order.id = 1L;
        order.buffer = destination;
        GroupingExecution execution = new GroupingExecution();
        execution.id = 100L;
        execution.handlingOrder = order;
        LoadedTote loadedTote = loadedTote(50L, tote(60L, "TOTE-1"));
        loadedTote.buffer = buffer(8L);
        execution.loadedTotes.add(loadedTote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));

            assertThrows(IllegalStateException.class,
                    () -> service.concludeGroupingExecution(100L));
        }
    }

    // --------------------------------------------------
    // saveAndConcludeExecution
    // --------------------------------------------------

    private static ToteItemReportDTO toteItem(Long pickedItemId, String qty) {
        ToteItemReportDTO dto = new ToteItemReportDTO();
        dto.pickedItemId = pickedItemId;
        dto.qty = new BigDecimal(qty);
        return dto;
    }

    private static ToteReportDTO toteReport(String code, TemperatureZone zone, List<ToteItemReportDTO> items) {
        ToteReportDTO dto = new ToteReportDTO();
        dto.code = code;
        dto.zone = zone;
        dto.items = items;
        return dto;
    }

    @Test
    void saveAndConcludeExecution_executionNotFound_throwsIAE() {
        ExecutionReportRequest request = new ExecutionReportRequest();

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.saveAndConcludeExecution(100L, request));
            assertEquals("Execution not found", ex.getMessage());
        }
    }

    @Test
    void saveAndConcludeExecution_nullRequest_throwsIAE() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.saveAndConcludeExecution(100L, null));
        assertEquals("Request cannot be null", ex.getMessage());
    }

    @Test
    void saveAndConcludeExecution_nullToteReport_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        List<ToteReportDTO> totes = new ArrayList<>();
        totes.add(null);
        request.totes = totes;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.saveAndConcludeExecution(100L, request));
            assertEquals("Tote report cannot be null", ex.getMessage());
        }
    }

    @Test
    void saveAndConcludeExecution_nullItemReport_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        LoadedTote loadedTote = loadedTote(50L, tote(60L, "TOTE-1"));
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        List<ToteItemReportDTO> items = new ArrayList<>();
        items.add(null);
        request.totes = List.of(toteReport("TOTE-1", TemperatureZone.AMBIENT, items));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1", "TOTE-1", loadedTote)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.saveAndConcludeExecution(100L, request));
            assertEquals("Item report cannot be null", ex.getMessage());
        }
    }

    @Test
    void saveAndConcludeExecution_noBufferNoTotes_marksDoneOnly() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        request.totes = null;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));

            service.saveAndConcludeExecution(100L, request);

            assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
        }
    }

    @Test
    void saveAndConcludeExecution_withBuffer_concludesExecution() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Buffer buffer = buffer(7L);
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = 7L;
        request.totes = null;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 7L, buffer));

            service.saveAndConcludeExecution(100L, request);

            assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
        }
    }

    @Test
    void saveAndConcludeExecution_existingLoadedTote_assignsItems() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        LoadedTote loadedTote = loadedTote(50L, tote(60L, "TOTE-1"));
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        request.totes = List.of(toteReport("TOTE-1", TemperatureZone.CHILLED,
                List.of(toteItem(10L, "2"))));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1", "TOTE-1", loadedTote)));

            service.saveAndConcludeExecution(100L, request);

            assertSame(loadedTote, item.loadedTote);
            assertEquals(PickedItem.PickStatus.PICKED, item.status);
            assertEquals(0, item.quantity.compareTo(new BigDecimal("2")));
            assertTrue(loadedTote.items.contains(item));
            // Le LoadedTote existait déjà : pas de nouvelle persistance
            verify(session, never()).persist(any());
        }
    }

    @Test
    void saveAndConcludeExecution_unknownToteCode_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        request.totes = List.of(toteReport("TOTE-X", TemperatureZone.AMBIENT, List.of()));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1", "TOTE-X", null),
                    new FindEntry("code", "TOTE-X", null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.saveAndConcludeExecution(100L, request));
            assertEquals("Tote not found: TOTE-X", ex.getMessage());
        }
    }

    @Test
    void saveAndConcludeExecution_unknownTote_createsLoadedTote() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        Tote physicalTote = tote(60L, "TOTE-1");
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        request.totes = List.of(toteReport("TOTE-1", TemperatureZone.CHILLED,
                List.of(toteItem(10L, "2"))));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1", "TOTE-1", null),
                    new FindEntry("code", "TOTE-1", physicalTote),
                    new FindEntry("tote = ?1 and status = ?2", physicalTote, null)));

            service.saveAndConcludeExecution(100L, request);

            assertEquals(1, execution.loadedTotes.size());
            LoadedTote loadedTote = execution.loadedTotes.iterator().next();
            assertSame(physicalTote, loadedTote.tote);
            assertEquals(TemperatureZone.CHILLED, loadedTote.storageType);
            assertSame(loadedTote, item.loadedTote);
            assertEquals(PickedItem.PickStatus.PICKED, item.status);
            verify(session).persist(loadedTote);
        }
    }

    @Test
    void saveAndConcludeExecution_unknownItemId_skipsItem() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        LoadedTote loadedTote = loadedTote(50L, tote(60L, "TOTE-1"));
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        request.totes = List.of(toteReport("TOTE-1", TemperatureZone.AMBIENT,
                List.of(toteItem(999L, "1"))));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(
                    new FindEntry("tote.code = ?1", "TOTE-1", loadedTote)));

            assertDoesNotThrow(() -> service.saveAndConcludeExecution(100L, request));

            assertTrue(loadedTote.items.isEmpty());
        }
    }

    // --------------------------------------------------
    // createAndConcludeBatch
    // --------------------------------------------------

    private static OrderReportDTO orderReport(Long orderId, List<ToteReportDTO> totes) {
        OrderReportDTO dto = new OrderReportDTO();
        dto.orderId = orderId;
        dto.totes = totes;
        return dto;
    }

    @Test
    void createAndConcludeBatch_nullOrderIds_throwsIAE() {
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = null;

        assertThrows(IllegalArgumentException.class,
                () -> service.createAndConcludeBatch(request));
    }

    @Test
    void createAndConcludeBatch_emptyOrderIds_throwsIAE() {
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of();

        assertThrows(IllegalArgumentException.class,
                () -> service.createAndConcludeBatch(request));
    }

    @Test
    void createAndConcludeBatch_nullRequest_throwsIAE() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createAndConcludeBatch(null));
        assertEquals("Request cannot be null", ex.getMessage());
    }

    @Test
    void createAndConcludeBatch_nullReport_throwsIAE() {
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        List<OrderReportDTO> reports = new ArrayList<>();
        reports.add(null);
        request.reports = reports;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createAndConcludeBatch(request));
        assertEquals("Order report cannot be null", ex.getMessage());
    }

    @Test
    void createAndConcludeBatch_orderNotFound_throwsIAE() {
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAndConcludeBatch(request));
            assertEquals("Order not found: 1", ex.getMessage());
        }
    }

    @Test
    void createAndConcludeBatch_orderAlreadyHasExecution_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution existing = pickingExecution(100L, order);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(1L, order));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, existing)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAndConcludeBatch(request));
            assertEquals("Order 1 already has an execution", ex.getMessage());
        }
    }

    @Test
    void createAndConcludeBatch_noConnectedAccount_throwsISE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        when(authenticationService.getConnectedAccount()).thenReturn(null);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(1L, order));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.createAndConcludeBatch(request));
            assertEquals("Employee not found for current user", ex.getMessage());
        }
    }

    @Test
    void createAndConcludeBatch_accountNotEmployee_throwsISE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        when(authenticationService.getConnectedAccount()).thenReturn(mock(Account.class));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(1L, order));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.createAndConcludeBatch(request));
            assertEquals("Employee not found for current user", ex.getMessage());
        }
    }

    @Test
    void createAndConcludeBatch_executionNotPicking_throwsISE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        HandlingExecution execution = mock(HandlingExecution.class);
        when(order.createExecution(employee)).thenReturn(execution);
        when(authenticationService.getConnectedAccount()).thenReturn(employee);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(1L, order));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.createAndConcludeBatch(request));
            assertEquals("Execution for order 1 is not a PickingExecution", ex.getMessage());
        }
    }

    @Test
    void createAndConcludeBatch_nullToteReportInReport_throwsIAE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        PickingExecution execution = pickingExecution(100L, order);
        when(order.createExecution(employee)).thenReturn(execution);
        when(authenticationService.getConnectedAccount()).thenReturn(employee);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        request.bufferId = null;
        List<ToteReportDTO> totes = new ArrayList<>();
        totes.add(null);
        request.reports = List.of(orderReport(1L, totes));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(1L, order));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAndConcludeBatch(request));
            assertEquals("Tote report cannot be null", ex.getMessage());
        }
    }

    @Test
    void createAndConcludeBatch_validRequest_createsAssignsAndConcludes() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        PickingExecution execution = pickingExecution(100L, order);
        Product pasta = product(1L, "Pâtes");
        LotLine lotLine = new LotLine();
        lotLine.id = 500L;
        lotLine.product = pasta;
        PickedItem item = item(10L, pasta, new BigDecimal("5"));
        item.lotLine = lotLine;
        execution.itemsToPick.add(item);
        // Item annulé sans LotLine : ignoré par le mapping ligne -> item, autorisé par la conclusion
        PickedItem canceled = item(11L, pasta, new BigDecimal("1"));
        canceled.status = PickedItem.PickStatus.CANCELED;
        execution.itemsToPick.add(canceled);
        when(order.createExecution(employee)).thenReturn(execution);
        when(authenticationService.getConnectedAccount()).thenReturn(employee);
        Tote physicalTote = tote(60L, "TOTE-1");
        Buffer buffer = buffer(7L);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        request.bufferId = 7L;
        // Le lookup se fait par lotLine.id (et non par pickedItem.id)
        request.reports = List.of(orderReport(1L, List.of(
                toteReport("TOTE-1", TemperatureZone.AMBIENT, List.of(toteItem(500L, "2"))))));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(1L, order, 100L, execution, 7L, buffer));
            stubFind(panacheMock, List.of(
                    new FindEntry("handlingOrder.id", 1L, null),
                    new FindEntry("tote.code = ?1", "TOTE-1", null),
                    new FindEntry("code", "TOTE-1", physicalTote),
                    new FindEntry("tote = ?1 and status = ?2", physicalTote, null)));

            service.createAndConcludeBatch(request);

            assertEquals(PickedItem.PickStatus.PICKED, item.status);
            assertEquals(0, item.quantity.compareTo(new BigDecimal("2")));
            assertNotNull(item.loadedTote);
            assertSame(buffer, item.loadedTote.buffer);
            assertEquals(1, execution.loadedTotes.size());
            assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
            verify(session).persist(execution);
            verify(session).persist(item.loadedTote);
        }
    }

    @Test
    void createAndConcludeBatch_noBufferNoReport_marksDoneOnly() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        PickingExecution execution = pickingExecution(100L, order);
        when(order.createExecution(employee)).thenReturn(execution);
        when(authenticationService.getConnectedAccount()).thenReturn(employee);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        request.bufferId = null;
        request.reports = null;

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(1L, order));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            service.createAndConcludeBatch(request);

            assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
            verify(session).persist(execution);
        }
    }

    @Test
    void createAndConcludeBatch_pendingItemsWithBuffer_throwsISE() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        PickingExecution execution = pickingExecution(100L, order);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        execution.itemsToPick.add(item);
        when(order.createExecution(employee)).thenReturn(execution);
        when(authenticationService.getConnectedAccount()).thenReturn(employee);
        Buffer buffer = buffer(7L);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        request.bufferId = 7L;
        request.reports = null;

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(1L, order, 7L, buffer));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            assertThrows(IllegalStateException.class,
                    () -> service.createAndConcludeBatch(request));
        }
    }

    // --------------------------------------------------
    // Edge cases & defensive branches
    // --------------------------------------------------

    @Test
    void listAvailableOrders_orderWithNullStatus_mapsNullStatus() {
        BaseHandlingOrder order = mock(BaseHandlingOrder.class);
        order.id = 3L;
        order.code = "ORD-3";
        // status laissé à null (les mocks n'exécutent pas les initialiseurs de champs)

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of(
                    "id not in (select he.handlingOrder.id from HandlingExecution he)",
                    List.of(order)));

            List<HandlingOrderSummaryDTO> result = service.listAvailableOrders();

            assertEquals(1, result.size());
            assertEquals(3L, result.get(0).id);
            assertNull(result.get(0).status);
        }
    }

    @Test
    void getBatchState_executionWithEdgeCases_mapsDefensiveDefaults() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);

        // Item sans produit : valeurs par défaut "Unknown"/null
        PickedItem noProduct = itemWithAisle(10L, null, new BigDecimal("2"), "A01");
        // Item avec une ligne mais un emplacement qui n'est pas un ShelfLocation : pas d'allée
        Product milk = product(2L, "Lait");
        PickedItem noShelf = item(11L, milk, new BigDecimal("1"));
        LotLine lotLine = new LotLine();
        lotLine.id = 511L;
        FulfillmentLine fulfillmentLine = new FulfillmentLine();
        fulfillmentLine.location = mock(BaseLocation.class);
        lotLine.fulfillmentLine = fulfillmentLine;
        noShelf.lotLine = lotLine;
        // Bac chargé sans bac physique, contenant un item sans produit ni quantité
        LoadedTote emptyTote = loadedTote(50L, null);
        PickedItem nullQty = item(13L, null, null);
        emptyTote.items.add(nullQty);
        nullQty.loadedTote = emptyTote;
        // Bac standard : un item sans poids de référence, un item sans volume ni quantité
        Product soap = product(3L, "Savon");
        soap.referenceWeight = null;
        soap.referenceVolume = new BigDecimal("0.5");
        Product oil = product(4L, "Huile");
        oil.referenceWeight = new BigDecimal("1.0");
        oil.referenceVolume = null;
        LoadedTote standardTote = loadedTote(51L, tote(61L, "TOTE-2"));
        standardTote.tote.toteType = Tote.ToteType.INSULATED;
        PickedItem noWeight = item(14L, soap, new BigDecimal("2"));
        PickedItem noVolume = item(15L, oil, null);
        standardTote.items.add(noWeight);
        standardTote.items.add(noVolume);
        noWeight.loadedTote = standardTote;
        noVolume.loadedTote = standardTote;

        execution.itemsToPick.add(noProduct);
        execution.itemsToPick.add(noShelf);
        execution.itemsToPick.add(nullQty);
        execution.loadedTotes.add(emptyTote);
        execution.loadedTotes.add(standardTote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of("id in ?1", List.of(execution)));

            PickingExecutionStateDTO state = service.getBatchState(List.of(100L));

            // 3 items : A01 en premier, les deux sans allée (ZZZ) à la fin
            assertEquals(3, state.items.size());
            PickingItemDTO dto1 = state.items.get(0);
            assertEquals(10L, dto1.uid);
            assertEquals("Unknown", dto1.productName);
            assertNull(dto1.ean);
            assertNull(dto1.temperatureZone);
            assertEquals("A01", dto1.aisle);
            PickingItemDTO dtoNoShelf = state.items.stream()
                    .filter(i -> i.uid == 11L).findFirst().orElseThrow();
            assertNull(dtoNoShelf.aisle);
            PickingItemDTO dtoNullQty = state.items.stream()
                    .filter(i -> i.uid == 13L).findFirst().orElseThrow();
            assertNull(dtoNullQty.qtyRequested);
            assertTrue(dtoNullQty.picked);
            assertNull(dtoNullQty.qtyAllocated);

            assertEquals(2, state.totes.size());
            var tote1 = state.totes.stream().filter(t -> t.id == 50L).findFirst().orElseThrow();
            assertEquals("Unknown", tote1.code);
            assertEquals("STANDARD", tote1.type);
            assertEquals(1, tote1.items.size());
            assertEquals("Unknown", tote1.items.get(0).productName);
            assertNull(tote1.items.get(0).qty);
            assertEquals(0, tote1.totalWeight.compareTo(BigDecimal.ZERO));
            assertEquals(0, tote1.totalVolume.compareTo(BigDecimal.ZERO));
            var tote2 = state.totes.stream().filter(t -> t.id == 51L).findFirst().orElseThrow();
            assertEquals("TOTE-2", tote2.code);
            assertEquals("INSULATED", tote2.type);
            assertEquals(2, tote2.items.size());
            // referenceWeight null -> poids ignoré ; referenceVolume null ou quantité null -> volume ignorée
            assertEquals(0, tote2.totalWeight.compareTo(BigDecimal.ZERO));
            assertEquals(0, tote2.totalVolume.compareTo(new BigDecimal("1.0")));
        }
    }

    @Test
    void getBatchState_executionWithNullCollections_returnsEmptyLists() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        // Mock : les initialiseurs de champs ne sont pas exécutés, itemsToPick/loadedTotes sont null
        PickingExecution nullCollections = mock(PickingExecution.class);
        nullCollections.id = 100L;
        nullCollections.handlingOrder = order;
        nullCollections.status = HandlingExecution.ExecutionStatus.IN_PROGRESS;
        // Bac chargé avec items null et bac physique null
        LoadedTote nullItems = mock(LoadedTote.class);
        nullItems.id = 50L;
        nullItems.storageType = TemperatureZone.AMBIENT;
        PickingExecution withNullItemsTote = mock(PickingExecution.class);
        withNullItemsTote.id = 101L;
        withNullItemsTote.handlingOrder = order;
        withNullItemsTote.status = HandlingExecution.ExecutionStatus.IN_PROGRESS;
        withNullItemsTote.itemsToPick = new HashSet<>();
        withNullItemsTote.loadedTotes = new HashSet<>(Set.of(nullItems));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of("id in ?1", List.of(nullCollections, withNullItemsTote)));

            PickingExecutionStateDTO state = service.getBatchState(List.of(100L, 101L));

            assertEquals(2, state.executions.size());
            assertTrue(state.items.isEmpty());
            assertEquals(1, state.totes.size());
            var toteDto = state.totes.get(0);
            assertEquals(50L, toteDto.id);
            assertEquals("Unknown", toteDto.code);
            assertEquals("STANDARD", toteDto.type);
            assertTrue(toteDto.items.isEmpty());
            assertEquals(0, toteDto.totalWeight.compareTo(BigDecimal.ZERO));
        }
    }

    @Test
    void getBatchState_itemWithLotLineWithoutFulfillmentLine_skipsAisle() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        // lotLine renseignée mais sans fulfillmentLine : pas d'allée renseignée
        Product milk = product(2L, "Lait");
        PickedItem noFulfillment = item(11L, milk, new BigDecimal("1"));
        LotLine lotLine = new LotLine();
        lotLine.id = 511L;
        lotLine.product = milk;
        noFulfillment.lotLine = lotLine;

        execution.itemsToPick.add(noFulfillment);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of("id in ?1", List.of(execution)));

            PickingExecutionStateDTO state = service.getBatchState(List.of(100L));

            assertEquals(1, state.items.size());
            PickingItemDTO dto = state.items.get(0);
            assertEquals(11L, dto.uid);
            assertEquals("Lait", dto.productName);
            assertNull(dto.aisle);
            assertNull(dto.unit);
            assertNull(dto.pos);
        }
    }

    @Test
    void getBatchState_toteItemWithVolumeButNullQuantity_skipsVolumeAccumulation() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        // Produit avec poids et volume de référence, mais quantité null : rien n'est accumulé
        Product oil = product(4L, "Huile");
        oil.referenceWeight = new BigDecimal("1.0");
        oil.referenceVolume = new BigDecimal("0.5");
        PickedItem nullQty = item(15L, oil, null);
        LoadedTote loadedTote = loadedTote(51L, tote(61L, "TOTE-2"));
        loadedTote.items.add(nullQty);
        nullQty.loadedTote = loadedTote;

        execution.itemsToPick.add(nullQty);
        execution.loadedTotes.add(loadedTote);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubList(panacheMock, Map.of("id in ?1", List.of(execution)));

            PickingExecutionStateDTO state = service.getBatchState(List.of(100L));

            assertEquals(1, state.totes.size());
            var toteDto = state.totes.get(0);
            assertEquals(51L, toteDto.id);
            assertEquals(1, toteDto.items.size());
            assertNull(toteDto.items.get(0).qty);
            assertEquals(0, toteDto.totalWeight.compareTo(BigDecimal.ZERO));
            assertEquals(0, toteDto.totalVolume.compareTo(BigDecimal.ZERO));
        }
    }

    @Test
    void saveAndConcludeExecution_toteWithoutItems_assignsNothing() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        LoadedTote loadedTote = loadedTote(50L, tote(60L, "TOTE-1"));
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        request.totes = List.of(toteReport("TOTE-1", TemperatureZone.AMBIENT, null));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution));
            stubFind(panacheMock, List.of(new FindEntry("tote.code = ?1", "TOTE-1", loadedTote)));

            service.saveAndConcludeExecution(100L, request);

            assertTrue(loadedTote.items.isEmpty());
            assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
        }
    }

    @Test
    void saveAndConcludeExecution_loadedToteWithNullItems_initializesItems() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        PickingExecution execution = pickingExecution(100L, order);
        // Mock : items est null (les initialiseurs de champs ne sont pas exécutés)
        LoadedTote loadedTote = mock(LoadedTote.class);
        PickedItem item = item(10L, product(1L, "Pâtes"), new BigDecimal("5"));
        ExecutionReportRequest request = new ExecutionReportRequest();
        request.bufferId = null;
        request.totes = List.of(toteReport("TOTE-1", TemperatureZone.AMBIENT,
                List.of(toteItem(10L, "2"))));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(100L, execution, 10L, item));
            stubFind(panacheMock, List.of(new FindEntry("tote.code = ?1", "TOTE-1", loadedTote)));

            service.saveAndConcludeExecution(100L, request);

            assertSame(loadedTote, item.loadedTote);
            assertEquals(PickedItem.PickStatus.PICKED, item.status);
            assertNotNull(loadedTote.items);
            assertTrue(loadedTote.items.contains(item));
        }
    }

    @Test
    void createAndConcludeBatch_reportWithoutTotes_skipsAssignment() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        PickingExecution execution = pickingExecution(100L, order);
        when(order.createExecution(employee)).thenReturn(execution);
        when(authenticationService.getConnectedAccount()).thenReturn(employee);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        request.bufferId = null;
        request.reports = List.of(orderReport(1L, null));

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            Session session = PanacheTestSupport.mockArcSession(arcMock);
            stubFindById(panacheMock, Map.of(1L, order));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            service.createAndConcludeBatch(request);

            assertEquals(HandlingExecution.ExecutionStatus.DONE, execution.status);
            assertTrue(execution.loadedTotes.isEmpty());
            verify(session).persist(execution);
        }
    }

    @Test
    void createAndConcludeBatch_executionWithNullItemsToPick_skipsItemMapping() {
        BaseHandlingOrder order = orderMock(1L, "ORD-1", BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        Employee employee = employee(7L, "BADGE-1");
        // Mock : itemsToPick est null (les initialiseurs de champs ne sont pas exécutés)
        PickingExecution execution = mock(PickingExecution.class);
        when(order.createExecution(employee)).thenReturn(execution);
        when(authenticationService.getConnectedAccount()).thenReturn(employee);
        BatchValidationRequest request = new BatchValidationRequest();
        request.orderIds = List.of(1L);
        request.bufferId = null;
        request.reports = null;

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            stubFindById(panacheMock, Map.of(1L, order));
            stubFind(panacheMock, List.of(new FindEntry("handlingOrder.id", 1L, null)));

            assertDoesNotThrow(() -> service.createAndConcludeBatch(request));
        }
    }
}

