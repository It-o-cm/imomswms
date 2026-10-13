package com.intermarche.backoffice.graphql.order;

import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import graphql.schema.DataFetchingEnvironment;
import io.quarkus.panache.common.Sort;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.graphql.api.Context;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing OrderRequests.
 * <p>
 * This resource provides CRUD operations for the {@link OrderRequest} entity.
 * It enforces role-based access control: MANAGERs can read, ADMINs can write.
 * <p>
 * Implements {@link GraphQLTrait} to centralize exception handling and transaction wrapping
 * via the {@code execute} template method.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class OrderRequestResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(OrderRequestResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

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

    /**
     * Retrieves a list of all order requests in the database (including history).
     * <p>
     * Accessible only by users with the 'MANAGER' role.
     *
     * @return A list of all OrderRequest entities.
     */
    @Query
    @Description("Get a list of all order requests")
    @RolesAllowed("MANAGER")
    public List<OrderRequest> allOrderRequests(Context context) {
        LOGGER.info("Entering method allOrderRequests");
        Store storeContext = getStoreContext();
        if (storeContext != null) {
            String jpql = buildOptimizedQuery(context, "o.store.id = ?1");
            LOGGER.info("Fetching OrderRequest for Store ID: " + storeContext.id);
            return OrderRequest.list(jpql, storeContext.id);
        } else {
            String jpql = buildOptimizedQuery(context, null);
            LOGGER.info("Fetching ALL OrderRequest (Global Access)");
            return OrderRequest.list(jpql);
        }
    }

    /**
     * Retrieves a specific order request by its unique technical ID.
     * <p>
     * Accessible only by users with the 'MANAGER' role.
     *
     * @param id The unique technical ID of the order request.
     * @return The OrderRequest entity.
     * @throws NoSuchElementException if the order request with the given ID does not exist.
     */
    @Query
    @Description("Get an order request by its ID")
    @RolesAllowed("MANAGER")
    public OrderRequest orderRequest(@Name("id") Long id) {
        LOGGER.info("Entering method orderRequest with id: " + id);
        OrderRequest order = OrderRequest.findById(id);
        if (order == null) {
            LOGGER.error("OrderRequest with id " + id + " not found");
            throw new NoSuchElementException("OrderRequest with id " + id + " not found");
        }
        LOGGER.info("Exiting method orderRequest");
        return order;
    }

    /**
     * Retrieves order requests by their order number.
     * <p>
     * Note: Since order numbers are not unique (history allowed), this may return multiple records.
     * <p>
     * Accessible only by users with the 'MANAGER' role.
     *
     * @param orderNumber The business order number.
     * @return A list of OrderRequest entities matching the number.
     */
    @Query
    @Description("Get order requests by order number (can return history)")
    @RolesAllowed("MANAGER")
    public List<OrderRequest> ordersByNumber(@Name("orderNumber") String orderNumber) {
        LOGGER.info("Entering method ordersByNumber with orderNumber: " + orderNumber);
        List<OrderRequest> result = OrderRequest.list("orderNumber", orderNumber);
        LOGGER.info("Exiting method ordersByNumber. Found: " + result.size());
        return result;
    }

    /**
     * Retrieves orders for a specific store created within a given time range.
     * <p>
     * If the end date is omitted, the query returns all orders starting from
     * the start date onwards. Results are sorted by creation date descending.
     * <p>
     * Accessible only by users with the 'MANAGER' role.
     *
     * @param start     The start datetime (inclusive).
     * @param end       The end datetime (optional, inclusive).
     * @return A list of matching OrderRequests.
     */
    @Query
    @Description("Retrieves orders for a specific store created within a given time range.")
    @RolesAllowed("MANAGER")
    public List<OrderRequest> ordersByCreatedDateRange(
            Context context,
            @Name("start") LocalDateTime start,
            @Name("end") LocalDateTime end) {

        LOGGER.infof("Entering method ordersByStore for start: %s, end: %s", start, end);
        String query;
        Object[] params;
        Store storeContext = getStoreContext();
        if (end == null) {
            // Case without end date: >= start
            query = storeContext == null ? "o.createdAt >= ?1" : "o.storeCode = ?1 AND o.createdAt >= ?2";
            params = storeContext == null ? new Object[]{start} : new Object[]{storeContext.code, start};
        } else {
            // Case with end date: between start and end
            query = storeContext == null ? "o.createdAt >= ?1 AND o.createdAt <= ?2" : "o.storeCode = ?1 AND o.createdAt >= ?2 AND o.createdAt <= ?3";
            params = storeContext == null ? new Object[]{start, end} : new Object[]{storeContext.code, start, end};
        }
        String jpql = buildOptimizedQuery(context, query);
        LOGGER.infof("Exiting method ordersByStore for start: %s, end: %s", start, end);
        return OrderRequest.list(jpql, params);
    }

    /**
     * Retrieves orders for a specific store modified within a given time range.
     * <p>
     * If the end date is omitted, the query returns all orders starting from
     * the start date onwards. Results are sorted by creation date descending.
     * <p>
     * Accessible only by users with the 'MANAGER' role.
     *
     * @param start     The start datetime (inclusive).
     * @param end       The end datetime (optional, inclusive).
     * @return A list of matching OrderRequests.
     */
    @Query
    @Description("Retrieves orders for a specific store modified within a given time range.")
    @RolesAllowed("MANAGER")
    public List<OrderRequest> ordersByUpdatedDateRange(
            Context context,
            @Name("start") LocalDateTime start,
            @Name("end") LocalDateTime end) {

        LOGGER.infof("Entering method ordersByStore for start: %s, end: %s", start, end);
        String query;
        Object[] params;
        Store storeContext = getStoreContext();
        if (end == null) {
            // Case without end date: >= start
            query = storeContext == null ? "o.updatedAt >= ?1" : "o.storeCode = ?1 AND o.updatedAt >= ?2";
            params = storeContext == null ? new Object[]{start} : new Object[]{storeContext.code, start};
        } else {
            // Case with end date: between start and end
            query = storeContext == null ? "o.updatedAt >= ?1 AND o.updatedAt <= ?2" : "o.storeCode = ?1 AND o.updatedAt >= ?2 AND o.updatedAt <= ?3";
            params = storeContext == null ? new Object[]{start, end} : new Object[]{storeContext.code, start, end};
        }
        String jpql = buildOptimizedQuery(context, query);
        LOGGER.infof("Exiting method ordersByStore for start: %s, end: %s", start, end);
        return OrderRequest.list(jpql, params);
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // All methods delegate to the default method 'execute' from GraphQLTrait.
    // --------------------------------------------------

    /**
     * Creates a new order request in the database.
     * <p>
     * Note: Unlike Products, Order Numbers are NOT unique. This allows creating
     * a new entry for an existing order number (e.g., retry or update history).
     * <p>
     * The reservation record is automatically initialized with PENDING status by the entity constructor.
     *
     * @param input The input record containing order details.
     * @return The newly created OrderRequest entity.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Create a new order request")
    @RolesAllowed("MANAGER")
    @Transactional(rollbackOn = Exception.class)
    public OrderRequest createOrderRequest(@Name("input") OrderRequestRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createOrderRequest for orderNumber: " + input.orderNumber);

            // --- Creation Logic ---
            // Le constructeur initialise déjà le ReservationRecord avec PENDING
            OrderRequest order = new OrderRequest();
            order.orderNumber = input.orderNumber;
            order.storeCode = input.storeCode;
            order.specification = input.specification;
            // Ensure createdAt is set (defensive, though entity constructor should handle it)
            order.createdAt = DateTimeProvider.now();

            order.persist();

            LOGGER.info("Exiting method createOrderRequest. Created ID: " + order.id);
            return order;
        }, OrderRequestResource.class, "createOrderRequest");
    }

    /**
     * Updates an existing order request identified by its ID.
     * <p>
     * Allows updating the order number, store code, and the JSON specification.
     * <p>
     * Note: To update the status (e.g., to PROCESSING), the business logic usually
     * relies on specific methods like startProcessing(...). This generic update focuses
     * on the data payload.
     *
     * @param id    The ID of the order request to update.
     * @param input The input record containing fields to update.
     * @return The updated OrderRequest entity.
     * @throws GraphQLException if validation fails or the order is not found.
     */
    @Mutation
    @Description("Update an existing order request")
    @RolesAllowed("MANAGER")
    @Transactional(rollbackOn = Exception.class)
    public OrderRequest updateOrderRequest(@Name("id") Long id, @Name("input") OrderRequestRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateOrderRequest for id: " + id);

            OrderRequest order = OrderRequest.findById(id);
            if (order == null) {
                LOGGER.error("OrderRequest with id " + id + " not found");
                throw new NoSuchElementException("OrderRequest with id " + id + " not found");
            }

            // --- Apply Updates (Partial Update Logic) ---
            if (input.orderNumber != null) order.orderNumber = input.orderNumber;
            if (input.storeCode != null) order.storeCode = input.storeCode;
            if (input.specification != null) order.specification = input.specification;

            LOGGER.info("Exiting method updateOrderRequest");
            return order;
        }, OrderRequestResource.class, "updateOrderRequest");
    }

    /**
     * Deletes an order request by its unique identifier.
     *
     * @param id The ID of the order request to delete.
     * @return true if the order request was deleted successfully, false if not found.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete an order request by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteOrderRequest(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteOrderRequest for id: " + id);
            boolean result = OrderRequest.deleteById(id);
            LOGGER.info("Exiting method deleteOrderRequest. Result: " + result);
            return result;
        }, OrderRequestResource.class, "deleteOrderRequest");
    }

    /**
     * Helper method to dynamically build an optimized JPQL query.
     * <p>
     * Analyzes the GraphQL selection set to add JOIN FETCH clauses.
     * Handles the relationships: GroupingOrder -> (Store, Customer, Building) -> Lots -> (FulfillmentLine, Product).
     *
     * @param context     The GraphQL context to inspect.
     * @param whereClause The WHERE clause (without the "where" keyword), or null.
     * @return The constructed JPQL query string.
     */
    private String buildOptimizedQuery(Context context, String whereClause) {
        DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
        StringBuilder query = new StringBuilder("select distinct o from OrderRequest o ");
        if (whereClause != null) {
            query.append("where ").append(whereClause);
        }
        return query.toString();
    }

    /**
     * Input record type for GraphQL OrderRequest mutations.
     * <p>
     * Contains fields that can be used for creating or updating an order request.
     * Fields are nullable to support partial updates.
     */
    @Input
    static public class OrderRequestRecord {
        public String orderNumber;
        public String storeCode;
        public String specification;

        public OrderRequestRecord() {
        }

        /**
         * Returns a string representation of the record.
         * Note: Used mainly for debugging/logging purposes.
         */
        public String toString() {
            return "OrderRequestRecord [orderNumber=" + orderNumber + ", storeCode=" + storeCode +
                    ", specification=" + specification + "]";
        }

    }
}