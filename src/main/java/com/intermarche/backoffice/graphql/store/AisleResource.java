package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.AisleWaypoint;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNode;
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

    /**
     * Retrieves the list of aisles of a building, including their geometric
     * layer (flow type, shape type, head/tail nodes and waypoints).
     *
     * @param buildingCode The unique code of the building.
     * @return The list of Aisle entities of the building, ordered by code.
     * @throws NoSuchElementException if the building does not exist.
     */
    @Query
    @Description("Get the aisles of a building")
    @RolesAllowed("MANAGER")
    public List<Aisle> aislesByBuilding(@Name("buildingCode") String buildingCode) {
        LOGGER.info("Entering method aislesByBuilding with buildingCode: " + buildingCode);
        Building building = Building.findByCode(buildingCode);
        if (building == null) {
            LOGGER.error("Building with code " + buildingCode + " not found");
            throw new NoSuchElementException("Building with code '" + buildingCode + "' not found");
        }
        List<Aisle> result = Aisle.listByBuilding(building);
        LOGGER.info("Exiting method aislesByBuilding");
        return result;
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

            // Geometric layer
            aisle.flowType = input.flowType;
            aisle.shapeType = input.shapeType;
            aisle.headNode = resolveNode(building, input.headNodeCode);
            aisle.tailNode = resolveNode(building, input.tailNodeCode);

            // Link Building
            aisle.building = building;

            aisle.persist();
            applyWaypoints(aisle, input.waypoints);

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

            // Geometric layer
            if (input.flowType != null) aisle.flowType = input.flowType;
            if (input.shapeType != null) aisle.shapeType = input.shapeType;
            if (input.headNodeCode != null) aisle.headNode = resolveNode(aisle.building, input.headNodeCode);
            if (input.tailNodeCode != null) aisle.tailNode = resolveNode(aisle.building, input.tailNodeCode);

            // Relationship mapping
            if (input.buildingId != null) {
                Building building = Building.findById(input.buildingId);
                if (building == null) {
                    throw new NoSuchElementException("Building with id " + input.buildingId + " not found");
                }
                aisle.building = building;
            }

            // Waypoints replacement (only when provided)
            if (input.waypoints != null) {
                applyWaypoints(aisle, input.waypoints);
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
    // Helpers
    // --------------------------------------------------

    /**
     * Resolves a plan node by its code within a building.
     *
     * @param building The building context.
     * @param code     The node code (may be null).
     * @return The PlanNode entity, or null if the code is null.
     * @throws NoSuchElementException if the code is provided but no node matches.
     */
    private PlanNode resolveNode(Building building, String code) {
        if (code == null) return null;
        PlanNode node = PlanNode.findByCode(building, code);
        if (node == null) {
            throw new NoSuchElementException("PlanNode with code '" + code + "' not found in building '" + building.buildingCode + "'");
        }
        return node;
    }

    /**
     * Replaces the waypoints of an aisle with the given input points.
     * <p>
     * The existing waypoints are removed (orphan removal) and new ones are created
     * from the input. The position index is taken from the input when provided,
     * otherwise it defaults to the list index.
     *
     * @param aisle     The managed aisle.
     * @param waypoints The input waypoints (may be null or empty).
     */
    private void applyWaypoints(Aisle aisle, List<WaypointInput> waypoints) {
        aisle.waypoints.clear();
        if (waypoints == null) return;
        for (int i = 0; i < waypoints.size(); i++) {
            WaypointInput w = waypoints.get(i);
            AisleWaypoint waypoint = new AisleWaypoint();
            waypoint.aisle = aisle;
            waypoint.position = w.position != null ? w.position : i;
            waypoint.x = w.x;
            waypoint.y = w.y;
            aisle.waypoints.add(waypoint);
        }
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
         * The flow type of the aisle on the store plan (LINEAR, DIAGONAL, CIRCULAR).
         */
        public String flowType;

        /**
         * The shape type of the aisle on the store plan (STRAIGHT, CURVED).
         */
        public String shapeType;

        /**
         * The code of the plan node at the START of this aisle (Head), if any.
         */
        public String headNodeCode;

        /**
         * The code of the plan node at the END of this aisle (Tail), if any.
         */
        public String tailNodeCode;

        /**
         * The ordered waypoints of the aisle on the store plan.
         * When provided on update, replaces the existing waypoints.
         */
        public List<WaypointInput> waypoints;

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
                    ", flowType=" + flowType + ", shapeType=" + shapeType +
                    ", headNodeCode=" + headNodeCode + ", tailNodeCode=" + tailNodeCode +
                    ", waypoints=" + (waypoints != null ? waypoints.size() : null) +
                    ", buildingId=" + buildingId + "]";
        }
    }

    /**
     * Input type for a single aisle waypoint.
     */
    @Input
    static public class WaypointInput {

        /**
         * The ordinal position of the waypoint along the aisle path (0-based).
         * Defaults to the list index when null.
         */
        public Integer position;

        /**
         * The X coordinate of the waypoint, in meters.
         */
        public Double x;

        /**
         * The Y coordinate of the waypoint, in meters.
         */
        public Double y;

        /**
         * Default constructor.
         */
        public WaypointInput() {
        }
    }
}