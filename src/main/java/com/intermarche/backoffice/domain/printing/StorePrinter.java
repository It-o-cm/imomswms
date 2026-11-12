package com.intermarche.backoffice.domain.printing;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.Store;import jakarta.persistence.*;import java.time.LocalDateTime;
@Entity @Table(name="store_printers",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","code"}))
public class StorePrinter extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
public String code,label,transport="SIMULATOR",host,simulation="SUCCESS";public int port=9100,dpi=203;public boolean enabled=true;public long revisionNumber;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
