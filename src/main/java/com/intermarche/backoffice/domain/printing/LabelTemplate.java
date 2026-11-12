package com.intermarche.backoffice.domain.printing;
import com.intermarche.backoffice.domain.BaseEntity;import com.intermarche.backoffice.domain.store.Store;import jakarta.persistence.*;import java.time.LocalDateTime;
@Entity @Table(name="label_templates",uniqueConstraints=@UniqueConstraint(columnNames={"store_id","code","revisionNumber"}))
public class LabelTemplate extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
public String code,kind="PARCEL",title;public int revisionNumber=1,widthDots=812,heightDots=600;public String createdBy;
@Lob public String options;
@Override public int getChecksum(){return java.util.Objects.hash(id);}
}
