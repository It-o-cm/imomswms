package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The share of a {@link FabricationLine} destined for one {@link CateringUnit} (§ 5, F6).
 * <p>
 * It is this part that the operator confirms (F12).
 */
@Entity
@Table(name = "fabrication_line_units")
public class FabricationLineUnit extends BaseEntity {

    /** The fabrication line. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "line_id")
    public FabricationLine line;

    /** The served catering unit. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "unit_id")
    public CateringUnit unit;

    /** Quantity of the line destined for this unit. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal quantity;

    /** Quantity produced for this unit. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal producedQuantity = BigDecimal.ZERO;

    /** Quantity declared not served for this unit. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal shortQuantity = BigDecimal.ZERO;

    /**
     * Computes the checksum from the business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                line != null ? line.id : null,
                unit != null ? unit.id : null,
                quantity, producedQuantity, shortQuantity);
    }
}
