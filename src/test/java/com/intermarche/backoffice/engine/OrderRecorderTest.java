package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the pure (non-database) methods of {@link OrderRecorder}:
 * delivery date parsing, order specification building, address mapping
 * and order creation from JSON.
 */
class OrderRecorderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private OrderRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new OrderRecorder();
        recorder.objectMapper = mapper;
    }

    private JsonNode json(String content) throws Exception {
        return mapper.readTree(content);
    }

    // --------------------------------------------------
    // parseDeliveryDate
    // --------------------------------------------------

    @Test
    void parseDeliveryDate_noFulfillmentNode_returnsNull() throws Exception {
        assertNull(recorder.parseDeliveryDate(json("{}")));
    }

    @Test
    void parseDeliveryDate_noSlotNode_returnsNull() throws Exception {
        assertNull(recorder.parseDeliveryDate(json("{\"fulfillment\":{}}")));
    }

    @Test
    void parseDeliveryDate_noStartNode_returnsNull() throws Exception {
        assertNull(recorder.parseDeliveryDate(json("{\"fulfillment\":{\"slot\":{}}}")));
    }

    @Test
    void parseDeliveryDate_emptyStart_returnsNull() throws Exception {
        assertNull(recorder.parseDeliveryDate(json("{\"fulfillment\":{\"slot\":{\"start\":\"\"}}}")));
    }

    @Test
    void parseDeliveryDate_nullStartText_returnsNull() {
        // asText() ne retourne jamais null avec du JSON réel : on mocke l'arbre pour couvrir la défense
        JsonNode root = mock(JsonNode.class);
        JsonNode fulfillment = mock(JsonNode.class);
        JsonNode slot = mock(JsonNode.class);
        JsonNode start = mock(JsonNode.class);
        when(root.path("fulfillment")).thenReturn(fulfillment);
        when(fulfillment.isMissingNode()).thenReturn(false);
        when(fulfillment.path("slot")).thenReturn(slot);
        when(slot.isMissingNode()).thenReturn(false);
        when(slot.path("start")).thenReturn(start);
        when(start.asText()).thenReturn(null);

        assertNull(recorder.parseDeliveryDate(root));
    }

    @Test
    void parseDeliveryDate_invalidStart_returnsNull() throws Exception {
        assertNull(recorder.parseDeliveryDate(json("{\"fulfillment\":{\"slot\":{\"start\":\"not-a-date\"}}}")));
    }

    @Test
    void parseDeliveryDate_validOffsetDateTime_returnsParsed() throws Exception {
        LocalDateTime result = recorder.parseDeliveryDate(
                json("{\"fulfillment\":{\"slot\":{\"start\":\"2026-10-14T10:15:30+02:00\"}}}"));
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 15, 30), result);
    }

    // --------------------------------------------------
    // buildOrderSpecification
    // --------------------------------------------------

    @Test
    void buildOrderSpecification_excludesLinesAndCustomer() throws Exception {
        JsonNode root = json("""
                {
                  "orderHeader": {"orderId": "O1"},
                  "customer": {"customerId": "C1"},
                  "lines": [{"lineNumber": 1}],
                  "fulfillment": {"slot": {"start": "2026-10-14T10:00:00+02:00"}},
                  "packaging": {"type": "BOX"},
                  "summary": {"total": 42}
                }
                """);

        String spec = recorder.buildOrderSpecification(root);
        JsonNode specNode = mapper.readTree(spec);

        assertTrue(specNode.has("orderHeader"));
        assertTrue(specNode.has("fulfillment"));
        assertTrue(specNode.has("packaging"));
        assertTrue(specNode.has("summary"));
        assertFalse(specNode.has("lines"));
        assertFalse(specNode.has("customer"));
    }

    @Test
    void buildOrderSpecification_emptyRoot_returnsEmptyObject() throws Exception {
        String spec = recorder.buildOrderSpecification(json("{}"));
        assertEquals("{}", spec);
    }

    @Test
    void buildOrderSpecification_partialRoot_keepsOnlyPresentSections() throws Exception {
        JsonNode root = json("""
                {"orderHeader": {"orderId": "O1"}, "lines": [], "customer": {}}
                """);

        JsonNode specNode = mapper.readTree(recorder.buildOrderSpecification(root));

        assertTrue(specNode.has("orderHeader"));
        assertEquals(1, specNode.size());
    }

    // --------------------------------------------------
    // mapAddress
    // --------------------------------------------------

    @Test
    void mapAddress_fullNode_mapsAllFields() throws Exception {
        JsonNode node = json("""
                {"streetLine1": "1 rue de Test", "streetLine2": "App 4",
                 "postalCode": "69001", "city": "Lyon", "country": "France"}
                """);

        Adresse adresse = recorder.mapAddress(node);

        assertEquals("1 rue de Test", adresse.streetLine1);
        assertEquals("App 4", adresse.streetLine2);
        assertEquals("69001", adresse.postalCode);
        assertEquals("Lyon", adresse.city);
        assertEquals("France", adresse.country);
    }

    @Test
    void mapAddress_missingFields_mapsNull() throws Exception {
        Adresse adresse = recorder.mapAddress(json("{}"));

        assertNull(adresse.streetLine1);
        assertNull(adresse.streetLine2);
        assertNull(adresse.postalCode);
        assertNull(adresse.city);
        assertNull(adresse.country);
    }

    // --------------------------------------------------
    // createOrder
    // --------------------------------------------------

    @Test
    void createOrder_mapsHeaderFields() throws Exception {
        JsonNode header = json("""
                {"orderId": "O1", "channel": "ECOMMERCE", "status": "VALIDATED",
                 "createdAt": "2026-10-14T10:15:30+02:00"}
                """);
        JsonNode root = json("""
                {"orderHeader": {"orderId": "O1"}, "lines": [], "customer": {}}
                """);
        Store store = new Store();
        store.id = 1L;
        Customer customer = new Customer();
        customer.id = 2L;

        Order order = recorder.createOrder(header, root, store, customer);

        assertEquals("O1", order.orderId);
        assertEquals("ECOMMERCE", order.channel);
        assertEquals("VALIDATED", order.status);
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 15, 30), order.orderDate);
        assertSame(store, order.store);
        assertSame(customer, order.customer);
        assertNotNull(order.specification);
        assertTrue(order.specification.contains("orderHeader"));
        assertFalse(order.specification.contains("\"lines\""));
        assertFalse(order.specification.contains("\"customer\""));
    }

    @Test
    void createOrder_invalidCreatedAt_throwsDateTimeParseException() throws Exception {
        JsonNode header = json("""
                {"orderId": "O1", "channel": "ECOMMERCE", "status": "VALIDATED", "createdAt": "not-a-date"}
                """);
        JsonNode root = json("{}");

        assertThrows(DateTimeParseException.class,
                () -> recorder.createOrder(header, root, new Store(), new Customer()));
    }

    @Test
    void createOrder_missingCreatedAt_throwsDateTimeParseException() throws Exception {
        JsonNode header = json("{\"orderId\": \"O1\"}");
        JsonNode root = json("{}");

        assertThrows(DateTimeParseException.class,
                () -> recorder.createOrder(header, root, new Store(), new Customer()));
    }

    // --------------------------------------------------
    // resolveStore
    // --------------------------------------------------

    @Test
    void resolveStore_found_returnsStore() {
        Store store = new Store();
        store.code = "S001";
        try (MockedStatic<Store> storeMock = mockStatic(Store.class)) {
            storeMock.when(() -> Store.findByCode("S001")).thenReturn(store);

            assertSame(store, recorder.resolveStore("S001"));
        }
    }

    @Test
    void resolveStore_notFound_throwsIllegalState() {
        try (MockedStatic<Store> storeMock = mockStatic(Store.class)) {
            storeMock.when(() -> Store.findByCode(anyString())).thenReturn(null);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> recorder.resolveStore("UNKNOWN"));
            assertTrue(ex.getMessage().contains("UNKNOWN"));
        }
    }

    // --------------------------------------------------
    // resolveOrCreateCustomer
    // --------------------------------------------------

    @Test
    void resolveOrCreateCustomer_existingCustomer_updatesFields() throws Exception {
        Customer existing = new Customer();
        existing.id = 5L;
        existing.customerId = "C1";
        JsonNode node = json("""
                {"customerId": "C1", "firstName": "John", "lastName": "Doe",
                 "email": "john@example.com", "phone": "0600000000", "loyaltyCardId": "LC-9",
                 "address": {"streetLine1": "1 rue de Test", "postalCode": "69001", "city": "Lyon", "country": "France"}}
                """);

        try (MockedStatic<Customer> customerMock = mockStatic(Customer.class)) {
            customerMock.when(() -> Customer.findByCustomerId("C1")).thenReturn(existing);

            Customer result = recorder.resolveOrCreateCustomer(node);

            assertSame(existing, result);
            assertEquals("John", result.firstName);
            assertEquals("Doe", result.lastName);
            assertEquals("john@example.com", result.email);
            assertEquals("0600000000", result.phone);
            assertEquals("LC-9", result.loyaltyCardId);
            assertNotNull(result.address);
            assertEquals("Lyon", result.address.city);
            assertEquals(node.toString(), result.specification);
        }
    }

    @Test
    void resolveOrCreateCustomer_newCustomer_createsWithMappedFields() throws Exception {
        JsonNode node = json("""
                {"customerId": "C2", "firstName": "Jane", "lastName": "Smith",
                 "email": "jane@example.com", "phone": null, "loyaltyCardId": null}
                """);

        try (MockedStatic<Customer> customerMock = mockStatic(Customer.class)) {
            customerMock.when(() -> Customer.findByCustomerId("C2")).thenReturn(null);

            Customer result = recorder.resolveOrCreateCustomer(node);

            assertNotNull(result);
            assertNull(result.id);
            assertEquals("C2", result.customerId);
            assertEquals("Jane", result.firstName);
            assertEquals("Smith", result.lastName);
            assertEquals("jane@example.com", result.email);
            assertNull(result.phone);
            assertNull(result.loyaltyCardId);
            assertNull(result.address);
            assertEquals(node.toString(), result.specification);
        }
    }

    @Test
    void resolveOrCreateCustomer_missingOptionalFields_mapsDefaults() throws Exception {
        JsonNode node = json("{\"customerId\": \"C3\"}");

        try (MockedStatic<Customer> customerMock = mockStatic(Customer.class)) {
            customerMock.when(() -> Customer.findByCustomerId("C3")).thenReturn(null);

            Customer result = recorder.resolveOrCreateCustomer(node);

            assertEquals("C3", result.customerId);
            assertEquals("", result.firstName);
            assertEquals("", result.lastName);
            assertEquals("", result.email);
            assertNull(result.phone);
            assertNull(result.loyaltyCardId);
            assertNull(result.address);
        }
    }

    // --------------------------------------------------
    // createFulfillmentLine
    // --------------------------------------------------

    @Test
    void createFulfillmentLine_validLine_mapsAllFields() throws Exception {
        Product product = new Product();
        product.id = 1L;
        product.ean = "123";
        JsonNode lineNode = json("""
                {"lineNumber": 3, "requestedQuantity": "2.5", "product": {"ean": "123"}}
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class)) {
            productMock.when(() -> Product.findByEan("123")).thenReturn(product);

            FulfillmentLine line = recorder.createFulfillmentLine(lineNode, 7);

            assertEquals(3, line.lineNumber);
            assertEquals(new BigDecimal("2.5"), line.requestedQuantity);
            assertSame(product, line.product);
        }
    }

    @Test
    void createFulfillmentLine_missingLineNumber_usesProvidedDefault() throws Exception {
        Product product = new Product();
        product.ean = "123";
        JsonNode lineNode = json("""
                {"requestedQuantity": "1", "product": {"ean": "123"}}
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class)) {
            productMock.when(() -> Product.findByEan("123")).thenReturn(product);

            FulfillmentLine line = recorder.createFulfillmentLine(lineNode, 7);

            assertEquals(7, line.lineNumber);
        }
    }

    @Test
    void createFulfillmentLine_missingQuantity_defaultsToZero() throws Exception {
        Product product = new Product();
        product.ean = "123";
        JsonNode lineNode = json("""
                {"lineNumber": 1, "product": {"ean": "123"}}
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class)) {
            productMock.when(() -> Product.findByEan("123")).thenReturn(product);

            FulfillmentLine line = recorder.createFulfillmentLine(lineNode, 1);

            assertEquals(new BigDecimal("0"), line.requestedQuantity);
        }
    }

    @Test
    void createFulfillmentLine_productNotFound_throwsNoSuchElement() throws Exception {
        JsonNode lineNode = json("""
                {"lineNumber": 1, "requestedQuantity": "1", "product": {"ean": "999"}}
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class)) {
            productMock.when(() -> Product.findByEan("999")).thenReturn(null);

            NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                    () -> recorder.createFulfillmentLine(lineNode, 1));
            assertTrue(ex.getMessage().contains("999"));
        }
    }

    // --------------------------------------------------
    // createFulfillmentChunk
    // --------------------------------------------------

    @Test
    void createFulfillmentChunk_withLines_createsPendingChunkWithLines() throws Exception {
        Product product = new Product();
        product.ean = "123";
        JsonNode root = json("""
                {
                  "fulfillment": {"slot": {"start": "2026-10-15T08:00:00+02:00"}},
                  "lines": [
                    {"lineNumber": 1, "requestedQuantity": "2.5", "product": {"ean": "123"}},
                    {"lineNumber": 2, "requestedQuantity": "1", "product": {"ean": "123"}}
                  ]
                }
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class)) {
            productMock.when(() -> Product.findByEan("123")).thenReturn(product);

            FulfillmentChunk chunk = recorder.createFulfillmentChunk(root);

            assertEquals(FulfillmentChunk.PENDING, chunk.type);
            assertEquals(LocalDateTime.of(2026, 10, 15, 8, 0), chunk.deliveryDate);
            assertEquals(2, chunk.lines.size());
        }
    }

    @Test
    void createFulfillmentChunk_noLines_createsEmptyChunkWithoutDate() throws Exception {
        FulfillmentChunk chunk = recorder.createFulfillmentChunk(json("{}"));

        assertEquals(FulfillmentChunk.PENDING, chunk.type);
        assertNull(chunk.deliveryDate);
        assertTrue(chunk.lines.isEmpty());
    }

    @Test
    void createFulfillmentChunk_linesNotArray_createsEmptyChunk() throws Exception {
        FulfillmentChunk chunk = recorder.createFulfillmentChunk(json("{\"lines\": {}}"));

        assertTrue(chunk.lines.isEmpty());
    }

    // --------------------------------------------------
    // createFulfillmentForOrder
    // --------------------------------------------------

    @Test
    void createFulfillmentForOrder_createsChunkAndPersistsFulfillment() throws Exception {
        Product product = new Product();
        product.ean = "123";
        Order order = new Order();
        order.id = 1L;
        JsonNode root = json("""
                {
                  "fulfillment": {"slot": {"start": "2026-10-15T08:00:00+02:00"}},
                  "lines": [{"lineNumber": 1, "requestedQuantity": "2.5", "product": {"ean": "123"}}]
                }
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            productMock.when(() -> Product.findByEan("123")).thenReturn(product);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            Fulfillment fulfillment = recorder.createFulfillmentForOrder(order, root);

            assertSame(order, fulfillment.order);
            assertEquals(1, fulfillment.chunks.size());
            FulfillmentChunk chunk = fulfillment.chunks.iterator().next();
            assertEquals(FulfillmentChunk.PENDING, chunk.type);
            assertEquals(1, chunk.lines.size());
            verify(session).persist(fulfillment);
        }
    }

    // --------------------------------------------------
    // processOrderLines
    // --------------------------------------------------

    @Test
    void processOrderLines_notArray_doesNothing() throws Exception {
        Order order = new Order();

        recorder.processOrderLines(json("{}"), order);

        assertTrue(order.lines.isEmpty());
    }

    @Test
    void processOrderLines_validLines_addsOrderLines() throws Exception {
        Product product = new Product();
        product.ean = "123";
        Order order = new Order();
        JsonNode linesNode = json("""
                [
                  {"lineNumber": 1, "requestedQuantity": "2.5", "product": {"ean": "123"}},
                  {"lineNumber": 2, "requestedQuantity": "1", "product": {"ean": "123"}}
                ]
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class)) {
            productMock.when(() -> Product.findByEan("123")).thenReturn(product);

            recorder.processOrderLines(linesNode, order);

            assertEquals(2, order.lines.size());
            for (OrderLine line : order.lines) {
                assertSame(product, line.product);
                assertNotNull(line.specification);
            }
        }
    }

    @Test
    void processOrderLines_missingLineNumber_usesSequence() throws Exception {
        Product product = new Product();
        product.ean = "123";
        Order order = new Order();
        JsonNode linesNode = json("""
                [
                  {"requestedQuantity": "2.5", "product": {"ean": "123"}},
                  {"requestedQuantity": "1", "product": {"ean": "123"}}
                ]
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class)) {
            productMock.when(() -> Product.findByEan("123")).thenReturn(product);

            recorder.processOrderLines(linesNode, order);

            assertEquals(2, order.lines.size());
            List<Integer> lineNumbers = order.lines.stream().map(l -> l.lineNumber).sorted().toList();
            assertEquals(List.of(1, 2), lineNumbers);
        }
    }

    @Test
    void processOrderLines_productNotFound_throwsNoSuchElement() throws Exception {
        Order order = new Order();
        JsonNode linesNode = json("""
                [{"lineNumber": 1, "requestedQuantity": "1", "product": {"ean": "999"}}]
                """);

        try (MockedStatic<Product> productMock = mockStatic(Product.class)) {
            productMock.when(() -> Product.findByEan("999")).thenReturn(null);

            NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                    () -> recorder.processOrderLines(linesNode, order));
            assertTrue(ex.getMessage().contains("999"));
        }
    }

    // --------------------------------------------------
    // persistOrderAndFulfillmentFromJson
    // --------------------------------------------------

    private static final String FULL_ORDER_JSON = """
            {
              "orderHeader": {
                "orderId": "ORD-1",
                "channel": "ECOMMERCE",
                "status": "VALIDATED",
                "createdAt": "2026-10-14T10:15:30+02:00",
                "store": {"code": "S001"}
              },
              "customer": {
                "customerId": "C1",
                "firstName": "John",
                "lastName": "Doe",
                "email": "john@example.com",
                "phone": "0600000000",
                "loyaltyCardId": "LC-9",
                "address": {"streetLine1": "1 rue de Test", "postalCode": "69001", "city": "Lyon", "country": "France"}
              },
              "lines": [
                {"lineNumber": 1, "requestedQuantity": "2.5", "product": {"ean": "111"}},
                {"lineNumber": 2, "requestedQuantity": "1", "product": {"ean": "222"}}
              ],
              "fulfillment": {"slot": {"start": "2026-10-15T08:00:00+02:00"}}
            }
            """;

    @Test
    void persistOrderAndFulfillmentFromJson_nullRoot_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> recorder.persistOrderAndFulfillmentFromJson(null));
        assertTrue(ex.getMessage().contains("JSON payload cannot be null"));
    }

    @Test
    void persistOrderAndFulfillmentFromJson_newCustomer_persistsCustomerOrderAndFulfillment() throws Exception {
        Store store = new Store();
        store.code = "S001";
        Product product = new Product();
        product.ean = "111";

        try (MockedStatic<Store> storeMock = mockStatic(Store.class);
             MockedStatic<Customer> customerMock = mockStatic(Customer.class);
             MockedStatic<Product> productMock = mockStatic(Product.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            storeMock.when(() -> Store.findByCode("S001")).thenReturn(store);
            customerMock.when(() -> Customer.findByCustomerId("C1")).thenReturn(null);
            productMock.when(() -> Product.findByEan(anyString())).thenReturn(product);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            recorder.persistOrderAndFulfillmentFromJson(json(FULL_ORDER_JSON));

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(session, times(3)).persist(captor.capture());
            List<Object> persisted = captor.getAllValues();
            assertInstanceOf(Customer.class, persisted.get(0));
            assertInstanceOf(Order.class, persisted.get(1));
            assertInstanceOf(Fulfillment.class, persisted.get(2));

            Customer customer = (Customer) persisted.get(0);
            assertEquals("C1", customer.customerId);
            assertEquals("John", customer.firstName);
            assertEquals("Lyon", customer.address.city);

            Order order = (Order) persisted.get(1);
            assertEquals("ORD-1", order.orderId);
            assertEquals("ECOMMERCE", order.channel);
            assertEquals("VALIDATED", order.status);
            assertEquals(LocalDateTime.of(2026, 10, 14, 10, 15, 30), order.orderDate);
            assertSame(store, order.store);
            assertSame(customer, order.customer);
            assertEquals(2, order.lines.size());

            Fulfillment fulfillment = (Fulfillment) persisted.get(2);
            assertSame(order, fulfillment.order);
            assertEquals(1, fulfillment.chunks.size());
            FulfillmentChunk chunk = fulfillment.chunks.iterator().next();
            assertEquals(FulfillmentChunk.PENDING, chunk.type);
            assertEquals(LocalDateTime.of(2026, 10, 15, 8, 0), chunk.deliveryDate);
            assertEquals(2, chunk.lines.size());
        }
    }

    @Test
    void persistOrderAndFulfillmentFromJson_existingCustomer_skipsCustomerPersist() throws Exception {
        Store store = new Store();
        store.code = "S001";
        Customer existing = new Customer();
        existing.id = 5L;
        existing.customerId = "C1";
        Product product = new Product();
        product.ean = "111";

        try (MockedStatic<Store> storeMock = mockStatic(Store.class);
             MockedStatic<Customer> customerMock = mockStatic(Customer.class);
             MockedStatic<Product> productMock = mockStatic(Product.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            storeMock.when(() -> Store.findByCode("S001")).thenReturn(store);
            customerMock.when(() -> Customer.findByCustomerId("C1")).thenReturn(existing);
            productMock.when(() -> Product.findByEan(anyString())).thenReturn(product);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            recorder.persistOrderAndFulfillmentFromJson(json(FULL_ORDER_JSON));

            // Only the order and the fulfillment are persisted; the customer already exists
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(session, times(2)).persist(captor.capture());
            List<Object> persisted = captor.getAllValues();
            assertInstanceOf(Order.class, persisted.get(0));
            assertInstanceOf(Fulfillment.class, persisted.get(1));
            assertSame(existing, ((Order) persisted.get(0)).customer);
        }
    }

    @Test
    void persistOrderAndFulfillmentFromJson_storeNotFound_throwsIllegalState() throws Exception {
        try (MockedStatic<Store> storeMock = mockStatic(Store.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            storeMock.when(() -> Store.findByCode(anyString())).thenReturn(null);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            assertThrows(IllegalStateException.class,
                    () -> recorder.persistOrderAndFulfillmentFromJson(json(FULL_ORDER_JSON)));
            verifyNoInteractions(session);
        }
    }

    @Test
    void persistOrderAndFulfillmentFromJson_productNotFound_throwsNoSuchElement() throws Exception {
        Store store = new Store();
        store.code = "S001";

        try (MockedStatic<Store> storeMock = mockStatic(Store.class);
             MockedStatic<Customer> customerMock = mockStatic(Customer.class);
             MockedStatic<Product> productMock = mockStatic(Product.class);
             MockedStatic<Arc> arcMock = mockStatic(Arc.class)) {
            storeMock.when(() -> Store.findByCode("S001")).thenReturn(store);
            customerMock.when(() -> Customer.findByCustomerId("C1")).thenReturn(null);
            productMock.when(() -> Product.findByEan(anyString())).thenReturn(null);
            Session session = PanacheTestSupport.mockArcSession(arcMock);

            assertThrows(NoSuchElementException.class,
                    () -> recorder.persistOrderAndFulfillmentFromJson(json(FULL_ORDER_JSON)));
            verifyNoInteractions(session);
        }
    }
}
