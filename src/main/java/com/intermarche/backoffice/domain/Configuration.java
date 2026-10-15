package com.intermarche.backoffice.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.graphql.Ignore;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Entity representing a specific configuration.
 * <p>
 * Configurations define rules or settings that can be applied to {@link Store}s.
 * A configuration can be linked directly to Stores or indirectly via {@link StoreGroup}s.
 * The applicable configuration for a context is determined by its {@link #priority}.
 */
@Entity
@Table(name = "configurations",
        indexes = {
                @Index(name = "idx_config_name", columnList = "name"),
                @Index(name = "idx_config_type", columnList = "type"),
                @Index(name = "idx_config_priority", columnList = "priority")
        }
)
@Cacheable
public class Configuration extends BaseEntity {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * The unique name of the configuration.
     */
    @Column(nullable = false)
    @NotBlank(message = "Configuration name is mandatory")
    public String name;

    /**
     * The type or category of the configuration (e.g., "PRICING", "FULFILLMENT").
     */
    @Column(nullable = false)
    @NotBlank(message = "Configuration type is mandatory")
    public String type;

    /**
     * The priority level of this configuration.
     * <p>
     * A higher integer value indicates a higher priority.
     * When multiple configurations apply to a store, the one with the highest priority is selected.
     */
    @Column(name = "priority")
    public int priority = 0;

    /**
     * The configuration details stored as a JSON string.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specification", nullable = false)
    @NotBlank(message = "Specification is mandatory")
    public String specification;

    /**
     * Stores directly linked to this configuration.
     */
    @ManyToMany
    @JoinTable(
            name = "configuration_stores",
            joinColumns = @JoinColumn(name = "configuration_id"),
            inverseJoinColumns = @JoinColumn(name = "store_id"),
            foreignKey = @ForeignKey(name = "fk_config_store"),
            inverseForeignKey = @ForeignKey(name = "fk_store_config")
    )
    public Set<Store> stores = new HashSet<>();

    /**
     * Store groups linked to this configuration.
     * Any store belonging to these groups is subject to this configuration.
     */
    @ManyToMany
    @JoinTable(
            name = "configuration_groups",
            joinColumns = @JoinColumn(name = "configuration_id"),
            inverseJoinColumns = @JoinColumn(name = "group_id"),
            foreignKey = @ForeignKey(name = "fk_config_group"),
            inverseForeignKey = @ForeignKey(name = "fk_group_config")
    )
    public Set<StoreGroup> storeGroups = new HashSet<>();

    /**
     * Lifecycle callback called before persistence.
     * Can be used to set default values if necessary.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        // Logic previously setting 'isActive' has been removed.
    }

    /**
     * Finds all configurations of a specific type.
     *
     * @param type The configuration type.
     * @return A list of matching configurations.
     */
    public static List<Configuration> findByType(String type) {
        return list("type", type);
    }

    /**
     * Finds all configurations applicable to a specific store for a given type.
     * <p>
     * A configuration applies to a store if:
     * <ul>
     *   <li>It is of the specified type.</li>
     *   <li>The store is directly linked to the configuration.</li>
     *   <li>The store is a member of a StoreGroup linked to the configuration.</li>
     * </ul>
     * This method returns all matching configurations; priority is not considered here.
     *
     * @param store The store to check.
     * @param type  The configuration type.
     * @return A list of applicable configurations.
     */
    public static List<Configuration> findConfigurationsForStore(Store store, String type) {
        if (store == null || store.id == null || type == null) {
            return List.of();
        }
        Set<StoreGroup> groups = StoreGroup.findAllStoreGroups(store);
        List<Long> groupIds = groups.stream().map(g -> g.id).collect(Collectors.toList());

        if (groupIds.isEmpty()) {
            return list("type = ?1 and ?2 member of stores", type, store);
        } else {
            return list(
                    "type = ?1 and (?2 member of stores or id in (select c.id from Configuration c join c.storeGroups g where g.id in ?3))",
                    type, store, groupIds
            );
        }
    }

    /**
     * Finds the single applicable configuration with the highest priority for a given store and type.
     * <p>
     * This method retrieves all applicable configurations and selects the one with the highest {@link #priority}.
     *
     * @param store The store to find the configuration for.
     * @param type  The configuration type.
     * @return The configuration with the highest priority, or null if no configuration applies.
     * @throws IllegalStateException if the store is null or if multiple configurations share the highest priority value.
     */
    public static Configuration findHighestPriorityForStore(Store store, String type) {
        if (store == null) {
            throw new IllegalStateException("Store cannot be null.");
        }
        List<Configuration> candidates = findConfigurationsForStore(store, type);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                String.format("No configuration found for Store '%s' and Type '%s'.", store.code, type)
            );
        }
        // Sort candidates by priority descending
        candidates.sort((c1, c2) -> Integer.compare(c2.priority, c1.priority));
        Configuration bestMatch = candidates.get(0);
        // Check for a tie in priority
        if (candidates.size() > 1) {
            Configuration runnerUp = candidates.get(1);
            if (bestMatch.priority == runnerUp.priority) {
                throw new IllegalStateException(
                    String.format("Ambiguous configuration for Store '%s' and Type '%s'. Multiple configurations have the highest priority (%d).",
                        store.code, type, bestMatch.priority)
                );
            }
        }
        return bestMatch;
    }

    /**
     * Parses the {@code specification} JSON string into a Jackson {@link JsonNode}.
     * <p>
     * Annotated with {@code @Ignore} to prevent exposure via GraphQL.
     *
     * @return The parsed JSON node.
     * @throws RuntimeException if parsing fails.
     */
    @Ignore
    public JsonNode getSpecificationFromJSON() {
        try {
            return mapper.readTree(this.specification);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse Configuration " + this.name + ": " + e.getMessage());
        }
    }

    /**
     * Helper method to check if this configuration applies to a specific store.
     * <p>
     * Checks if the store is directly linked or part of a linked StoreGroup.
     * Annotated with {@code @Ignore} to ensure this is not exposed via GraphQL.
     *
     * @param store The store to check against.
     * @return true if the configuration applies to the store, false otherwise.
     */
    @Ignore
    public boolean appliesTo(Store store) {
        // 1. Check direct link
        if (stores.contains(store)) {
            return true;
        }

        // 2. Check indirect link via StoreGroups
        // Logic: Is there any Group in 'this.storeGroups' that contains 'store'?
        // Note: This might trigger lazy loading queries depending on your fetch strategy.
        return this.storeGroups.stream().anyMatch(group -> group.stores.contains(store));
    }

    /**
     * Calculates a checksum representing the state of the configuration.
     * <p>
     * Covers exactly the fields imported by the Configuration CSV: name, type,
     * specification, priority and the linked store/group codes. Links are hashed
     * as sorted sets of codes (not entity sets) so that the value is reproducible
     * from the CSV data by the importer.
     *
     * @return An integer hash representing the state.
     */
    @Override
    public int getChecksum() {
        Set<String> storeCodes = stores.stream().map(s -> s.code).collect(Collectors.toCollection(TreeSet::new));
        Set<String> groupCodes = storeGroups.stream().map(g -> g.code).collect(Collectors.toCollection(TreeSet::new));
        return Objects.hash(name, type, specification, priority, storeCodes, groupCodes);
    }
}