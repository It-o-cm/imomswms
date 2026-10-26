package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.AlreadyExistsException;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Buffers.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * <p>
 * Manages creation, update, deletion, and retrieval of buffer configurations.
 * A Buffer represents the staging/waiting capacities of a Building.
 * <p>
 * Access is restricted to MANAGERs for reading and ADMINs for writing.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class BufferResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(BufferResource.class);

    @Inject com.intermarche.backoffice.engine.executing.BufferOccupancyService occupancyService;

    @Query @Transactional @RolesAllowed({"ADMIN","MANAGER","SERVICE_CALLER"})
    public List<com.intermarche.backoffice.engine.executing.BufferOccupancyService.BufferOccupancy> bufferOccupancy(@Name("storeCode") String storeCode) {
        var store=com.intermarche.backoffice.domain.store.Store.findByCode(storeCode);
        if(store==null)throw new NoSuchElementException("Magasin introuvable");
        if(!identity.hasRole("ADMIN") && !identity.hasRole("SERVICE_CALLER")){
            var context=getStoreContext();
            if(context==null || !context.id.equals(store.id))throw new SecurityException("Buffer hors du magasin autorisé");
        }
        return occupancyService.occupancy(store);
    }

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
     * Retrieves the list of all buffers the user may read: those of the buildings of their store for a manager.
     *
     * @return A list of the readable Buffer entities.
     */
    @Query
    @Description("Get the list of all buffers")
    @RolesAllowed("MANAGER")
    public List<Buffer> allBuffers() {
        LOGGER.info("Entering method allBuffers");
        Store scope = readScope();
        List<Buffer> result = scope == null ? Buffer.listAll()
                : Buffer.list("building.id in (select b.id from Store s join s.buildings b where s.id = ?1)", scope.id);
        LOGGER.info("Exiting method allBuffers");
        return result;
    }

    /**
     * Retrieves a specific buffer by its unique identifier.
     *
     * @param id The unique ID of the buffer.
     * @return The Buffer entity.
     * @throws NoSuchElementException if the buffer with the given ID does not exist in the scope of the user.
     */
    @Query
    @Description("Get a buffer by its ID")
    @RolesAllowed("MANAGER")
    public Buffer buffer(@Name("id") Long id) {
        LOGGER.info("Entering method buffer with id: " + id);
        Buffer buffer = readable(Buffer.findById(id));
        if (buffer == null) {
            LOGGER.error("Buffer with id " + id + " not found");
            throw new NoSuchElementException("Buffer with id " + id + " not found");
        }
        LOGGER.info("Exiting method buffer");
        return buffer;
    }

    /**
     * Retrieves the buffer associated with a specific Building ID.
     * <p>
     * Since the relationship is 1-to-1, this is the most common way to find a buffer.
     *
     * @param buildingId The ID of the Building.
     * @return The Buffer entity.
     * @throws NoSuchElementException if no buffer is found for the building in the scope of the user.
     */
    @Query
    @Description("Get the buffer associated with a specific Building ID")
    @RolesAllowed("MANAGER")
    public Buffer bufferByBuildingId(@Name("buildingId") Long buildingId) {
        LOGGER.info("Entering method bufferByBuildingId with buildingId: " + buildingId);
        // Query using the relation
        Buffer buffer = readable(Buffer.find("building.id", buildingId).firstResult());
        if (buffer == null) {
            LOGGER.error("Buffer for building id " + buildingId + " not found");
            throw new NoSuchElementException("Buffer for building id '" + buildingId + "' not found");
        }
        LOGGER.info("Exiting method bufferByBuildingId");
        return buffer;
    }

    /**
     * Hides a buffer of another store from a manager.
     *
     * @param buffer the buffer found, or null
     * @return the buffer if the user may read it, otherwise null
     */
    private Buffer readable(Buffer buffer) {
        Store scope = readScope();
        if (buffer == null || scope == null) return buffer;
        Store owner = Building.findStoreByBuildingId(buffer.building.id);
        return owner != null && scope.id.equals(owner.id) ? buffer : null;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new buffer in the database.
     * <p>
     * Validates that the linked Building does not already have a Buffer.
     *
     * @param input The input record containing buffer details.
     * @return The newly created Buffer entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new buffer")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Buffer createBuffer(@Name("input") BufferRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createBuffer for buildingId: " + input.buildingId);

            // --- Validation: Check Building existence ---
            Building building = Building.findById(input.buildingId);
            if (building == null) {
                throw new NoSuchElementException("Building with id " + input.buildingId + " not found");
            }

            // --- Validation: Check Uniqueness (1-to-1 constraint) ---
            Buffer existingBuffer = Buffer.findByBuilding(building);
            if (existingBuffer != null) {
                LOGGER.warn("Attempt to create buffer for building that already has one: " + input.buildingId);
                throw new AlreadyExistsException("A buffer already exists for Building id '" + input.buildingId + "'.");
            }

            // --- Creation Logic ---
            Buffer buffer = new Buffer();
            buffer.building = building;

            // Populate Fields
            populateBuffer(input, buffer);

            buffer.persist();

            LOGGER.info("Exiting method createBuffer. Created ID: " + buffer.id);
            return buffer;
        }, BufferResource.class, "createBuffer");
    }

    /**
     * Updates an existing buffer identified by its ID.
     * <p>
     * Validates new Building assignment if it is changed (ensures 1-to-1 integrity).
     * Only non-null fields in the input are updated (partial update support).
     *
     * @param id    The ID of the buffer to update.
     * @param input The input record containing fields to update.
     * @return The updated Buffer entity.
     * @throws GraphQLException if validation fails or the buffer is not found.
     */
    @Mutation
    @Description("Update an existing buffer")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Buffer updateBuffer(@Name("id") Long id, @Name("input") BufferRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateBuffer for id: " + id);

            Buffer buffer = Buffer.findById(id);
            if (buffer == null) {
                LOGGER.error("Buffer with id " + id + " not found");
                throw new NoSuchElementException("Buffer with id " + id + " not found");
            }

            // --- Pre-validation Checks (Conditional) ---

            // 1. Building Uniqueness:
            // Only check if the input Building ID is provided AND different from the current Building.
            if (input.buildingId != null && !input.buildingId.equals(buffer.building.id)) {
                Building newBuilding = Building.findById(input.buildingId);
                if (newBuilding == null) {
                    throw new NoSuchElementException("Target Building with id " + input.buildingId + " not found");
                }

                Buffer bufferAtNewBuilding = Buffer.findByBuilding(newBuilding);
                if (bufferAtNewBuilding != null) {
                    LOGGER.warn("Attempt to update buffer to conflicting building: " + input.buildingId);
                    throw new AlreadyExistsException("A buffer already exists for Building id '" + input.buildingId + "'.");
                }
                buffer.building = newBuilding;
            }

            // --- Apply Updates (Partial Update Logic) ---
            populateBuffer(input, buffer);

            LOGGER.info("Exiting method updateBuffer");
            return buffer;
        }, BufferResource.class, "updateBuffer");
    }

    /**
     * Deletes a buffer by its unique identifier.
     *
     * @param id The ID of the buffer to delete.
     * @return true if the buffer was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a buffer by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteBuffer(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteBuffer for id: " + id);
            boolean result = Buffer.deleteById(id);
            LOGGER.info("Exiting method deleteBuffer. Result: " + result);
            return result;
        }, BufferResource.class, "deleteBuffer");
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record type for GraphQL Buffer mutations.
     * <p>
     * Contains fields corresponding to the {@link Buffer} entity.
     * The relationship to {@link Building} is represented by the buildingId.
     * All fields are mutable and nullable to support partial updates.
     */
    @Input
    static public class BufferRecord {

        /**
         * The ID of the Building associated with this buffer.
         */
        public Long buildingId;

        /**
         * The operational purpose (STAGING, PROCESSING, RECEIPT).
         */
        public String purpose;

        /**
         * Physical location description.
         */
        public String locationDescription;

        /**
         * Flag indicating if the buffer is active.
         */
        public Boolean active;

        /**
         * Maximum capacity for Ambient totes.
         */
        public Integer ambientCapacity;

        /**
         * Maximum capacity for Chilled totes.
         */
        public Integer chilledCapacity;

        /**
         * Maximum capacity for Frozen totes.
         */
        public Integer frozenCapacity;

        /**
         * Maximum weight capacity in Kg.
         */
        public Double maxWeightKg;

        /**
         * Default constructor.
         */
        public BufferRecord() {
        }

        /**
         * Returns a string representation of the record.
         *
         * @return A string representation.
         */
        @Override
        public String toString() {
            return "BufferRecord [buildingId=" + buildingId + ", purpose=" + purpose +
                    ", ambientCapacity=" + ambientCapacity + ", chilledCapacity=" + chilledCapacity +
                    ", frozenCapacity=" + frozenCapacity + "]";
        }
    }

    // --------------------------------------------------
    // Private Helpers
    // --------------------------------------------------

    /**
     * Populates the Buffer entity with data from the Input Record.
     *
     * @param input  The input record.
     * @param buffer The entity to populate.
     */
    private void populateBuffer(BufferRecord input, Buffer buffer) {
        if (input.purpose != null) {
            try {
                buffer.purpose = Buffer.BufferPurpose.valueOf(input.purpose.toUpperCase());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Unknown BufferPurpose: " + input.purpose);
            }
        }

        if (input.locationDescription != null) buffer.locationDescription = input.locationDescription;
        if (input.active != null) buffer.active = input.active;
        if (input.ambientCapacity != null) buffer.ambientCapacity = input.ambientCapacity;
        if (input.chilledCapacity != null) buffer.chilledCapacity = input.chilledCapacity;
        if (input.frozenCapacity != null) buffer.frozenCapacity = input.frozenCapacity;
        if (input.maxWeightKg != null) buffer.maxWeightKg = input.maxWeightKg;
    }
}