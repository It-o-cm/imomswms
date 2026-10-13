package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.domain.*;
import com.intermarche.backoffice.domain.oms.*;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;

/**
 * Recorder responsible for persisting Order and Fulfillment aggregates.
 * <p>
 * This class encapsulates all business logic related to mapping JSON payloads
 * to JPA entities and persisting them to the database.
 * <p>
 * It is designed to be called within a transactional context (managed by the caller).
 * It does not handle JMS or Transaction management directly.
 */
@ApplicationScoped
public class OrderRecorder {

    private static final Logger LOGGER = Logger.getLogger(OrderRecorder.class);

    /**
     * Jackson ObjectMapper for parsing and processing JSON payloads.
     */
    @Inject
    ObjectMapper objectMapper;

    /**
     * Orchestrates the mapping and persistence of the Order aggregate and the Fulfillment.
     * <p>
     * This method coordinates the resolution of dependencies (Store, Customer),
     * the creation of the Order entity, the processing of order lines, and finally
     * the creation of the Fulfillment structure.
     *
     * @param root The root JSON node of the order payload.
     * @return The ID of the newly created Fulfillment.
     */
    public void persistOrderAndFulfillmentFromJson(JsonNode root) {
        JsonNode header = root.path("orderHeader");
        JsonNode customerNode = root.path("customer");
        JsonNode linesNode = root.path("lines");
        Store store = resolveStore(header.path("store").path("code").asText());
        Customer customer = resolveOrCreateCustomer(customerNode);
        Order order = createOrder(header, root, store, customer);
        processOrderLines(linesNode, order);
        if (customer.id == null) {
            customer.persist();
        }
        order.persist();
        Fulfillment fulfillment = createFulfillmentForOrder(order, root);
    }

    /**
     * Creates the Fulfillment aggregate for a given Order.
     * <p>
     * Initializes the Fulfillment entity. The status is managed internally by the
     * embedded {@code ReservationRecord}, which defaults to PENDING.
     *
     * @param order The Order entity just created/persisted.
     * @param root  The root JSON node containing the order payload (used to extract lines and fulfillment details).
     * @return The created Fulfillment entity.
     */
    public Fulfillment createFulfillmentForOrder(Order order, JsonNode root) {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = order;
        // Status is now handled by the embedded reservationRecord (defaults to PENDING).
        // No manual status setting is required here.
        FulfillmentChunk chunk = createFulfillmentChunk(root);
        fulfillment.addChunk(chunk);
        fulfillment.persist();
        return fulfillment;
    }

    /**
     * Creates a FulfillmentChunk populated with lines from the JSON payload.
     * <p>
     * The chunk is typed as "PENDING". The delivery date is extracted from the
     * 'fulfillment.slot.start' field. Lines are created by iterating over the JSON lines array.
     *
     * @param root The root JSON node of the order payload.
     * @return A fully populated FulfillmentChunk entity (not yet persisted).
     */
    public FulfillmentChunk createFulfillmentChunk(JsonNode root) {
        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.PENDING;
        chunk.deliveryDate = parseDeliveryDate(root);

        JsonNode linesNode = root.path("lines");
        if (linesNode.isArray()) {
            int lineNum = 1;
            for (JsonNode lineNode : linesNode) {
                FulfillmentLine line = createFulfillmentLine(lineNode, lineNum++);
                chunk.addLine(line);
            }
        }
        return chunk;
    }

    /**
     * Extracts and parses the delivery date from the JSON payload.
     * <p>
     * Looks for the path {@code fulfillment -> slot -> start}.
     *
     * @param root The root JSON node.
     * @return The parsed LocalDateTime, or null if the date is missing or invalid.
     */
    public LocalDateTime parseDeliveryDate(JsonNode root) {
        JsonNode fulfillmentNode = root.path("fulfillment");
        if (fulfillmentNode.isMissingNode()) return null;

        JsonNode slot = fulfillmentNode.path("slot");
        if (slot.isMissingNode()) return null;

        String startStr = slot.path("start").asText();
        if (startStr != null && !startStr.isEmpty()) {
            try {
                return LocalDateTime.parse(startStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception e) {
                LOGGER.warnf("Failed to parse delivery date '%s'", startStr);
            }
        }
        return null;
    }

    /**
     * Creates a FulfillmentLine entity from a JSON line node.
     * <p>
     * Maps the line number, requested quantity, and resolves the Product via EAN.
     *
     * @param lineNode  The JSON node representing a single order line.
     * @param lineNumber The sequence number to assign to this line.
     * @return A populated FulfillmentLine entity.
     */
    public FulfillmentLine createFulfillmentLine(JsonNode lineNode, int lineNumber) {
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = lineNode.path("lineNumber").asInt(lineNumber);
        line.requestedQuantity = new BigDecimal(lineNode.path("requestedQuantity").asText("0"));

        String ean = lineNode.path("product").path("ean").asText();
        Product product = Product.findByEan(ean);
        if (product == null) {
            throw new NoSuchElementException("Product with ean " + ean + " not found for line");
        }
        line.product = product;

        return line;
    }

    /**
     * Resolves the Store entity from the database using its code.
     *
     * @param storeCode The unique code of the store.
     * @return The Store entity.
     * @throws IllegalStateException if the store is not found.
     */
    public Store resolveStore(String storeCode) {
        Store store = Store.findByCode(storeCode);
        if (store == null) {
            throw new IllegalStateException("Store not found with code: " + storeCode);
        }
        return store;
    }

    /**
     * Finds an existing customer or creates a new one based on the JSON payload.
     * <p>
     * Updates mutable fields on the customer entity to ensure data consistency.
     *
     * @param customerNode The JSON node representing the customer.
     * @return The managed or transient Customer entity.
     */
    public Customer resolveOrCreateCustomer(JsonNode customerNode) {
        String customerIdStr = customerNode.path("customerId").asText();
        Customer customer = Customer.findByCustomerId(customerIdStr);
        if (customer == null) {
            customer = new Customer();
            customer.customerId = customerIdStr;
        }
        customer.firstName = customerNode.path("firstName").asText();
        customer.lastName = customerNode.path("lastName").asText();
        customer.email = customerNode.path("email").asText();
        customer.phone = customerNode.path("phone").asText(null);
        customer.loyaltyCardId = customerNode.path("loyaltyCardId").asText(null);
        JsonNode addrNode = customerNode.path("address");
        if (!addrNode.isMissingNode()) {
            customer.address = mapAddress(addrNode);
        }
        customer.specification = customerNode.toString();
        return customer;
    }

    /**
     * Creates the Order entity from JSON data.
     *
     * @param header   The order header JSON node.
     * @param root     The root JSON node (used for building the specification).
     * @param store    The resolved Store entity.
     * @param customer The resolved Customer entity.
     * @return The constructed Order entity (not yet persisted).
     */
    public Order createOrder(JsonNode header, JsonNode root, Store store, Customer customer) {
        Order order = new Order();
        order.orderId = header.path("orderId").asText();
        order.channel = header.path("channel").asText();
        order.status = header.path("status").asText();
        String dateStr = header.path("createdAt").asText();
        order.orderDate = LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        order.store = store;
        order.customer = customer;
        order.specification = buildOrderSpecification(root);
        return order;
    }

    /**
     * Iterates over the JSON lines array and adds OrderLines to the Order.
     *
     * @param linesNode The JSON array of lines.
     * @param order     The parent Order entity to which lines will be added.
     */
    public void processOrderLines(JsonNode linesNode, Order order) {
        if (!linesNode.isArray()) return;
        int lineNum = 1;
        for (JsonNode lineNode : linesNode) {
            OrderLine line = new OrderLine();
            line.lineNumber = lineNode.path("lineNumber").asInt(lineNum++);
            line.requestedQuantity = new BigDecimal(lineNode.path("requestedQuantity").asText("0"));
            String ean = lineNode.path("product").path("ean").asText();
            Product product = Product.findByEan(ean);
            if (product == null) {
                throw new NoSuchElementException("Product with ean " + ean + " not found for line");
            }
            line.product = product;
            line.specification = lineNode.toString();
            order.addLine(line);
        }
    }

    /**
     * Builds the JSON string for the Order specification.
     * <p>
     * Filters out 'lines' and 'customer' nodes as they are stored in separate entities/tables.
     *
     * @param root The root JSON node.
     * @return The JSON string specification.
     */
    public String buildOrderSpecification(JsonNode root) {
        ObjectNode orderSpec = objectMapper.createObjectNode();
        if (root.has("orderHeader")) orderSpec.set("orderHeader", root.get("orderHeader"));
        if (root.has("fulfillment")) orderSpec.set("fulfillment", root.get("fulfillment"));
        if (root.has("packaging")) orderSpec.set("packaging", root.get("packaging"));
        if (root.has("summary")) orderSpec.set("summary", root.get("summary"));
        return orderSpec.toString();
    }

    /**
     * Helper to map a JSON address node to an {@link Adresse} embeddable.
     *
     * @param node The JSON node representing the address.
     * @return The mapped Adresse object.
     */
    public Adresse mapAddress(JsonNode node) {
        Adresse adresse = new Adresse();
        adresse.streetLine1 = node.path("streetLine1").asText(null);
        adresse.streetLine2 = node.path("streetLine2").asText(null);
        adresse.postalCode = node.path("postalCode").asText(null);
        adresse.city = node.path("city").asText(null);
        adresse.country = node.path("country").asText(null);
        return adresse;
    }
}