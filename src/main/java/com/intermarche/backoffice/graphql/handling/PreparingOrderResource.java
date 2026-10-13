package com.intermarche.backoffice.graphql.handling;

import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.graphql.api.Context;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Handling Orders.
 * <p>
 * Provides query methods to retrieve handling orders, specifically filtered by Fulfillment Chunks.
 * Implements {@link HandlingOrderGraphQLTrait} to centralize optimization logic.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class PreparingOrderResource implements HandlingOrderGraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(PreparingOrderResource.class);

    @Inject
    SecurityIdentity identity;

    @Override
    public SecurityIdentity getIdentity() {
        return identity;
    }

    /**
     * Retrieves a specific Handling Order by its unique technical ID.
     * <p>
     * Accessible by MANAGER, ADMIN, or SERVICE_CALLER.
     * Throws NoSuchElementException which is cleanly caught by GraphQLTrait.
     *
     * @param id The unique technical ID of the handling order.
     * @return The BaseHandlingOrder entity.
     */
    @Query
    @Description("Get a handling order by its ID")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public BaseHandlingOrder handlingOrder(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            BaseHandlingOrder order = BaseHandlingOrder.findById(id);
            if (order == null) {
                // Le GraphQLTrait va intercepter ce NoSuchElementException
                // et le transformer en erreur GraphQL propre pour le frontend
                throw new NoSuchElementException("HandlingOrder with id " + id + " not found");
            }
            return order;
        }, PreparingOrderResource.class, "handlingOrder");
    }

    /**
     * Retrieves Handling Orders associated with a specific Fulfillment Chunk ID.
     * <p>
     * This query is optimized to fetch the Store and Assignee relationships only if requested
     * by the GraphQL selection set.
     *
     * @param chunkId The ID of the Fulfillment Chunk to filter by.
     * @param context The GraphQL context used for query optimization.
     * @return A list of {@link BaseHandlingOrder} entities linked to the chunk.
     * @throws GraphQLException if execution fails.
     */
    @Query
    @Description("Get Preparing Orders linked to a specific Fulfillment Chunk ID")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public List<BaseHandlingOrder> preparingOrdersByChunkId(
            @Name("chunkId") Long chunkId,
            Context context) throws GraphQLException {

        return this.execute(() -> {
            Store storeContext = getStoreContext();

            // 1. Build optimized fetch joins based on the request selection set
            String fetchJoins = optimizeHandlingOrderQuery(context);

            // 2. Construct the base query
            StringBuilder jpql = new StringBuilder("SELECT DISTINCT po FROM PreparingOrder po JOIN po.lots lot ");
            jpql.append(fetchJoins);

            // Filter by the chunk ID.
            jpql.append("WHERE lot.chunk.id = ?1 ");

            // 3. Apply Store scope security if user is not ADMIN
            if (storeContext != null) {
                jpql.append("AND po.store.id = ?2");
                return BaseHandlingOrder.find(jpql.toString(), chunkId, storeContext.id).list();
            } else {
                return BaseHandlingOrder.find(jpql.toString(), chunkId).list();
            }

        }, PreparingOrderResource.class, "preparingOrdersByChunkId");
    }
}