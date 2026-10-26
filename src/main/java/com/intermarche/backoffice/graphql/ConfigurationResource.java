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

    @Inject com.intermarche.backoffice.rules.RuleSchemaRegistry ruleSchemas;
    @Inject jakarta.enterprise.event.Event<com.intermarche.backoffice.engine.ConfigurationChanged> configurationChanged;
    /**
     * Notifies the engines that a configuration changed, when the event is available.
     */
    private void notifyConfigurationChanged(){if(configurationChanged!=null) configurationChanged.fire(new com.intermarche.backoffice.engine.ConfigurationChanged());}

    /**
     * Executes an action, turning validation and security errors into GraphQL errors carrying their message; other
     * errors follow the standard handling of {@link GraphQLTrait#execute}.
     *
     * @param <T> result type
     * @param action the action
     * @param context class used for logging
     * @param operation operation name used for logging
     * @return the result of the action
     * @throws GraphQLException if the action fails
     */
    @Override
    public <T> T execute(java.util.function.Supplier<T> action,Class<?> context,String operation) throws GraphQLException {
        try {return action.get();}
        catch(IllegalArgumentException | IllegalStateException | SecurityException e) {throw new GraphQLException(e.getMessage(),e);}
        catch(RuntimeException e) {return GraphQLTrait.super.execute(() -> {throw e;},context,operation);}
    }

    /**
     * Resolves a store the user may access: any store for an administrator, their own store for a manager.
     *
     * @param id store identifier
     * @return the store
     * @throws NoSuchElementException if the store does not exist
     * @throws SecurityException if a manager addresses another store
     */
    private Store scopedStore(Long id) {
        Store store=Store.findById(id);
        if(store==null) throw new NoSuchElementException("Store not found");
        if(!identity.hasRole("ADMIN")) {
            Store own=getStoreContext();
            if(own==null || !own.id.equals(id)) throw new SecurityException("Magasin hors de votre périmètre");
        }
        return store;
    }

    /**
     * Checks that a non-administrator only writes rule-based configurations of their own store.
     *
     * @param config configuration being modified, or null for a creation
     * @param input submitted configuration
     */
    private void checkWrite(Configuration config,ConfigurationRecord input) {
        if(identity.hasRole("ADMIN")) return;
        Store own=getStoreContext();
        if(own==null) throw new SecurityException("Magasin requis");
        if(config!=null && (config.stores.size()!=1 || config.stores.stream().noneMatch(s -> s.id.equals(own.id)) || !config.storeGroups.isEmpty())) throw new SecurityException("Configuration partagée ou hors magasin");
        if(config!=null && !com.intermarche.backoffice.rules.RuleSchemaRegistry.CATEGORIES.contains(config.type)) throw new SecurityException("Type de règle non autorisé");
        if(input!=null) {
            if(input.storeIds!=null && (input.storeIds.size()!=1 || !own.id.equals(input.storeIds.get(0)))) throw new SecurityException("Magasin hors de votre périmètre");
            if(input.storeGroupIds!=null && !input.storeGroupIds.isEmpty()) throw new SecurityException("Les groupes sont réservés à l’administration");
            String type=input.type!=null ? input.type : config==null?null:config.type;
            if(!com.intermarche.backoffice.rules.RuleSchemaRegistry.CATEGORIES.contains(type==null?"":type)) throw new SecurityException("Type de règle non autorisé");
            input.storeIds=java.util.List.of(own.id);input.storeGroupIds=java.util.List.of();
        }
    }

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
    @RolesAllowed({"ADMIN", "MANAGER"})
    public List<Configuration> configurationsByStore(@Name("storeId") Long storeId) {
        LOGGER.info("Entering method configurationsByStore for storeId: " + storeId);
        Store store = scopedStore(storeId);
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
    @RolesAllowed({"ADMIN", "MANAGER"})
    public List<Configuration> configurationsForStore(@Name("storeId") Long storeId, @Name("type") String type) {
        LOGGER.info("Entering method configurationsForStore for storeId: " + storeId + " and type: " + type);
        Store store = scopedStore(storeId);
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
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Configuration effectiveConfigurationForStore(@Name("storeId") Long storeId, @Name("type") String type) {
        LOGGER.info("Entering method effectiveConfigurationForStore for storeId: " + storeId + " and type: " + type);
        Store store = scopedStore(storeId);
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
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public Configuration createConfiguration(@Name("input") ConfigurationRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createConfiguration");

            checkWrite(null,input);
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

            ruleSchemas.validateSpecification(config.type,config.specification,config.stores);
            config.specification=ruleSchemas.normalize(config.type,config.specification);
            config.persist();
            notifyConfigurationChanged();

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

            checkWrite(config,input);
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

            ruleSchemas.validateSpecification(config.type,config.specification,config.stores);
            config.specification=ruleSchemas.normalize(config.type,config.specification);
            notifyConfigurationChanged();
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
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteConfiguration(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteConfiguration for id: " + id);
            Configuration config=Configuration.findById(id);
            if(config==null) return false;
            checkWrite(config,null);
            boolean result = Configuration.deleteById(id);
            notifyConfigurationChanged();
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

        /**
         * Describes the record for logging.
         *
         * @return the name, type, priority and specification of the record
         */
        @Override
        public String toString() {
            return "ConfigurationRecord [name=" + name + ", type=" + type +
                    ", priority=" + priority +
                    ", specification=" + specification + "]";
        }
    }
}