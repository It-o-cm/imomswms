package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import java.util.Objects;

/**
 * A recipe (bill of materials) of a catering article (§ 5, F4).
 * <p>
 * One base recipe per variant ({@code stepId}, {@code choiceId}, {@code flavourId}
 * all null); one complement per composition choice or per flavour. Uniqueness is on
 * {@code (item, variantId, stepId, choiceId, flavourId, revision)}.
 */
@Entity
@Table(name = "catering_recipes",
        uniqueConstraints = @UniqueConstraint(name = "uk_recipe_key",
                columnNames = {"item_id", "variantId", "stepId", "choiceId", "flavourId", "revision"}))
public class CateringRecipe extends BaseEntity {

    /** The catering article this recipe belongs to. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "item_id")
    public CateringItem item;

    /** Variant identifier. */
    @Column(nullable = false, length = 20)
    public String variantId;

    /** Composition step identifier for a complement, or null for the base recipe. */
    @Column(length = 60)
    public String stepId;

    /** Composition choice identifier for a complement, or null for the base recipe. */
    @Column(length = 60)
    public String choiceId;

    /** Flavour identifier for a cake complement, or null otherwise. */
    @Column(length = 60)
    public String flavourId;

    /** Labour seconds for one unit of this recipe (additive across complements). */
    public Integer laborSeconds;

    /** Revision number; the highest active revision wins. */
    @Column(nullable = false)
    public int revision = 1;

    /** Whether the recipe is active. */
    @Column(nullable = false)
    public boolean active = true;

    /**
     * Computes the checksum from the recipe's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                item != null ? item.id : null,
                variantId, stepId, choiceId, flavourId, laborSeconds, revision, active);
    }
}
