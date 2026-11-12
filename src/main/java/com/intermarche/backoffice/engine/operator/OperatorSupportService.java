package com.intermarche.backoffice.engine.operator;

import com.intermarche.backoffice.domain.operator.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.ws.rs.*;
import javax.sql.DataSource;
import java.util.*;

/** Session state does not change missions; notices are committed with the operation that produced them. */
@ApplicationScoped
public class OperatorSupportService {
 @Inject OperatorContext context;
 @Inject StoreOperationsResource scopes;
 @Inject EntityManager em;
 @Inject ObjectMapper json;
 @Inject DataSource database;
 @Inject com.intermarche.backoffice.engine.semantic.ProductSemanticIndex semantic;
 String actor(){return context.account().getUsername();}
 Store store(Long id){context.account();return scopes.scope(id);}
 Store locked(Long id){Store store=store(id);em.lock(store,LockModeType.PESSIMISTIC_WRITE);return store;}
 String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 void manager(){var account=context.account();if(Collections.disjoint(context.roles(account),Set.of("ADMIN","MANAGER")))throw new ForbiddenException();}
 public static Map<String,Object> policy(){return Map.of("localPersistence",false,"purgeOnLogout",true,"restoreBetweenLaunches",false,"preferencesServerSide",true,"eventsServerSide",true,"diagnosticRetentionDays",14,"noticeAutomaticDeletion",false);}
 public Object session(Long id){Store s=store(id);OperatorProfile p=OperatorProfile.find("store=?1 and principal=?2",s,actor()).firstResult();return sessionView(s,p);}
 Map<String,Object> sessionView(Store s,OperatorProfile p){return Map.of("consultationPolicy",com.intermarche.backoffice.engine.fieldops.OperationalPolicyVersions.active(s,"ConsultationConstraints"),"state",p==null?"AVAILABLE":p.state,"language",p==null?"fr":p.language,"textPercent",p==null?100:p.textPercent,"sound",p==null||p.sound,"vibration",p==null||p.vibration,"policy",policy());}
 public static class SessionInput {public Long storeId;public String state,language;public Integer textPercent;public Boolean sound,vibration;}
 public Object session(SessionInput input){
  if(input==null)throw new BadRequestException();
  if(input.state!=null && !Set.of("AVAILABLE","PAUSED","FINISHED").contains(input.state) || input.language!=null && !Set.of("fr","en").contains(input.language) || input.textPercent!=null && !Set.of(100,115,130).contains(input.textPercent))throw new OperatorFailure(400,"INVALID_PREFERENCE","Préférence ou état non autorisé.");
  Store s=locked(input.storeId);String actor=actor();OperatorProfile p=OperatorProfile.find("store=?1 and principal=?2",s,actor).firstResult();
  if(p==null){p=new OperatorProfile();p.store=s;p.principal=actor;}
  if(input.state!=null)p.state=input.state;if(input.language!=null)p.language=input.language;if(input.textPercent!=null)p.textPercent=input.textPercent;if(input.sound!=null)p.sound=input.sound;if(input.vibration!=null)p.vibration=input.vibration;p.persistAndFlush();if(input.state!=null)OperatorShiftService.serviceState(s,actor,input.state);return sessionView(s,p);
 }
 OperatorNotice publish(Store s,String key,String fingerprint,String author,String category,String label,String message,String urgency,boolean mandatory,Long orderId,Collection<String> audience){
  em.lock(s,LockModeType.PESSIMISTIC_WRITE);
  OperatorNotice old=OperatorNotice.find("store=?1 and eventKey=?2",s,key).firstResult();
  if(old!=null){if(!old.fingerprint.equals(fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne une autre consigne.");return old;}
  OperatorStream stream=OperatorStream.find("store",s).firstResult();if(stream==null){stream=new OperatorStream();stream.store=s;stream.persist();}
  OperatorNotice n=new OperatorNotice();n.store=s;n.cursor=++stream.cursor;n.eventKey=key;n.fingerprint=fingerprint;n.author=author;n.category=category;n.label=label;n.message=message;n.urgency=urgency;n.mandatory=mandatory;n.orderId=orderId;n.persist();
  for(String principal:new TreeSet<>(audience)){OperatorNoticeRecipient recipient=new OperatorNoticeRecipient();recipient.notice=n;recipient.principal=principal;recipient.persist();}
  em.flush();return n;
 }
 public void fieldChangeNotice(Store store,String type,String key,String label,String message,Long orderId,Collection<String> audience){var n=publish(store,key,CommandCanonical.hash(encode(Arrays.asList(type,label,message,orderId,audience))),actor(),"INSTRUCTION",label.length()>120?label.substring(0,120):label,message.length()>1000?message.substring(0,1000):message,"PRIORITY_CHANGED".equals(type)?"URGENT":"NORMAL",false,orderId,audience);n.eventType=type;}
 /**
  * Publishes a notice raised by a system rather than by an operator account, such as a Drive arrival announced by
  * commerce.
  *
  * @param store the store
  * @param type the event type
  * @param key the idempotency key of the notice
  * @param author the system that raised it
  * @param label the title
  * @param message the text
  * @param orderId the related order, or null
  * @param audience the badges notified
  */
 public void systemNotice(Store store,String type,String key,String author,String label,String message,Long orderId,Collection<String> audience){var n=publish(store,key,CommandCanonical.hash(encode(Arrays.asList(type,label,message,orderId,audience))),author,"INSTRUCTION",label.length()>120?label.substring(0,120):label,message.length()>1000?message.substring(0,1000):message,"URGENT",false,orderId,audience);n.eventType=type;}
 public void fieldNotice(Store store,String key,String label,String message,Long orderId,Collection<String> audience){publish(store,key,CommandCanonical.hash(encode(Arrays.asList(label,message,orderId,audience))),actor(),"INSTRUCTION",label,message.length()>1000?message.substring(0,1000):message,"NORMAL",false,orderId,audience);}
 public void fieldCaseNotice(Store store,String key,String label,String message,Long orderId,Long caseId,Collection<String> audience){var n=publish(store,key,CommandCanonical.hash(encode(Arrays.asList(label,message,orderId,caseId,audience))),actor(),"INSTRUCTION",label,message.length()>1000?message.substring(0,1000):message,"NORMAL",false,orderId,audience);n.fieldCaseId=caseId;n.eventType="CASE_UPDATED";}
 public void confirmed(com.intermarche.backoffice.engine.executing.OperatorCommandInput input,String result){
  Store s=locked(input.storeId);String principal=actor();var audience=new TreeSet<String>();audience.add(principal);
  Employee.<Employee>list("store=?1 and active=true and role=?2",s,Employee.EmployeeRole.MANAGER).forEach(e->audience.add(e.badgeId));
  Long orderId=input.orderId;try{var orders=json.readTree(result).path("orders");if(orderId==null && !orders.isEmpty())orderId=orders.get(0).path("id").asLong();}catch(Exception e){throw new IllegalStateException(e);}
  String key="command:"+principal+":"+input.requestKey;
  publish(s,key,CommandCanonical.hash(result),principal,"COMMAND_CONFIRMED","Opération confirmée",input.action,"NORMAL",false,orderId,audience);
 }
 public static class InstructionInput {public Long storeId,employeeId,orderId;public String requestKey,label,message;public boolean urgent,mandatory;}
 public Object instruction(InstructionInput input){
  manager();if(input==null || input.requestKey==null || !input.requestKey.matches("[a-zA-Z0-9-]{8,64}") || input.label==null || input.label.isBlank() || input.label.length()>120 || input.message==null || input.message.isBlank() || input.message.length()>1000)throw new OperatorFailure(400,"INVALID_INSTRUCTION","Titre, consigne et référence de confirmation requis.");
  Store s=locked(input.storeId);if(input.orderId!=null){BaseHandlingOrder order=BaseHandlingOrder.findById(input.orderId);if(order==null || !org.hibernate.Hibernate.unproxy(order.store,Store.class).id.equals(s.id))throw new NotFoundException();}
  List<Employee> recipients=input.employeeId==null?Employee.list("store=?1 and active=true",s):Employee.list("store=?1 and id=?2 and active=true",s,input.employeeId);
  recipients=recipients.stream().filter(e->Set.of(Employee.EmployeeRole.ADMIN,Employee.EmployeeRole.MANAGER,Employee.EmployeeRole.PICKER).contains(e.role)).toList();
  if(recipients.isEmpty())throw new OperatorFailure(400,"INVALID_AUDIENCE","Aucun employé actif dans cette audience.");
  var n=publish(s,"instruction:"+actor()+":"+input.requestKey,CommandCanonical.hash(encode(input)),actor(),"INSTRUCTION",input.label.trim(),input.message.trim(),input.urgent?"URGENT":"NORMAL",input.mandatory,input.orderId,recipients.stream().map(e->e.badgeId).toList());
  return noticeView(n,null,true);
 }
 Map<String,Object> noticeView(OperatorNotice n,OperatorNoticeRecipient recipient,boolean receipts){
  var v=new LinkedHashMap<String,Object>();v.put("id",n.id);v.put("cursor",n.cursor);v.put("category",n.category);v.put("eventType",n.eventType);v.put("label",n.label);v.put("message",n.message);v.put("urgency",n.urgency);v.put("mandatory",n.mandatory);v.put("orderId",n.orderId);v.put("fieldCaseId",n.fieldCaseId);v.put("createdAt",n.createdAt);v.put("receivedAt",recipient==null?null:recipient.receivedAt);v.put("acknowledgedAt",recipient==null?null:recipient.acknowledgedAt);
  if(receipts)v.put("recipients",OperatorNoticeRecipient.<OperatorNoticeRecipient>list("notice=?1 order by principal",n).stream().map(r->{var a=new LinkedHashMap<String,Object>();a.put("principal",r.principal);a.put("receivedAt",r.receivedAt);a.put("acknowledgedAt",r.acknowledgedAt);return a;}).toList());return v;
 }
 public Object feed(Long id,long after,int limit,String category){
  Store s=store(id);if(after<0 || limit<1 || limit>100 || category!=null && !Set.of("INSTRUCTION","COMMAND_CONFIRMED").contains(category))throw new BadRequestException();
  boolean masked=context.roles(context.account()).contains("PICKER")&&!com.intermarche.backoffice.engine.fieldops.OperationalPolicyVersions.active(s,"ConsultationConstraints").path("showCommandHistory").asBoolean();
  if(masked&&"COMMAND_CONFIRMED".equals(category))return Map.of("items",List.of(),"nextCursor",after,"hasMore",false,"historyMasked",true);
  if(masked&&category==null)category="INSTRUCTION";
  List<OperatorNoticeRecipient> rows=OperatorNoticeRecipient.find("select r from OperatorNoticeRecipient r join fetch r.notice n where n.store=?1 and r.principal=?2 and n.cursor>?3"+(category==null?"":" and n.category=?4")+" order by n.cursor",category==null?new Object[]{s,actor(),after}:new Object[]{s,actor(),after,category}).range(0,limit).list();
  boolean more=rows.size()>limit;var page=rows.stream().limit(limit).toList();long cursor=page.isEmpty()?after:page.getLast().notice.cursor;
  return Map.of("items",page.stream().map(r->noticeView(r.notice,r,false)).toList(),"nextCursor",cursor,"hasMore",more);
 }
 public Object instructions(Long id,long after,int limit){manager();Store s=store(id);if(after<0||limit<1||limit>100)throw new BadRequestException();List<OperatorNotice> rows=OperatorNotice.find("store=?1 and category='INSTRUCTION' and cursor>?2 order by cursor",s,after).range(0,limit).list();var page=rows.stream().limit(limit).toList();return Map.of("items",page.stream().map(n->noticeView(n,null,true)).toList(),"nextCursor",page.isEmpty()?after:page.getLast().cursor,"hasMore",rows.size()>limit);}
 public Object acknowledge(Long storeId,Long noticeId,boolean acknowledge){
  Store s=locked(storeId);OperatorNoticeRecipient r=OperatorNoticeRecipient.find("notice.store=?1 and notice.id=?2 and principal=?3",s,noticeId,actor()).firstResult();if(r==null)throw new NotFoundException();
  if(r.receivedAt==null)r.receivedAt=DateTimeProvider.now();if(acknowledge && r.acknowledgedAt==null)r.acknowledgedAt=DateTimeProvider.now();em.flush();return noticeView(r.notice,r,false);
 }
 public Object health(){
  boolean db;try(var c=database.getConnection()){db=c.isValid(2);}catch(Exception e){db=false;}
  String semanticState=String.valueOf(semantic.status().get("state"));
  return Map.of("state",db?"UP":"DEGRADED","dependencies",Map.of("database",db?"UP":"DOWN","operatorEvents",db?"UP":"DOWN","semanticSearch",semanticState),"nextActions",db?List.of("CONTINUE"):List.of("KEEP_PENDING_COMMAND","RETRY_CONNECTION"));
 }
 public static class DiagnosticInput {public Long storeId;public String requestKey,appVersion,osVersion,screen,errorCode,relatedTrackingId;public Integer sdk,httpStatus,pendingCount;}
 public Object diagnostic(DiagnosticInput input){
  if(input==null || input.requestKey==null || !input.requestKey.matches("[a-fA-F0-9-]{36}") || input.appVersion!=null && !input.appVersion.matches("[0-9]+(\\.[0-9]+){0,3}") || input.osVersion!=null && !input.osVersion.matches("[0-9]+(\\.[0-9]+){0,3}") || input.sdk!=null && (input.sdk<1||input.sdk>100) || input.pendingCount!=null && (input.pendingCount<0||input.pendingCount>1000) || input.httpStatus!=null && input.httpStatus!=0 && (input.httpStatus<400||input.httpStatus>599) || input.relatedTrackingId!=null && !input.relatedTrackingId.matches("[a-fA-F0-9-]{36}"))throw new OperatorFailure(400,"INVALID_DIAGNOSTIC","Diagnostic technique invalide.");
  if(input.screen!=null && !Set.of("TERMINAL","MISSIONS","EXECUTION","PREPARATION","BUFFERS","SHIPPING","UNKNOWN").contains(input.screen) || input.errorCode!=null && !Set.of("NETWORK_UNCERTAIN","SERVICE_UNAVAILABLE","RESOURCE_CONFLICT","STALE_REVISION","UNKNOWN").contains(input.errorCode))throw new BadRequestException();
  Store s=locked(input.storeId);String author=actor();String payload=encode(input);String fingerprint=CommandCanonical.hash(payload);
  // Unknown input properties are never copied. Only this typed allowlist is stored.
  OperatorDiagnostic old=OperatorDiagnostic.find("store=?1 and principal=?2 and requestKey=?3",s,author,input.requestKey).firstResult();if(old!=null){if(!old.fingerprint.equals(fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne un autre diagnostic.");return diagnosticView(old,false);}
  if(OperatorDiagnostic.count("store=?1 and principal=?2 and createdAt>?3",s,author,DateTimeProvider.now().minusHours(1))>=10)throw new OperatorFailure(429,"DIAGNOSTIC_LIMIT","Dix diagnostics par heure maximum. Conservez l’identifiant existant.");
  OperatorDiagnostic d=new OperatorDiagnostic();d.store=s;d.principal=author;d.trackingId=UUID.randomUUID().toString();d.requestKey=input.requestKey;d.fingerprint=fingerprint;d.payload=payload;d.persistAndFlush();var audience=Employee.<Employee>list("store=?1 and active=true and role=?2",s,Employee.EmployeeRole.MANAGER).stream().map(e->e.badgeId).toList();if(!audience.isEmpty())fieldNotice(s,"diagnostic:"+d.trackingId,"Diagnostic opérateur reçu","Référence de suivi : "+d.trackingId,null,audience);return diagnosticView(d,false);
 }
 Map<String,Object> diagnosticView(OperatorDiagnostic d,boolean details){var v=new LinkedHashMap<String,Object>();v.put("id",d.id);v.put("trackingId",d.trackingId);v.put("createdAt",d.createdAt);v.put("nextActions",List.of("CHECK_RECEIPT","RETRY_CONNECTION"));if(details){v.put("principal",d.principal);try{v.put("technical",json.readTree(d.payload));}catch(Exception e){throw new IllegalStateException(e);}}return v;}
 public Object diagnostics(Long id,int limit){manager();Store s=store(id);if(limit<1||limit>100)throw new BadRequestException();return OperatorDiagnostic.<OperatorDiagnostic>find("store=?1 order by id desc",s).range(0,limit-1).list().stream().map(d->diagnosticView(d,true)).toList();}
 @io.quarkus.scheduler.Scheduled(every="24h") @jakarta.transaction.Transactional
 void purgeDiagnostics(){OperatorDiagnostic.delete("createdAt < ?1",DateTimeProvider.now().minusDays(14));}
}
