package com.intermarche.backoffice.domain.executing;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** Compensation d'un manquant : la déclaration d'origine demeure inchangée. */
@Entity @Table(name="picking_shortage_corrections")
public class PickingShortageCorrection extends BaseEntity {
    @ManyToOne(optional=false) public PickingShortage shortage;
    @ManyToOne(optional=false) public BaseHandlingOrder resumedOrder;
    @ManyToOne(optional=false) public Employee employee;
    @Column(nullable=false,precision=10,scale=3) public BigDecimal quantity;
    @Column(nullable=false,length=300) public String reason;
    @Override public int getChecksum(){return java.util.Objects.hash(shortage,resumedOrder,employee,quantity,reason);}
}
