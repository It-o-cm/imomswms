package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.engine.operator.*;
import com.intermarche.backoffice.engine.executing.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.util.*;
@ApplicationScoped @Transactional
public class SupportService {
 @Inject FieldOperationsService field;@Inject OperatorExecutionService operator;
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="SupportTypeInput") public static class TypeInput {public Long storeId,id,reorderThreshold,reorderTarget;public String code,label,kind,allowedZones,expectedRevision;public BigDecimal maxWeightKg,maxVolumeLiters;}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="PhysicalSupportInput") public static class Input {public Long storeId,typeId;public String code,state,expectedRevision;public BigDecimal volumeLiters;}
 private static String revision(com.intermarche.backoffice.domain.BaseEntity e){return CommandCanonical.hash("["+e.id+","+e.version+"]");}
 private static boolean sameCapacity(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
 private static void positive(BigDecimal value){if(value!=null&&(value.signum()<=0||value.scale()>3||value.precision()>12))throw new BadRequestException("Capacité positive, 3 décimales maximum");}
 private static Set<String> zones(String value){try{var result=new TreeSet<String>();for(var zone:value.split(","))result.add(TemperatureZone.valueOf(zone.trim()).name());if(result.isEmpty())throw new IllegalArgumentException();return result;}catch(Exception e){throw new BadRequestException("Conditionnements inconnus");}}
 private Map<String,Object> type(SupportType t){var v=new LinkedHashMap<String,Object>();v.put("id",t.id);v.put("code",t.code);v.put("label",t.label);v.put("kind",t.kind);v.put("allowedZones",zones(t.allowedZones));v.put("maxWeightKg",t.maxWeightKg);v.put("maxVolumeLiters",t.maxVolumeLiters);v.put("revision",revision(t));return v;}
 public Object types(Long storeId){Store store=field.store(storeId,false);return SupportType.<SupportType>list("store=?1 order by kind,code",store).stream().map(this::type).toList();}
 public Object saveType(TypeInput r){if(r==null)throw new BadRequestException();Store store=field.store(r.storeId,true);field.requireManager();FieldOperationsService.text(r.label,100);if(r.code==null||!r.code.matches("[A-Z0-9_-]{1,30}"))throw new BadRequestException("Code de type invalide");positive(r.maxWeightKg);positive(r.maxVolumeLiters);SupportType.Kind kind;try{kind=SupportType.Kind.valueOf(r.kind);}catch(Exception e){throw new BadRequestException("Type : TOTE, CARTON, PALLET ou CART");}var allowed=zones(r.allowedZones);
  // Backing Tote compatibility remains authoritative for existing WMS operations.
  if(allowed.stream().anyMatch(z->Set.of("AMBIENT","HOUSEHOLD").contains(z))&&allowed.stream().anyMatch(z->Set.of("CHILLED","FROZEN").contains(z)))throw new BadRequestException("Séparez les supports ambiants et isothermes");
  SupportType t=r.id==null?SupportType.find("store=?1 and code=?2",store,r.code).firstResult():SupportType.find("store=?1 and id=?2",store,r.id).firstResult();if(r.id!=null&&t==null)throw new NotFoundException();if(t!=null){if(!Objects.equals(r.expectedRevision,revision(t)))throw new OperatorFailure(409,"STALE_SUPPORT_TYPE","Le type a changé. Actualisez.");if(PhysicalSupport.count("type",t)>0&&(t.kind!=kind||!zones(t.allowedZones).equals(allowed)||!sameCapacity(t.maxWeightKg,r.maxWeightKg)||!sameCapacity(t.maxVolumeLiters,r.maxVolumeLiters)))throw new BadRequestException("Type déjà utilisé : créez une variante pour modifier ses capacités ou son conditionnement");}else{t=new SupportType();t.store=store;}
  if(ConsumableService.consumable(t)||kind==SupportType.Kind.CARTON||kind==SupportType.Kind.PALLET){long threshold=r.reorderThreshold==null?t.reorderThreshold:r.reorderThreshold;long target=r.reorderTarget==null?(t.reorderTarget>t.reorderThreshold?t.reorderTarget:Math.max(100,t.reorderThreshold+1)):r.reorderTarget;if(threshold<0||target<=threshold)throw new BadRequestException("Stock cible supérieur au seuil requis");t.reorderThreshold=threshold;t.reorderTarget=target;}
  t.code=r.code;t.label=r.label.trim();t.kind=kind;t.allowedZones=String.join(",",allowed);t.maxWeightKg=r.maxWeightKg;t.maxVolumeLiters=r.maxVolumeLiters;t.persistAndFlush();return type(t);
 }
 public Object save(Input r){return save(r,false);}
 Object save(Input r,boolean issuing){if(r==null)throw new BadRequestException();Store store=field.store(r.storeId,true);if(!issuing)field.requireManager();if(r.code==null||!r.code.matches("[A-Za-z0-9_-]{1,20}"))throw new BadRequestException("Code support de 20 caractères maximum");SupportType type=SupportType.find("id=?1 and store=?2",r.typeId,store).firstResult();if(type==null)throw new NotFoundException();PhysicalSupport support=PhysicalSupport.find("store=?1 and code=?2",store,r.code).firstResult();String state=r.state==null?"AVAILABLE":r.state;if(!Set.of("AVAILABLE","BLOCKED","RETIRED").contains(state))throw new BadRequestException("État de support inconnu");
  if(support!=null){if(!Objects.equals(r.expectedRevision,revision(support)))throw new OperatorFailure(409,"STALE_SUPPORT","Le support a changé. Actualisez.");if(!id(support.type).equals(type.id))throw new BadRequestException("Le type d’un support existant est conservé");if((LoadedTote.count("tote=?1 and status=?2",support.tote,LoadedTote.Status.IN_USE)>0||CartCompartment.count("cart=?1 and (occupant is not null or handlingOrder is not null)",support)>0)&&!support.state.equals(state))throw new BadRequestException("Support utilisé : terminez ou corrigez son utilisation avant de changer son état");support.state=state;support.persistAndFlush();return summary(support,null);}
  positive(r.volumeLiters);BigDecimal volume=r.volumeLiters==null?type.maxVolumeLiters:r.volumeLiters;if(volume==null)throw new BadRequestException("Volume du support requis");if(type.maxVolumeLiters!=null&&volume.compareTo(type.maxVolumeLiters)>0)throw new BadRequestException("Volume supérieur à la capacité du type");Tote tote=Tote.findByCode(r.code);if(tote!=null){if(!id(tote.store).equals(store.id))throw new ForbiddenException("Code de support déjà utilisé");if(type.kind!=SupportType.Kind.TOTE||volume.compareTo(tote.volume)!=0)throw new BadRequestException("Le bac historique conserve son type et sa capacité");for(String zone:zones(type.allowedZones))if(!tote.isUsageAllowed(TemperatureZone.valueOf(zone)))throw new BadRequestException("Conditionnement incompatible avec le bac");}else{tote=new Tote();tote.store=store;tote.code=r.code;tote.volume=volume;tote.toteType=zones(type.allowedZones).contains("CHILLED")||zones(type.allowedZones).contains("FROZEN")?Tote.ToteType.INSULATED:Tote.ToteType.STANDARD;tote.persist();}
  support=new PhysicalSupport();support.store=store;support.type=type;support.tote=tote;support.code=r.code;support.state=state;support.persistAndFlush();return summary(support,null);
 }
 private Map<String,Object> summary(PhysicalSupport support,Long loaded){return summary(support,loaded,false);}
 private Map<String,Object> summary(PhysicalSupport support,Long loaded,boolean cartOccupied){var v=new LinkedHashMap<String,Object>();v.put("id",support.id);v.put("code",support.code);v.put("toteId",id(support.tote));v.put("type",type(support.type));v.put("state",loaded==null&&!cartOccupied?support.state:"IN_USE");v.put("loadedToteId",loaded);v.put("volumeLiters",support.tote.volume);v.put("revision",revision(support));return v;}
 public Object list(Long storeId,String query,long after,int limit){return list(storeId,query,after,limit,null);}
 public Object list(Long storeId,String query,long after,int limit,String kind){return list(storeId,query,after,limit,kind,null,null,null,null);}
 public Object list(Long storeId,String query,long after,int limit,String kind,String availability,Long typeId,String locationKind,Long locationId){
  Store store=field.store(storeId,false);if(limit<1||limit>100||after<0)throw new BadRequestException("Pagination invalide");String q=query==null?"":query.trim();if(q.length()>100)throw new BadRequestException("Recherche trop longue");
  String where="s.store=?1 and s.id>?2 and lower(s.code) like ?3";var args=new ArrayList<Object>(List.of(store,after,"%"+q.toLowerCase(Locale.ROOT)+"%"));
  if("REUSABLE".equals(kind))where+=" and s.type.kind in (TOTE,CART)";else if(kind!=null&&!kind.isBlank()){try{args.add(SupportType.Kind.valueOf(kind));}catch(Exception invalid){throw new BadRequestException("Type de support inconnu");}where+=" and s.type.kind=?"+args.size();}
  if(typeId!=null){if(SupportType.count("store=?1 and id=?2",store,typeId)==0)throw new BadRequestException("Type hors magasin");args.add(typeId);where+=" and s.type.id=?"+args.size();}
  String inCart="exists(select c.id from CartCompartment c where c.occupant=s)",cartBusy="exists(select c.id from CartCompartment c where c.cart=s and (c.occupant is not null or c.handlingOrder is not null))",loaded="exists(select l.id from LoadedTote l where l.tote=s.tote and l.status=IN_USE)";
  String free="(s.state='AVAILABLE' and not "+loaded+" and not "+inCart+" and not "+cartBusy+")";
  if(availability!=null&&!availability.isBlank()){if(!Set.of("FREE","BUSY").contains(availability))throw new BadRequestException("Disponibilité inconnue");where+=" and "+("FREE".equals(availability)?free:"not "+free);}
  if(locationKind!=null&&!locationKind.isBlank()){
   switch(locationKind){
    case "BUILDING","BUFFER" -> {if(locationId==null)throw new BadRequestException("Lieu requis");args.add(locationId);where+=" and not "+inCart+" and exists(select l.id from LoadedTote l where l.tote=s.tote and l.status=IN_USE and l.buffer."+("BUFFER".equals(locationKind)?"id":"building.id")+"=?"+args.size()+")";}
    case "CART" -> {where+=" and "+inCart;}
    case "UNLOCATED" -> {where+=" and not "+inCart+" and not exists(select l.id from LoadedTote l where l.tote=s.tote and l.status=IN_USE and l.buffer is not null)";}
    default -> throw new BadRequestException("Lieu inconnu");
   }
  }
  var supports=PhysicalSupport.<PhysicalSupport>find("select s from PhysicalSupport s join fetch s.type join fetch s.tote where "+where+" order by s.id",args.toArray()).range(0,limit).list();boolean more=supports.size()>limit;if(more)supports=new ArrayList<>(supports.subList(0,limit));
  var ids=supports.stream().map(s->id(s.tote)).toList();var supportIds=supports.stream().map(s->s.id).toList();var loads=ids.isEmpty()?List.<LoadedTote>of():LoadedTote.<LoadedTote>list("tote.id in ?1 and status=?2",ids,LoadedTote.Status.IN_USE);var loadedIds=new HashMap<Long,Long>();for(var l:loads)loadedIds.put(id(l.tote),l.id);
  var busy=new HashSet<Long>();var locations=new HashMap<Long,String>();
  if(!supportIds.isEmpty()){
   for(Object[] row:PhysicalSupport.getEntityManager().createQuery("select c.cart.id,c.occupant.id,c.cart.code from CartCompartment c where (c.cart.id in ?1 or c.occupant.id in ?1) and (c.occupant is not null or c.handlingOrder is not null)",Object[].class).setParameter(1,supportIds).getResultList()){busy.add((Long)row[0]);if(row[1]!=null){busy.add((Long)row[1]);locations.put((Long)row[1],"Chariot "+row[2]);}}
   for(Object[] row:PhysicalSupport.getEntityManager().createQuery("select s.id,l.buffer.bufferCode,l.buffer.building.buildingCode from PhysicalSupport s,LoadedTote l where s.id in ?1 and l.tote=s.tote and l.status=IN_USE and l.buffer is not null",Object[].class).setParameter(1,supportIds).getResultList())locations.putIfAbsent((Long)row[0],row[2]+" · "+row[1]);
  }
  return Map.of("items",supports.stream().map(s->{var v=summary(s,loadedIds.get(id(s.tote)),busy.contains(s.id));v.put("location",locations.getOrDefault(s.id,"Emplacement non renseigné"));return v;}).toList(),"hasMore",more,"nextAfter",supports.isEmpty()?after:supports.getLast().id);
 }

 public Object attach(OperatorCommandInput command){if(command==null||!"ADD_TOTE".equals(command.action))throw new BadRequestException("Commande d’attachement ADD_TOTE requise");field.store(command.storeId,false);return field.decode(operator.apply(command),Map.class);}
}
