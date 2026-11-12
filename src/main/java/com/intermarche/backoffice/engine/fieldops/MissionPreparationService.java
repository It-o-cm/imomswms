package com.intermarche.backoffice.engine.fieldops;
import com.intermarche.backoffice.domain.fieldops.MissionPreparationContext;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.PreparingOrder;
import com.intermarche.backoffice.engine.operator.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.*;
@ApplicationScoped @Transactional
public class MissionPreparationService {
 @Inject FieldOperationsService field;@Inject StockCommands receipts;
 public static final Set<String> MODES=Set.of("SINGLE_ORDER","MULTI_ORDER","PRODUCT","ZONE","BATCH","WAVE","ROUTE");
 public static Map<String,Object> view(MissionPreparationContext c){var out=c==null?view(null,null,null,null):view(c.id,c.version,c.mode,c.groupCode);out.put("requireFinalScan",c!=null&&c.requireFinalScan);return out;}
 public static Map<String,Object> view(Long id,Long version,String mode,String groupCode){var out=new LinkedHashMap<String,Object>();out.put("mode",mode);out.put("groupCode",groupCode);out.put("revision",id==null?"0":CommandCanonical.hash("{\"id\":"+id+",\"version\":"+version+"}"));return out;}
 public static Map<String,Object> projection(Long id){return view(MissionPreparationContext.find("handlingOrder.id",id).firstResult());}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="MissionPreparationInput") public static class Change {public Long storeId;public String requestKey,expectedRevision,mode,groupCode;public Boolean requireFinalScan;}
 public Object read(Long storeId,Long id){var store=field.store(storeId,false);field.order(store,id);return projection(id);}
 public Object change(Long id,Change input){
  if(!field.manager())throw new ForbiddenException("Mode de préparation réservé au responsable");
  if(input==null||!MODES.contains(input.mode==null?"":input.mode))throw new BadRequestException("Mode de préparation inconnu");
  String group=input.groupCode==null?null:input.groupCode.trim();if(group!=null&&group.isEmpty())group=null;
  if(group!=null&&group.length()>80)throw new BadRequestException("Groupe limité à 80 caractères");
  if(Set.of("BATCH","WAVE").contains(input.mode)&&group==null)throw new BadRequestException("Identifiant de vague ou de lot de préparation requis");
  var store=field.store(input.storeId,true);var order=field.order(store,id);
  var hash=field.hash(Arrays.asList(id,input));var previous=receipts.previous(store,input.requestKey,hash);if(previous!=null)return receipts.value(previous);
  if(!(org.hibernate.Hibernate.unproxy(order,BaseHandlingOrder.class) instanceof PreparingOrder))throw new BadRequestException("Mission de préparation requise");
  if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(order.status))throw new BadRequestException("Mission fermée");
  MissionPreparationContext context=MissionPreparationContext.find("handlingOrder",order).firstResult();
  if(!Objects.equals(input.expectedRevision,view(context).get("revision")))throw new OperatorFailure(409,"STALE_PREPARATION_CONTEXT","Le mode a changé. Actualisez avant de modifier");
  if(context==null){context=new MissionPreparationContext();context.store=store;context.handlingOrder=order;}
  context.mode=input.mode;context.groupCode=group;if(input.requireFinalScan!=null)context.requireFinalScan=input.requireFinalScan;context.persistAndFlush();
  return receipts.record(store,input.requestKey,hash,"PREPARATION_CONTEXT",view(context));
 }
}
