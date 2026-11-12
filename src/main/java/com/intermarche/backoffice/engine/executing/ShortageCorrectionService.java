package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.util.*;

@ApplicationScoped
public class ShortageCorrectionService {
    private Long id(Object value){return value==null?null:((com.intermarche.backoffice.domain.BaseEntity)Hibernate.unproxy(value)).id;}
    private boolean active(HandlingExecution e){return e.status==HandlingExecution.ExecutionStatus.PENDING || e.status==HandlingExecution.ExecutionStatus.IN_PROGRESS;}
    public List<PickingShortage> events(BaseHandlingOrder raw){
        var order=Hibernate.unproxy(raw,BaseHandlingOrder.class);
        if(!(order instanceof PreparingOrder p))return List.of();
        var lines=p.lots.stream().flatMap(l->l.lines.stream()).distinct().toList();
        return lines.isEmpty()?List.of():PickingShortage.list("lotLine in ?1 order by id",lines);
    }
    public String unavailable(BaseHandlingOrder raw,PickingShortage event,Employee actor){
        var order=Hibernate.unproxy(raw,BaseHandlingOrder.class);
        if(!(order instanceof PreparingOrder p) || events(order).stream().noneMatch(e->e.id.equals(event.id)))return "Manquant hors de cet ordre.";
        if(actor==null || !Objects.equals(id(actor.store),id(order.store)))return "Employé du magasin requis.";
        if(event.replacementProduct!=null)return "Une substitution se rectifie par le contenu du bac, pas par l’annulation d’un manquant.";
        if(event.effectiveQuantity().signum()<=0)return "Ce manquant a déjà été entièrement rectifié.";
        if(order.status==BaseHandlingOrder.HandlingOrderStatus.CANCELLED || order.status==BaseHandlingOrder.HandlingOrderStatus.FAILED)return "Cet ordre est fermé sans reprise possible ici.";
        if(order.assignee!=null && !Objects.equals(id(order.assignee),actor.id))return "Ordre affecté à un autre opérateur.";
        List<HandlingExecution> executions=HandlingExecution.list("handlingOrder",order);
        if(executions.size()!=1)return "Ouvrez l’exécution de préparation concernée.";
        var execution=Hibernate.unproxy(executions.getFirst(),HandlingExecution.class);
        if(!Objects.equals(id(execution.employee),actor.id))return "La rectification appartient à l’opérateur responsable de cette exécution.";
        if(execution.status==HandlingExecution.ExecutionStatus.CANCELLED)return "Cette exécution est annulée.";
        var picked=PickedItem.<PickedItem>list("lotLine=?1 and status=?2 and quantity>0",event.lotLine,PickedItem.PickStatus.PICKED);
        if(com.intermarche.backoffice.domain.shipping.ShipmentLine.count("pickedItem.lotLine",event.lotLine)>0)return "Cette ligne est déjà partie : sa remise client ne peut pas être rouverte implicitement.";
        var totes=execution instanceof PickingExecution picking?picking.loadedTotes:execution instanceof GroupingExecution grouping?grouping.loadedTotes:Set.<LoadedTote>of();
        if(picked.stream().anyMatch(i->i.loadedTote==null || totes.stream().noneMatch(t->Objects.equals(t.id,id(i.loadedTote)))))return "La ligne a poursuivi sa préparation : rectifiez-la depuis l’exécution qui détient ses bacs actuels.";
        List<HandlingExecution> others=HandlingExecution.list("handlingOrder.store=?1 and id<>?2",order.store,execution.id);
        for(var other:others)if(active(other)){
            var otherOrder=Hibernate.unproxy(other.handlingOrder,BaseHandlingOrder.class);
            if(otherOrder instanceof PreparingOrder otherPreparing && otherPreparing.lots.stream().flatMap(l->l.lines.stream()).anyMatch(l->l.id.equals(id(event.lotLine))))return "Une autre exécution travaille déjà cette ligne. Terminez ou libérez cette prise en charge avant de rectifier.";
        }
        return null;
    }
    public void correct(BaseHandlingOrder order,Employee actor,OperatorCommandInput input){
        PickingShortage event=input.shortageId==null?null:PickingShortage.findById(input.shortageId);
        if(event==null)throw new IllegalArgumentException("Manquant introuvable.");
        String blocked=unavailable(order,event,actor);if(blocked!=null)throw new IllegalArgumentException(blocked);
        BigDecimal effective=event.effectiveQuantity(),q=input.quantity;
        if(input.expectedQuantity==null || input.expectedQuantity.compareTo(effective)!=0)throw new IllegalArgumentException("Le manquant a changé. Actualisez l’exécution avant de rectifier.");
        if(q==null || q.signum()<=0 || q.compareTo(effective)>0 || q.stripTrailingZeros().scale()>3 || event.requestedProduct.productType==ProductType.UNIT && q.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Quantité positive, au plus le manquant restant et trois décimales ; pièces entières.");
        if(input.reason==null || input.reason.isBlank() || input.reason.length()>300)throw new IllegalArgumentException("Motif de rectification requis (300 caractères maximum).");
        var correction=new PickingShortageCorrection();correction.shortage=event;correction.resumedOrder=order;correction.employee=actor;correction.quantity=q;correction.reason=input.reason.trim();correction.persist();
        var execution=Hibernate.unproxy(HandlingExecution.<HandlingExecution>find("handlingOrder",order).firstResult(),HandlingExecution.class);
        if(execution instanceof PickingExecution picking && !MixedPreparationService.supports(order)){
            var pending=picking.itemsToPick.stream().filter(i->i.status==PickedItem.PickStatus.PENDING && Objects.equals(id(i.lotLine),id(event.lotLine))).findFirst().orElse(null);
            if(pending==null){pending=new PickedItem();pending.lotLine=event.lotLine;pending.product=event.requestedProduct;pending.quantity=q;pending.status=PickedItem.PickStatus.PENDING;pending.persist();picking.itemsToPick.add(pending);}else pending.quantity=pending.quantity.add(q);
        }
        execution.status=HandlingExecution.ExecutionStatus.IN_PROGRESS;order.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        if(order instanceof PreparingOrder p)for(var lot:p.lots)if(lot.lines.stream().anyMatch(l->l.id.equals(id(event.lotLine))))lot.status=order instanceof CollectingOrder?Lot.LotStatus.PLANNED_TO_BE_COLLECTED:Lot.LotStatus.PLANNED_TO_BE_PACKED;
    }
    public List<Map<String,Object>> view(BaseHandlingOrder order,Employee actor){
        return events(order).stream().map(event->{var row=new LinkedHashMap<String,Object>();
            row.put("id",event.id);row.put("productId",event.requestedProduct.id);row.put("requestedEan",event.requestedProduct.ean);row.put("originOrderCode",event.handlingOrder.code);row.put("name",event.requestedProduct.name);row.put("quantity",event.quantity);row.put("remainingQuantity",event.effectiveQuantity());row.put("reason",event.reason);row.put("replacement",event.replacementProduct==null?null:event.replacementProduct.name);
            String blocked=unavailable(order,event,actor);row.put("canCorrect",blocked==null);row.put("correctionBlocked",blocked);
            row.put("corrections",PickingShortageCorrection.<PickingShortageCorrection>list("shortage=?1 order by id",event).stream().map(c->Map.of("quantity",c.quantity,"reason",c.reason,"operator",c.employee.getFullName(),"orderCode",c.resumedOrder.code)).toList());return (Map<String,Object>)row;
        }).toList();
    }
}
