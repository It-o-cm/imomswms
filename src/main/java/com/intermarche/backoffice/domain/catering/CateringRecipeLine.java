package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A component line of a {@link CateringRecipe} (§ 5).
 * <p>
 * Quantity is for one unit of the variant.
 */
@Entity
@Table(name = "catering_recipe_lines")
public class CateringRecipeLine extends BaseEntity {

    /** Kind of recipe line. */
    public enum RecipeLineKind {
        /** Component consumed from stock at fabrication (ingredient or sale packaging). */
        INGREDIENT,
        /** Product handed over as-is (STOCKED mode). */
        PICKED
    }

    /** The recipe this line belongs to. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "recipe_id")
    public CateringRecipe recipe;

    /** The component product. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "component_id")
    public Product component;

    /** Quantity for one unit of the variant. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal quantity;

    /** Whether the component is consumed or picked. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public RecipeLineKind kind;

    /**
     * Computes the checksum from the line's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                recipe != null ? recipe.id : null,
                component != null ? component.id : null,
                quantity, kind);
    }
}
