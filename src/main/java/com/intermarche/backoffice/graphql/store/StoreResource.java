package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.Adresse;
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
 * GraphQL API for managing Stores.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * <p>
 * <b>Performance Optimization Strategy:</b>
 * <ul>
 *   <li>For single entity queries ({@code store}, {@code storeByCode}): Uses {@code JOIN FETCH} via Named Queries
 *       to load the Store and its Buildings in a single SQL query, avoiding the N+1 problem.</li>
 *   <li>For list queries ({@code allStores}): Relies on the {@code @BatchSize} annotation defined on the
 *       Entity relationships. This allows fetching relationships in batches (e.g., 50 at a time)
 *       instead of one-by-one, preventing N+1 issues without the overhead of Cartesian products in memory.</li>
 * </ul>
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class StoreResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(StoreResource.class);

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
     * Retrieves the list of all stores in the database.
     * <p>
     * This method relies on the {@code @BatchSize} annotation on the {@link Store#buildings} and
     * {@link Building#locations} fields to optimize collection fetching.
     *
     * @return A list of all Store entities.
     */
    @Query
    @Description("Get the list of all stores")
    @RolesAllowed("MANAGER")
    public List<Store> allStores() {
        LOGGER.info("Entering method allStores");
        // No explicit JOIN FETCH here to avoid Cartesian product issues with large lists.
        // @BatchSize on entities handles the optimization.
        List<Store> result = Store.listAll();
        LOGGER.info("Exiting method allStores");
        return result;
    }

    /**
     * Retrieves a specific store by its unique identifier.
     * <p>
     * Uses an optimized query to fetch the Store and its Buildings simultaneously.
     *
     * @param id The unique ID of the store.
     * @return The Store entity.
     * @throws NoSuchElementException if the store with the given ID does not exist.
     */
    @Query
    @Description("Get a store by its ID")
    @RolesAllowed("MANAGER")
    public Store store(@Name("id") Long id) {
        LOGGER.info("Entering method store with id: " + id);
        Store store = findStoreByIdWithDetails(id);
        if (store == null) {
            LOGGER.error("Store with id " + id + " not found");
            throw new NoSuchElementException("Store with id " + id + " not found");
        }
        LOGGER.info("Exiting method store");
        return store;
    }

    /**
     * Retrieves a specific store by its unique code.
     * <p>
     * Uses an optimized query to fetch the Store and its Buildings simultaneously.
     *
     * @param code The unique code of the store.
     * @return The Store entity.
     * @throws NoSuchElementException if the store with the given code does not exist.
     */
    @Query
    @Description("Get a store by its code")
    @RolesAllowed("MANAGER")
    public Store storeByCode(@Name("code") String code) {
        LOGGER.info("Entering method storeByCode with code: " + code);
        Store store = findStoreByCodeWithDetails(code);
        if (store == null) {
            LOGGER.error("Store with code " + code + " not found");
            throw new NoSuchElementException("Store with code " + code + " not found");
        }
        LOGGER.info("Exiting method storeByCode");
        return store;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new store in the database.
     * <p>
     * Validates that the code and name are unique before creation.
     *
     * @param input The input record containing store details.
     * @return The newly created Store entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new store")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Store createStore(@Name("input") StoreRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createStore for code: " + input.code);
            // Check if Code is already used
            Store existingStoreByCode = Store.findByCode(input.code);
            if (existingStoreByCode != null) {
                LOGGER.warn("Attempt to create store with existing code: " + input.code);
                throw new AlreadyExistsException("Store with code '" + input.code + "' already exists.");
            }
            // Check if Name is already used (Assuming name should be unique)
            long nameCount = Store.count("name", input.name);
            if (nameCount > 0) {
                LOGGER.warn("Attempt to create store with existing name: " + input.name);
                throw new AlreadyExistsException("Store with name '" + input.name + "' already exists.");
            }
            // Creation Logic
            Store store = new Store();
            store.code = input.code;
            store.name = input.name;

            Adresse address = new Adresse();
            address.streetLine1 = input.streetLine1;
            address.streetLine2 = input.streetLine2;
            address.postalCode = input.postalCode;
            address.city = input.city;
            address.country = input.country;
            store.address = address;

            store.persist();
            LOGGER.info("Exiting method createStore. Created ID: " + store.id);
            return store;
        }, StoreResource.class, "createStore");
    }

    /**
     * Updates an existing store identified by its ID.
     * <p>
     * Validates new code/name uniqueness if they are changed.
     *
     * @param id    The ID of the store to update.
     * @param input The input record containing fields to update.
     * @return The updated Store entity.
     * @throws GraphQLException if validation fails or the store is not found.
     */
    @Mutation
    @Description("Update an existing store")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Store updateStore(@Name("id") Long id, @Name("input") StoreRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateStore for id: " + id);
            Store store = Store.findById(id);
            if (store == null) {
                LOGGER.error("Store with id " + id + " not found");
                throw new NoSuchElementException("Store with id " + id + " not found");
            }
            // Pre-validation Checks (Update Logic)
            if (input.code != null && !input.code.equals(store.code)) {
                Store otherStoreWithCode = Store.findByCode(input.code);
                if (otherStoreWithCode != null) {
                    LOGGER.warn("Attempt to update store to conflicting code: " + input.code);
                    throw new AlreadyExistsException("Another store with code '" + input.code + "' already exists.");
                }
            }
            if (input.name != null && !input.name.equals(store.name)) {
                long nameCount = Store.count("name = ?1 and id <> ?2", input.name, id);
                if (nameCount > 0) {
                    LOGGER.warn("Attempt to update store to conflicting name: " + input.name);
                    throw new AlreadyExistsException("Another store with name '" + input.name + "' already exists.");
                }
            }
            // Apply Updates
            if (input.code != null) store.code = input.code;
            if (input.name != null) store.name = input.name;

            if (store.address == null) store.address = new Adresse();

            if (input.streetLine1 != null) store.address.streetLine1 = input.streetLine1;
            if (input.streetLine2 != null) store.address.streetLine2 = input.streetLine2;
            if (input.postalCode != null) store.address.postalCode = input.postalCode;
            if (input.city != null) store.address.city = input.city;
            if (input.country != null) store.address.country = input.country;

            LOGGER.info("Exiting method updateStore");
            return store;
        }, StoreResource.class, "updateStore");
    }

    /**
     * Deletes a store by its unique identifier.
     *
     * @param id The ID of the store to delete.
     * @return true if the store was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a store by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteStore(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteStore for id: " + id);
            boolean result = Store.deleteById(id);
            LOGGER.info("Exiting method deleteStore. Result: " + result);
            return result;
        }, StoreResource.class, "deleteStore");
    }

    // --------------------------------------------------
    // Private Helpers
    // --------------------------------------------------

    /**
     * Fetches a single Store by ID using a Named Query with {@code JOIN FETCH}.
     * <p>
     * This ensures that the {@code buildings} relationship is loaded in the same SQL query,
     * preventing the N+1 query problem for single entity retrieval.
     *
     * @param id The ID of the store.
     * @return The Store entity with fetched buildings, or null if not found.
     */
    private Store findStoreByIdWithDetails(Long id) {
        return Store.find("#Store.findWithBuildingsById", id).firstResult();
    }

    /**
     * Fetches a single Store by code using a Named Query with {@code JOIN FETCH}.
     *
     * @param code The code of the store.
     * @return The Store entity with fetched buildings, or null if not found.
     */
    private Store findStoreByCodeWithDetails(String code) {
        return Store.find("#Store.findWithBuildingsByCode", code).firstResult();
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record type for GraphQL Store mutations.
     * <p>
     * Contains fields corresponding to the {@link Store} entity and its embedded {@link Adresse}.
     * All fields are mutable and nullable to support partial updates.
     */
    @Input
    static public class StoreRecord {

        /**
         * The unique code of the store (e.g., "STORE_01").
         */
        public String code;

        /**
         * The name of the store.
         */
        public String name;

        /**
         * Address line 1 (Street address).
         */
        public String streetLine1;

        /**
         * Address line 2 (Apartment, suite, etc.).
         */
        public String streetLine2;

        /**
         * Postal code / ZIP code.
         */
        public String postalCode;

        /**
         * City name.
         */
        public String city;

        /**
         * Country name.
         */
        public String country;

        /**
         * GPS Latitude coordinate.
         * <p>
         * Note: Currently ignored in Store creation as GPS data belongs to the physical Building entity,
         * not the Business/Store entity.
         */
        public Double latitude;

        /**
         * GPS Longitude coordinate.
         * <p>
         * Note: Currently ignored in Store creation as GPS data belongs to the physical Building entity,
         * not the Business/Store entity.
         */
        public Double longitude;

        /**
         * Default constructor.
         */
        public StoreRecord() {
        }

        /**
         * Returns a string representation of the record.
         *
         * @return A string representation.
         */
        @Override
        public String toString() {
            return "StoreRecord [code=" + code + ", name=" + name +
                    ", streetLine1=" + streetLine1 + ", streetLine2=" + streetLine2 +
                    ", postalCode=" + postalCode + ", city=" + city +
                    ", country=" + country + "]";
        }
    }
}

