package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;

import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.executing.PickingShortage;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.operator.*;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.util.*;

/** Store-scoped field cases. Reporting and decisions never implicitly correct a quantity. */
@ApplicationScoped @Transactional
public class FieldOperationsService {
 @Inject OperatorContext context;@Inject StoreOperationsResource stores;@Inject EntityManager em;@Inject ObjectMapper json;
 @Inject OperatorSupportService notices;
 public static final Set<String> KINDS=Set.of("MISSING","DAMAGED","SAFETY","REPLENISHMENT","EMPTY_LOCATION","STOCK_DISCREPANCY","OTHER");
 String actor(){return context.account().getUsername();}
 boolean manager(){return !Collections.disjoint(context.roles(context.account()),Set.of("ADMIN","MANAGER"));}
 void requireManager(){if(!manager())throw new ForbiddenException();}
 Store store(Long id,boolean lock){if(id==null)throw new BadRequestException("Magasin requis");context.account();Store s=stores.scope(id);if(lock)em.createNativeQuery("select id from stores where id=?1 for update").setParameter(1,s.id).getSingleResult();return s;}
 static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new BadRequestException("Texte requis, "+max+" caractères maximum");}
 static void key(String value){if(value==null||!value.matches("[a-zA-Z0-9-]{8,64}"))throw new BadRequestException("Référence de confirmation requise");}
 String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 void flush(){em.flush();}
 <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
 void notice(Store store,String key,String label,String message,Long orderId,Collection<String> audience){if(key.startsWith("cancellation:"))changeNotice(store,"ORDER_CANCELLED",key,label,message,orderId,audience);else if(key.startsWith("handover-accepted:")||key.startsWith("cart-handover:"))changeNotice(store,"CUSTODY_CHANGED",key,label,message,orderId,audience);else notices.fieldNotice(store,key,label,message,orderId,audience);}
 void changeNotice(Store store,String type,String key,String label,String message,Long orderId,Collection<String> audience){notices.fieldChangeNotice(store,type,key,label,message,orderId,audience);}
 String hash(Object value){return CommandCanonical.hash(encode(value));}
 BaseHandlingOrder order(Store s,Long id){BaseHandlingOrder o=BaseHandlingOrder.findById(id);if(o==null||!id(o.store).equals(s.id))throw new NotFoundException();return o;}
 FieldIncident incident(Store s,Long id){FieldIncident i=FieldIncident.find("id=?1 and store=?2",id,s).firstResult();if(i==null||!manager()&&!i.reporter.equals(actor())&&!Objects.equals(i.owner,actor()))throw new NotFoundException();return i;}
 String revision(FieldIncident i){return hash(Arrays.asList(i.id,i.version,i.state,i.owner,i.replenishmentId));}
 void expected(FieldIncident i,String revision){if(revision==null||!revision.equals(revision(i)))throw new OperatorFailure(409,"STALE_CASE","Le dossier a changé. Actualisez avant de décider.");}
 void history(FieldIncident i,String eventKey,String fingerprint,String action,String detail){var h=new IncidentHistory();h.incident=i;h.eventKey=eventKey;h.fingerprint=fingerprint;h.actor=actor();h.action=action;h.detail=detail;h.persist();}
 void notify(FieldIncident i,String eventKey,String message){var audience=new TreeSet<String>();audience.add(i.reporter);if(i.owner!=null)audience.add(i.owner);Employee.<Employee>list("store=?1 and active=true and role=?2",i.store,Employee.EmployeeRole.MANAGER).forEach(e->audience.add(e.badgeId));notices.fieldCaseNotice(i.store,"case:"+i.id+":"+eventKey,"Incident #"+i.id+" · "+i.title,message,i.handlingOrder==null?null:id(i.handlingOrder),i.id,audience);}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="FieldIncidentReport") public static class Report {public Long storeId,orderId,productId,locationId,bufferId,shortageId;public String requestKey,kind,title,comment,observedAddress;public BigDecimal requestedQuantity,announcedQuantity,observedQuantity;}
 public Object locationStock(Long storeId,Long productId,Long locationId){Store s=store(storeId,false);Product product=Product.findById(productId);BaseLocation location=BaseLocation.findById(locationId);if(product==null||location==null)throw new NotFoundException();var buildings=BaseLocation.findBuildingsByLocations(List.of(location));if(buildings.size()!=1||!Objects.equals(Building.findStoreByBuildingId(buildings.getFirst().id).id,s.id))throw new ForbiddenException();if(location.product!=null&&!id(location.product).equals(productId))throw new BadRequestException("Produit absent de cet emplacement");
  if(!manager())for(var line:com.intermarche.backoffice.domain.inventory.InventoryCountLine.<com.intermarche.backoffice.domain.inventory.InventoryCountLine>list("session.handlingOrder.store=?1 and session.handlingOrder.blind=true and session.state in ?2 and session.handlingOrder.status not in ?3",s,List.of("COUNTING","RECOUNT_REQUIRED","SUBMITTED","DISPUTED","APPROVED"),List.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED)))if(line.location!=null&&id(line.location).equals(locationId)||decode(line.sourceLocationIds,List.class).stream().anyMatch(v->v instanceof Number n&&n.longValue()==locationId))throw new OperatorFailure(409,"BLIND_INVENTORY","Le stock annoncé reste masqué pendant le comptage aveugle");

  BigDecimal known=em.createQuery("select coalesce(sum(m.quantity),0) from StockMovement m where m.account.store=?1 and m.account.product=?2 and m.location=?3 and m.effectiveAt<=?4",BigDecimal.class).setParameter(1,s).setParameter(2,product).setParameter(3,location).setParameter(4,DateTimeProvider.now()).getSingleResult();
  return Map.of("productId",productId,"locationId",locationId,"address",location.locationCode,"announcedQuantity",known,"unit",com.intermarche.backoffice.engine.catalog.ProductInformationService.unit(product),"productType",product.productType.name(),"observedAt",DateTimeProvider.now());
 }
 public Object report(Report r){
  if(r==null)throw new BadRequestException();key(r.requestKey);if(!KINDS.contains(r.kind==null?"":r.kind))throw new BadRequestException("Motif inconnu");text(r.title,120);text(r.comment,2000);if(r.observedAddress!=null&&r.observedAddress.length()>300)throw new BadRequestException("Adresse trop longue");
  Store s=store(r.storeId,true);String fingerprint=hash(r);FieldIncident old=FieldIncident.find("store=?1 and reporter=?2 and requestKey=?3",s,actor(),r.requestKey).firstResult();if(old!=null){if(!old.fingerprint.equals(fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne un autre signalement.");return detail(s,old);}
  if(FieldIncident.count("store=?1 and reporter=?2 and createdAt>?3",s,actor(),DateTimeProvider.now().minusHours(1))>=100)throw new OperatorFailure(429,"REPORT_LIMIT","Cent signalements par heure maximum.");
  var i=new FieldIncident();i.store=s;i.reporter=actor();i.requestKey=r.requestKey;i.fingerprint=fingerprint;i.kind=r.kind;i.title=r.title.trim();i.comment=r.comment.trim();i.observedAddress=r.observedAddress;
  if(r.orderId!=null)i.handlingOrder=order(s,r.orderId);
  if(r.productId!=null){i.product=Product.findById(r.productId);if(i.product==null||!i.product.active)throw new NotFoundException();}
  if(r.locationId!=null){i.location=BaseLocation.findById(r.locationId);if(i.location==null)throw new NotFoundException();var buildings=BaseLocation.findBuildingsByLocations(List.of(i.location));if(buildings.size()!=1||!Building.findStoreByBuildingId(buildings.getFirst().id).id.equals(s.id))throw new ForbiddenException("Emplacement hors du magasin");if(i.product!=null&&i.location.product!=null&&!id(i.location.product).equals(id(i.product)))throw new BadRequestException("Produit et emplacement incohérents");}
  if(r.bufferId!=null){i.buffer=Buffer.findById(r.bufferId);if(i.buffer==null||i.buffer.building==null||!Building.findStoreByBuildingId(id(i.buffer.building)).id.equals(s.id))throw new NotFoundException();}
  if(r.shortageId!=null){PickingShortage shortage=PickingShortage.findById(r.shortageId);if(shortage==null||!shortage.handlingOrder.store.id.equals(s.id)||i.handlingOrder!=null&&!id(shortage.handlingOrder).equals(id(i.handlingOrder))||i.product!=null&&!id(shortage.requestedProduct).equals(id(i.product)))throw new BadRequestException("Manquant hors de ce contexte");i.shortageId=shortage.id;i.handlingOrder=shortage.handlingOrder;i.product=shortage.requestedProduct;}
  if(i.handlingOrder==null&&i.product==null)throw new BadRequestException("Précisez la mission ou le produit concerné");
  if(r.requestedQuantity!=null){if(r.requestedQuantity.signum()<=0||r.requestedQuantity.precision()>19||r.requestedQuantity.scale()>3||r.requestedQuantity.precision()-r.requestedQuantity.scale()>13||i.product!=null&&i.product.productType==ProductType.UNIT&&r.requestedQuantity.stripTrailingZeros().scale()>0)throw new BadRequestException("Quantité demandée invalide");i.requestedQuantity=r.requestedQuantity;}
  if(i.kind.equals("REPLENISHMENT")&&(i.product==null||i.requestedQuantity==null))throw new BadRequestException("Produit et quantité requis pour une demande de réapprovisionnement");
  if(Set.of("EMPTY_LOCATION","STOCK_DISCREPANCY").contains(i.kind)){
   BigDecimal observed=r.observedQuantity;
   if(i.product==null||i.location==null)throw new BadRequestException("Produit et emplacement requis pour ce constat");
   if("EMPTY_LOCATION".equals(i.kind)){if(observed!=null&&observed.signum()!=0)throw new BadRequestException("Un emplacement vide contient zéro produit");observed=BigDecimal.ZERO;}
   if(observed==null||"STOCK_DISCREPANCY".equals(i.kind)&&r.announcedQuantity==null)throw new BadRequestException("Quantités annoncée et constatée requises");
   for(var q:Arrays.asList(r.announcedQuantity,observed))if(q!=null&&(q.precision()>19||q.scale()>6||q.precision()-q.scale()>13))throw new BadRequestException("Quantité incohérente avec l’unité du produit");
   if(observed.scale()>3||i.product.productType==ProductType.UNIT&&observed.stripTrailingZeros().scale()>0)throw new BadRequestException("Quantité constatée incohérente avec l’unité du produit");
   if(observed.signum()<0)throw new BadRequestException("Le stock constaté ne peut pas être négatif");
   if("STOCK_DISCREPANCY".equals(i.kind)&&observed.compareTo(r.announcedQuantity)==0)throw new BadRequestException("Les quantités sont identiques : aucun écart à signaler");
   i.announcedQuantity=r.announcedQuantity;i.observedQuantity=observed;
  }else if(r.announcedQuantity!=null||r.observedQuantity!=null)throw new BadRequestException("Quantités de constat réservées aux anomalies d’emplacement");
  i.persistAndFlush();history(i,r.requestKey,fingerprint,"REPORTED",i.comment);notify(i,r.requestKey,"Signalement enregistré : "+i.comment);em.flush();return detail(s,i);
 }
 Map<String,Object> summary(FieldIncident i,boolean canManage){var v=new LinkedHashMap<String,Object>();v.put("id",i.id);v.put("revision",revision(i));v.put("state",i.state);v.put("kind",i.kind);v.put("title",i.title);v.put("comment",i.comment);v.put("reporter",i.reporter);v.put("owner",i.owner);v.put("createdAt",i.createdAt);v.put("resolvedAt",i.resolvedAt);v.put("observedAddress",i.observedAddress);v.put("orderId",i.handlingOrder==null?null:id(i.handlingOrder));v.put("orderCode",i.handlingOrder==null?null:i.handlingOrder.code);v.put("product",i.product==null?null:Map.of("id",id(i.product),"name",i.product.name,"ean",i.product.ean,"unit",com.intermarche.backoffice.engine.catalog.ProductInformationService.unit(i.product)));v.put("locationId",i.location==null?null:id(i.location));v.put("bufferId",i.buffer==null?null:id(i.buffer));v.put("shortageId",i.shortageId);v.put("replenishmentId",i.replenishmentId);v.put("linkedHandlingOrderId",i.linkedHandlingOrderId);v.put("announcedQuantity",i.announcedQuantity);v.put("observedQuantity",i.observedQuantity);v.put("requestedQuantity",i.requestedQuantity==null?null:i.requestedQuantity.toPlainString());var resolution=new LinkedHashMap<String,Object>();resolution.put("kind",i.outcome==null?i.state:i.outcome);resolution.put("nextAction",i.nextAction);resolution.put("remainingQuantity",i.remainingQuantity);resolution.put("replacement",i.replacementProduct==null?null:Map.of("id",id(i.replacementProduct),"name",i.replacementProduct.name,"ean",i.replacementProduct.ean));v.put("resolution",resolution);v.put("canManage",canManage);return v;}
 Object detail(Store s,FieldIncident i){var v=summary(i,manager());
  if(i.replenishmentId!=null){var d=com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition.<com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition>find("select d from ReplenishmentDefinition d left join fetch d.handlingOrder where d.id=?1 and d.store=?2",i.replenishmentId,s).firstResult();if(d!=null){var linked=new LinkedHashMap<String,Object>();linked.put("id",d.id);linked.put("code",d.code);linked.put("handlingOrderId",d.handlingOrder==null?null:d.handlingOrder.id);linked.put("handlingOrderCode",d.handlingOrder==null?null:d.handlingOrder.code);linked.put("state",d.handlingOrder==null?"TO_ALLOCATE":d.handlingOrder.status);v.put("replenishment",linked);}}
 v.put("history",IncidentHistory.<IncidentHistory>find("incident=?1 order by id desc",i).range(0,99).list().stream().map(h->Map.of("id",h.id,"action",h.action,"actor",h.actor,"detail",h.detail,"createdAt",h.createdAt)).toList());v.put("attachments",em.createQuery("select a.id,a.filename,a.mediaType,a.width,a.height,a.sha256 from IncidentAttachment a where a.incident=?1 order by a.id",Object[].class).setParameter(1,i).setMaxResults(5).getResultList().stream().map(a->Map.of("id",a[0],"filename",a[1],"mediaType",a[2],"width",a[3],"height",a[4],"sha256",a[5])).toList());return v;}
 public Object get(Long storeId,Long id){Store s=store(storeId,false);return detail(s,incident(s,id));}
 public Object list(Long storeId,String state,String kind,Long orderId,Long productId,String query,long before,int limit){
  Store s=store(storeId,false);if(before<0||limit<1||limit>100||state!=null&&!state.isBlank()&&!Set.of("OPEN","OWNED","WAITING","RESOLVED","REJECTED").contains(state)||kind!=null&&!kind.isBlank()&&!KINDS.contains(kind)||query!=null&&query.length()>120)throw new BadRequestException();
  String where="i.store=?1";var args=new ArrayList<Object>();args.add(s);
  boolean canManage=manager();if(!canManage){where+=" and (i.reporter=?2 or i.owner=?2)";args.add(actor());}
  for(var filter:List.of(new Object[]{"state",state},new Object[]{"kind",kind},new Object[]{"handlingOrder.id",orderId},new Object[]{"product.id",productId})){if(filter[1]!=null&&!filter[1].toString().isBlank()){args.add(filter[1]);where+=" and i."+filter[0]+"=?"+args.size();}}
  if(query!=null&&!query.isBlank()){args.add("%"+query.toLowerCase(Locale.ROOT).replace("%","\\%").replace("_","\\_")+"%");where+=" and lower(i.title) like ?"+args.size()+" escape '\\'";}
  if(before>0){args.add(before);where+=" and i.id<?"+args.size();}
  var queryRows=em.createQuery("select i.id,i.version,i.state,i.kind,i.title,i.comment,i.reporter,i.owner,i.createdAt,i.resolvedAt,i.observedAddress,o.id,o.code,p.id,p.name,p.ean,p.productType,i.location.id,i.buffer.id,i.shortageId,i.replenishmentId,i.linkedHandlingOrderId,i.requestedQuantity from FieldIncident i left join i.handlingOrder o left join i.product p where "+where+" order by i.id desc",Object[].class);for(int index=0;index<args.size();index++)queryRows.setParameter(index+1,args.get(index));var rows=queryRows.setMaxResults(limit+1).getResultList();var page=rows.stream().limit(limit).toList();return Map.of("items",page.stream().map(row->scalarSummary(row,canManage)).toList(),"hasMore",rows.size()>limit,"nextBefore",page.isEmpty()?before:page.getLast()[0]);

 }
 Map<String,Object> scalarSummary(Object[] r,boolean canManage){
  var v=new LinkedHashMap<String,Object>();String[] names={"id","version","state","kind","title","comment","reporter","owner","createdAt","resolvedAt","observedAddress","orderId","orderCode"};for(int x=0;x<names.length;x++)if(!names[x].equals("version"))v.put(names[x],r[x]);v.put("revision",hash(Arrays.asList(r[0],r[1],r[2],r[7],r[20])));
  v.put("product",r[13]==null?null:Map.of("id",r[13],"name",r[14],"ean",r[15],"unit",switch((ProductType)r[16]){case UNIT->"PCS";case WEIGHT->"KG";case VOLUME->"L";}));v.put("locationId",r[17]);v.put("bufferId",r[18]);v.put("shortageId",r[19]);v.put("replenishmentId",r[20]);v.put("linkedHandlingOrderId",r[21]);v.put("requestedQuantity",r[22]==null?null:((BigDecimal)r[22]).toPlainString());v.put("canManage",canManage);return v;
 }
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="FieldIncidentDecision") public static class Decision {public Long storeId;public String requestKey,expectedRevision,action,reason,owner,outcome,nextAction;public Long replacementProductId;public BigDecimal remainingQuantity;}
 public Object decide(Long id,Decision d){
  requireManager();if(d==null)throw new BadRequestException();key(d.requestKey);text(d.reason,2000);Store s=store(d.storeId,true);FieldIncident i=incident(s,id);String fingerprint=hash(d);IncidentHistory previous=IncidentHistory.find("incident=?1 and eventKey=?2",i,d.requestKey).firstResult();if(previous!=null){if(!previous.fingerprint.equals(fingerprint)||!previous.actor.equals(actor()))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Décision différente sous la même référence");return detail(s,i);}expected(i,d.expectedRevision);
  switch(d.action==null?"":d.action){case "CLAIM"->{if(Set.of("RESOLVED","REJECTED").contains(i.state))throw new BadRequestException("Rouvrez d’abord le dossier");i.owner=actor();i.state="OWNED";}case "ASSIGN"->{var e=Employee.findByBadgeId(d.owner);if(e==null||!e.active||e.store==null||!id(e.store).equals(s.id)||e.role!=Employee.EmployeeRole.MANAGER)throw new BadRequestException("Responsable actif du magasin requis");i.owner=e.badgeId;i.state="OWNED";}case "WAIT"->i.state="WAITING";case "RESOLVE"->{i.state="RESOLVED";i.resolvedAt=DateTimeProvider.now();}case "REJECT"->{i.state="REJECTED";i.resolvedAt=DateTimeProvider.now();}case "REOPEN"->{i.state="OPEN";i.resolvedAt=null;}case "COMMENT"->{}default->throw new BadRequestException("Décision inconnue");}
  if(d.outcome!=null){if(!Set.of("WAITING","REPLACEMENT","REMAINDER","CANCELLED","RESOLVED").contains(d.outcome))throw new BadRequestException("Suite donnée inconnue");text(d.nextAction,1000);
   if("REPLACEMENT".equals(d.outcome)){Product p=Product.findById(d.replacementProductId);if(i.product==null||p==null||!p.active||com.intermarche.backoffice.domain.executing.ProductSubstitution.count("store=?1 and requested=?2 and replacement=?3 and active=true",s,i.product,p)==0)throw new BadRequestException("Remplacement autorisé requis");i.replacementProduct=p;}else i.replacementProduct=null;
   if("REMAINDER".equals(d.outcome)&&(d.remainingQuantity==null||d.remainingQuantity.signum()<=0||d.remainingQuantity.scale()>3||d.remainingQuantity.precision()-d.remainingQuantity.scale()>13||i.product!=null&&i.product.productType==ProductType.UNIT&&d.remainingQuantity.stripTrailingZeros().scale()>0))throw new BadRequestException("Quantité de reliquat positive et cohérente requise");
   i.remainingQuantity="REMAINDER".equals(d.outcome)?d.remainingQuantity:null;i.outcome=d.outcome;i.nextAction=d.nextAction.trim();
  }else if("WAIT".equals(d.action)){i.outcome="WAITING";i.nextAction=d.reason.trim();}else if(Set.of("RESOLVE","REJECT","REOPEN").contains(d.action)){i.outcome=d.action.equals("RESOLVE")?"RESOLVED":d.action.equals("REJECT")?"REJECTED":"OPEN";i.nextAction=d.reason.trim();}
  history(i,d.requestKey,fingerprint,d.action,d.reason.trim());em.flush();notify(i,d.requestKey,d.action+" · "+d.reason.trim());return detail(s,i);
 }
}
