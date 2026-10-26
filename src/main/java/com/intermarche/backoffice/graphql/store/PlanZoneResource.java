package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.PlanZoneVertex;
import com.intermarche.backoffice.domain.store.TemperatureZone;
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

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Plan Zones (geometric areas of the store plan).
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * <p>
 * Manages creation, update, deletion, and retrieval of plan zones.
 * Access is restricted to MANAGERs for reading and ADMINs for writing.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class PlanZoneResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(PlanZoneResource.class);

    /**
     * Minimum number of vertices required to form a valid polygon.
     */
    private static final int MIN_VERTICES = 3;

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
     * Retrieves the list of all plan zones in the database.
     *
     * @return A list of all PlanZone entities.
     */
    @Query
    @Description("Get the list of all plan zones")
    @RolesAllowed("MANAGER")
    public List<PlanZone> allPlanZones() {
        LOGGER.info("Entering method allPlanZones");
        List<PlanZone> result = PlanZone.listAll();
        LOGGER.info("Exiting method allPlanZones");
        return result;
    }

    /**
     * Retrieves a specific plan zone by its unique identifier.
     *
     * @param id The unique ID of the zone.
     * @return The PlanZone entity.
     * @throws NoSuchElementException if the zone with the given ID does not exist.
     */
    @Query
    @Description("Get a plan zone by its ID")
    @RolesAllowed("MANAGER")
    public PlanZone planZone(@Name("id") Long id) {
        LOGGER.info("Entering method planZone with id: " + id);
        PlanZone zone = PlanZone.findById(id);
        if (zone == null) {
            LOGGER.error("PlanZone with id " + id + " not found");
            throw new NoSuchElementException("PlanZone with id " + id + " not found");
        }
        LOGGER.info("Exiting method planZone");
        return zone;
    }

    /**
     * Retrieves a specific plan zone by its unique code.
     *
     * @param code The unique code of the zone.
     * @return The PlanZone entity.
     * @throws NoSuchElementException if the zone with the given code does not exist.
     */
    @Query
    @Description("Get a plan zone by its code")
    @RolesAllowed("MANAGER")
    public PlanZone planZoneByCode(@Name("code") String code) {
        LOGGER.info("Entering method planZoneByCode with code: " + code);
        PlanZone zone = PlanZone.find("code", code).firstResult();
        if (zone == null) {
            LOGGER.error("PlanZone with code " + code + " not found");
            throw new NoSuchElementException("PlanZone with code '" + code + "' not found");
        }
        LOGGER.info("Exiting method planZoneByCode");
        return zone;
    }

    /**
     * Retrieves the list of plan zones of a building.
     *
     * @param buildingCode The unique code of the building.
     * @return The list of PlanZone entities of the building, ordered by code.
     * @throws NoSuchElementException if the building does not exist.
     */
    @Query
    @Description("Get the plan zones of a building")
    @RolesAllowed("MANAGER")
    public List<PlanZone> planZonesByBuilding(@Name("buildingCode") String buildingCode) {
        LOGGER.info("Entering method planZonesByBuilding with buildingCode: " + buildingCode);
        Building building = Building.findByCode(buildingCode);
        if (building == null) {
            LOGGER.error("Building with code " + buildingCode + " not found");
            throw new NoSuchElementException("Building with code '" + buildingCode + "' not found");
        }
        List<PlanZone> result = PlanZone.listByBuilding(building);
        LOGGER.info("Exiting method planZonesByBuilding");
        return result;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new plan zone in the database.
     * <p>
     * Validates that the code is unique before creation.
     * The Building is mandatory and must exist. The polygon must have at least
     * {@value #MIN_VERTICES} vertices.
     *
     * @param input The input record containing zone details.
     * @return The newly created PlanZone entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new plan zone")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public PlanZone createPlanZone(@Name("input") PlanZoneRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createPlanZone for code: " + input.code);

            // --- Validation: Check Code uniqueness ---
            PlanZone existing = PlanZone.find("code", input.code).firstResult();
            if (existing != null) {
                LOGGER.warn("Attempt to create plan zone with existing code: " + input.code);
                throw new AlreadyExistsException("PlanZone with code '" + input.code + "' already exists.");
            }

            // --- Relationship Mapping: Building ---
            if (input.buildingId == null) {
                throw new IllegalArgumentException("Building ID is mandatory to create a plan zone.");
            }
            Building building = Building.findById(input.buildingId);
            if (building == null) {
                throw new NoSuchElementException("Building with id " + input.buildingId + " not found");
            }

            // --- Creation Logic ---
            PlanZone zone = new PlanZone();
            zone.code = input.code;
            zone.label = input.label;
            zone.color = input.color;
            zone.temperatureZone = input.temperatureZone;
            zone.labelOffsetX = input.labelOffsetX;
            zone.labelOffsetY = input.labelOffsetY;
            zone.building = building;
            applyVertices(zone, input.vertices);

            zone.persist();

            LOGGER.info("Exiting method createPlanZone. Created ID: " + zone.id);
            return zone;
        }, PlanZoneResource.class, "createPlanZone");
    }

    /**
     * Updates an existing plan zone identified by its ID.
     * <p>
     * Validates new code uniqueness if it is changed.
     * Only non-null fields in the input are updated (partial update support).
     * If vertices are provided, they replace the existing polygon.
     *
     * @param id    The ID of the zone to update.
     * @param input The input record containing fields to update.
     * @return The updated PlanZone entity.
     * @throws GraphQLException if validation fails or the zone is not found.
     */
    @Mutation
    @Description("Update an existing plan zone")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public PlanZone updatePlanZone(@Name("id") Long id, @Name("input") PlanZoneRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updatePlanZone for id: " + id);

            PlanZone zone = PlanZone.findById(id);
            if (zone == null) {
                LOGGER.error("PlanZone with id " + id + " not found");
                throw new NoSuchElementException("PlanZone with id " + id + " not found");
            }

            // --- Pre-validation Checks (Conditional) ---
            if (input.code != null && !input.code.equals(zone.code)) {
                PlanZone other = PlanZone.find("code", input.code).firstResult();
                if (other != null) {
                    LOGGER.warn("Attempt to update plan zone to conflicting code: " + input.code);
                    throw new AlreadyExistsException("Another plan zone with code '" + input.code + "' already exists.");
                }
            }

            // --- Apply Updates (Partial Update Logic) ---
            if (input.code != null) zone.code = input.code;
            if (input.label != null) zone.label = input.label;
            if (input.color != null) zone.color = input.color;
            if (input.temperatureZone != null) zone.temperatureZone = input.temperatureZone;
            if (input.labelOffsetX != null) zone.labelOffsetX = input.labelOffsetX;
            if (input.labelOffsetY != null) zone.labelOffsetY = input.labelOffsetY;

            // Relationship mapping
            if (input.buildingId != null) {
                Building building = Building.findById(input.buildingId);
                if (building == null) {
                    throw new NoSuchElementException("Building with id " + input.buildingId + " not found");
                }
                zone.building = building;
            }

            // Polygon replacement (only when vertices are provided)
            if (input.vertices != null) {
                applyVertices(zone, input.vertices);
            }

            LOGGER.info("Exiting method updatePlanZone");
            return zone;
        }, PlanZoneResource.class, "updatePlanZone");
    }

    /**
     * Deletes a plan zone by its unique identifier.
     *
     * @param id The ID of the zone to delete.
     * @return true if the zone was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a plan zone by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deletePlanZone(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deletePlanZone for id: " + id);
            boolean result = PlanZone.deleteById(id);
            LOGGER.info("Exiting method deletePlanZone. Result: " + result);
            return result;
        }, PlanZoneResource.class, "deletePlanZone");
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Replaces the polygon of a zone with the given vertices.
     * <p>
     * The existing vertices are removed (orphan removal) and new ones are created
     * from the input. The position index is taken from the input when provided,
     * otherwise it defaults to the list index.
     *
     * @param zone     The managed zone.
     * @param vertices The input vertices (must contain at least {@value #MIN_VERTICES} elements).
     */
    private void applyVertices(PlanZone zone, List<VertexInput> vertices) {
        if (vertices == null || vertices.size() < MIN_VERTICES) {
            throw new IllegalArgumentException("A zone polygon requires at least " + MIN_VERTICES + " vertices.");
        }
        zone.vertices.clear();
        for (int i = 0; i < vertices.size(); i++) {
            VertexInput v = vertices.get(i);
            PlanZoneVertex vertex = new PlanZoneVertex();
            vertex.zone = zone;
            vertex.position = v.position != null ? v.position : i;
            vertex.x = v.x;
            vertex.y = v.y;
            zone.vertices.add(vertex);
        }
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record type for GraphQL PlanZone mutations.
     * <p>
     * Contains fields corresponding to the {@link PlanZone} entity.
     * The relationship to {@link Building} is represented by the buildingId.
     * All fields are mutable and nullable to support partial updates.
     */
    @Input
    static public class PlanZoneRecord {

        /**
         * The unique code of the zone.
         */
        public String code;

        /**
         * The display label of the zone.
         */
        public String label;

        /**
         * The display color of the zone (hex format).
         */
        public String color;

        /**
         * The temperature zone associated with this area (nullable).
         */
        public TemperatureZone temperatureZone;

        /**
         * The horizontal offset of the zone label from the polygon centroid, in meters.
         */
        public Double labelOffsetX;

        /**
         * The vertical offset of the zone label from the polygon centroid, in meters.
         */
        public Double labelOffsetY;

        /**
         * The ordered vertices of the zone polygon (minimum 3).
         * When provided on update, replaces the existing polygon.
         */
        public List<VertexInput> vertices;

        /**
         * The ID of the Building containing this zone.
         */
        public Long buildingId;

        /**
         * Default constructor.
         */
        public PlanZoneRecord() {
        }

        /**
         * Returns a string representation of the record.
         *
         * @return A string representation.
         */
        @Override
        public String toString() {
            return "PlanZoneRecord [code=" + code + ", label=" + label +
                    ", color=" + color + ", temperatureZone=" + temperatureZone +
                    ", vertices=" + (vertices != null ? vertices.size() : null) +
                    ", buildingId=" + buildingId + "]";
        }
    }

    /**
     * Input type for a single polygon vertex.
     */
    @Input
    static public class VertexInput {

        /**
         * The ordinal position of the vertex in the polygon (0-based).
         * Defaults to the list index when null.
         */
        public Integer position;

        /**
         * The X coordinate of the vertex, in meters.
         */
        public Double x;

        /**
         * The Y coordinate of the vertex, in meters.
         */
        public Double y;

        /**
         * Default constructor.
         */
        public VertexInput() {
        }
    }
}
