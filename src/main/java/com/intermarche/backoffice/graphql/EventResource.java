package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.store.Store;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Events.
 * <p>
 * This resource provides operations for the {@link Event} entity.
 * All queries are strictly scoped to a Store context to enforce data isolation.
 * It enforces role-based access control: MANAGERs can read, ADMINs can write.
 * <p>
 * Implements {@link GraphQLTrait} to centralize exception handling and transaction wrapping.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class EventResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(EventResource.class);

    // --------------------------------------------------
    // Dependencies
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

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Retrieves Events that were created within a specific date range.
     * <p>
     * Filters by the user's store context if the user is not an ADMIN.
     *
     * @param start   The start of the date range.
     * @param end     The end of the date range.
     * @param context The GraphQL context.
     * @return A list of matching Events.
     * @throws GraphQLException if execution fails.
     */
    @Query
    @Description("Get Events by creation date range")
    @RolesAllowed({"MANAGER", "ADMIN", "SERVICE_CALLER"})
    public List<Event> eventsByDateRange(
            @Name("start") LocalDateTime start,
            @Name("end") LocalDateTime end,
            Context context) throws GraphQLException {

        return this.execute(() -> {
            Store storeContext = getStoreContext();

            DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
            boolean wantsStore = env.getSelectionSet().contains("store");

            StringBuilder jpql = new StringBuilder("SELECT DISTINCT e FROM Event e ");

            // Store Logic
            if (storeContext != null || wantsStore) {
                if (wantsStore) {
                    jpql.append("JOIN FETCH e.store s ");
                } else {
                    jpql.append("JOIN e.store s ");
                }
            }

            jpql.append("WHERE e.createdAt >= ?1 AND e.createdAt <= ?2 ");

            if (storeContext != null) {
                jpql.append("AND s.id = ?3");
                return Event.find(jpql.toString(), start, end, storeContext.id).list();
            } else {
                return Event.find(jpql.toString(), start, end).list();
            }

        }, EventResource.class, "eventsByDateRange");
    }

    /**
     * Retrieves a list of events filtered by the business code of their linked Store.
     *
     * @param storeCode The business identifier of the linked Store.
     * @param context   The SmallRye GraphQL context.
     * @return The found {@link List} of {@link Event} entities.
     * @throws GraphQLException if execution fails.
     */
    @Query
    @Description("Get events by the linked Store code")
    @RolesAllowed("MANAGER")
    public List<Event> eventsByStoreCode(@Name("storeCode") String storeCode, Context context) throws GraphQLException {
        return this.execute(() -> {
            String jpql = buildOptimizedQuery(context, "s.code = ?1", true);
            return Event.list(jpql, storeCode);
        }, EventResource.class, "eventsByStoreCode");
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new event in the database.
     *
     * @param input The input record containing event details.
     * @return The newly created Event entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new event")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Event createEvent(@Name("input") EventRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createEvent for label: " + input.label);

            // --- Validation: Check Store existence ---
            Store store = Store.findByCode(input.storeCode);
            if (store == null) {
                throw new NoSuchElementException("Store with code '" + input.storeCode + "' not found");
            }

            // --- Creation Logic ---
            Event event = new Event();
            event.label = input.label;
            event.description = input.description;

            // Map String from GraphQL to Enum for the Database
            if (input.urgencyLevel != null && !input.urgencyLevel.isBlank()) {
                try {
                    // TODO event.urgencyLevel = Event.UrgencyLevel.valueOf(input.urgencyLevel);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid urgency level provided: " + input.urgencyLevel);
                }
            }

            event.store = store;
            event.targetEntityType = input.targetEntityType;
            event.targetEntityId = input.targetEntityId;
            event.targetEntityCode = input.targetEntityCode;

            event.persist();
            LOGGER.info("Exiting method createEvent. Created ID: " + event.id);
            return event;
        }, EventResource.class, "createEvent");
    }

    /**
     * Updates an existing event identified by its ID.
     *
     * @param id    The ID of the event to update.
     * @param input The input record containing fields to update.
     * @return The updated Event entity.
     * @throws GraphQLException if validation fails or the event is not found.
     */
    @Mutation
    @Description("Update an existing event")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Event updateEvent(@Name("id") Long id, @Name("input") EventRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateEvent for id: " + id);
            Event event = Event.findById(id);
            if (event == null) {
                LOGGER.error("Event with id " + id + " not found");
                throw new NoSuchElementException("Event with id " + id + " not found");
            }

            // Apply updates from the input (only if provided)
            if (input.label != null) {
                event.label = input.label;
            }
            if (input.description != null) {
                event.description = input.description;
            }
            if (input.urgencyLevel != null && !input.urgencyLevel.isBlank()) {
                try {
                    // TODO event.urgencyLevel = Event.UrgencyLevel.valueOf(input.urgencyLevel);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid urgency level provided: " + input.urgencyLevel);
                }
            }
            if (input.targetEntityType != null) {
                event.targetEntityType = input.targetEntityType;
            }
            if (input.targetEntityId != null) {
                event.targetEntityId = input.targetEntityId;
            }
            if (input.targetEntityCode != null) {
                event.targetEntityCode = input.targetEntityCode;
            }

            // If storeCode is changed, re-assign store
            if (input.storeCode != null && !input.storeCode.equals(event.store.code)) {
                Store newStore = Store.findByCode(input.storeCode);
                if (newStore == null) {
                    throw new NoSuchElementException("Store with code '" + input.storeCode + "' not found");
                }
                event.store = newStore;
            }

            LOGGER.info("Exiting method updateEvent");
            return event;
        }, EventResource.class, "updateEvent");
    }

    /**
     * Deletes an event by its unique identifier.
     *
     * @param id The ID of the event to delete.
     * @return true if the event was deleted successfully, false if not found.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete an event by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteEvent(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteEvent for id: " + id);
            boolean result = Event.deleteById(id);
            LOGGER.info("Exiting method deleteEvent. Result: " + result);
            return result;
        }, EventResource.class, "deleteEvent");
    }

    // --------------------------------------------------
    // Private Helpers
    // --------------------------------------------------

    /**
     * Helper method to dynamically build an optimized JPQL query.
     *
     * @param context        The GraphQL context to inspect.
     * @param whereClause    The WHERE clause (without the "where" keyword), or null.
     * @param forceStoreJoin If true, forces a JOIN on the Store table.
     * @return The constructed JPQL query string.
     */
    private String buildOptimizedQuery(Context context, String whereClause, boolean forceStoreJoin) {
        DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
        boolean wantsStore = env.getSelectionSet().contains("store");

        StringBuilder query = new StringBuilder("select distinct e from Event e ");

        // Store Logic
        if (forceStoreJoin || wantsStore) {
            if (wantsStore) {
                query.append("join fetch e.store s ");
            } else {
                // Join only for filtering, not for fetching data
                query.append("join e.store s ");
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
     * Input record type for GraphQL Event mutations.
     */
    @Input
    static public class EventRecord {
        /**
         * The descriptive label of the event.
         */
        public String label;

        /**
         * The detailed description of the event.
         */
        public String description;

        /**
         * The urgency level of the event (e.g., "LOW", "HIGH", "CRITICAL").
         * <p>
         * We use String here instead of the Enum directly to bypass a known SmallRye GraphQL startup
         * schema validation bug when using inner classes with enums.
         */
        public String urgencyLevel;

        /**
         * The business code of the associated store.
         */
        public String storeCode;

        /**
         * The fully qualified class name of the targeted entity.
         */
        public String targetEntityType;

        /**
         * The technical ID of the targeted entity.
         */
        public Long targetEntityId;

        /**
         * The functional business identifier of the targeted entity.
         */
        public String targetEntityCode;

        /**
         * Default constructor.
         */
        public EventRecord() {
        }
    }
}