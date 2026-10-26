package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GraphQL API for managing Lots.
 * <p>
 * This resource provides CRUD operations for the {@link Lot} entity.
 * It enforces role-based access control: MANAGERs can read, ADMINs can write.
 * <p>
 * Implements {@link GraphQLTrait} to centralize exception handling and transaction wrapping
 * via the {@code execute} template method.
 * <p>
 * <b>Performance:</b> This resource uses a dynamic query builder ({@link #buildOptimizedQuery})
 * to fetch nested relationships (Lines, FulfillmentLine, Product, Chunk, Location) efficiently
 * using JOIN FETCH, preventing the N+1 select problem.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class LotResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(LotResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Security identity of the current authenticated user.
     */
    @Inject
    SecurityIdentity identity;

    @Inject
    LotResourceService lotService;

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
     * Retrieves a list of all lots the user may read: those of their store for a manager.
     * <p>
     * Delegates to {@link #buildOptimizedQuery(Context, String, boolean)} to construct
     * a JPQL query with dynamic JOIN FETCH clauses.
     *
     * @param context The SmallRye GraphQL context.
     * @return A {@link List} of the readable {@link Lot} entities.
     */
    @Query
    @Description("Get a list of all lots")
    @RolesAllowed("MANAGER")
    public List<Lot> allLots(Context context) {
        LOGGER.info("Entering method allLots");
        Store scope = readScope();
        List<Lot> result = scope == null ? Lot.list(buildOptimizedQuery(context, null, false))
                : Lot.list(buildOptimizedQuery(context, "l.store.id = ?1", false), scope.id);
        LOGGER.info("Exiting method allLots");
        return result;
    }

    /**
     * Retrieves a specific lot by its unique technical identifier (Primary Key).
     *
     * @param id      The unique technical ID (Primary Key) of the Lot.
     * @param context The SmallRye GraphQL context.
     * @return The found {@link Lot} entity.
     * @throws NoSuchElementException if no Lot exists with the given ID in the scope of the user.
     */
    @Query
    @Description("Get a lot by its technical ID")
    @RolesAllowed("MANAGER")
    public Lot lot(@Name("id") Long id, Context context) {
        LOGGER.info("Entering method lot with id: " + id);
        Store scope = readScope();
        Lot lot = scope == null ? Lot.find(buildOptimizedQuery(context, "l.id = ?1", false), id).firstResult()
                : Lot.find(buildOptimizedQuery(context, "l.id = ?1 and l.store.id = ?2", false), id, scope.id).firstResult();
        if (lot == null) {
            LOGGER.error("Lot with id " + id + " not found");
            throw new NoSuchElementException("Lot with id " + id + " not found");
        }
        LOGGER.info("Exiting method lot");
        return lot;
    }

    /**
     * Retrieves all lots associated with a specific Fulfillment Chunk.
     * <p>
     * Forces a join on the Chunk table for filtering purposes.
     *
     * @param chunkId The ID of the parent FulfillmentChunk.
     * @param context The SmallRye GraphQL context.
     * @return A list of {@link Lot} entities, restricted to the store of a manager.
     */
    @Query
    @Description("Get lots by the linked Fulfillment Chunk ID")
    @RolesAllowed("MANAGER")
    public List<Lot> lotsByChunkId(@Name("chunkId") Long chunkId, Context context) {
        LOGGER.info("Entering method lotsByChunkId with chunkId: " + chunkId);
        Store scope = readScope();
        List<Lot> lots = scope == null ? Lot.list(buildOptimizedQuery(context, "c.id = ?1", true), chunkId)
                : Lot.list(buildOptimizedQuery(context, "c.id = ?1 and l.store.id = ?2", true), chunkId, scope.id);
        LOGGER.info("Exiting method lotsByChunkId");
        return lots;
    }

    // --------------------------------------------------
    // Private Helpers
    // --------------------------------------------------

    /**
     * Helper method to dynamically build an optimized JPQL query.
     * <p>
     * Analyzes the GraphQL selection set to add JOIN FETCH clauses.
     * Handles the unidirectional structure: Lot -> LotLine -> (FulfillmentLine, Product).
     * Also handles Lot -> (Chunk, Location).
     *
     * @param context         The GraphQL context to inspect.
     * @param whereClause     The WHERE clause (without the "where" keyword), or null.
     * @param forceChunkJoin  If true, forces a JOIN on the Chunk table (required for filtering by Chunk ID).
     * @return The constructed JPQL query string.
     */
    private String buildOptimizedQuery(Context context, String whereClause, boolean forceChunkJoin) {
        DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);

        // 1. Analyze Selection Set
        boolean wantsChunk = env.getSelectionSet().contains("chunk");
        boolean wantsLocation = env.getSelectionSet().contains("location");
        boolean wantsLines = env.getSelectionSet().contains("lines");

        boolean wantsFulfillmentLine = false;
        boolean wantsProduct = false;

        if (wantsLines) {
            // Check nested fields inside 'lines'
            wantsFulfillmentLine = env.getSelectionSet().getFields().stream()
                    .anyMatch(f -> f.getName().equals("lines") && f.getSelectionSet().contains("fulfillmentLine"));
            wantsProduct = env.getSelectionSet().getFields().stream()
                    .anyMatch(f -> f.getName().equals("lines") && f.getSelectionSet().contains("product"));
        }

        // 2. Build Query
        StringBuilder query = new StringBuilder("select distinct l from Lot l ");

        // Chunk Logic
        if (forceChunkJoin || wantsChunk) {
            if (wantsChunk) {
                query.append("left join fetch l.chunk c ");
            } else {
                query.append("join l.chunk c ");
            }
        }

        // Location Logic
        if (wantsLocation) {
            query.append("left join fetch l.location loc ");
        }

        // Lines Logic
        if (wantsLines) {
            query.append("left join fetch l.lines ll ");
            if (wantsFulfillmentLine) {
                query.append("left join fetch ll.fulfillmentLine ffl ");
            }
            if (wantsProduct) {
                query.append("left join fetch ll.product p ");
            }
        }

        // Where Clause
        if (whereClause != null) {
            query.append("where ").append(whereClause);
        }
        return query.toString();
    }

    /**
     * Updates an existing Lot, including its lines.
     * <p>
     * If a line is added without a locationId, the system calculates the common Area/Aisle
     * of the Lot's current lines and finds the closest ShelfLocation for the product.
     */
    @Mutation
    @Description("Update a Lot and its lines")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public Lot updateLot(@Name("id") Long id, @Name("input") LotRecord input) throws GraphQLException {
        return execute(() -> {
            LOGGER.info("Entering method updateLot for id: " + id);
            Lot lot = Lot.findById(id);
            if (lot == null) {
                throw new NoSuchElementException("Lot with id " + id + " not found");
            }
            // Security Check
            Store storeContext = getStoreContext();
            if (storeContext != null && !lot.store.id.equals(storeContext.id)) {
                throw new SecurityException("Access denied: Lot belongs to a different store.");
            }
            // 1. Update Basic Fields
            if (input.status != null) {
                lot.status = input.status;
            }
            // 2. Update Lines
            if (input.lines != null) {
                lotService.synchronizeLotLines(lot, input.lines);
            }
            LOGGER.info("Exiting method updateLot");
            return lot;
        }, LotResource.class, "updateLot");

    }

    /**
     * Input record type for updating a Lot.
     */
    @Input
    static public class LotRecord {
        /**
         * The new status for the lot.
         */
        public Lot.LotStatus status;

        /**
         * The list of lines to update or create.
         * Lines missing from this list will be deleted.
         */
        public List<LotResourceService.LotLineRecord> lines;
    }

}