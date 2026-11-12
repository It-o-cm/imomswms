package com.intermarche.backoffice.domain.wms.handling;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import jakarta.persistence.*;
@Entity @DiscriminatorValue("INVENTORY")
public class InventoryOrder extends BaseHandlingOrder {
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="inventory_building_id") public Building building;
 @Column(name="inventory_reference_at") public java.time.LocalDateTime referenceAt;
 @Column(name="inventory_scope_kind",length=30) public String scopeKind;
 @Column(name="inventory_scope_label",length=200) public String scopeLabel;
 @Column(name="inventory_area",length=50) public String area;
 @Column(name="inventory_aisle",length=10) public String aisle;
 @Column(name="inventory_bay",length=10) public String bay;
 @Column(name="inventory_blind") public Boolean blind=true;
 @Override public String getPrefix(){return "INV";}
 @Override public HandlingExecution createExecution(Employee employee){var execution=new com.intermarche.backoffice.domain.inventory.InventoryExecution();execution.handlingOrder=this;execution.employee=employee;return execution;}
}
