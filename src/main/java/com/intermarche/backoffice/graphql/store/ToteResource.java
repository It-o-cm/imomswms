package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.Tote;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Totes.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * <p>
 * Manages creation, update, deletion, and retrieval of totes (logistics bins).
 * Access is restricted to MANAGERs for reading and ADMINs for writing.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class ToteResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(ToteResource.class);

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
     * Retrieves the list of all totes in the database.
     *
     * @return A list of all Tote entities.
     */
    @Query
    @Description("Get the list of all totes")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public List<Tote> allTotes() {
        LOGGER.info("Entering method allTotes");
        List<Tote> result = Tote.listAll();
        LOGGER.info("Exiting method allTotes");
        return result;
    }

    /**
     * Retrieves a specific tote by its unique identifier.
     *
     * @param id The unique ID of the tote.
     * @return The Tote entity.
     * @throws NoSuchElementException if the tote with the given ID does not exist.
     */
    @Query
    @Description("Get a tote by its ID")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Tote tote(@Name("id") Long id) {
        LOGGER.info("Entering method tote with id: " + id);
        Tote tote = Tote.findById(id);
        if (tote == null) {
            LOGGER.error("Tote with id " + id + " not found");
            throw new NoSuchElementException("Tote with id " + id + " not found");
        }
        LOGGER.info("Exiting method tote");
        return tote;
    }

    /**
     * Retrieves a specific tote by its unique Code.
     * <p>
     * This is the primary lookup method used for inventory tracking.
     *
     * @param code The unique Code of the tote.
     * @return The Tote entity.
     * @throws NoSuchElementException if the tote with the given Code does not exist.
     */
    @Query
    @Description("Get a tote by its Code")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Tote toteByCode(@Name("code") String code) {
        LOGGER.info("Entering method toteByCode with code: " + code);
        Tote tote = Tote.findByCode(code);
        if (tote == null) {
            LOGGER.error("Tote with code " + code + " not found");
            throw new NoSuchElementException("Tote with code '" + code + "' not found");
        }
        LOGGER.info("Exiting method toteByCode");
        return tote;
    }

    /**
     * Retrieves all totes belonging to a specific store, identified by its unique code.
     *
     * @param storeCode The unique business code of the Store.
     * @return A list of Tote entities associated with the store.
     */
    @Query
    @Description("Get all totes for a specific store code")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public List<Tote> totesByStoreCode(@Name("storeCode") String storeCode) {
        LOGGER.info("Entering method totesByStoreCode for store code: " + storeCode);
        List<Tote> result = Tote.findByStoreCode(storeCode);
        LOGGER.info("Exiting method totesByStoreCode");
        return result;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new tote in the database.
     * <p>
     * Validates that the Code is unique before creation.
     *
     * @param input The input record containing tote details.
     * @return The newly created Tote entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new tote")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public Tote createTote(@Name("input") ToteRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createTote for code: " + input.code);

            // --- Validation: Check Code uniqueness ---
            Tote existingToteByCode = Tote.findByCode(input.code);
            if (existingToteByCode != null) {
                LOGGER.warn("Attempt to create tote with existing code: " + input.code);
                throw new AlreadyExistsException("Tote with code '" + input.code + "' already exists.");
            }

            // --- Creation Logic ---
            Tote tote = new Tote();

            // Basic fields
            tote.code = input.code;
            // Volume is passed as String in Input Record, convert to BigDecimal
            if (input.volume != null && !input.volume.isBlank()) {
                try {
                    tote.volume = new BigDecimal(input.volume);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid volume format: " + input.volume);
                }
            }

            // Enum conversion
            if (input.toteType != null) {
                tote.toteType = Tote.ToteType.valueOf(input.toteType.toUpperCase());
            }

            // Relationship mapping
            if (input.storeId != null) {
                Store store = Store.findById(input.storeId);
                if (store == null) {
                    throw new NoSuchElementException("Store with id " + input.storeId + " not found");
                }
                tote.store = store;
            }

            tote.persist();

            LOGGER.info("Exiting method createTote. Created ID: " + tote.id);
            return tote;
        }, ToteResource.class, "createTote");
    }

    /**
     * Updates an existing tote identified by its ID.
     * <p>
     * Validates new Code uniqueness if it is changed.
     * Only non-null fields in the input are updated (partial update support).
     *
     * @param id    The ID of the tote to update.
     * @param input The input record containing fields to update.
     * @return The updated Tote entity.
     * @throws GraphQLException if validation fails or the tote is not found.
     */
    @Mutation
    @Description("Update an existing tote")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public Tote updateTote(@Name("id") Long id, @Name("input") ToteRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateTote for id: " + id);

            Tote tote = Tote.findById(id);
            if (tote == null) {
                LOGGER.error("Tote with id " + id + " not found");
                throw new NoSuchElementException("Tote with id " + id + " not found");
            }

            // --- Pre-validation Checks (Conditional) ---

            // 1. Code Uniqueness:
            // Only check if the input Code is provided AND different from the current Code.
            if (input.code != null && !input.code.equals(tote.code)) {
                Tote otherToteWithCode = Tote.findByCode(input.code);
                if (otherToteWithCode != null) {
                    LOGGER.warn("Attempt to update tote to conflicting code: " + input.code);
                    throw new AlreadyExistsException("Another tote with code '" + input.code + "' already exists.");
                }
            }

            // --- Apply Updates (Partial Update Logic) ---

            if (input.code != null) tote.code = input.code;

            if (input.volume != null && !input.volume.isBlank()) {
                try {
                    tote.volume = new BigDecimal(input.volume);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid volume format: " + input.volume);
                }
            }

            // Enum conversion
            if (input.toteType != null) {
                tote.toteType = Tote.ToteType.valueOf(input.toteType.toUpperCase());
            }

            // Relationship mapping
            if (input.storeId != null) {
                Store store = Store.findById(input.storeId);
                if (store == null) {
                    throw new NoSuchElementException("Store with id " + input.storeId + " not found");
                }
                tote.store = store;
            }

            LOGGER.info("Exiting method updateTote");
            return tote;
        }, ToteResource.class, "updateTote");
    }

    /**
     * Deletes a tote by its unique identifier.
     *
     * @param id The ID of the tote to delete.
     * @return true if the tote was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a tote by ID")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteTote(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteTote for id: " + id);
            boolean result = Tote.deleteById(id);
            LOGGER.info("Exiting method deleteTote. Result: " + result);
            return result;
        }, ToteResource.class, "deleteTote");
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record type for GraphQL Tote mutations.
     * <p>
     * Contains fields corresponding to the {@link Tote} entity.
     * The relationship to {@link Store} is represented by the storeId.
     * All fields are mutable and nullable to support partial updates.
     * Volume is passed as String for easier JSON handling from clients.
     */
    @Input
    static public class ToteRecord {

        /**
         * The business code of the tote.
         */
        public String code;

        /**
         * The volume capacity (String representation, e.g., "45.5").
         */
        public String volume;

        /**
         * The type of the tote (STANDARD, INSULATED).
         */
        public String toteType;

        /**
         * The ID of the Store to which the tote is assigned.
         */
        public Long storeId;

        /**
         * Default constructor.
         */
        public ToteRecord() {
        }

        /**
         * Returns a string representation of the record.
         *
         * @return A string representation.
         */
        @Override
        public String toString() {
            return "ToteRecord [code=" + code + ", volume=" + volume +
                    ", toteType=" + toteType +
                    ", storeId=" + storeId + "]";
        }
    }
}
