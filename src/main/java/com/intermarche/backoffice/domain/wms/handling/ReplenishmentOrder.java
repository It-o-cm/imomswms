package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.executing.ReplenishmentExecution;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

/** Physical replenishment work, separate from customer picking. Each supply is one product movement. */
@Entity
@DiscriminatorValue("REPLENISHMENT")
public class ReplenishmentOrder extends BaseHandlingOrder {
    public enum Operation { TRUCK_UNLOADING, SHELF_REPLENISHMENT, STOCK_PROTECTION, STOCK_TRANSFER }
    @Enumerated(EnumType.STRING) public Operation operation;
    @Column(length=80) public String truckReference;
    @Column(length=200) public String generatedByRule;
    @Column(length=1000) public String generationReason;
    @Column(unique=true,length=90) public String replenishmentRequestKey;
    @Column(length=64) public String replenishmentRequestHash;
    @ManyToOne public Building sourceBuilding;
    @ManyToOne public Building destinationBuilding;
    @ManyToOne public ReplenishmentOrder predecessor;
    @OneToMany
    @JoinTable(name="replenishment_order_supplies", uniqueConstraints=@UniqueConstraint(columnNames="supplies_id"))
    @OrderBy("id")
    public List<ExpectedSupply> supplies = new ArrayList<>();
    @Override public String getPrefix() { return "REA"; }
    @Override public HandlingExecution createExecution(Employee employee) {
        ReplenishmentExecution execution = new ReplenishmentExecution();
        execution.handlingOrder=this;
        execution.employee=employee;
        return execution;
    }
}
