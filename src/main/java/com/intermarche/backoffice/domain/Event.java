package com.intermarche.backoffice.domain;

import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.graphql.Ignore;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Entity representing a Business Event linked to a Store and a target entity.
 * <p>
 * Instead of using a direct JPA relationship (@ManyToOne) for the target entity,
 * this class uses a polymorphic weak-link pattern: it stores the {@link #targetEntityType}
 * (canonical class name) and the {@link #targetEntityId}. This avoids schema coupling
 * and allows linking to various entities (e.g., OrderRequest, FulfillmentChunk, HandlingOrder)
 * without requiring foreign key constraints.
 * <p>
 * Extends {@link BaseEntity} for auditing, ID generation, and checksum capabilities.
 */
@Entity
@Table(name = "events",
        indexes = {
                @Index(name = "idx_event_store_id", columnList = "store_id"),
                // Composite index to efficiently find events for a specific target entity
                @Index(name = "idx_event_target", columnList = "target_entity_type, target_entity_id"),
                @Index(name = "idx_event_target_code", columnList = "target_entity_code"),
                @Index(name = "idx_event_urgency", columnList = "urgency_level")
        }
)
public class Event extends BaseEntity {

    private static final Logger log = LoggerFactory.getLogger(Event.class);

    /**
     * Enumeration representing the urgency level of the event.
     */
    public enum UrgencyLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    // --------------------------------------------------
    // Event Details
    // --------------------------------------------------

    /**
     * A descriptive label for the event (e.g., "Order Delay", "Picking Started").
     */
    @Column(name = "label", nullable = false, length = 255)
    @NotBlank(message = "Event label is mandatory")
    public String label;

    /**
     * A detailed description providing context about the event.
     * Optional field, used to store detailed messages, contextual data, or stack traces.
     */
    @Column(name = "description", columnDefinition = "text")
    public String description;

    /**
     * The urgency level of the event.
     * Used to prioritize event processing or user notifications.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "urgency_level", nullable = false, length = 20)
    @NotNull(message = "Urgency level is mandatory")
    public UrgencyLevel urgencyLevel;

    // --------------------------------------------------
    // Store Relationship (Direct Link)
    // --------------------------------------------------

    /**
     * The store to which this event is associated.
     * Direct relationship because the Store is a mandatory, stable aggregate root.
     */
    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false)
    public Store store;

    // --------------------------------------------------
    // Target Entity Relationship (Weak / Polymorphic Link)
    // --------------------------------------------------

    /**
     * The fully qualified class name or logical type name of the targeted entity.
     * <p>
     * Examples: "com.intermarche.backoffice.domain.order.OrderRequest",
     *           "com.intermarche.backoffice.domain.oms.FulfillmentChunk",
     *           "com.intermarche.backoffice.domain.wms.BaseHandlingOrder".
     */
    @Column(name = "target_entity_type", nullable = false, length = 255)
    @NotBlank(message = "Target entity type is mandatory")
    public String targetEntityType;

    /**
     * The technical ID (database primary key) of the targeted entity.
     * <p>
     * Combined with {@link #targetEntityType}, it uniquely identifies the source of the event
     * without requiring a foreign key constraint.
     */
    @Column(name = "target_entity_id", nullable = false)
    @NotNull(message = "Target entity ID is mandatory")
    public Long targetEntityId;

    /**
     * The functional business identifier of the targeted entity.
     * <p>
     * This field stores the human-readable code used by the business to identify the source
     * (e.g., "ORD-1050", "HO-102", "CHUNK-5002"). It is indexed to allow fast filtering
     * from the UI based on functional search.
     */
    @Column(name = "target_entity_code", nullable = false, length = 100)
    @NotBlank(message = "Target entity code is mandatory")
    public String targetEntityCode;

    // --------------------------------------------------
    // Constructors
    // --------------------------------------------------

    /**
     * Default constructor required by JPA.
     */
    public Event() {
    }

    /**
     * Convenience constructor to quickly create an event (does NOT persist).
     *
     * @param label            The event description.
     * @param description      The detailed description or context.
     * @param urgencyLevel     The priority of the event.
     * @param store            The associated Store.
     * @param targetEntityType The class of the entity triggering the event.
     * @param targetEntityId   The technical ID of the entity triggering the event.
     * @param targetEntityCode The functional business code of the entity triggering the event.
     */
    public Event(String label, String description, UrgencyLevel urgencyLevel, Store store, Class<?> targetEntityType, Long targetEntityId, String targetEntityCode) {
        this.label = label;
        this.description = description;
        this.urgencyLevel = urgencyLevel;
        this.store = store;
        this.targetEntityType = targetEntityType.getName();
        this.targetEntityId = targetEntityId;
        this.targetEntityCode = targetEntityCode;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds all events associated with a specific target entity using its type and ID.
     *
     * @param targetEntityType The class of the targeted entity.
     * @param targetEntityId   The ID of the targeted entity.
     * @return A list of matching Events, ordered by creation date descending.
     */
    public static List<Event> findByTarget(Class<?> targetEntityType, Long targetEntityId) {
        return find("targetEntityType = ?1 and targetEntityId = ?2 ORDER BY createdAt DESC",
                targetEntityType.getName(), targetEntityId).list();
    }

    /**
     * Finds all critical events for a specific store.
     *
     * @param store The store to filter by.
     * @return A list of critical Events.
     */
    public static List<Event> findCriticalByStore(Store store) {
        return list("store = ?1 and urgencyLevel = ?2", store, UrgencyLevel.CRITICAL);
    }

    // --------------------------------------------------
    // Dynamic Entity Resolution
    // --------------------------------------------------

    /**
     * Retrieves the actual target entity instance associated with this event.
     * <p>
     * Uses the stored {@link #targetEntityType} and {@link #targetEntityId} to dynamically
     * load the entity from the database via the JPA EntityManager.
     *
     * @return The loaded entity instance, or null if not found in the database.
     * @throws IllegalStateException if the targetEntityType string cannot be resolved to a valid Class.
     */
    @Ignore
    public Object getTargetEntity() {
        try {
            Class<?> clazz = Class.forName(targetEntityType);
            return getEntityManager().find(clazz, targetEntityId);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    String.format("Cannot resolve target entity type '%s' for Event ID %d. Class not found.",
                            targetEntityType, this.id), e);
        }
    }

    /**
     * Retrieves the target entity and safely casts it to the expected type.
     *
     * @param <T>  The expected type of the entity.
     * @param type The Class object of the expected type (e.g., {@code OrderRequest.class}).
     * @return The entity cast to the requested type, or null if not found.
     * @throws IllegalStateException if the type doesn't match or if the class resolution fails.
     */
    @Ignore
    @SuppressWarnings("unchecked")
    public <T> T getTargetEntityAs(Class<T> type) {
        Object entity = getTargetEntity();
        if (entity == null) {
            return null;
        }
        if (!type.isInstance(entity)) {
            throw new IllegalStateException(
                    String.format("Expected target entity to be of type %s, but was %s for Event ID %d",
                            type.getName(), entity.getClass().getName(), this.id));
        }
        return (T) entity;
    }

    // --------------------------------------------------
    // Event Creation & Persistence
    // --------------------------------------------------

    /**
     * Creates and persists an event within the CURRENT active transaction.
     * <p>
     * If the current transaction rolls back, this event will also roll back.
     *
     * @param label            The event description.
     * @param description      The detailed description or context.
     * @param urgencyLevel     The priority of the event.
     * @param store            The associated Store (must be managed/persisted).
     * @param targetEntity     The entity instance triggering the event (must extend BaseEntity).
     * @param targetEntityCode The functional business code of the entity triggering the event.
     * @return The persisted Event instance.
     */
    public static Event publishInCurrentTx(String label, String description, UrgencyLevel urgencyLevel, Store store, Object targetEntity, String targetEntityCode) {
        Event event = buildEvent(label, description, urgencyLevel, store, targetEntity, targetEntityCode);
        event.persist();
        return event;
    }

    /**
     * Creates and persists an event in a COMPLETELY INDEPENDENT transaction.
     * <p>
     * Use this method in catch blocks or failure scenarios to guarantee that the event
     * is saved to the database, even if the outer transaction is rolling back.
     * <p>
     * <b>Technical details:</b>
     * <ul>
     *   <li>Extracts primitive values (IDs, Class name, Code) BEFORE opening the new transaction
     *       to avoid LazyInitializationException from the dying persistence context.</li>
     *   <li>Refetches the Store inside the new transaction to ensure it is attached.</li>
     *   <li>Uses {@link QuarkusTransaction#requiringNew} to suspend the current tx and start a new one.</li>
     * </ul>
     *
     * @param label            The event description.
     * @param description      The detailed description or context.
     * @param urgencyLevel     The priority of the event.
     * @param store            The associated Store.
     * @param targetEntity     The entity instance triggering the event.
     * @param targetEntityCode The functional business code of the entity triggering the event.
     * @return The persisted Event instance, or null if the independent transaction failed.
     */
    public static Event publishInNewTx(String label, String description, UrgencyLevel urgencyLevel, Store store, Object targetEntity, String targetEntityCode) {
        // 1. Extract primitive data BEFORE leaving the current transaction context
        final String targetType = Hibernate.unproxy(targetEntity).getClass().getName();
        final Long targetId = extractId(targetEntity);
        final Long storeId = extractId(store);
        final String targetCode = targetEntityCode; // String is immutable, safe to pass

        if (storeId == null) {
            throw new IllegalArgumentException("Cannot publish event: Store must have a valid ID.");
        }
        if (targetId == null) {
            throw new IllegalArgumentException("Cannot publish event: Target entity must have a valid ID.");
        }

        // 2. Execute in a totally isolated transaction using QuarkusTransaction API
        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                // Refetch the store in this new context to ensure it is managed
                Store managedStore = Store.findById(storeId);
                if (managedStore == null) {
                    throw new IllegalStateException("Store not found with ID " + storeId + " during independent event creation.");
                }

                Event event = new Event();
                event.label = label;
                event.description = description;
                event.urgencyLevel = urgencyLevel;
                event.store = managedStore;
                event.targetEntityType = targetType;
                event.targetEntityId = targetId;
                event.targetEntityCode = targetCode;

                event.persistAndFlush();
                return event;
            });
        } catch (Exception e) {
            log.error("CRITICAL: Failed to save event in independent transaction. Label='{}', Code='{}', TargetType='{}', TargetId={}, StoreId={}",
                    label, targetCode, targetType, targetId, storeId, e);
            return null;
        }
    }


    // --------------------------------------------------
    // Internal Helpers
    // --------------------------------------------------

    /**
     * Builds the event object without persisting it.
     *
     * @param label            The event description.
     * @param description      The detailed description or context.
     * @param urgencyLevel     The priority of the event.
     * @param store            The associated Store.
     * @param targetEntity     The entity instance triggering the event.
     * @param targetEntityCode The functional business code of the entity triggering the event.
     * @return A fully initialized, transient Event instance.
     */
    private static Event buildEvent(String label, String description, UrgencyLevel urgencyLevel, Store store, Object targetEntity, String targetEntityCode) {
        Event event = new Event();
        event.label = label;
        event.description = description;
        event.urgencyLevel = urgencyLevel;
        event.store = store;
        event.targetEntityType = Hibernate.unproxy(targetEntity).getClass().getName();
        event.targetEntityId = extractId(targetEntity);
        event.targetEntityCode = targetEntityCode;
        return event;
    }

    /**
     * Safely extracts the ID from a BaseEntity.
     *
     * @param entity The entity to extract the ID from.
     * @return The technical ID.
     * @throws IllegalArgumentException if the object does not extend BaseEntity.
     */
    private static Long extractId(Object entity) {
        if (entity instanceof BaseEntity baseEntity) {
            return baseEntity.id;
        }
        throw new IllegalArgumentException("Target entity must extend BaseEntity to be linked to an Event.");
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the event's core attributes.
     *
     * @return An integer hash representing the event's state.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                label,
                description,
                urgencyLevel,
                store != null ? store.id : null,
                targetEntityType,
                targetEntityId,
                targetEntityCode
        );
    }
}