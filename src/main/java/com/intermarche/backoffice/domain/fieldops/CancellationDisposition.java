package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.wms.handling.CancellationDispositionOrder;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.store.*;
import jakarta.persistence.*;
@Entity @Table(name="cancellation_dispositions",uniqueConstraints=@UniqueConstraint(columnNames={"item_id"}))
public class CancellationDisposition extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public CancellationDispositionOrder handlingOrder;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public PickedItem item;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public LoadedTote source;
 @Column(nullable=false,precision=19,scale=3) public java.math.BigDecimal quantity;
 @Column(nullable=false,length=20) public String state="PENDING";
 @Column(nullable=false,length=20) public String policy="RETURN";
 @Column(nullable=false,length=500) public String instruction;
 @ManyToOne(fetch=FetchType.LAZY) public BaseLocation destination;
 @ManyToOne(fetch=FetchType.LAZY) public LoadedTote destinationTote;
 @ManyToOne(fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.stock.StockCommitment hold;
 public Long targetItemId;
 @Column(length=64) public String confirmationKey,fingerprint;
 @Column(columnDefinition="text") public String result;
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
}
