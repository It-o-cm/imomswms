package com.intermarche.backoffice.domain.wms.handling;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import jakarta.persistence.*;
@Entity @DiscriminatorValue("CANCELLATION_DISPOSITION")
public class CancellationDispositionOrder extends BaseHandlingOrder {
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="disposition_origin_order_id") public BaseHandlingOrder origin;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="disposition_customer_order_id") public com.intermarche.backoffice.domain.order.Order customerOrder;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="disposition_building_id") public Building building;
 @Override public String getPrefix(){return "RET";}
 @Override public HandlingExecution createExecution(Employee employee){var e=new com.intermarche.backoffice.domain.fieldops.DispositionExecution();e.handlingOrder=this;e.employee=employee;return e;}
}
