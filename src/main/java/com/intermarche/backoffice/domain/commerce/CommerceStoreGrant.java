package com.intermarche.backoffice.domain.commerce;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.util.Objects;

/** Grants a technical commerce identity access to one store: the service role alone opens no store. */
@Entity @Table(name="commerce_store_grants")
public class CommerceStoreGrant extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @Column(nullable=false,length=100) public String principal;
 @Column(nullable=false,length=20) public String sourceSystem;
 @Column(nullable=false) public boolean active=true;
 @Column(nullable=false,length=100) public String grantedBy;
 /**
  * Returns the checksum of the identity of this grant.
  *
  * @return the checksum
  */
 @Override public int getChecksum(){return Objects.hash(id);}
}
