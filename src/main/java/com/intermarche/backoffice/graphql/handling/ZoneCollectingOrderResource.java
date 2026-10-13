package com.intermarche.backoffice.graphql.handling;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.ZoneCollectingOrder;
import com.intermarche.backoffice.graphql.LotResourceService;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.graphql.api.Context;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Zone Collecting Orders.
 * <p>
 * This resource provides CRUD operations for the {@link ZoneCollectingOrder} entity.
 * It enforces data scoping based on the user's store association and delegates
 * complex Lot and LotLine synchronization to the {@link LotResourceService}.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class ZoneCollectingOrderResource implements CollectingOrderGraphQLTrait {

    /**
     * The security identity of the current authenticated user.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * The service responsible for Lot and LotLine business logic.
     */
    @Inject
    LotResourceService lotResourceService;

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
    // Queries
    // --------------------------------------------------

    /**
     * Retrieves a list of all Zone Collecting Orders, filtered by context.
     *
     * @param context The SmallRye GraphQL context used for query optimization.
     * @return A {@link List} of all {@link ZoneCollectingOrder} entities accessible to the user.
     * @throws GraphQLException if the execution fails unexpectedly.
     */
    @Query
    @Description("Get a list of all Zone Collecting Orders")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public List<ZoneCollectingOrder> allZoneCollectingOrders(Context context) throws GraphQLException {
        return execute(() -> {
            Store storeContext = getStoreContext();
            String jpql = buildOptimizedQuery(context, storeContext != null ? "o.store.id = ?1" : null);
            if (storeContext != null) {
                return ZoneCollectingOrder.list(jpql, storeContext.id);
            }
            return ZoneCollectingOrder.list(jpql);
        }, ZoneCollectingOrderResource.class, "allZoneCollectingOrders");
    }

    /**
     * Retrieves a list of Zone Collecting Orders filtered by requested date range.
     *
     * @param start   The start of the date range (inclusive).
     * @param end     The end of the date range (inclusive).
     * @param context The SmallRye GraphQL context used for query optimization.
     * @return A list of matching {@link ZoneCollectingOrder} entities.
     * @throws GraphQLException if the execution fails unexpectedly.
     */
    @Query
    @Description("Get Zone Collecting Orders by requested date range")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public List<ZoneCollectingOrder> zoneCollectingOrdersByDateRange(
            @Name("start") LocalDateTime start,
            @Name("end") LocalDateTime end,
            Context context) throws GraphQLException {
        return execute(() -> {
            Store storeContext = getStoreContext();
            String whereClause = "o.requestedStart >= ?1 AND o.requestedStart <= ?2";

            if (storeContext != null) {
                whereClause += " AND o.store.id = ?3";
                String jpql = buildOptimizedQuery(context, whereClause);
                return ZoneCollectingOrder.list(jpql, start, end, storeContext.id);
            } else {
                String jpql = buildOptimizedQuery(context, whereClause);
                return ZoneCollectingOrder.list(jpql, start, end);
            }
        }, ZoneCollectingOrderResource.class, "zoneCollectingOrdersByDateRange");
    }

    /**
     * Retrieves a specific Zone Collecting Order by its unique technical identifier.
     *
     * @param id      The unique technical ID of the order.
     * @param context The SmallRye GraphQL context used for query optimization.
     * @return The found {@link ZoneCollectingOrder} entity.
     * @throws NoSuchElementException if no Zone Collecting Order exists with the given ID.
     * @throws GraphQLException        if the execution fails unexpectedly.
     */
    @Query
    @Description("Get a Zone Collecting Order by its technical ID")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public ZoneCollectingOrder zoneCollectingOrder(@Name("id") Long id, Context context) throws GraphQLException {
        return execute(() -> {
            Store storeContext = getStoreContext();
            String jpql = buildOptimizedQuery(context, "o.id = ?1");

            if (storeContext != null) {
                jpql += " AND o.store.id = ?2";
                ZoneCollectingOrder order = ZoneCollectingOrder.find(jpql, id, storeContext.id).firstResult();
                if (order == null) throw new NoSuchElementException("Order not found in your store.");
                return order;
            } else {
                ZoneCollectingOrder order = ZoneCollectingOrder.find(jpql, id).firstResult();
                if (order == null) throw new NoSuchElementException("Order not found.");
                return order;
            }
        }, ZoneCollectingOrderResource.class, "zoneCollectingOrder");
    }

    // --------------------------------------------------
    // Mutations
    // --------------------------------------------------

    /**
     * Updates a Zone Collecting Order including its Lots and LotLines.
     * <p>
     * Delegates the complex synchronization of Lots and their nested LotLines
     * to the {@link LotResourceService}.
     *
     * @param id    The ID of the order to update.
     * @param input The input record containing fields to update at the order and lot levels.
     * @return The updated {@link ZoneCollectingOrder} entity.
     * @throws GraphQLException        if the execution fails unexpectedly.
     * @throws NoSuchElementException if the order, employee, or nested entities are not found.
     * @throws SecurityException      if the user attempts to access an order outside their store scope.
     */
    @Mutation
    @Description("Update a Zone Collecting Order including Lots and LotLines")
    @RolesAllowed({"MANAGER", "ADMIN"})
    @Transactional
    public ZoneCollectingOrder updateZoneCollectingOrder(
            @Name("id") Long id,
            @Name("input") ZoneCollectingOrderRecord input) throws GraphQLException {

        return execute(() -> {
            Store storeContext = getStoreContext();
            ZoneCollectingOrder order = ZoneCollectingOrder.findById(id);

            if (order == null) {
                throw new NoSuchElementException("Order not found.");
            }

            if (storeContext != null && !order.store.id.equals(storeContext.id)) {
                throw new SecurityException("Access denied.");
            }

            updateOrderFields(order, input, storeContext);
            lotResourceService.synchronizeLots(order, input.lots);

            return order;
        }, ZoneCollectingOrderResource.class, "updateZoneCollectingOrder");
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record for Zone Collecting Order updates.
     */
    @Input
    public static class ZoneCollectingOrderRecord {

        /**
         * The new status for the order.
         */
        public BaseHandlingOrder.HandlingOrderStatus status;

        /**
         * The technical ID of the employee to assign.
         */
        public Long assigneeId;

        /**
         * The new requested start time.
         */
        public LocalDateTime requestedStart;

        /**
         * The new requested end time.
         */
        public LocalDateTime requestedEnd;

        /**
         * The list of Lots to synchronize within this order.
         */
        public List<LotResourceService.LotUpdateRecord> lots;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Updates the order-level fields from the input record.
     *
     * @param order        The order entity to update.
     * @param input        The input data containing the new values.
     * @param storeContext The current user's store context (used for security validation).
     * @throws NoSuchElementException If the assignee employee is not found.
     * @throws SecurityException     If the assignee does not belong to the same store.
     */
    private void updateOrderFields(ZoneCollectingOrder order, ZoneCollectingOrderRecord input, Store storeContext) {
        if (input.status != null) order.status = input.status;
        if (input.requestedStart != null) order.requestedStart = input.requestedStart;
        if (input.requestedEnd != null) order.requestedEnd = input.requestedEnd;

        if (input.assigneeId != null) {
            Employee emp = Employee.findById(input.assigneeId);
            if (emp == null) throw new NoSuchElementException("Employee not found.");
            if (storeContext != null && !emp.store.id.equals(storeContext.id)) {
                throw new SecurityException("Cannot assign employee from a different store.");
            }
            order.assignee = emp;
        } else {
            order.assignee = null;
        }
    }

    /**
     * Helper method to dynamically build an optimized JPQL query based on the GraphQL selection set.
     *
     * @param context     The GraphQL context to inspect.
     * @param whereClause The WHERE clause (without the "where" keyword), or null.
     * @return The constructed JPQL query string.
     */
    private String buildOptimizedQuery(Context context, String whereClause) {
        StringBuilder query = new StringBuilder("select distinct o from ZoneCollectingOrder o ");
        query.append(this.optimizeCollectingQuery(context));
        if (whereClause != null) query.append("where ").append(whereClause);
        return query.toString();
    }
}