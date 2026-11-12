package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.engine.executing.*;
import com.intermarche.backoffice.engine.operator.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.*;

@ApplicationScoped @Transactional
public class MissionStateService {
 @Inject FieldOperationsService field;@Inject StockCommands commandReceipts;@Inject OperatorExecutionService operator;
 public static Map<String,Object> view(MissionOperationalState state){var v=new LinkedHashMap<String,Object>();v.put("state",state==null?"ACTIVE":state.state);v.put("reason",state==null?"":state.reason);v.put("actor",state==null?null:state.actor);v.put("stateRevision",state==null?"0":CommandCanonical.hash("{\"id\":"+state.id+",\"version\":"+state.version+"}"));v.put("changedAt",state==null?null:state.updatedAt);return v;}
 public Object list(Long storeId,String ids){Store s=field.store(storeId,false);if(ids==null||ids.isBlank())return List.of();List<Long> wanted;try{wanted=Arrays.stream(ids.split(",")).map(Long::valueOf).distinct().toList();}catch(Exception e){throw new BadRequestException("Identifiants de missions invalides");}if(wanted.size()>500)throw new BadRequestException("500 missions maximum par page");return MissionOperationalState.<MissionOperationalState>list("store=?1 and handlingOrder.id in ?2 and state<>'ACTIVE'",s,wanted).stream().map(state->{var v=view(state);v.put("orderId",id(state.handlingOrder));return v;}).toList();}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="MissionStateChangeInput") public static class Change {public Long storeId;public String requestKey,state,reason,expectedRevision,expectedStateRevision;}
 public Object read(Long storeId,Long id){Store s=field.store(storeId,false);field.order(s,id);return view(MissionOperationalState.find("store=?1 and handlingOrder.id=?2",s,id).firstResult());}
 @SuppressWarnings("unchecked") public Object change(Long id,Change c){
  if(c==null||!Set.of("ACTIVE","PAUSED","WAITING").contains(c.state==null?"":c.state))throw new BadRequestException("État de mission inconnu");FieldOperationsService.key(c.requestKey);if(!c.state.equals("ACTIVE"))FieldOperationsService.text(c.reason,300);if(c.reason!=null&&c.reason.length()>300)throw new BadRequestException("Motif limité à 300 caractères");
  Store s=field.store(c.storeId,true);BaseHandlingOrder order=field.order(s,id);String actor=field.actor(),fingerprint=field.hash(Arrays.asList(id,c));MissionStateChange previous=MissionStateChange.find("store=?1 and principal=?2 and requestKey=?3",s,actor,c.requestKey).firstResult();if(previous!=null){if(!previous.fingerprint.equals(fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne un autre changement de mission");try{return field.decode(previous.result,Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
  if(!field.manager()&&(order.assignee==null||!order.assignee.badgeId.equals(actor))&&HandlingExecution.count("handlingOrder=?1 and employee.badgeId=?2",order,actor)==0)throw new ForbiddenException("Seul le préparateur affecté ou un responsable peut suspendre cette mission");
  if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(order.status))throw new BadRequestException("Cette mission est fermée");
  var current=(Map<String,Object>)operator.order(s.id,id);if(c.expectedRevision==null||!c.expectedRevision.equals(current.get("revision")))throw new OperatorFailure(409,"STALE_REVISION","La mission a changé. Actualisez avant de suspendre ou reprendre");MissionOperationalState state=MissionOperationalState.find("store=?1 and handlingOrder=?2",s,order).firstResult();if(c.expectedStateRevision==null||!c.expectedStateRevision.equals(view(state).get("stateRevision")))throw new OperatorFailure(409,"STALE_MISSION_STATE","Un autre opérateur a changé l’état de la mission");
  if(state==null){state=new MissionOperationalState();state.store=s;state.handlingOrder=order;}state.state=c.state;state.reason=c.reason==null?"":c.reason.trim();state.actor=actor;state.persistAndFlush();var result=view(state);
  var receipt=new MissionStateChange();receipt.store=s;receipt.handlingOrder=order;receipt.principal=actor;receipt.requestKey=c.requestKey;receipt.fingerprint=fingerprint;receipt.result=field.encode(result);receipt.persist();var audience=new TreeSet<String>();audience.add(actor);if(order.assignee!=null)audience.add(order.assignee.badgeId);field.notice(s,"mission-state:"+id+":"+c.requestKey,"Mission "+order.code,"État : "+c.state+(state.reason.isBlank()?"":" · "+state.reason),id,audience);return commandReceipts.record(s,c.requestKey,fingerprint,"MISSION_STATE",result);
 }
 /** Called after receipt replay, before any new physical mutation. */
 public void requireActive(Store store,OperatorCommandInput command){
  var ids=new TreeSet<Long>();if(command.orderId!=null)ids.add(command.orderId);if(command.report!=null&&command.report.orderIds!=null)ids.addAll(command.report.orderIds);if(command.executionId!=null){HandlingExecution e=HandlingExecution.findById(command.executionId);if(e!=null)ids.add(id(e.handlingOrder));}if(ids.isEmpty())return;
  MissionOperationalState paused=MissionOperationalState.find("store=?1 and handlingOrder.id in ?2 and state<>'ACTIVE'",store,ids).firstResult();if(paused!=null)throw new OperatorFailure(409,"MISSION_PAUSED","Mission suspendue : "+paused.reason+". Reprenez-la avant une nouvelle opération.");
 }
}
