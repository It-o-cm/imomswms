package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.Building;
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
 * GraphQL API for managing Aisles.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * <p>
 * Manages creation, update, deletion, and retrieval of aisles (topology elements).
 * Access is restricted to MANAGERs for reading and ADMINs for writing.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class AisleResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(AisleResource.class);

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
     * Retrieves the list of all aisles in the database.
     *
     * @return A list of all Aisle entities.
     */
    @Query
    @Description("Get the list of all aisles")
    @RolesAllowed("MANAGER")
    public List<Aisle> allAisles() {
        LOGGER.info("Entering method allAisles");
        List<Aisle> result = Aisle.listAll();
        LOGGER.info("Exiting method allAisles");
        return result;
    }

    /**
     * Retrieves a specific aisle by its unique identifier.
     *
     * @param id The unique ID of the aisle.
     * @return The Aisle entity.
     * @throws NoSuchElementException if the aisle with the given ID does not exist.
     */
    @Query
    @Description("Get an aisle by its ID")
    @RolesAllowed("MANAGER")
    public Aisle aisle(@Name("id") Long id) {
        LOGGER.info("Entering method aisle with id: " + id);
        Aisle aisle = Aisle.findById(id);
        if (aisle == null) {
            LOGGER.error("Aisle with id " + id + " not found");
            throw new NoSuchElementException("Aisle with id " + id + " not found");
        }
        LOGGER.info("Exiting method aisle");
        return aisle;
    }

    /**
     * Retrieves a specific aisle by its unique Code.
     * <p>
     * This is the primary lookup method used for topology definition.
     *
     * @param code The unique Code of the aisle.
     * @return The Aisle entity.
     * @throws NoSuchElementException if the aisle with the given Code does not exist.
     */
    @Query
    @Description("Get an aisle by its Code")
    @RolesAllowed("MANAGER")
    public Aisle aisleByCode(@Name("code") String code) {
        LOGGER.info("Entering method aisleByCode with code: " + code);
        // Using Panache query directly as the code is globally unique in the schema
        Aisle aisle = Aisle.find("code", code).firstResult();
        if (aisle == null) {
            LOGGER.error("Aisle with code " + code + " not found");
            throw new NoSuchElementException("Aisle with code '" + code + "' not found");
        }
        LOGGER.info("Exiting method aisleByCode");
        return aisle;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new aisle in the database.
     * <p>
     * Validates that the Code is unique before creation.
     * The Building is mandatory and must exist.
     *
     * @param input The input record containing aisle details.
     * @return The newly created Aisle entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new aisle")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Aisle createAisle(@Name("input") AisleRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createAisle for code: " + input.code);

            // --- Validation: Check Code uniqueness ---
            Aisle existingAisleByCode = Aisle.find("code", input.code).firstResult();
            if (existingAisleByCode != null) {
                LOGGER.warn("Attempt to create aisle with existing code: " + input.code);
                throw new AlreadyExistsException("Aisle with code '" + input.code + "' already exists.");
            }

            // --- Relationship Mapping: Building ---
            if (input.buildingId == null) {
                throw new IllegalArgumentException("Building ID is mandatory to create an aisle.");
            }
            Building building = Building.findById(input.buildingId);
            if (building == null) {
                throw new NoSuchElementException("Building with id " + input.buildingId + " not found");
            }

            // --- Creation Logic ---
            Aisle aisle = new Aisle();

            // Basic fields
            aisle.code = input.code;
            aisle.lengthMeters = input.lengthMeters;
            aisle.aisleType = input.aisleType;
            aisle.connectedToHead = input.connectedToHead;
            aisle.connectedToTail = input.connectedToTail;

            // Link Building
            aisle.building = building;

            aisle.persist();

            LOGGER.info("Exiting method createAisle. Created ID: " + aisle.id);
            return aisle;
        }, AisleResource.class, "createAisle");
    }

    /**
     * Updates an existing aisle identified by its ID.
     * <p>
     * Validates new Code uniqueness if it is changed.
     * Only non-null fields in the input are updated (partial update support).
     *
     * @param id    The ID of the aisle to update.
     * @param input The input record containing fields to update.
     * @return The updated Aisle entity.
     * @throws GraphQLException if validation fails or the aisle is not found.
     */
    @Mutation
    @Description("Update an existing aisle")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Aisle updateAisle(@Name("id") Long id, @Name("input") AisleRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateAisle for id: " + id);

            Aisle aisle = Aisle.findById(id);
            if (aisle == null) {
                LOGGER.error("Aisle with id " + id + " not found");
                throw new NoSuchElementException("Aisle with id " + id + " not found");
            }

            // --- Pre-validation Checks (Conditional) ---

            // 1. Code Uniqueness:
            // Only check if the input Code is provided AND different from the current Code.
            if (input.code != null && !input.code.equals(aisle.code)) {
                Aisle otherAisleWithCode = Aisle.find("code", input.code).firstResult();
                if (otherAisleWithCode != null) {
                    LOGGER.warn("Attempt to update aisle to conflicting code: " + input.code);
                    throw new AlreadyExistsException("Another aisle with code '" + input.code + "' already exists.");
                }
            }

            // --- Apply Updates (Partial Update Logic) ---

            if (input.code != null) aisle.code = input.code;
            if (input.lengthMeters != null) aisle.lengthMeters = input.lengthMeters;
            if (input.aisleType != null) aisle.aisleType = input.aisleType;
            if (input.connectedToHead != null) aisle.connectedToHead = input.connectedToHead;
            if (input.connectedToTail != null) aisle.connectedToTail = input.connectedToTail;

            // Relationship mapping
            if (input.buildingId != null) {
                Building building = Building.findById(input.buildingId);
                if (building == null) {
                    throw new NoSuchElementException("Building with id " + input.buildingId + " not found");
                }
                aisle.building = building;
            }

            LOGGER.info("Exiting method updateAisle");
            return aisle;
        }, AisleResource.class, "updateAisle");
    }

    /**
     * Deletes an aisle by its unique identifier.
     *
     * @param id The ID of the aisle to delete.
     * @return true if the aisle was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete an aisle by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteAisle(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteAisle for id: " + id);
            boolean result = Aisle.deleteById(id);
            LOGGER.info("Exiting method deleteAisle. Result: " + result);
            return result;
        }, AisleResource.class, "deleteAisle");
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record type for GraphQL Aisle mutations.
     * <p>
     * Contains fields corresponding to the {@link Aisle} entity.
     * The relationship to {@link Building} is represented by the buildingId.
     * All fields are mutable and nullable to support partial updates.
     */
    @Input
    static public class AisleRecord {

        /**
         * The unique code of the aisle.
         */
        public String code;

        /**
         * The physical length of the aisle in meters.
         */
        public Double lengthMeters;

        /**
         * The type of Aisle (e.g., "LINEAR", "CIRCULAR").
         */
        public String aisleType;

        /**
         * The code of the Aisle connected to the Start (Head).
         */
        public String connectedToHead;

        /**
         * The code of the Aisle connected to the End (Tail).
         */
        public String connectedToTail;

        /**
         * The ID of the Building containing this aisle.
         */
        public Long buildingId;

        /**
         * Default constructor.
         */
        public AisleRecord() {
        }

        /**
         * Returns a string representation of the record.
         *
         * @return A string representation.
         */
        @Override
        public String toString() {
            return "AisleRecord [code=" + code + ", lengthMeters=" + lengthMeters +
                    ", aisleType=" + aisleType +
                    ", connectedToHead=" + connectedToHead +
                    ", connectedToTail=" + connectedToTail +
                    ", buildingId=" + buildingId + "]";
        }
    }
}