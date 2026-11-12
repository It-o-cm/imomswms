package com.intermarche.backoffice.domain.shipping;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
@Entity @Table(name="shipment_lines")
public class ShipmentLine extends BaseEntity {
    @ManyToOne(optional=false) public PickedItem pickedItem;
    @ManyToOne public LoadedTote loadedTote;
    @ManyToOne(fetch=FetchType.LAZY) public Parcel parcel;
    public String sourceToteCode;
    public String toteCode,departureBuffer,productName,unit;
    @Column(precision=19,scale=6) public BigDecimal quantity;
    public LocalDateTime toteReturnedAt;
    public String toteReturnedBy;
    @Override public int getChecksum(){return Objects.hash(pickedItem,loadedTote,quantity);}
}
