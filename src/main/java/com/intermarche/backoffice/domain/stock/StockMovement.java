package com.intermarche.backoffice.domain.stock;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
/** Append-only physical ledger. Corrections are additional signed movements, never edits. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name="stock_movements", uniqueConstraints=@UniqueConstraint(columnNames={"account_id","eventKey"}), indexes=@Index(columnList="account_id,effectiveAt"))
public class StockMovement extends BaseEntity {
    @ManyToOne(optional=false) public StockAccount account;
    @ManyToOne(optional=false,fetch=FetchType.LAZY) public StockSegment segment;
    @ManyToOne(optional=false) public BaseLocation location;
    @Column(nullable=false,length=160) public String eventKey;
    @Column(length=160) public String operationKey;
    @Column(nullable=false,length=40) public String reason;
    @Column(nullable=false,precision=19,scale=6) public BigDecimal quantity;
    @Column(nullable=false) public LocalDateTime effectiveAt;
    @Column(name="activity_actor",length=100) public String activityActor;
    @Column(name="activity_unit",length=8) public String activityUnit;
    @PrePersist public void activityAttribution(){
        if(activityUnit==null&&account!=null&&account.product!=null)activityUnit=com.intermarche.backoffice.engine.catalog.ProductInformationService.unit(account.product);
        if(activityActor==null&&io.quarkus.arc.Arc.container()!=null){
            var handle=io.quarkus.arc.Arc.container().instance(io.quarkus.security.identity.SecurityIdentity.class);
            if(handle.isAvailable()){var identity=handle.get();if(!identity.isAnonymous())activityActor=identity.getPrincipal().getName();}
        }
    }
    @Override public int getChecksum() { return Objects.hash(account,location,eventKey,reason,quantity,effectiveAt); }
}
