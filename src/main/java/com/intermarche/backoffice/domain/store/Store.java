package com.intermarche.backoffice.domain.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.BatchSize;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Entity representing the Business Entity (Company or Franchisee).
 * <p>
 * Represents the legal owner or operator ("Adhérent").
 * It owns multiple physical buildings (Shops and Warehouses).
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "stores",
        indexes = {
                @Index(name = "idx_store_code", columnList = "code"),
                @Index(name = "idx_store_name", columnList = "name")
        }
)
@Cacheable
@NamedQueries({
        @NamedQuery(
                name = "Store.findWithBuildingsById",
                query = "SELECT s FROM Store s LEFT JOIN FETCH s.buildings WHERE s.id = ?1"
        ),
        @NamedQuery(
                name = "Store.findWithBuildingsByCode",
                query = "SELECT s FROM Store s LEFT JOIN FETCH s.buildings WHERE s.code = ?1"
        )
})
public class Store extends BaseEntity {

    // --------------------------------------------------
    // Business Details
    // --------------------------------------------------

    /**
     * The unique code of the business entity (e.g., "FR-LYON-001").
     */
    @Column(unique = true, nullable = false, length = 20)
    @NotBlank(message = "Store code is mandatory")
    public String code;

    /**
     * The name of the business entity (e.g., "SCA Lyon Centre").
     * <p>
     * Indexed to speed up search queries.
     */
    @Column(nullable = false)
    @NotBlank(message = "Store name is mandatory")
    public String name;

    // --------------------------------------------------
    // Address Information
    // --------------------------------------------------

    /**
     * The Legal / Registered Address (Siège Social) of the business entity.
     * <p>
     * This is the official address used for invoices and legal documents.
     * It is distinct from the physical addresses of the {@link Building}s.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "streetLine1", column = @Column(name = "legal_address_street_line1")),
            @AttributeOverride(name = "streetLine2", column = @Column(name = "legal_address_street_line2")),
            @AttributeOverride(name = "postalCode", column = @Column(name = "legal_address_postal_code")),
            @AttributeOverride(name = "city", column = @Column(name = "legal_address_city")),
            @AttributeOverride(name = "country", column = @Column(name = "legal_address_country"))
    })
    public Adresse address;

    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The list of physical buildings (Shops and Warehouses) belonging to this business entity.
     * <p>
     * Managed by the parent Store. Cascades delete operations.
     * <p>
     * <b>Performance Note:</b> Annotated with {@link BatchSize} to optimize loading when accessing
     * multiple stores (e.g., in {@code listAll()}). It prevents the N+1 query problem by loading
     * collections in batches of 50.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "store_id")
    @BatchSize(size = 50)
    public Set<Building> buildings = new HashSet<>();

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a store by its unique code.
     *
     * @param code The store code.
     * @return The Store instance or null if not found.
     */
    public static Store findByCode(String code) {
        return find("code", code).firstResult();
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Retrieves the unique physical store building ({@link StoreBuilding}) associated with this entity.
     * <p>
     * Iterates through the owned buildings to find the one corresponding to the retail shop.
     * <p>
     * <b>Business Rule:</b> A Store entity should typically have exactly one main shop building.
     * If multiple are found, this method throws an exception to indicate a data integrity issue.
     *
     * @return The {@link StoreBuilding} instance, or null if none exists.
     * @throws IllegalStateException if multiple StoreBuildings are found for this Store.
     */
    public StoreBuilding getStoreBuilding() {
        StoreBuilding found = null;
        for (Building building : buildings) {
            if (building instanceof StoreBuilding) {
                if (found != null) {
                    throw new IllegalStateException(
                            String.format("Data Integrity Error: Store '%s' has multiple StoreBuildings.", this.code)
                    );
                }
                found = (StoreBuilding) building;
            }
        }
        return found;
    }

    /**
     * Finds a specific building belonging to this store by its unique building code.
     * <p>
     * Searches the associated collection of buildings.
     *
     * @param buildingCode The unique code of the building to find.
     * @return The {@link Building} instance, or null if no building matches the code.
     */
    public Building findBuildingByCode(String buildingCode) {
        if (buildingCode == null || buildingCode.isBlank()) {
            return null;
        }
        for (Building building : buildings) {
            if (buildingCode.equals(building.buildingCode)) {
                return building;
            }
        }
        return null;
    }

    /**
     * Calculates a checksum based on the store's key attributes.
     * <p>
     * Covers exactly the fields imported by the Store CSV: code, name and address.
     * The buildings collection is managed by the Building CSV import and is
     * deliberately excluded.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        int legalAddressChecksum = (address == null) ? 0 : address.getChecksum();
        return Objects.hash(code, name, legalAddressChecksum);
    }
}