package com.intermarche.backoffice.engine.fieldops;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.*;
import java.util.*;
@ApplicationScoped @Transactional
public class ConsumableService {
 @Inject FieldOperationsService field; @Inject StockCommands receipts;
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="PackagingConsumableCommand")
 public static class Input {public Long storeId,typeId;public String requestKey,reason;public java.math.BigDecimal quantity;public Long threshold,target;}
 public static boolean consumable(SupportType t){return t.kind==SupportType.Kind.CARTON||t.kind==SupportType.Kind.PALLET;}
 static Map<String,Object> view(SupportType t){return Map.of("id",t.id,"code",t.code,"label",t.label,"kind",t.kind,"stock",t.stockQuantity,"threshold",t.reorderThreshold,"target",t.reorderTarget,"low",t.stockQuantity<=t.reorderThreshold,"suggestedQuantity",t.stockQuantity<=t.reorderThreshold?Math.max(0,t.reorderTarget-t.stockQuantity):0);}
 public Object list(Long storeId){Store s=field.store(storeId,false);return SupportType.<SupportType>list("store=?1 and kind in ?2 order by kind,label",s,List.of(SupportType.Kind.CARTON,SupportType.Kind.PALLET)).stream().map(ConsumableService::view).toList();}
 public Object inUse(Long storeId){Store store=field.store(storeId,false);return SupportType.getEntityManager().createQuery("select s.id,s.code,s.tote.id,s.type.kind,s.type.label,count(i) from PhysicalSupport s join LoadedTote l on l.tote=s.tote join PickedItem i on i.loadedTote=l where s.store=?1 and s.type.kind in ?2 and l.status=?3 and i.status=?4 and i.quantity>0 group by s.id,s.code,s.tote.id,s.type.kind,s.type.label order by s.id",Object[].class).setParameter(1,store).setParameter(2,List.of(SupportType.Kind.CARTON,SupportType.Kind.PALLET)).setParameter(3,LoadedTote.Status.IN_USE).setParameter(4,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED).setMaxResults(100).getResultList().stream().map(r->Map.of("id",r[0],"code",r[1],"physicalToteId",r[2],"kind",r[3],"label",r[4],"productLines",r[5])).toList();}
 private SupportType type(Store s,Long id){SupportType t=SupportType.find("store=?1 and id=?2",s,id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();if(t==null||!consumable(t))throw new BadRequestException("Type de consommable requis");return t;}
 public Object history(Long storeId,Long typeId){Store s=field.store(storeId,false);var t=type(s,typeId);return PackagingMovement.<PackagingMovement>find("type=?1 order by id desc",t).range(0,49).list().stream().map(m->Map.of("id",m.id,"at",m.createdAt,"quantity",m.quantity,"balance",m.balance,"actor",m.actor,"reason",m.reason,"supportCode",m.support==null?"":m.support.code)).toList();}
 public Object command(Input r,String action){if(r==null)throw new BadRequestException();Store s=field.store(r.storeId,true);if(!"LOSS".equals(action))field.requireManager();String hash=field.hash(Arrays.asList("PACKAGING_"+action,r));
 // Store lock serializes retries before consulting the command receipt.
 Store.getEntityManager().lock(s,LockModeType.PESSIMISTIC_WRITE);var old=receipts.previous(s,r.requestKey,hash);if(old!=null)return receipts.value(old);var t=type(s,r.typeId);Object result;long amount=0;if(Set.of("RECEIVE","ADJUST","LOSS").contains(action)){if(r.quantity==null||r.quantity.stripTrailingZeros().scale()>0||r.quantity.abs().compareTo(new java.math.BigDecimal("1000000000"))>0)throw new BadRequestException("Quantité entière requise, un milliard maximum");amount=r.quantity.longValueExact();}
 switch(action){
 case "RECEIVE" -> {if(amount<=0)throw new BadRequestException("Quantité entière positive requise");FieldOperationsService.text(r.reason,500);t.stockQuantity=Math.addExact(t.stockQuantity,amount);movement(t,null,amount,field.actor(),r.reason);result=view(t);}
 case "ADJUST" -> {if(amount==0||t.stockQuantity+amount<0)throw new BadRequestException("Écart entier non nul, sans stock négatif");FieldOperationsService.text(r.reason,500);t.stockQuantity=Math.addExact(t.stockQuantity,amount);movement(t,null,amount,field.actor(),r.reason);result=view(t);}
 case "SETTINGS" -> {if(r.threshold==null||r.target==null||r.threshold<0||r.target<=r.threshold||r.target>1000000000L)throw new BadRequestException("Le stock cible doit dépasser le seuil, positif ou nul");t.reorderThreshold=r.threshold;t.reorderTarget=r.target;result=view(t);}
 case "LOSS" -> {if(amount<=0)throw new BadRequestException("Quantité entière positive requise");if(t.stockQuantity<amount)throw new BadRequestException("Stock de consommables insuffisant");FieldOperationsService.text(r.reason,500);t.stockQuantity-=amount;movement(t,null,-amount,field.actor(),"Perte · "+r.reason);result=view(t);}
 default -> throw new BadRequestException("Action inconnue");}
 return receipts.record(s,r.requestKey,hash,"PACKAGING_"+action,result);}
 static void movement(SupportType t,PhysicalSupport p,long q,String actor,String reason){var m=new PackagingMovement();m.type=t;m.support=p;m.quantity=q;m.balance=t.stockQuantity;m.actor=actor;m.reason=reason;m.persist();}
 public static void consume(PhysicalSupport p,String actor){if(p==null||!consumable(p.type))return;var t=p.type;SupportType.getEntityManager().refresh(t,LockModeType.PESSIMISTIC_WRITE);if(p.consumableUsed)return;if(t.stockQuantity<=0)throw new BadRequestException("Stock de "+t.label+" épuisé");t.stockQuantity--;p.consumableUsed=true;movement(t,p,-1,actor,"Mise en service · "+p.code);}
 public static void onLoad(Tote tote){PhysicalSupport p=PhysicalSupport.find("select s from PhysicalSupport s join fetch s.type where s.tote=?1",tote).firstResult();if(p==null||!consumable(p.type))return;if(p.consumableUsed&&(LoadedTote.count("tote",tote)>0||com.intermarche.backoffice.domain.shipping.Parcel.count("packagingSupport",p)>0))throw new BadRequestException("Cet emballage a déjà été utilisé : prenez un nouveau carton ou une nouvelle palette");consume(p,"SYSTEM");}
}
