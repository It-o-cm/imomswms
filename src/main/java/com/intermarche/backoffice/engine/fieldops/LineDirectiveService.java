package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.engine.operator.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.*;

/** Stable line identity survives partial picks; instruction acknowledgements are versioned independently. */
@ApplicationScoped @Transactional
public class LineDirectiveService {
 @Inject FieldOperationsService field;@Inject StockCommands commandReceipts;
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="LineDirectiveInput")
 public static class Change {public Long storeId,lineId;public String lineKind,requestKey,expectedRevision,state,instruction,reason;public Integer priority;}
 private void member(BaseHandlingOrder raw,String kind,Long lineId){
  var order=org.hibernate.Hibernate.unproxy(raw,BaseHandlingOrder.class);
  if(lineId==null)throw new BadRequestException("Ligne requise");
  boolean valid="LOT_LINE".equals(kind)&&order instanceof PreparingOrder p&&p.lots.stream().anyMatch(l->l.lines.stream().anyMatch(x->Objects.equals(x.id,lineId)))
   ||"EXPECTED_SUPPLY".equals(kind)&&order instanceof ReplenishmentOrder r&&r.supplies.stream().anyMatch(x->Objects.equals(x.id,lineId));
  if(!valid)throw new NotFoundException("Ligne absente de cette mission");
 }
 private void owner(BaseHandlingOrder order){if(!field.manager()&&(order.assignee==null||!order.assignee.badgeId.equals(field.actor()))&&HandlingExecution.count("handlingOrder=?1 and employee.badgeId=?2 and status in ?3",order,field.actor(),List.of(HandlingExecution.ExecutionStatus.PENDING,HandlingExecution.ExecutionStatus.IN_PROGRESS))==0)throw new ForbiddenException("Mission affectée à un autre préparateur");}
 public static String revision(LineDirective d){return d==null?"0":CommandCanonical.hash("["+d.id+","+d.version+",\""+d.state+"\","+d.priority+","+d.instructionVersion+"]");}
 public static Map<String,Object> view(LineDirective d,boolean acknowledged){var v=new LinkedHashMap<String,Object>();v.put("id",d.id);v.put("lineId",d.lineId);v.put("lineKind",d.lineKind);v.put("state",d.state);v.put("reason",d.deferReason);v.put("priority",d.priority);v.put("instruction",d.instruction);v.put("instructionVersion",d.instructionVersion);v.put("acknowledged",acknowledged);v.put("revision",revision(d));return v;}
 public Set<Long> cancelledLines(List<Long> ids){if(ids.isEmpty())return Set.of();return new HashSet<>(CancelledMissionLine.getEntityManager().createQuery("select c.line.id from CancelledMissionLine c where c.line.id in :ids",Long.class).setParameter("ids",ids).getResultList());}
 public List<LineDirective> forOrders(List<Long> ids){return ids.isEmpty()?List.of():LineDirective.list("handlingOrder.id in ?1 and lineKind='LOT_LINE'",ids);}
 public List<Map<String,Object>> read(Long storeId,Long orderId){Store store=field.store(storeId,false);field.order(store,orderId);return projection(orderId,field.actor());}
 public static List<Map<String,Object>> projection(Long orderId,String actor){
  var directives=LineDirective.<LineDirective>list("handlingOrder.id=?1 order by priority desc,id",orderId);
  var acks=LineInstructionAck.<LineInstructionAck>list("directive.handlingOrder.id=?1 and actor=?2",orderId,actor);
  var acknowledged=new HashSet<String>();for(var a:acks)acknowledged.add(id(a.directive)+":"+a.instructionVersion);
  return directives.stream().map(d->view(d,d.instructionVersion==0||acknowledged.contains(d.id+":"+d.instructionVersion))).toList();
 }
 public Object change(Long orderId,Change c){
  if(c==null)throw new BadRequestException();FieldOperationsService.key(c.requestKey);
  Store store=field.store(c.storeId,true);BaseHandlingOrder order=field.order(store,orderId);member(order,c.lineKind,c.lineId);owner(order);
  if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(order.status))throw new BadRequestException("Mission fermée");
  if("DEFERRED".equals(c.state))FieldOperationsService.text(c.reason,500);
  if(c.priority!=null&&(c.priority<0||c.priority>100))throw new BadRequestException("Priorité de 0 à 100");
  if(c.state!=null&&!Set.of("READY","DEFERRED").contains(c.state))throw new BadRequestException("État de ligne inconnu");
  if(c.instruction!=null&&c.instruction.length()>2000)throw new BadRequestException("Consigne limitée à 2000 caractères");
  if((c.priority!=null||c.instruction!=null)&&!field.manager())throw new ForbiddenException("Priorités et consignes réservées au responsable");
  LineDirective d=LineDirective.find("handlingOrder=?1 and lineKind=?2 and lineId=?3",order,c.lineKind,c.lineId).firstResult();
  String hash=field.hash(c);
  if(d!=null){LineDirectiveChange old=LineDirectiveChange.find("directive=?1 and actor=?2 and requestKey=?3",d,field.actor(),c.requestKey).firstResult();if(old!=null){if(!old.fingerprint.equals(hash))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne une autre modification");return field.decode(old.result,Map.class);}}
  if(!Objects.equals(c.expectedRevision,revision(d)))throw new OperatorFailure(409,"STALE_LINE","La ligne a changé. Actualisez avant de confirmer.");
  if(d==null){d=new LineDirective();d.store=store;d.handlingOrder=order;d.lineId=c.lineId;d.lineKind=c.lineKind;}
  if(c.state!=null){d.state=c.state;d.deferReason="DEFERRED".equals(c.state)?c.reason.trim():null;}if(c.priority!=null)d.priority=c.priority;
  if(c.instruction!=null&&!d.instruction.equals(c.instruction.trim())){d.instruction=c.instruction.trim();d.instructionVersion++;}
  d.persistAndFlush();var result=view(d,d.instructionVersion==0||LineInstructionAck.count("directive=?1 and actor=?2 and instructionVersion=?3",d,field.actor(),d.instructionVersion)>0);
  var receipt=new LineDirectiveChange();receipt.directive=d;receipt.actor=field.actor();receipt.requestKey=c.requestKey;receipt.fingerprint=hash;receipt.result=field.encode(result);receipt.persist();
  var audience=new TreeSet<String>();audience.add(field.actor());if(order.assignee!=null)audience.add(order.assignee.badgeId);
  field.changeNotice(store,c.priority!=null?"PRIORITY_CHANGED":"LINE_CHANGED","line:"+d.id+":"+c.requestKey,"Mission "+order.code,"Ligne "+d.lineId+" · "+("DEFERRED".equals(d.state)?"différée":"à traiter")+" · priorité "+d.priority+(d.instruction.isBlank()?"":" · "+d.instruction),orderId,audience);return commandReceipts.record(store,c.requestKey,hash,"LINE_DIRECTIVE",result);
 }
 public static void requireReady(BaseHandlingOrder order,String kind,Long line){LineDirective d=LineDirective.find("handlingOrder=?1 and lineKind=?2 and lineId=?3",order,kind,line).firstResult();if(d!=null&&"DEFERRED".equals(d.state))throw new OperatorFailure(409,"LINE_DEFERRED","Ligne en attente : "+(d.deferReason==null?"Motif antérieur non renseigné":d.deferReason)+". Reprenez-la avant le prélèvement.");}
 public Object acknowledge(Long storeId,Long orderId,Long directiveId,long version){Store store=field.store(storeId,true);BaseHandlingOrder order=field.order(store,orderId);owner(order);LineDirective d=LineDirective.find("id=?1 and handlingOrder=?2",directiveId,order).firstResult();if(d==null)throw new NotFoundException();if(d.instructionVersion!=version)throw new OperatorFailure(409,"STALE_INSTRUCTION","La consigne a changé. Lisez la nouvelle version.");if(LineInstructionAck.count("directive=?1 and actor=?2 and instructionVersion=?3",d,field.actor(),version)==0){var ack=new LineInstructionAck();ack.directive=d;ack.actor=field.actor();ack.instructionVersion=version;ack.persist();}return view(d,true);}
}
