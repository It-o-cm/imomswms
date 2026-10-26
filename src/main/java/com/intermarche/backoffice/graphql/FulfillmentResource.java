package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.handling.CollectingOrderGraphQLTrait;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GraphQL API for managing Fulfillments.
 * <p>
 * This resource provides CRUD operations for the {@link Fulfillment} entity.
 * It enforces role-based access control: MANAGERs can read, ADMINs can write.
 * <p>
 * Implements {@link CollectingOrderGraphQLTrait} to centralize exception handling and transaction wrapping.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class FulfillmentResource implements CollectingOrderGraphQLTrait {

    /**
     * Logger instance for this class.
     */
    private static final Logger LOGGER = Logger.getLogger(FulfillmentResource.class);

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
     * Retrieves Fulfillments that have chunks scheduled within a specific delivery date range.
     * <p>
     * Filters by the user's store context if the user is not an ADMIN.
     *
     * @param start   The start of the date range.
     * @param end     The end of the date range.
     * @param context The GraphQL context.
     * @return A list of matching Fulfillments.
     * @throws GraphQLException if execution fails.
     */
    @Query
    @Description("Get Fulfillments by chunk delivery date range")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public List<Fulfillment> fulfillmentsByDeliveryDate(
            @Name("start") LocalDateTime start,
            @Name("end") LocalDateTime end,
            Context context) throws GraphQLException {

        return this.execute(() -> {
            Store storeContext = getStoreContext();

            DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
            boolean wantsChunks = env.getSelectionSet().contains("chunks");
            boolean wantsOrder = env.getSelectionSet().contains("order");
            boolean wantsLines = wantsChunks && env.getSelectionSet().getFields().stream()
                    .anyMatch(f -> f.getName().equals("chunks") && f.getSelectionSet().contains("lines"));

            StringBuilder jpql = new StringBuilder("SELECT DISTINCT f FROM Fulfillment f ");

            // Join chunks for date filtering
            if (wantsChunks) {
                jpql.append("JOIN FETCH f.chunks c ");
            } else {
                jpql.append("JOIN f.chunks c ");
            }

            // Join order for store filtering
            if (storeContext != null || wantsOrder) {
                if (wantsOrder) {
                    jpql.append("JOIN FETCH f.order o ");
                } else {
                    jpql.append("JOIN f.order o ");
                }
            }

            if (wantsLines) {
                jpql.append("LEFT JOIN FETCH c.lines l LEFT JOIN FETCH l.product ");
            }

            jpql.append("WHERE c.deliveryDate >= ?1 AND c.deliveryDate <= ?2 ");

            if (storeContext != null) {
                jpql.append("AND o.store.id = ?3");
                return Fulfillment.find(jpql.toString(), start, end, storeContext.id).list();
            } else {
                return Fulfillment.find(jpql.toString(), start, end).list();
            }

        }, FulfillmentResource.class, "fulfillmentsByDeliveryDate");
    }

    /**
     * Retrieves a list of all fulfillments the user may read: those of their store for a manager.
     *
     * @param context The SmallRye GraphQL context.
     * @return A {@link List} of the readable {@link Fulfillment} entities.
     * @throws GraphQLException if execution fails.
     */
    @Query
    @Description("Get a list of all fulfillments")
    @RolesAllowed("MANAGER")
    public List<Fulfillment> allFulfillments(Context context) throws GraphQLException {
        return this.execute(() -> {
            Store scope = readScope();
            if (scope == null) return Fulfillment.list(buildOptimizedQuery(context, null, false));
            return Fulfillment.list(buildOptimizedQuery(context, "o.store.id = ?1", true), scope.id);
        }, FulfillmentResource.class, "allFulfillments");
    }

    /**
     * Retrieves a specific fulfillment by its unique technical identifier (Primary Key).
     *
     * @param id      The unique technical ID (Primary Key) of the Fulfillment.
     * @param context The SmallRye GraphQL context.
     * @return The found {@link Fulfillment} entity.
     * @throws NoSuchElementException if no Fulfillment exists with the given ID in the scope of the user.
     * @throws GraphQLException if execution fails.
     */
    @Query
    @Description("Get a fulfillment by its technical ID")
    @RolesAllowed("MANAGER")
    public Fulfillment fulfillment(@Name("id") Long id, Context context) throws GraphQLException {
        return this.execute(() -> {
            Store scope = readScope();
            Fulfillment fulfillment = scope == null
                    ? Fulfillment.find(buildOptimizedQuery(context, "f.id = ?1", false), id).firstResult()
                    : Fulfillment.find(buildOptimizedQuery(context, "f.id = ?1 and o.store.id = ?2", true), id, scope.id).firstResult();
            if (fulfillment == null) {
                throw new NoSuchElementException("Fulfillment with id " + id + " not found");
            }
            return fulfillment;
        }, FulfillmentResource.class, "fulfillment");
    }

    /**
     * Retrieves a specific fulfillment by the business ID of its linked Order.
     *
     * @param orderId The business identifier of the linked Order.
     * @param context The SmallRye GraphQL context.
     * @return The found {@link Fulfillment} entity.
     * @throws NoSuchElementException if no Fulfillment exists for the given Order ID in the scope of the user.
     * @throws GraphQLException if execution fails.
     */
    @Query
    @Description("Get a fulfillment by the linked Order ID")
    @RolesAllowed("MANAGER")
    public Fulfillment fulfillmentByOrderId(@Name("orderId") String orderId, Context context) throws GraphQLException {
        return this.execute(() -> {
            Store scope = readScope();
            Fulfillment fulfillment = scope == null
                    ? Fulfillment.find(buildOptimizedQuery(context, "o.orderId = ?1", true), orderId).firstResult()
                    : Fulfillment.find(buildOptimizedQuery(context, "o.orderId = ?1 and o.store.id = ?2", true), orderId, scope.id).firstResult();
            if (fulfillment == null) {
                throw new NoSuchElementException("Fulfillment for orderId '" + orderId + "' not found");
            }
            return fulfillment;
        }, FulfillmentResource.class, "fulfillmentByOrderId");
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new fulfillment in the database.
     *
     * @param input The input record containing fulfillment details.
     * @return The newly created Fulfillment entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new fulfillment")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Fulfillment createFulfillment(@Name("input") FulfillmentRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createFulfillment for orderId: " + input.orderId);

            // --- Validation: Check Order existence ---
            Order order = Order.find("orderId", input.orderId).firstResult();
            if (order == null) {
                throw new NoSuchElementException("Order with orderId '" + input.orderId + "' not found");
            }

            // --- Validation: Check if Fulfillment already exists for this Order ---
            Fulfillment existingFulfillment = Fulfillment.find("order", order).firstResult();
            if (existingFulfillment != null) {
                LOGGER.warn("Attempt to create fulfillment for existing order: " + input.orderId);
                throw new AlreadyExistsException("Fulfillment for orderId '" + input.orderId + "' already exists.");
            }

            // --- Creation Logic ---
            Fulfillment fulfillment = new Fulfillment();
            fulfillment.order = order;

            // Apply Reservation Record if provided
            if (input.reservationRecord != null) {
                fulfillment.reservationRecord.status = input.reservationRecord.status;
                fulfillment.reservationRecord.leasing = input.reservationRecord.leasing;
            }

            // Process Chunks if provided
            if (input.chunks != null && !input.chunks.isEmpty()) {
                for (FulfillmentChunkRecord chunkInput : input.chunks) {
                    FulfillmentChunk chunk = new FulfillmentChunk();
                    chunk.type = chunkInput.type;
                    chunk.deliveryDate = chunkInput.deliveryDate;

                    // Process Lines for this Chunk
                    if (chunkInput.lines != null && !chunkInput.lines.isEmpty()) {
                        for (FulfillmentLineRecord lineInput : chunkInput.lines) {
                            Product product = Product.findById(lineInput.productId);
                            if (product == null) {
                                throw new NoSuchElementException("Product with id " + lineInput.productId + " not found for line");
                            }
                            FulfillmentLine line = new FulfillmentLine();
                            line.lineNumber = lineInput.lineNumber;
                            line.product = product;
                            line.requestedQuantity = lineInput.requestedQuantity;
                            chunk.addLine(line);
                        }
                    }
                    fulfillment.addChunk(chunk);
                }
            }

            fulfillment.persist();
            LOGGER.info("Exiting method createFulfillment. Created ID: " + fulfillment.id);
            return fulfillment;
        }, FulfillmentResource.class, "createFulfillment");
    }

    /**
     * Updates an existing fulfillment identified by its ID.
     *
     * @param id    The ID of the fulfillment to update.
     * @param input The input record containing fields to update.
     * @return The updated Fulfillment entity.
     * @throws GraphQLException if validation fails or the fulfillment is not found.
     */
    @Mutation
    @Description("Update an existing fulfillment")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Fulfillment updateFulfillment(@Name("id") Long id, @Name("input") FulfillmentRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateFulfillment for id: " + id);
            Fulfillment fulfillment = Fulfillment.findById(id);
            if (fulfillment == null) {
                LOGGER.error("Fulfillment with id " + id + " not found");
                throw new NoSuchElementException("Fulfillment with id " + id + " not found");
            }

            // Apply updates from the Reservation Record input
            if (input.reservationRecord != null) {
                if (input.reservationRecord.status != null) {
                    fulfillment.reservationRecord.status = input.reservationRecord.status;
                }
                if (input.reservationRecord.leasing != null) {
                    fulfillment.reservationRecord.leasing = input.reservationRecord.leasing;
                }
            }

            LOGGER.info("Exiting method updateFulfillment");
            return fulfillment;
        }, FulfillmentResource.class, "updateFulfillment");
    }

    /**
     * Updates an existing Fulfillment Chunk, including its lines.
     * <p>
     * Handles creation, update, and deletion of lines based on the provided input.
     *
     * @param id    The ID of the chunk to update.
     * @param input The input record containing chunk details.
     * @return The updated FulfillmentChunk entity.
     * @throws GraphQLException if validation fails or the chunk is not found.
     */
    @Mutation
    @Description("Update a Fulfillment Chunk and its lines")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public FulfillmentChunk updateFulfillmentChunk(@Name("id") Long id, @Name("input") FulfillmentChunkRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateFulfillmentChunk for id: " + id);
            FulfillmentChunk chunk = FulfillmentChunk.findById(id);
            if (chunk == null) {
                throw new NoSuchElementException("FulfillmentChunk with id " + id + " not found");
            }
            // 1. Update Basic Fields
            if (input.type != null) chunk.type = input.type;
            if (input.label != null) chunk.label = input.label;
            if (input.deliveryDate != null) chunk.deliveryDate = input.deliveryDate;
            // 2. Update Building
            if (input.buildingId != null) {
                chunk.building = Building.findById(input.buildingId);
            } else {
                chunk.building = null;
            }
            // 3. Update Lines (Create, Update, Delete)
            if (input.lines != null) {
                ShelfLocation.LocationSpecification locSpec = FulfillmentChunk.findCommonSpecification(chunk);
                // IDs provided in the input are the lines we want to KEEP (update or create).
                Set<Long> inputLineIds = input.lines.stream()
                        .map(l -> l.id)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                // A. Remove lines that are missing from the input (Delete)
                // Uses the orphanRemoval=true defined in the entity
                chunk.lines.removeIf(line -> !inputLineIds.contains(line.id));
                // B. Process incoming lines (Create or Update)
                for (FulfillmentLineRecord lineInput : input.lines) {
                    FulfillmentLine line;
                    if (lineInput.id != null) {
                        // Update existing line
                        line = chunk.lines.stream()
                                .filter(l -> l.id.equals(lineInput.id))
                                .findFirst()
                                .orElse(null);
                        if (line == null) {
                            throw new NoSuchElementException("Line with id " + lineInput.id + " not found");
                        }
                    } else {
                        // Create new line
                        line = new FulfillmentLine();
                        chunk.lines.add(line); // Add to collection for cascading persist
                    }
                    // Update fields
                    line.lineNumber = lineInput.lineNumber;
                    line.requestedQuantity = lineInput.requestedQuantity;
                    if (lineInput.productId != null) {
                        line.product = Product.findById(lineInput.productId);
                    }
                    if (lineInput.locationId != null) {
                        line.location = BaseLocation.findById(lineInput.locationId);
                    } else {
                        line.location = ShelfLocation.findClosestForProduct(
                            locSpec.store, line.product, locSpec.building, locSpec.area, locSpec.aisle
                        ).getFirst();
                    }
                }
            }
            LOGGER.info("Exiting method updateFulfillmentChunk");
            return chunk;
        }, FulfillmentResource.class, "updateFulfillmentChunk");
    }

    /**
     * Deletes a fulfillment by its unique identifier.
     *
     * @param id The ID of the fulfillment to delete.
     * @return true if the fulfillment was deleted successfully, false if not found.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a fulfillment by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteFulfillment(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteFulfillment for id: " + id);
            boolean result = Fulfillment.deleteById(id);
            LOGGER.info("Exiting method deleteFulfillment. Result: " + result);
            return result;
        }, FulfillmentResource.class, "deleteFulfillment");
    }

    // --------------------------------------------------
    // Private Helpers
    // --------------------------------------------------

    /**
     * Helper method to dynamically build an optimized JPQL query.
     *
     * @param context        The GraphQL context to inspect.
     * @param whereClause    The WHERE clause (without the "where" keyword), or null.
     * @param forceOrderJoin If true, forces a JOIN on the Order table.
     * @return The constructed JPQL query string.
     */
    private String buildOptimizedQuery(Context context, String whereClause, boolean forceOrderJoin) {
        DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
        // 1. Analyze Selection Set
        boolean wantsOrder = env.getSelectionSet().contains("order");
        boolean wantsChunks = env.getSelectionSet().contains("chunks");
        boolean wantsLines = false;
        if (wantsChunks) {
            // Check if 'lines' is requested inside 'chunks'
            wantsLines = env.getSelectionSet().getFields().stream()
                    .anyMatch(f -> f.getName().equals("chunks") && f.getSelectionSet().contains("lines"));
        }
        // 2. Build Query
        StringBuilder query = new StringBuilder("select distinct f from Fulfillment f ");
        // Order Logic
        // If filtering by order, we must join. If fetching order data, we must FETCH.
        if (forceOrderJoin || wantsOrder) {
            if (wantsOrder) {
                query.append("join fetch f.order o ");
            } else {
                // Join only for filtering, not for fetching data
                query.append("join f.order o ");
            }
        }
        // Chunks & Lines Logic
        if (wantsChunks) {
            query.append("left join fetch f.chunks c ");
            if (wantsLines) {
                query.append("left join fetch c.lines l left join fetch l.product ");
            }
        }
        // Where Clause
        if (whereClause != null) {
            query.append("where ").append(whereClause);
        }
        return query.toString();
    }

    // --------------------------------------------------
    // Input Records
    // --------------------------------------------------

    /**
     * Input record type for GraphQL Fulfillment mutations.
     */
    @Input
    static public class FulfillmentRecord {
        /**
         * The business ID of the order.
         */
        public String orderId;

        /**
         * The reservation status and leasing info.
         */
        public ReservationRecord reservationRecord;

        /**
         * The list of chunks to create.
         */
        public List<FulfillmentChunkRecord> chunks;

        /**
         * Default constructor.
         */
        public FulfillmentRecord() {
        }
    }

    /**
     * Input record type for the embedded ReservationRecord.
     */
    @Input
    static public class ReservationRecord {
        /**
         * The status of the reservation.
         */
        public com.intermarche.backoffice.domain.ReservationRecord.ReservationStatus status;

        /**
         * The leasing timestamp.
         */
        public Instant leasing;

        /**
         * Default constructor.
         */
        public ReservationRecord() {
        }
    }

    // --------------------------------------------------
    // Input Records for Update Mutation
    // --------------------------------------------------

    /**
     * Input record type for updating a FulfillmentChunk.
     */
    @Input
    static public class FulfillmentChunkRecord {
        /**
         * The type of chunk (e.g., PENDING, READY).
         */
        public String type;

        /**
         * Label indicating the origin of the chunk.
         */
        public String label;

        /**
         * The delivery date.
         */
        public LocalDateTime deliveryDate;

        /**
         * ID of the building associated with this chunk.
         */
        public Long buildingId;

        /**
         * The list of lines to update or create.
         * Lines missing from this list will be deleted.
         */
        public List<FulfillmentLineRecord> lines;

        /**
         * Default constructor.
         */
        public FulfillmentChunkRecord() {
        }
    }

    /**
     * Input record type for updating a FulfillmentLine.
     */
    @Input
    static public class FulfillmentLineRecord {
        /**
         * The ID of the line.
         * If null, a new line is created.
         * If present, the existing line is updated.
         */
        public Long id;

        /**
         * The line number.
         */
        public int lineNumber;

        /**
         * The product ID.
         */
        public Long productId;

        /**
         * The requested quantity.
         */
        public BigDecimal requestedQuantity;

        /**
         * The location ID.
         */
        public Long locationId;

        /**
         * Default constructor.
         */
        public FulfillmentLineRecord() {
        }
    }
}