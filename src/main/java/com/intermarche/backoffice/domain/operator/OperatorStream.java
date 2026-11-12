package com.intermarche.backoffice.domain.operator;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
@Entity @Table(name="operator_streams",uniqueConstraints=@UniqueConstraint(columnNames={"store_id"}))
public class OperatorStream extends BaseEntity {
 @ManyToOne(optional=false) public Store store;
 @Column(nullable=false) public long cursor;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
