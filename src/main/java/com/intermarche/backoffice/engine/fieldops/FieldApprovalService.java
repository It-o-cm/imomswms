package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.executing.*;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.*;

/** Approval grants one immutable correction at one observed business revision, never a general permission. */
@ApplicationScoped @Transactional
public class FieldApprovalService {
 @Inject FieldOperationsService field;@Inject OperatorExecutionService operator;
 static final Set<String> CORRECTIONS=Set.of("RETURN_ITEM","MOVE_ITEM","REOPEN_SUPPLY","CORRECT_SHORTAGE");
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="FieldApprovalRequest") public static class Request {public Long storeId;public String requestKey;public OperatorCommandInput command;}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="FieldApprovalDecision") public static class Decision {public Long storeId;public String requestKey,reason;public Boolean approve;}
 Long orderId(Store store,OperatorCommandInput c){Long id=c.orderId;if(c.executionId!=null){HandlingExecution e=HandlingExecution.findById(c.executionId);if(e==null||id!=null&&!id.equals(id(e.handlingOrder)))throw new BadRequestException("Exécution et mission incohérentes");id=id(e.handlingOrder);}if(id==null)throw new BadRequestException("Mission requise pour une correction");field.order(store,id);return id;}
 @SuppressWarnings("unchecked") Map<String,Object> snapshot(Store s,Long order){return (Map<String,Object>)operator.order(s.id,order);}
 Object readCommand(String text){try{return field.decode(text,Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
 Map<String,Object> view(SensitiveApproval a,String actor){var v=new LinkedHashMap<String,Object>();v.put("id",a.id);v.put("incidentId",id(a.incident));v.put("state",a.state);v.put("requester",a.requester);v.put("decidedBy",a.decidedBy);v.put("decisionReason",a.decisionReason);v.put("expiresAt",a.expiresAt);v.put("createdAt",a.createdAt);v.put("command",readCommand(a.commandPayload));v.put("expired",!a.expiresAt.isAfter(DateTimeProvider.now()));v.put("canDecide",field.manager()&&a.state.equals("PENDING")&&!a.requester.equals(actor)&&a.expiresAt.isAfter(DateTimeProvider.now()));v.put("canApply",a.state.equals("APPROVED")&&a.requester.equals(actor)&&a.expiresAt.isAfter(DateTimeProvider.now()));return v;}
 public Object request(Long incidentId,Request r){
  if(r==null||r.command==null)throw new BadRequestException();FieldOperationsService.key(r.requestKey);Store s=field.store(r.storeId,true);FieldIncident i=field.incident(s,incidentId);OperatorCommandInput c=r.command;
  if(!CORRECTIONS.contains(c.action==null?"":c.action)||!s.id.equals(c.storeId)||c.report!=null||c.measurement!=null||c.scanId!=null)throw new BadRequestException("Correction ciblée requise");FieldOperationsService.key(c.requestKey);
  String fingerprint=field.hash(c);SensitiveApproval old=SensitiveApproval.find("store=?1 and requester=?2 and requestKey=?3",s,field.actor(),r.requestKey).firstResult();if(old!=null){if(!id(old.incident).equals(i.id)||!old.commandHash.equals(fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence concerne une autre correction");return view(old,field.actor());}
  var policy=OperationalPolicyVersions.active(s,"ApprovalConstraints");boolean allowed=false;for(var action:policy.path("allowedActions"))if(action.asText().equals(c.action))allowed=true;if(!allowed)throw new OperatorFailure(409,"CORRECTION_POLICY_BLOCKED","Cette correction est désactivée par la politique du magasin");Long order=orderId(s,c);if(i.handlingOrder!=null&&!id(i.handlingOrder).equals(order))throw new BadRequestException("Correction hors de ce dossier");var current=snapshot(s,order);String revision=(String)current.get("revision");if(!Integer.valueOf(2).equals(c.contractVersion)||!revision.equals(c.expectedRevision))throw new OperatorFailure(409,"STALE_REVISION","Actualisez la mission avant de demander cette correction");
  if(!((Collection<?>)current.get("allowedActions")).contains(c.action))throw new OperatorFailure(409,"CORRECTION_PATH_CHANGED","Ce geste ne peut plus corriger cette situation. Consultez le transfert de garde ou le retour d’expédition.");
  var a=new SensitiveApproval();a.store=s;a.incident=i;a.requester=field.actor();a.requestKey=r.requestKey;a.commandHash=fingerprint;a.commandPayload=field.encode(c);a.orderRevision=revision;a.expiresAt=DateTimeProvider.now().plusMinutes(policy.path("expiryMinutes").asInt(30));a.persistAndFlush();field.history(i,field.hash(Arrays.asList("approval",r.requestKey)),fingerprint,"APPROVAL_REQUESTED","Correction demandée : "+c.action);field.notify(i,field.hash(Arrays.asList("approval",r.requestKey)),"Validation d’une correction requise : "+c.action);return view(a,field.actor());
 }
 public Object list(Long storeId,Long incidentId){Store s=field.store(storeId,false);var i=field.incident(s,incidentId);String actor=field.actor();return SensitiveApproval.<SensitiveApproval>find("incident=?1 order by id desc",i).range(0,49).list().stream().map(a->view(a,actor)).toList();}
 SensitiveApproval scoped(Store s,Long id){var a=SensitiveApproval.<SensitiveApproval>find("store=?1 and id=?2",s,id).firstResult();if(a==null)throw new NotFoundException();field.incident(s,id(a.incident));return a;}
 public Object decide(Long id,Decision d){
  field.requireManager();if(d==null||d.approve==null)throw new BadRequestException();FieldOperationsService.key(d.requestKey);FieldOperationsService.text(d.reason,500);Store s=field.store(d.storeId,true);var a=scoped(s,id);String fingerprint=field.hash(d);
  var previous=IncidentHistory.<IncidentHistory>find("incident=?1 and eventKey=?2",a.incident,d.requestKey).firstResult();if(previous!=null){if(!previous.fingerprint.equals(fingerprint)||!previous.actor.equals(field.actor()))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette décision a changé");return view(a,field.actor());}
  if(!a.state.equals("PENDING"))throw new OperatorFailure(409,"APPROVAL_DECIDED","Cette demande a déjà été décidée");if(a.requester.equals(field.actor()))throw new ForbiddenException("Un autre responsable doit approuver votre correction");if(!a.expiresAt.isAfter(DateTimeProvider.now()))throw new OperatorFailure(409,"APPROVAL_EXPIRED","La demande est périmée. Actualisez la mission et demandez une nouvelle validation");
  a.state=d.approve?"APPROVED":"REJECTED";a.decidedBy=field.actor();a.decisionReason=d.reason.trim();field.history(a.incident,d.requestKey,fingerprint,a.state,"Correction #"+a.id+" : "+d.reason);field.notify(a.incident,d.requestKey,"Correction #"+a.id+" "+a.state+" · "+d.reason);field.flush();return view(a,field.actor());
 }
 public Object apply(Long storeId,Long id){
  Store s=field.store(storeId,true);var a=scoped(s,id);if(!a.requester.equals(field.actor()))throw new ForbiddenException("Seul l’auteur du geste peut appliquer cette validation");if(a.state.equals("APPLIED"))return readCommand(a.result);if(!a.state.equals("APPROVED")||!a.expiresAt.isAfter(DateTimeProvider.now()))throw new OperatorFailure(409,"APPROVAL_UNAVAILABLE","Approbation absente ou périmée");
  OperatorCommandInput c;try{c=field.decode(a.commandPayload,OperatorCommandInput.class);}catch(Exception e){throw new IllegalStateException(e);}if(!field.hash(c).equals(a.commandHash))throw new IllegalStateException("Approval integrity failure");Long order=orderId(s,c);if(!a.orderRevision.equals(snapshot(s,order).get("revision")))throw new OperatorFailure(409,"APPROVAL_STALE","La mission ou les bacs ont changé. Demandez une nouvelle validation");
  String result=operator.apply(c);a.state="APPLIED";a.result=result;a.appliedRequestKey=c.requestKey;field.history(a.incident,"apply-"+a.id,field.hash(Arrays.asList(a.id,c.requestKey)),"CORRECTION_APPLIED","Correction #"+a.id+" confirmée une fois");field.notify(a.incident,"apply-"+a.id,"Correction #"+a.id+" appliquée");field.flush();return readCommand(result);
 }
}
