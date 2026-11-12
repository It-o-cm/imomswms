package com.intermarche.backoffice.domain.inventory;
import jakarta.persistence.*;
@Entity @DiscriminatorValue("INVENTORY")
public class InventoryExecution extends com.intermarche.backoffice.domain.handling.HandlingExecution {
 @Column(name="inventory_count_started") public Boolean countStarted=false;
}
