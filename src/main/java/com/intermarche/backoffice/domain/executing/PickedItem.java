package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.wms.LotLine;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Entity representing a single picking action (collection) for a {@link LotLine}.
 * <p>
 * A {@link LotLine} can be split into multiple PickedItems if, for example,
 * the items are placed into different bins or picked at different times.
 * <p>
 * This entity tracks the specific quantity picked, the target {@link LoadedTote},
 * and the current status of this specific collection.
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning, and audit fields.
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "picked_items",
        indexes = {
                @Index(name = "idx_picked_item_lotline", columnList = "lot_line_id"),
                @Index(name = "idx_picked_item_product", columnList = "product_id"),
                @Index(name = "idx_picked_item_tote", columnList = "loaded_tote_id"),
                @Index(name = "idx_picked_item_status", columnList = "status")
        }
)
public class PickedItem extends BaseEntity {

    /**
     * Enumeration representing the picking status of this specific item.
     */
    public enum PickStatus {
        /**
         * The item is assigned but not yet physically picked.
         */
        PENDING,
        /**
         * The item has been picked and placed into the tote.
         */
        PICKED,
        /**
         * The item cannot be picked and will not be.
         */
        CANCELED
    }

    // --------------------------------------------------
    // Relationships
    // --------------------------------------------------

    /**
     * The source LotLine from which this item is picked.
     * <p>
     * Multiple PickedItems can reference the same LotLine if the quantity is split.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lot_line_id", nullable = false, foreignKey = @ForeignKey(name = "fk_pickeditem_lotline"))
    @NotNull(message = "LotLine reference is mandatory")
    public LotLine lotLine;

    /**
     * The Product being picked.
     * <p>
     * Denormalized for quick access without joining the LotLine hierarchy.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "fk_pickeditem_product"))
    @NotNull(message = "Product is mandatory")
    public Product product;

    /**
     * The LoadedTote (destination bin) where this picked item is placed.
     * <p>
     * Can be null if the item has been picked but not yet assigned to a specific tote/bin,
     * or if the picking process precedes tote assignment.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loaded_tote_id", foreignKey = @ForeignKey(name = "fk_pickeditem_loadedtote"))
    public LoadedTote loadedTote;

    // --------------------------------------------------
    // Details
    // --------------------------------------------------

    /**
     * The quantity picked for this specific action.
     * <p>
     * The sum of quantities across all PickedItems for a given LotLine
     * should not exceed the LotLine's requested quantity.
     */
    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    @NotNull(message = "Quantity is mandatory")
    public BigDecimal quantity;

    /**
     * The current status of this specific picking action.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @NotNull(message = "Status is mandatory")
    public PickStatus status = PickStatus.PENDING;

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum based on the picked item's attributes.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                lotLine != null ? lotLine.id : null,
                product != null ? product.id : null,
                loadedTote != null ? loadedTote.id : null,
                quantity,
                status
        );
    }
}