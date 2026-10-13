package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
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
 * GraphQL API for managing Configurations.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * Handles logic for linking configurations to multiple Stores or StoreGroups.
 * Configurations are selected based on {@code priority} instead of a simple active flag.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class ConfigurationResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(ConfigurationResource.class);

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
     * Retrieves all configurations for a specific store.
     * <p>
     * Note: This returns configurations directly linked to the store.
     * For configurations inherited via groups, use {@link #configurationsForStore(Long, String)}.
     *
     * @param storeId The ID of the store.
     * @return A list of Configuration entities.
     * @throws NoSuchElementException if the store does not exist.
     */
    @Query
    @Description("Get all configurations directly linked to a specific store")
    @RolesAllowed("MANAGER")
    public List<Configuration> configurationsByStore(@Name("storeId") Long storeId) {
        LOGGER.info("Entering method configurationsByStore for storeId: " + storeId);
        Store store = Store.findById(storeId);
        if (store == null) {
            throw new NoSuchElementException("Store with id " + storeId + " not found");
        }
        // This relies on the inverse side of the relationship if mapped,
        // otherwise we query the Configuration entity
        return Configuration.list("select c from Configuration c join c.stores s where s = ?1", store);
    }

    /**
     * Retrieves configurations applicable to a store, considering direct links and group inheritance.
     *
     * @param storeId The ID of the store.
     * @param type    The type of configuration to filter by.
     * @return A list of applicable Configuration entities.
     */
    @Query
    @Description("Get all configurations applicable to a store (direct or via groups) by type")
    @RolesAllowed("MANAGER")
    public List<Configuration> configurationsForStore(@Name("storeId") Long storeId, @Name("type") String type) {
        LOGGER.info("Entering method configurationsForStore for storeId: " + storeId + " and type: " + type);
        Store store = Store.findById(storeId);
        if (store == null) {
            throw new NoSuchElementException("Store with id " + storeId + " not found");
        }
        return Configuration.findConfigurationsForStore(store, type);
    }

    /**
     * Retrieves the single effective configuration for a store and type.
     * <p>
     * This method evaluates all applicable configurations (direct and via groups) and returns
     * the one with the highest priority.
     *
     * @param storeId The ID of the store.
     * @param type    The type of configuration.
     * @return The Configuration with the highest priority.
     * @throws NoSuchElementException if the store is not found.
     * @throws IllegalStateException   if multiple configurations share the highest priority.
     */
    @Query
    @Description("Get the effective configuration (highest priority) for a store and type")
    @RolesAllowed("MANAGER")
    public Configuration effectiveConfigurationForStore(@Name("storeId") Long storeId, @Name("type") String type) {
        LOGGER.info("Entering method effectiveConfigurationForStore for storeId: " + storeId + " and type: " + type);
        Store store = Store.findById(storeId);
        if (store == null) {
            throw new NoSuchElementException("Store with id " + storeId + " not found");
        }
        return Configuration.findHighestPriorityForStore(store, type);
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new configuration.
     * <p>
     * Links are created based on provided lists of Store IDs and StoreGroup IDs.
     * The priority defaults to 0 if not provided.
     *
     * @param input The input record containing configuration details.
     * @return The newly created Configuration entity.
     * @throws GraphQLException if validation fails.
     */
    @Mutation
    @Description("Create a new configuration linked to stores and groups")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Configuration createConfiguration(@Name("input") ConfigurationRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createConfiguration");

            Configuration config = new Configuration();
            config.name = input.name;
            config.type = input.type;
            config.specification = input.specification;

            // Set priority, defaulting to 0 if not provided
            if (input.priority != null) {
                config.priority = input.priority;
            }

            // Link Stores
            if (input.storeIds != null) {
                for (Long id : input.storeIds) {
                    Store store = Store.findById(id);
                    if (store != null) {
                        config.stores.add(store);
                    } else {
                        LOGGER.warn("Store ID not found during creation: " + id);
                    }
                }
            }

            // Link Store Groups
            if (input.storeGroupIds != null) {
                for (Long id : input.storeGroupIds) {
                    StoreGroup group = StoreGroup.findById(id);
                    if (group != null) {
                        config.storeGroups.add(group);
                    } else {
                        LOGGER.warn("StoreGroup ID not found during creation: " + id);
                    }
                }
            }

            config.persist();

            LOGGER.info("Exiting method createConfiguration. Created ID: " + config.id);
            return config;
        }, ConfigurationResource.class, "createConfiguration");
    }

    /**
     * Updates an existing configuration's details (Name, Spec, Priority) and its links.
     *
     * @param id    The ID of the configuration to update.
     * @param input The input record containing fields to update.
     * @return The updated Configuration entity.
     * @throws GraphQLException if the configuration is not found.
     */
    @Mutation
    @Description("Update configuration details, priority, and links")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public Configuration updateConfiguration(@Name("id") Long id, @Name("input") ConfigurationRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateConfiguration for id: " + id);
            Configuration config = Configuration.findById(id);
            if (config == null) {
                throw new NoSuchElementException("Configuration with id " + id + " not found");
            }

            // Update basic fields
            if (input.name != null) config.name = input.name;
            if (input.type != null) config.type = input.type;
            if (input.specification != null) config.specification = input.specification;
            if (input.priority != null) config.priority = input.priority;

            // Update Store Links (Replace strategy)
            if (input.storeIds != null) {
                config.stores.clear();
                for (Long storeId : input.storeIds) {
                    Store store = Store.findById(storeId);
                    if (store != null) config.stores.add(store);
                }
            }

            // Update Group Links (Replace strategy)
            if (input.storeGroupIds != null) {
                config.storeGroups.clear();
                for (Long groupId : input.storeGroupIds) {
                    StoreGroup group = StoreGroup.findById(groupId);
                    if (group != null) config.storeGroups.add(group);
                }
            }

            LOGGER.info("Exiting method updateConfiguration");
            return config;
        }, ConfigurationResource.class, "updateConfiguration");
    }

    /**
     * Deletes a configuration by its ID.
     *
     * @param id The ID of the configuration to delete.
     * @return true if deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a configuration by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteConfiguration(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteConfiguration for id: " + id);
            boolean result = Configuration.deleteById(id);
            LOGGER.info("Exiting method deleteConfiguration. Result: " + result);
            return result;
        }, ConfigurationResource.class, "deleteConfiguration");
    }

    // --------------------------------------------------
    // Input Record
    // --------------------------------------------------

    /**
     * Input record for Configuration mutations.
     */
    @Input
    static public class ConfigurationRecord {

        /**
         * The name of the configuration.
         */
        public String name;

        /**
         * The type of the configuration (e.g., "OPENING_HOURS").
         */
        public String type;

        /**
         * The priority of the configuration.
         * Higher values indicate higher priority.
         */
        public Integer priority;

        /**
         * The JSON specification string.
         */
        public String specification;

        /**
         * List of Store IDs to associate with this configuration.
         */
        public List<Long> storeIds;

        /**
         * List of StoreGroup IDs to associate with this configuration.
         */
        public List<Long> storeGroupIds;

        /**
         * Default constructor.
         */
        public ConfigurationRecord() {
        }

        @Override
        public String toString() {
            return "ConfigurationRecord [name=" + name + ", type=" + type +
                    ", priority=" + priority +
                    ", specification=" + specification + "]";
        }
    }
}