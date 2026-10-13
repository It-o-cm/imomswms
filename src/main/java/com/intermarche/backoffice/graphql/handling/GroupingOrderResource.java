package com.intermarche.backoffice.graphql.handling;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.graphql.LotResourceService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Grouping Handling Orders.
 * <p>
 * This resource provides CRUD operations for the {@link GroupingOrder} entity.
 * It enforces data scoping based on the user's store association and delegates
 * complex Lot and LotLine synchronization to the {@link LotResourceService}.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class GroupingOrderResource implements CollectingOrderGraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(GroupingOrderResource.class);

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
     * Retrieves a list of all Grouping Handling Orders in the database, filtered by context.
     *
     * @param context The SmallRye GraphQL context used for query optimization.
     * @return A {@link List} of all {@link GroupingOrder} entities accessible to the user.
     * @throws GraphQLException if the execution fails unexpectedly.
     */
    @Query
    @Description("Get a list of all Grouping Handlers")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public List<GroupingOrder> allGroupingOrders(Context context) throws GraphQLException {
        return execute(() -> {
            Store storeContext = getStoreContext();
            String jpql = buildOptimizedQuery(context, storeContext != null ? "o.store.id = ?1" : null);
            if (storeContext != null) {
                return GroupingOrder.list(jpql, storeContext.id);
            }
            return GroupingOrder.list(jpql);
        }, GroupingOrderResource.class, "allGroupingOrders");
    }

    /**
     * Retrieves a list of Grouping Orders filtered by requested date range.
     *
     * @param start   The start of the date range (inclusive).
     * @param end     The end of the date range (inclusive).
     * @param context The SmallRye GraphQL context used for query optimization.
     * @return A list of matching {@link GroupingOrder} entities.
     * @throws GraphQLException if the execution fails unexpectedly.
     */
    @Query
    @Description("Get Grouping Orders by requested date range")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public List<GroupingOrder> groupingOrdersByDateRange(
            @Name("start") LocalDateTime start,
            @Name("end") LocalDateTime end,
            Context context) throws GraphQLException {
        return execute(() -> {
            Store storeContext = getStoreContext();
            String whereClause = "o.requestedStart >= ?1 AND o.requestedStart <= ?2";

            if (storeContext != null) {
                whereClause += " AND o.store.id = ?3";
                String jpql = buildOptimizedQuery(context, whereClause);
                return GroupingOrder.list(jpql, start, end, storeContext.id);
            } else {
                String jpql = buildOptimizedQuery(context, whereClause);
                return GroupingOrder.list(jpql, start, end);
            }
        }, GroupingOrderResource.class, "groupingOrdersByDateRange");
    }

    /**
     * Retrieves a specific Grouping Handler by its unique technical identifier (Primary Key).
     *
     * @param id      The unique technical ID (Primary Key) of the Grouping Handler.
     * @param context The SmallRye GraphQL context used for query optimization.
     * @return The found {@link GroupingOrder} entity.
     * @throws NoSuchElementException if no Grouping Handler exists with the given ID.
     * @throws GraphQLException        if the execution fails unexpectedly.
     */
    @Query
    @Description("Get a Grouping Handler by its technical ID")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public GroupingOrder groupingOrder(@Name("id") Long id, Context context) throws GraphQLException {
        return execute(() -> {
            Store storeContext = getStoreContext();
            String jpql = buildOptimizedQuery(context, "o.id = ?1");

            if (storeContext != null) {
                jpql += " AND o.store.id = ?2";
                GroupingOrder handler = GroupingOrder.find(jpql, id, storeContext.id).firstResult();
                if (handler == null) {
                    throw new NoSuchElementException("Grouping Handler with id " + id + " not found in your store");
                }
                return handler;
            } else {
                GroupingOrder handler = GroupingOrder.find(jpql, id).firstResult();
                if (handler == null) {
                    throw new NoSuchElementException("Grouping Handler with id " + id + " not found");
                }
                return handler;
            }
        }, GroupingOrderResource.class, "groupingOrder");
    }

    // --------------------------------------------------
    // Mutations
    // --------------------------------------------------

    /**
     * Updates a Grouping Order including its Lots and LotLines.
     * <p>
     * This mutation allows updating order-level fields (status, assignee, schedule)
     * and delegates the complex synchronization of Lots and their nested LotLines
     * to the {@link LotResourceService}.
     *
     * @param id    The ID of the order to update.
     * @param input The input record containing fields to update at the order and lot levels.
     * @return The updated {@link GroupingOrder} entity with all nested changes applied.
     * @throws GraphQLException        if the execution fails unexpectedly.
     * @throws NoSuchElementException if the order, employee, or nested entities are not found.
     * @throws SecurityException      if the user attempts to access an order outside their store scope.
     */
    @Mutation
    @Description("Update a Grouping Order including Lots and LotLines")
    @RolesAllowed({"MANAGER", "ADMIN"})
    @Transactional
    public GroupingOrder updateGroupingOrder(
            @Name("id") Long id,
            @Name("input") GroupingOrderRecord input) throws GraphQLException {

        return execute(() -> {
            Store storeContext = getStoreContext();
            GroupingOrder order = GroupingOrder.findById(id);
            if (order == null) {
                throw new NoSuchElementException("Order not found.");
            }
            if (storeContext != null && !order.store.id.equals(storeContext.id)) {
                throw new SecurityException("Access denied: Order belongs to a different store.");
            }
            updateOrderFields(order, input, storeContext);
            // Delegate lot and lot line updates to the dedicated service
            lotResourceService.synchronizeLots(order, input.lots);
            return order;
        }, GroupingOrderResource.class, "updateGroupingOrder");
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record for Grouping Order updates.
     * <p>
     * All fields are nullable to support partial updates.
     * Includes the ability to update nested Lots and LotLines via the service.
     */
    @Input
    public static class GroupingOrderRecord {

        /**
         * The new status for the order.
         */
        public BaseHandlingOrder.HandlingOrderStatus status;

        /**
         * The technical ID of the employee to assign.
         * If null, the assignee is cleared.
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
         * The list of Lots to update within this order.
         * <p>
         * Only lots specified in this list will be modified. The synchronization
         * of lines within these lots is handled by {@link LotResourceService}.
         */
        public List<LotResourceService.LotUpdateRecord> lots;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Updates the order-level fields from the input record.
     *
     * @param order       The order entity to update.
     * @param input       The input data containing the new values.
     * @param storeContext The current user's store context (used for security validation on assignee).
     * @throws NoSuchElementException If the assignee employee is not found.
     * @throws SecurityException     If the assignee does not belong to the same store.
     */
    private void updateOrderFields(GroupingOrder order, GroupingOrderRecord input, Store storeContext) {
        if (input.status != null) {
            order.status = input.status;
        }
        if (input.requestedStart != null) {
            order.requestedStart = input.requestedStart;
        }
        if (input.requestedEnd != null) {
            order.requestedEnd = input.requestedEnd;
        }
        if (input.assigneeId != null) {
            Employee emp = Employee.findById(input.assigneeId);
            if (emp == null) {
                throw new NoSuchElementException("Employee not found.");
            }
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
     * @param whereClause The WHERE clause (without the "where" keyword), or null if no filter is needed.
     * @return The constructed JPQL query string.
     */
    private String buildOptimizedQuery(Context context, String whereClause) {
        DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
        boolean wantsCustomer = env.getSelectionSet().contains("customer");
        boolean wantsBuilding = env.getSelectionSet().contains("building");

        StringBuilder query = new StringBuilder("select distinct o from GroupingOrder o ");
        query.append(this.optimizeCollectingQuery(context));

        if (wantsCustomer) {
            query.append("left join fetch o.customer c ");
        }
        if (wantsBuilding) {
            query.append("left join fetch o.buffer b left outer join fetch b.building bl ");
        }
        if (whereClause != null) {
            query.append("where ").append(whereClause);
        }
        return query.toString();
    }
}