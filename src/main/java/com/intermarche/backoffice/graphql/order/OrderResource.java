package com.intermarche.backoffice.graphql.order;

import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.AlreadyExistsException;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import graphql.schema.DataFetchingEnvironment;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.graphql.api.Context;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Orders.
 * <p>
 * This resource provides CRUD operations for the {@link Order} entity.
 * It enforces role-based access control: MANAGERs can read, ADMINs can write.
 * <p>
 * Implements {@link GraphQLTrait} to centralize exception handling and transaction wrapping
 * via the {@code execute} template method.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class OrderResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(OrderResource.class);

    /**
     * Security identity of the current authenticated user.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * Retrieves the current security identity of the user or context.
     *
     * @return The {@link SecurityIdentity} associated with the current context.
     */
    @Override
    public SecurityIdentity getIdentity() {
        return identity;
    }

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Retrieves a list of all orders the user may read: those of their store for a manager.
     * <p>
     * Delegates to {@link #buildOptimizedQuery(Context, String)} to construct
     * a JPQL query with dynamic JOIN FETCH clauses based on the requested fields.
     *
     * @param context The SmallRye GraphQL context.
     * @return A {@link List} of the readable {@link Order} entities. Returns an empty list if no orders exist.
     */
    @Query
    @Description("Get a list of all orders")
    @RolesAllowed("MANAGER")
    public List<Order> allOrders(Context context) {
        LOGGER.info("Entering method allOrders");
        Store scope = readScope();
        List<Order> result = scope == null ? Order.list(buildOptimizedQuery(context, null))
                : Order.list(buildOptimizedQuery(context, "o.store.id = ?1"), scope.id);
        LOGGER.info("Exiting method allOrders");
        return result;
    }

    /**
     * Retrieves a specific order by its unique technical identifier (Primary Key).
     * <p>
     * Delegates to {@link #buildOptimizedQuery(Context, String)} to construct
     * an optimized JPQL query including the WHERE clause.
     *
     * @param id      The unique technical ID (Primary Key) of the Order to retrieve.
     * @param context The SmallRye GraphQL context.
     * @return The found {@link Order} entity.
     * @throws NoSuchElementException if no Order exists with the given technical ID in the scope of the user.
     */
    @Query
    @Description("Get an order by its technical ID")
    @RolesAllowed("MANAGER")
    public Order order(@Name("id") Long id, Context context) {
        LOGGER.info("Entering method order with id: " + id);
        Store scope = readScope();
        Order order = scope == null ? Order.find(buildOptimizedQuery(context, "o.id = ?1"), id).firstResult()
                : Order.find(buildOptimizedQuery(context, "o.id = ?1 and o.store.id = ?2"), id, scope.id).firstResult();
        if (order == null) {
            LOGGER.error("Order with id " + id + " not found");
            throw new NoSuchElementException("Order with id " + id + " not found");
        }
        LOGGER.info("Exiting method order");
        return order;
    }

    /**
     * Retrieves a specific order by its business identifier.
     * <p>
     * Delegates to {@link #buildOptimizedQuery(Context, String)} to construct
     * an optimized JPQL query filtering by the business Order ID.
     *
     * @param orderId The unique business identifier of the Order.
     * @param context The SmallRye GraphQL context.
     * @return The found {@link Order} entity.
     * @throws NoSuchElementException if no Order exists with the given business ID in the scope of the user.
     */
    @Query
    @Description("Get an order by its business Order ID")
    @RolesAllowed("MANAGER")
    public Order orderByOrderId(@Name("orderId") String orderId, Context context) {
        LOGGER.info("Entering method orderByOrderId with orderId: " + orderId);
        Store scope = readScope();
        Order order = scope == null ? Order.find(buildOptimizedQuery(context, "o.orderId = ?1"), orderId).firstResult()
                : Order.find(buildOptimizedQuery(context, "o.orderId = ?1 and o.store.id = ?2"), orderId, scope.id).firstResult();
        if (order == null) {
            LOGGER.error("Order with orderId " + orderId + " not found");
            throw new NoSuchElementException("Order with orderId " + orderId + " not found");
        }
        LOGGER.info("Exiting method orderByOrderId");
        return order;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new order in the database.
     * <p>
     * Validates that the orderId, Store, and Customer exist.
     * Also validates that products referenced in lines exist.
     *
     * @param input The input record containing order details.
     * @return The newly created Order entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new order")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Order createOrder(@Name("input") OrderRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createOrder for orderId: " + input.orderId);
            // --- Validation: Check OrderId uniqueness ---
            Order existingOrder = Order.find("orderId", input.orderId).firstResult();
            if (existingOrder != null) {
                LOGGER.warn("Attempt to create order with existing orderId: " + input.orderId);
                throw new AlreadyExistsException("Order with orderId '" + input.orderId + "' already exists.");
            }
            // --- Resolve Dependencies ---
            Store store = Store.findById(input.storeId);
            if (store == null) {
                throw new NoSuchElementException("Store with id " + input.storeId + " not found");
            }
            Customer customer = Customer.findById(input.customerId);
            if (customer == null) {
                throw new NoSuchElementException("Customer with id " + input.customerId + " not found");
            }
            // --- Creation Logic ---
            Order order = new Order();
            order.orderId = input.orderId;
            order.channel = input.channel;
            order.status = input.status;
            order.orderDate = input.orderDate != null ? input.orderDate : LocalDateTime.now();
            order.store = store;
            order.customer = customer;
            order.specification = input.specification;
            // Process Lines if provided
            if (input.lines != null && !input.lines.isEmpty()) {
                for (OrderLineRecord lineInput : input.lines) {
                    Product product = Product.findById(lineInput.productId);
                    if (product == null) {
                        throw new NoSuchElementException("Product with id " + lineInput.productId + " not found for line");
                    }
                    OrderLine line = new OrderLine();
                    line.lineNumber = lineInput.lineNumber;
                    line.product = product;
                    line.requestedQuantity = lineInput.requestedQuantity;
                    line.specification = lineInput.specification;
                    order.addLine(line);
                }
            }
            order.persist();
            LOGGER.info("Exiting method createOrder. Created ID: " + order.id);
            return order;
        }, OrderResource.class, "createOrder");
    }

    /**
     * Updates an existing order identified by its ID.
     * <p>
     * Allows updating status, channel, and specification.
     * Does not allow changing the Store or Customer (business rule: cancel and recreate).
     *
     * @param id    The ID of the order to update.
     * @param input The input record containing fields to update.
     * @return The updated Order entity.
     * @throws GraphQLException if validation fails or the order is not found.
     */
    @Mutation
    @Description("Update an existing order")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Order updateOrder(@Name("id") Long id, @Name("input") OrderRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateOrder for id: " + id);
            Order order = Order.findById(id);
            if (order == null) {
                LOGGER.error("Order with id " + id + " not found");
                throw new NoSuchElementException("Order with id " + id + " not found");
            }
            // --- Apply Updates (Partial Update Logic) ---
            if (input.status != null) order.status = input.status;
            if (input.channel != null) order.channel = input.channel;
            if (input.specification != null) order.specification = input.specification;
            // Note: Updating lines is typically done via specific endpoints or complex logic,
            // here we just update the header info for simplicity.
            LOGGER.info("Exiting method updateOrder");
            return order;
        }, OrderResource.class, "updateOrder");
    }

    /**
     * Deletes an order by its unique identifier.
     *
     * @param id The ID of the order to delete.
     * @return true if the order was deleted successfully, false if the order was not found.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete an order by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteOrder(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteOrder for id: " + id);
            boolean result = Order.deleteById(id);
            LOGGER.info("Exiting method deleteOrder. Result: " + result);
            return result;
        }, OrderResource.class, "deleteOrder");
    }

    // --------------------------------------------------
    // Private Helpers
    // --------------------------------------------------

    /**
     * Helper method to dynamically build an optimized JPQL query.
     * <p>
     * Analyzes the GraphQL selection set to add JOIN FETCH clauses only for the fields
     * that are actually requested by the client. This prevents the N+1 select problem.
     *
     * @param context     The GraphQL context to inspect.
     * @param whereClause The WHERE clause (without the "where" keyword), or null if no filtering is needed.
     * @return The constructed JPQL query string.
     */
    private String buildOptimizedQuery(Context context, String whereClause) {
        DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
        // 1. Analyze Selection Set
        boolean wantsLines = env.getSelectionSet().contains("lines");
        boolean wantsCustomer = env.getSelectionSet().contains("customer");
        // 2. Build Query
        StringBuilder queryBuilder = new StringBuilder("select distinct o from Order o ");
        if (wantsLines) {
            // Fetch lines and their associated products to avoid N+1 queries
            queryBuilder.append("left join fetch o.lines l left join fetch l.product ");
        }
        if (wantsCustomer) {
            queryBuilder.append("left join fetch o.customer ");
        }
        // Where Clause
        if (whereClause != null) {
            queryBuilder.append("where ").append(whereClause);
        }
        return queryBuilder.toString();
    }

    // --------------------------------------------------
    // Input Records
    // --------------------------------------------------

    /**
     * Input record type for GraphQL Order mutations.
     * <p>
     * Contains fields necessary for creating or updating an order header.
     */
    @Input
    static public class OrderRecord {
        public String orderId;
        public LocalDateTime orderDate;
        public String channel;
        public String status;
        public Long storeId;
        public Long customerId;
        public String specification;
        public List<OrderLineRecord> lines;

        public OrderRecord() {
        }
    }

    /**
     * Input record type for OrderLine within Order mutations.
     */
    @Input
    static public class OrderLineRecord {
        public int lineNumber;
        public Long productId;
        public BigDecimal requestedQuantity;
        public String specification;

        public OrderLineRecord() {
        }
    }
}