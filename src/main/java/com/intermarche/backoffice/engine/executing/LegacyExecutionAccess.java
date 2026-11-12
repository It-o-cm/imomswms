package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.engine.operator.OperatorContext;
import com.intermarche.backoffice.domain.fieldops.FieldEntityIds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.ws.rs.*;
import java.util.*;

/** Compatibility endpoints obey the same employee, store and custody boundary as mobile commands. */
@ApplicationScoped
public class LegacyExecutionAccess {
 @Inject OperatorContext context;
 @Inject EntityManager em;
 @Inject OperatorExecutionService executions;
 public Employee actor(){var a=context.account();if(!(a instanceof Employee e)||e.store==null)throw new ForbiddenException("Préparateur du magasin requis");return e;}
 public BaseHandlingOrder order(Long id,boolean write){
  Employee actor=actor();BaseHandlingOrder o=BaseHandlingOrder.findById(id);
  if(o==null||!Objects.equals(FieldEntityIds.id(o.store),FieldEntityIds.id(actor.store)))throw new NotFoundException("Ordre hors périmètre");
  if(write){em.lock(org.hibernate.Hibernate.unproxy(actor.store,Store.class),LockModeType.PESSIMISTIC_WRITE);em.refresh(o);if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(o.status))throw new ClientErrorException("Ordre terminé ou annulé",409);if(o.assignee!=null&&!Objects.equals(FieldEntityIds.id(o.assignee),actor.id))throw new ForbiddenException("Ordre affecté à un autre préparateur");com.intermarche.backoffice.domain.fieldops.MissionOperationalState.requireActive(id);}
  return o;
 }
 public HandlingExecution execution(Long id,boolean write){
  HandlingExecution e=HandlingExecution.findById(id);if(e==null)throw new NotFoundException("Exécution inconnue");order(FieldEntityIds.id(e.handlingOrder),write);
  if(write)em.refresh(e);
  if(!Objects.equals(FieldEntityIds.id(e.employee),actor().id))throw new ForbiddenException("Exécution d’un autre préparateur");
  if(write){if(e.status!=HandlingExecution.ExecutionStatus.PENDING&&e.status!=HandlingExecution.ExecutionStatus.IN_PROGRESS)throw new ClientErrorException("Exécution déjà terminée",409);}
  return org.hibernate.Hibernate.unproxy(e,HandlingExecution.class);
 }
 public void reserve(Long id){order(id,true);if(HandlingExecution.count("handlingOrder.id",id)>0)throw new ClientErrorException("Ordre déjà réservé",409);}
 public void release(Long id){
        order(id,true);
        var reserved=com.intermarche.backoffice.domain.handling.HandlingExecution.<com.intermarche.backoffice.domain.handling.HandlingExecution>find("handlingOrder.id",id).firstResult();
        if(reserved!=null){reserved=execution(reserved.id,true);if(!executions.canRelease(reserved))throw new ClientErrorException("Une exécution engagée ne peut pas être supprimée",409);}
 }
 public void item(Long execution,Long item){var e=execution(execution,true);PickedItem i=PickedItem.findById(item);if(!(e instanceof PickingExecution p)||i==null||p.itemsToPick.stream().noneMatch(v->Objects.equals(v.id,item))&&p.loadedTotes.stream().flatMap(t->t.items.stream()).noneMatch(v->Objects.equals(v.id,item)))throw new BadRequestException("Article hors de cette exécution");}
 public void itemInTote(Long execution,Long item,String code){item(execution,item);tote(execution,code,true);var picked=PickedItem.<PickedItem>findById(item);if(picked.loadedTote==null||!Objects.equals(picked.loadedTote.tote.code,code))throw new BadRequestException("Article absent du bac source indiqué");}
 public void tote(Long execution,String code,boolean attached){var e=execution(execution,true);Tote t=Tote.find("code",code).firstResult();if(t==null||!Objects.equals(FieldEntityIds.id(t.store),FieldEntityIds.id(e.handlingOrder.store)))throw new BadRequestException("Bac hors du magasin");if(attached&& !((e instanceof PickingExecution p&&p.loadedTotes.stream().anyMatch(v->Objects.equals(FieldEntityIds.id(v.tote),t.id)))||(e instanceof GroupingExecution g&&g.loadedTotes.stream().anyMatch(v->Objects.equals(FieldEntityIds.id(v.tote),t.id)))))throw new BadRequestException("Bac hors de cette exécution");}
 public void buffer(Long execution,Long id){if(id==null)return;var e=execution(execution,true);Buffer b=Buffer.findById(id);if(b==null||!Objects.equals(FieldEntityIds.id(Building.findStoreByBuildingId(FieldEntityIds.id(b.building))),FieldEntityIds.id(e.handlingOrder.store)))throw new BadRequestException("Buffer hors du magasin");}
 public void orders(List<Long> ids){if(ids==null||ids.isEmpty()||ids.size()>200||ids.stream().anyMatch(java.util.Objects::isNull))throw new BadRequestException("Sélection d’ordres invalide");for(Long id:new TreeSet<>(ids)){order(id,true);for(HandlingExecution e:HandlingExecution.<HandlingExecution>list("handlingOrder.id",id))execution(e.id,true);}}
 public void report(Long execution,HandlingOrderService.ExecutionReportRequest r){execution(execution,true);buffer(execution,r.bufferId);if(r.totes!=null)for(var t:r.totes){if(t==null)throw new BadRequestException("Bac requis");tote(execution,t.code,false);buffer(execution,t.bufferId);if(t.items!=null)for(var i:t.items){if(i==null)throw new BadRequestException("Article requis");if(!MixedPreparationService.supports(org.hibernate.Hibernate.unproxy(execution(execution,true).handlingOrder,BaseHandlingOrder.class)))item(execution,i.pickedItemId);}}}
}
