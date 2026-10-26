package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanNodeType;
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
 * GraphQL API for managing Plan Nodes (topological nodes of the store plan).
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * <p>
 * Manages creation, update, deletion, and retrieval of plan nodes.
 * Access is restricted to MANAGERs for reading and ADMINs for writing.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class PlanNodeResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(PlanNodeResource.class);

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
     * Retrieves the list of all plan nodes in the database.
     *
     * @return A list of all PlanNode entities.
     */
    @Query
    @Description("Get the list of all plan nodes")
    @RolesAllowed("MANAGER")
    public List<PlanNode> allPlanNodes() {
        LOGGER.info("Entering method allPlanNodes");
        List<PlanNode> result = PlanNode.listAll();
        LOGGER.info("Exiting method allPlanNodes");
        return result;
    }

    /**
     * Retrieves a specific plan node by its unique identifier.
     *
     * @param id The unique ID of the node.
     * @return The PlanNode entity.
     * @throws NoSuchElementException if the node with the given ID does not exist.
     */
    @Query
    @Description("Get a plan node by its ID")
    @RolesAllowed("MANAGER")
    public PlanNode planNode(@Name("id") Long id) {
        LOGGER.info("Entering method planNode with id: " + id);
        PlanNode node = PlanNode.findById(id);
        if (node == null) {
            LOGGER.error("PlanNode with id " + id + " not found");
            throw new NoSuchElementException("PlanNode with id " + id + " not found");
        }
        LOGGER.info("Exiting method planNode");
        return node;
    }

    /**
     * Retrieves a specific plan node by its unique code.
     *
     * @param code The unique code of the node.
     * @return The PlanNode entity.
     * @throws NoSuchElementException if the node with the given code does not exist.
     */
    @Query
    @Description("Get a plan node by its code")
    @RolesAllowed("MANAGER")
    public PlanNode planNodeByCode(@Name("code") String code) {
        LOGGER.info("Entering method planNodeByCode with code: " + code);
        PlanNode node = PlanNode.find("code", code).firstResult();
        if (node == null) {
            LOGGER.error("PlanNode with code " + code + " not found");
            throw new NoSuchElementException("PlanNode with code '" + code + "' not found");
        }
        LOGGER.info("Exiting method planNodeByCode");
        return node;
    }

    /**
     * Retrieves the list of plan nodes of a building.
     *
     * @param buildingCode The unique code of the building.
     * @return The list of PlanNode entities of the building, ordered by code.
     * @throws NoSuchElementException if the building does not exist.
     */
    @Query
    @Description("Get the plan nodes of a building")
    @RolesAllowed("MANAGER")
    public List<PlanNode> planNodesByBuilding(@Name("buildingCode") String buildingCode) {
        LOGGER.info("Entering method planNodesByBuilding with buildingCode: " + buildingCode);
        Building building = Building.findByCode(buildingCode);
        if (building == null) {
            LOGGER.error("Building with code " + buildingCode + " not found");
            throw new NoSuchElementException("Building with code '" + buildingCode + "' not found");
        }
        List<PlanNode> result = PlanNode.listByBuilding(building);
        LOGGER.info("Exiting method planNodesByBuilding");
        return result;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new plan node in the database.
     * <p>
     * Validates that the code is unique before creation.
     * The Building is mandatory and must exist.
     *
     * @param input The input record containing node details.
     * @return The newly created PlanNode entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new plan node")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public PlanNode createPlanNode(@Name("input") PlanNodeRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createPlanNode for code: " + input.code);

            // --- Validation: Check Code uniqueness ---
            PlanNode existing = PlanNode.find("code", input.code).firstResult();
            if (existing != null) {
                LOGGER.warn("Attempt to create plan node with existing code: " + input.code);
                throw new AlreadyExistsException("PlanNode with code '" + input.code + "' already exists.");
            }

            // --- Relationship Mapping: Building ---
            if (input.buildingId == null) {
                throw new IllegalArgumentException("Building ID is mandatory to create a plan node.");
            }
            Building building = Building.findById(input.buildingId);
            if (building == null) {
                throw new NoSuchElementException("Building with id " + input.buildingId + " not found");
            }

            // --- Creation Logic ---
            PlanNode node = new PlanNode();
            node.code = input.code;
            node.label = input.label;
            node.nodeType = input.nodeType;
            node.x = input.x;
            node.y = input.y;
            node.building = building;

            node.persist();

            LOGGER.info("Exiting method createPlanNode. Created ID: " + node.id);
            return node;
        }, PlanNodeResource.class, "createPlanNode");
    }

    /**
     * Updates an existing plan node identified by its ID.
     * <p>
     * Validates new code uniqueness if it is changed.
     * Only non-null fields in the input are updated (partial update support).
     *
     * @param id    The ID of the node to update.
     * @param input The input record containing fields to update.
     * @return The updated PlanNode entity.
     * @throws GraphQLException if validation fails or the node is not found.
     */
    @Mutation
    @Description("Update an existing plan node")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public PlanNode updatePlanNode(@Name("id") Long id, @Name("input") PlanNodeRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updatePlanNode for id: " + id);

            PlanNode node = PlanNode.findById(id);
            if (node == null) {
                LOGGER.error("PlanNode with id " + id + " not found");
                throw new NoSuchElementException("PlanNode with id " + id + " not found");
            }

            // --- Pre-validation Checks (Conditional) ---
            if (input.code != null && !input.code.equals(node.code)) {
                PlanNode other = PlanNode.find("code", input.code).firstResult();
                if (other != null) {
                    LOGGER.warn("Attempt to update plan node to conflicting code: " + input.code);
                    throw new AlreadyExistsException("Another plan node with code '" + input.code + "' already exists.");
                }
            }

            // --- Apply Updates (Partial Update Logic) ---
            if (input.code != null) node.code = input.code;
            if (input.label != null) node.label = input.label;
            if (input.nodeType != null) node.nodeType = input.nodeType;
            if (input.x != null) node.x = input.x;
            if (input.y != null) node.y = input.y;

            // Relationship mapping
            if (input.buildingId != null) {
                Building building = Building.findById(input.buildingId);
                if (building == null) {
                    throw new NoSuchElementException("Building with id " + input.buildingId + " not found");
                }
                node.building = building;
            }

            LOGGER.info("Exiting method updatePlanNode");
            return node;
        }, PlanNodeResource.class, "updatePlanNode");
    }

    /**
     * Deletes a plan node by its unique identifier.
     *
     * @param id The ID of the node to delete.
     * @return true if the node was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a plan node by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deletePlanNode(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deletePlanNode for id: " + id);
            boolean result = PlanNode.deleteById(id);
            LOGGER.info("Exiting method deletePlanNode. Result: " + result);
            return result;
        }, PlanNodeResource.class, "deletePlanNode");
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record type for GraphQL PlanNode mutations.
     * <p>
     * Contains fields corresponding to the {@link PlanNode} entity.
     * The relationship to {@link Building} is represented by the buildingId.
     * All fields are mutable and nullable to support partial updates.
     */
    @Input
    static public class PlanNodeRecord {

        /**
         * The unique code of the node.
         */
        public String code;

        /**
         * The display label of the node.
         */
        public String label;

        /**
         * The type of the node (ENTRANCE, INTERSECTION, DEADEND).
         */
        public PlanNodeType nodeType;

        /**
         * The X coordinate of the node on the store plan, in meters.
         */
        public Double x;

        /**
         * The Y coordinate of the node on the store plan, in meters.
         */
        public Double y;

        /**
         * The ID of the Building containing this node.
         */
        public Long buildingId;

        /**
         * Default constructor.
         */
        public PlanNodeRecord() {
        }

        /**
         * Returns a string representation of the record.
         *
         * @return A string representation.
         */
        @Override
        public String toString() {
            return "PlanNodeRecord [code=" + code + ", label=" + label +
                    ", nodeType=" + nodeType + ", x=" + x + ", y=" + y +
                    ", buildingId=" + buildingId + "]";
        }
    }
}
