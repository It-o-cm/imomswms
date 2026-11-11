package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.stock.ManufacturerLot;
import com.intermarche.backoffice.domain.stock.StockSegment;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A component output of a {@link FabricationLine} (§ 5, F13).
 * <p>
 * {@code loss} is true for a declared loss (F12).
 */
@Entity
@Table(name = "fabrication_consumptions")
public class FabricationConsumption extends BaseEntity {

    /** The fabrication line that consumed the component. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "line_id")
    public FabricationLine line;

    /** The consumed component product. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "component_id")
    public Product component;

    /** The stock segment the component was taken from. */
    @ManyToOne
    @JoinColumn(name = "segment_id")
    public StockSegment segment;

    /** The manufacturer lot of the consumed component. */
    @ManyToOne
    @JoinColumn(name = "manufacturerLot_id")
    public ManufacturerLot manufacturerLot;

    /** Consumed quantity. */
    @Column(nullable = false, precision = 19, scale = 6)
    public BigDecimal quantity;

    /** Whether this output is a declared loss. */
    @Column(nullable = false)
    public boolean loss = false;

    /**
     * Computes the checksum from the business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                line != null ? line.id : null,
                component != null ? component.id : null,
                segment != null ? segment.id : null,
                manufacturerLot != null ? manufacturerLot.id : null,
                quantity, loss);
    }
}
