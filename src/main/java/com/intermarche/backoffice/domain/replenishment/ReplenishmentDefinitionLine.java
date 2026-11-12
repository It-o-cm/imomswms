package com.intermarche.backoffice.domain.replenishment;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;
@Entity @Table(name="replenishment_definition_lines")
public class ReplenishmentDefinitionLine extends BaseEntity {
    @ManyToOne(optional=false) public Product product;
    @Column(precision=19,scale=6,nullable=false) public BigDecimal quantity;
    @ManyToOne public ExpectedSupply forecast;
    @Override public int getChecksum(){return Objects.hash(product,quantity);}
}
