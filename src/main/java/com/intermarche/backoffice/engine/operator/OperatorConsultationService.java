package com.intermarche.backoffice.engine.operator;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.fieldops.OperationalPolicyVersions;
import com.intermarche.backoffice.engine.catalog.ProductInformationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only, actor-scoped projections. No account creation, inferred presence or stock adjustment. */
@ApplicationScoped @Transactional
public class OperatorConsultationService {
 @Inject OperatorSupportService support;
 @Inject EntityManager em;
 @Inject ObjectMapper json;
 record Entry(String source,Long id,LocalDateTime at,String action,Long orderId,BigDecimal quantity,String unit,String declaredState,String declaredReason) {}
 record Cursor(LocalDateTime anchor,LocalDateTime at,String source,Long id) {}
 private Cursor cursor(String raw,LocalDateTime now){
  if(raw==null)return new Cursor(now,null,null,null);
  try{if(raw.length()>512)throw new IllegalArgumentException();var n=json.readTree(Base64.getUrlDecoder().decode(raw));var c=new Cursor(LocalDateTime.parse(n.path("anchor").asText()),LocalDateTime.parse(n.path("at").asText()),n.path("source").asText(),n.path("id").longValue());if(!Set.of("MISSION","OPERATION").contains(c.source)||c.id<=0||c.anchor.isAfter(now)||c.at.isAfter(c.anchor))throw new IllegalArgumentException();return c;}catch(Exception e){throw new BadRequestException("Curseur d’activité invalide");}
 }
 private String cursor(Cursor c){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(c));}catch(Exception e){throw new IllegalStateException(e);}}
 public Object activity(Long storeId,int limit,String raw){
  Store store=support.store(storeId);if(limit<1||limit>100)throw new BadRequestException("Entre 1 et 100 opérations par page");
  var policy=OperationalPolicyVersions.active(store,"ConsultationConstraints");
  if(!policy.path("showOwnActivity").asBoolean()||!policy.path("showCommandHistory").asBoolean())return Map.of("items",List.of(),"hasMore",false,"historyMasked",true);
  var now=DateTimeProvider.now();var c=cursor(raw,now);var since=now.minusHours(policy.path("activityWindowHours").asInt());
  var rows=new ArrayList<Entry>();
  for(String source:List.of("MISSION","OPERATION")){
   String entity=source.equals("MISSION")?"OperatorCommandReceipt":"StockOperationReceipt";
   String actor=source.equals("MISSION")?"performedBy":"actor",action=source.equals("MISSION")?"action":"operation";
   var params=new LinkedHashMap<String,Object>();params.put("store",store);params.put("actor",support.actor());params.put("since",since);params.put("anchor",c.anchor);
   String after="";if(c.at!=null){params.put("at",c.at);int cmp=source.compareTo(c.source);after=cmp>0?" and r.createdAt<=:at":cmp<0?" and r.createdAt<:at":" and (r.createdAt<:at or (r.createdAt=:at and r.id<:lastId))";if(cmp==0)params.put("lastId",c.id);}
   var query=em.createQuery("select r.id,r.createdAt,r."+action+","+(source.equals("MISSION")?"r.orderId,r.activityQuantity,r.activityUnit,null,null":"null,null,null,r.activityState,r.activityReason")+" from "+entity+" r where r.store=:store and r."+actor+"=:actor and r.createdAt>=:since and r.createdAt<=:anchor"+after+" order by r.createdAt desc,r.id desc",Object[].class);params.forEach(query::setParameter);
   for(var r:query.setMaxResults(limit+1).getResultList())rows.add(new Entry(source,((Number)r[0]).longValue(),(LocalDateTime)r[1],(String)r[2],r[3]==null?null:((Number)r[3]).longValue(),(BigDecimal)r[4],(String)r[5],(String)r[6],(String)r[7]));
  }
  rows.sort(Comparator.comparing(Entry::at).reversed().thenComparing(Entry::source).thenComparing(Entry::id,Comparator.reverseOrder()));
  var page=rows.stream().limit(limit).toList();var result=new LinkedHashMap<String,Object>();result.put("items",page);result.put("hasMore",rows.size()>limit);result.put("historyMasked",false);result.put("since",since);result.put("asOf",c.anchor);result.put("nextCursor",page.isEmpty()?null:cursor(new Cursor(c.anchor,page.getLast().at,page.getLast().source,page.getLast().id)));result.put("basis","CONFIRMED_COMMANDS_ONLY");return result;
 }
 public Object summary(Long storeId){
  Store store=support.store(storeId);var policy=OperationalPolicyVersions.active(store,"ConsultationConstraints");
  if(!policy.path("showOwnActivity").asBoolean()||!policy.path("showCommandHistory").asBoolean())return Map.of("historyMasked",true,"available",true);
  var since=DateTimeProvider.now().minusHours(policy.path("activityWindowHours").asInt());String actor=support.actor();
  var amounts=em.createQuery("select q.action,q.metric,q.unit,sum(q.quantity),sum(case when q.quantity>0 then q.quantity else 0 end),sum(case when q.quantity<0 then -q.quantity else 0 end),count(distinct q.lineId),count(distinct q.receiptId) from ActivityQuantity q where q.store=:store and q.actor=:actor and q.createdAt>=:since group by q.action,q.metric,q.unit order by q.action,q.metric,q.unit",Object[].class).setParameter("store",store).setParameter("actor",actor).setParameter("since",since).getResultList();
  var operations=new ArrayList<Map<String,Object>>();
  for(var row:amounts)operations.add(Map.of("action",row[0],"metric",row[1],"unit",row[2],"quantity",row[3],"confirmedQuantity",row[4],"reversedQuantity",row[5],"distinctLines",row[6],"confirmedCommands",row[7]));
  // Preserve the old field for installed clients, but it is no longer the complete report.
  var picks=em.createQuery("select q.unit,sum(q.quantity),count(distinct q.lineId),count(distinct concat(q.source,':',str(q.receiptId))) from ActivityQuantity q where q.store=:store and q.actor=:actor and q.createdAt>=:since and q.metric='PICKED' group by q.unit",Object[].class).setParameter("store",store).setParameter("actor",actor).setParameter("since",since).getResultList().stream().map(v->Map.of("unit",v[0],"quantity",v[1],"distinctLines",v[2],"confirmedCommands",v[3])).toList();
  // Physical stock is reported separately from work: a paired transfer is never a second receipt.
  var movements=em.createQuery("select g.reason,g.unit,sum(g.incoming),sum(g.outgoing),sum(case when g.incoming>g.outgoing then g.incoming else g.outgoing end) from (select m.reason as reason,m.activityUnit as unit,m.operationKey as operation,m.account.id as account,sum(case when m.quantity>0 then m.quantity else 0 end) as incoming,sum(case when m.quantity<0 then -m.quantity else 0 end) as outgoing from StockMovement m where m.account.store=:store and m.activityActor=:actor and m.createdAt>=:since group by m.reason,m.activityUnit,m.operationKey,m.account.id) g group by g.reason,g.unit",Object[].class).setParameter("store",store).setParameter("actor",actor).setParameter("since",since).getResultList();
  var physical=new TreeMap<String,Map<String,Object>>();
  for(var row:movements){if(row[1]==null)continue;String kind=physicalKind((String)row[0]),unit=(String)row[1];var v=physical.computeIfAbsent(kind+":"+unit,k->new LinkedHashMap<>(Map.of("operation",kind,"unit",unit,"incoming",BigDecimal.ZERO,"outgoing",BigDecimal.ZERO,"net",BigDecimal.ZERO,"handled",BigDecimal.ZERO)));BigDecimal incoming=(BigDecimal)row[2],outgoing=(BigDecimal)row[3];v.put("incoming",((BigDecimal)v.get("incoming")).add(incoming));v.put("outgoing",((BigDecimal)v.get("outgoing")).add(outgoing));v.put("net",((BigDecimal)v.get("net")).add(incoming.subtract(outgoing)));v.put("handled",((BigDecimal)v.get("handled")).add((BigDecimal)row[4]));}
  var commands=new ArrayList<Map<String,Object>>();long unknown=0,unknownPicks=0;
  for(String source:List.of("MISSION","OPERATION")){boolean mission=source.equals("MISSION");var counts=em.createQuery("select r."+(mission?"action":"operation")+",count(r),sum(case when r.quantityProjectionVersion is null"+(mission?" or (r.action in ('VALIDATE_REPORT','RECOVER_REPORT') and r.quantityProjectionVersion<2)":"")+" then 1 else 0 end) from "+(mission?"OperatorCommandReceipt":"StockOperationReceipt")+" r where r.store=:store and r."+(mission?"performedBy":"actor")+"=:actor and r.createdAt>=:since group by r."+(mission?"action":"operation"),Object[].class).setParameter("store",store).setParameter("actor",actor).setParameter("since",since).getResultList();for(var row:counts){long missing=((Number)row[2]).longValue();unknown+=missing;if("PICK".equals(row[0]))unknownPicks+=missing;commands.add(Map.of("source",source,"action",row[0],"confirmedCommands",row[1],"historicalWithoutProjection",missing));}}
  var result=new LinkedHashMap<String,Object>();result.put("available",true);result.put("historyMasked",false);result.put("since",since);result.put("basis","ALL_CONFIRMED_OPERATIONS");result.put("quantities",picks);result.put("operations",operations);result.put("physicalQuantities",physical.values());result.put("commands",commands);result.put("historicalCommandsWithoutQuantities",unknown);result.put("unquantifiedPickCommands",unknownPicks);result.put("separatePreparationReports",0);result.put("incomplete",unknown>0);result.put("durationInferred",false);result.put("totalsAreAdditiveAcrossOperations",false);return result;
 }
 private static String physicalKind(String reason){
  if(reason.matches("PICKING_[0-9]+"))return "REPLENISHMENT_PICK";
  if(reason.matches("SUPPLY_[0-9]+_(PARTIAL|FINAL)"))return "REPLENISHMENT_RECEIPT";
  if(reason.startsWith("REOPEN_"))return "REPLENISHMENT_REVERSAL";
  if(reason.startsWith("COMMITMENT_"))return "PREPARATION";
  return reason;
 }
 public Object product(Long storeId,Long productId){
  Store store=support.store(storeId);Product product=Product.findById(productId);if(product==null||!product.active)throw new NotFoundException();var now=DateTimeProvider.now();
  var balances=em.createQuery("select m.location.id,m.location.locationCode,sum(m.quantity) from StockMovement m where m.account.store=:store and m.account.product=:product and m.effectiveAt<=:now group by m.location.id,m.location.locationCode",Object[].class).setParameter("store",store).setParameter("product",product).setParameter("now",now).getResultList();
  boolean known=!balances.isEmpty();var positions=new LinkedHashMap<Long,Map<String,Object>>();BigDecimal physical=BigDecimal.ZERO;
  for(var row:balances){var address=new LinkedHashMap<String,Object>();address.put("id",row[0]);address.put("code",row[1]);address.put("quantity",row[2]);address.put("quantityKnown",true);positions.put(((Number)row[0]).longValue(),address);physical=physical.add((BigDecimal)row[2]);}
  var addresses=em.createQuery("select l.id,l.locationCode,b.id,b.buildingCode,type(b) from Store s join s.buildings b join b.locations l where s=:store and l.product=:product order by b.buildingCode,l.locationCode,l.id",Object[].class).setParameter("store",store).setParameter("product",product).getResultList();
  for(var row:addresses){var address=positions.computeIfAbsent(((Number)row[0]).longValue(),id->new LinkedHashMap<>());address.put("id",row[0]);address.put("code",row[1]);address.put("buildingId",row[2]);address.put("buildingCode",row[3]);address.put("buildingType",((Class<?>)row[4]).getSimpleName());address.putIfAbsent("quantity",known?BigDecimal.ZERO:null);address.put("quantityKnown",known);}
  BigDecimal weight=null;String weightBasis="UNKNOWN";
  if(known&&product.productType==ProductType.WEIGHT){weight=physical;weightBasis="LEDGER_KG";}else if(known&&product.referenceWeight!=null&&product.productType==ProductType.UNIT){weight=physical.multiply(product.referenceWeight);weightBasis="REFERENCE_ESTIMATE";}
  var result=new LinkedHashMap<String,Object>();result.put("productId",product.id);result.put("ean",product.ean);result.put("unit",ProductInformationService.unit(product));result.put("physical",known?physical:null);result.put("quantityKnown",known);result.put("weightKg",weight);result.put("weightKnown",weight!=null);result.put("weightBasis",weightBasis);result.put("incomplete",!known||weight==null);result.put("addresses",positions.values());result.put("asOf",now);result.put("readOnly",true);return result;
 }
}
