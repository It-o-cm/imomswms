package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
@Entity @Table(name="field_incident_attachments",uniqueConstraints=@UniqueConstraint(columnNames={"incident_id","requestKey"}))
public class IncidentAttachment extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public FieldIncident incident;
 @Column(length=64,nullable=false) public String requestKey;
 @Column(length=64,nullable=false) public String sha256;
 @Column(length=80,nullable=false) public String filename;
 @Column(length=30,nullable=false) public String mediaType;
 @Lob @Column(nullable=false) public byte[] data;
 public int width,height;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
