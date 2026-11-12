package com.intermarche.backoffice.domain.inventory;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="inventory_scope_locks")
public class InventoryScopeLock extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public InventoryCountSession session;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.BaseLocation location;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.store.BufferCabinet cabinet;
 public Integer cellRow,cellColumn;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
