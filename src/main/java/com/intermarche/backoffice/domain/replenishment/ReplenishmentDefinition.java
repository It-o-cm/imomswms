package com.intermarche.backoffice.domain.replenishment;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
@Entity @Table(name="replenishment_definitions")
public class ReplenishmentDefinition extends BaseEntity {
    @ManyToOne(optional=false) public Store store;
    @Column(unique=true) public String requestKey;
    public String requestHash,code,truckReference;
    @Enumerated(EnumType.STRING) public ReplenishmentOrder.Operation operation;
    @ManyToOne public Building sourceBuilding;
    @ManyToOne(optional=false) public Building destinationBuilding;
    @Column(name="expected_start") public LocalDateTime start;
    @Column(name="expected_end") public LocalDateTime end;
    @ManyToOne public ReplenishmentOrder handlingOrder;
    public String handlingRequestHash;
    @OneToMany(cascade=CascadeType.ALL) @JoinColumn(name="definition_id")
    @OrderBy("id") public List<ReplenishmentDefinitionLine> lines=new ArrayList<>();
    @Override public int getChecksum(){return Objects.hash(code,requestKey,operation,start,end);}
}
