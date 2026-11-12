package com.intermarche.backoffice.engine.fieldops;

import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.StockSegments;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.util.*;

/** Reclassification appends two opposite movements at the same instant. Aggregates and commitments stay intact. */
@ApplicationScoped @Transactional
public class StockSegmentService {
    @Inject FieldOperationsService field;
    @Inject com.intermarche.backoffice.engine.stock.InventoryScopeGuard freezes;
    @Inject com.intermarche.backoffice.engine.stock.StockTraceLedger ledger;
    @Inject QualityService qualityService;
    @org.eclipse.microprofile.openapi.annotations.media.Schema(name="StockSegmentReclassificationInput")
    public static class Transfer {
        public Long storeId,sourceId,lotId,serialId,supportId;
        public BigDecimal quantity;
        public String requestKey,expectedRevision,quality,reason;
    }
    private String revision(StockSegment s,BigDecimal balance){return field.hash(Arrays.asList(s.id,s.version,balance.stripTrailingZeros().toPlainString()));}
    public Object list(Long storeId,Long productId,Long locationId){Store store=field.store(storeId,false);
        var args=new ArrayList<Object>(List.of(store,productId));String filter="s.account.store=?1 and s.account.product.id=?2";
        if(locationId!=null){args.add(locationId);filter+=" and s.location.id=?3";}
        var query=StockSegment.getEntityManager().createQuery("select s.id,s.version,s.location.id,s.location.locationCode,s.support.code,s.lot.id,s.lot.code,s.lot.useBy,s.lot.bestBefore,s.serial.code,s.quality,coalesce(sum(m.quantity),0),s.support.id,s.serial.id from StockSegment s left join s.support left join s.lot left join s.serial left join StockMovement m on m.segment=s and m.effectiveAt<=:now where "+filter+" group by s.id,s.version,s.location.id,s.location.locationCode,s.support.code,s.lot.id,s.lot.code,s.lot.useBy,s.lot.bestBefore,s.serial.code,s.quality,s.support.id,s.serial.id order by s.location.locationCode,s.id",Object[].class);
        for(int i=0;i<args.size();i++)query.setParameter(i+1,args.get(i));query.setParameter("now",DateTimeProvider.now());
        var policy=TraceabilityPolicy.<TraceabilityPolicy>find("store=?1 and product.id=?2 and context='PICKING'",store,productId).firstResult();
        var holds=QualityCase.<QualityCase>list("store=?1 and product.id=?2 and state='ACTIVE'",store,productId);
        var entities=new HashMap<Long,StockSegment>();for(var segment:StockSegment.<StockSegment>list("select s from StockSegment s left join fetch s.lot left join fetch s.serial where s.account.store=?1 and s.account.product.id=?2",store,productId))entities.put(segment.id,segment);
        var result=new ArrayList<Map<String,Object>>();
        for(var r:query.getResultList()) {var v=new LinkedHashMap<String,Object>();String[] keys={"id","version","locationId","locationCode","supportCode","lotId","lotCode","useBy","bestBefore","serial","quality","quantity","supportId","serialId"};for(int i=0;i<keys.length;i++)v.put(keys[i],r[i]);
         var segment=entities.get((Long)r[0]);String reason=null;try{com.intermarche.backoffice.engine.stock.StockQualityRules.requireSegment(segment,policy,holds);}catch(OperatorFailure|ClientErrorException denied){reason=denied instanceof OperatorFailure failure?((Map<?,?>)failure.getResponse().getEntity()).get("message").toString():denied.getMessage();}
         var physical=org.hibernate.Hibernate.unproxy(segment.location);
         boolean reserved=physical instanceof ParcelStockLocation||physical instanceof PickingToteLocation held&&held.traceOnly;
         if(reserved&&reason==null)reason="Déjà affecté à une préparation";
         v.put("state",reserved?"RESERVED":reason==null?"AVAILABLE":"BLOCKED");v.put("allowed",reason==null);v.put("reason",reason);v.put("manufacturedOn",segment.lot==null?null:segment.lot.manufacturedOn);v.put("receivedAt",segment.receivedAt);v.put("allocationMethod",policy==null?"FEFO":policy.allocationMethod);v.put("minimumRemainingDays",policy==null?0:policy.minimumRemainingDays);
         v.put("revision",field.hash(Arrays.asList(r[0],r[1],((BigDecimal)r[11]).stripTrailingZeros().toPlainString())));result.add(v);
        }
        var ordering=com.intermarche.backoffice.engine.stock.StockEligibility.comparator(policy==null?"FEFO":policy.allocationMethod);
        result.sort((a,b)->{boolean aa=(boolean)a.get("allowed"),bb=(boolean)b.get("allowed");if(aa!=bb)return aa?-1:1;var x=entities.get(a.get("id"));var y=entities.get(b.get("id"));return ordering.compare(new com.intermarche.backoffice.engine.stock.StockEligibility.Candidate(x.id,x.receivedAt,x.lot==null?null:x.lot.useBy,x.lot==null?null:x.lot.bestBefore),new com.intermarche.backoffice.engine.stock.StockEligibility.Candidate(y.id,y.receivedAt,y.lot==null?null:y.lot.useBy,y.lot==null?null:y.lot.bestBefore));});
        Long preferred=result.stream().filter(v->Boolean.TRUE.equals(v.get("allowed"))&&((BigDecimal)v.get("quantity")).signum()>0).map(v->(Long)v.get("id")).findFirst().orElse(null);for(var v:result)v.put("preferred",Objects.equals(v.get("id"),preferred));return result;
    }
    public Object transfer(Transfer r){if(r==null)throw new BadRequestException();Store store=field.store(r.storeId,true);field.requireManager();FieldOperationsService.key(r.requestKey);FieldOperationsService.text(r.reason,500);
        String fingerprint=field.hash(r);SupportOperation receipt=SupportOperation.find("store=?1 and actor=?2 and requestKey=?3",store,field.actor(),r.requestKey).firstResult();
        if(receipt!=null){if(!receipt.fingerprint.equals(fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne une autre opération");return field.decode(receipt.result,Map.class);}
        if(r.quantity==null||r.quantity.signum()<=0||r.quantity.scale()>6||r.quantity.precision()>19)throw new BadRequestException("Quantité positive requise, six décimales maximum");
        StockSegment source=StockSegment.find("id=?1 and account.store=?2",r.sourceId,store).firstResult();if(source==null)throw new NotFoundException();
        freezes.location(store,source.location);
        var now=DateTimeProvider.now();BigDecimal balance=StockSegments.balance(source,now);if(!Objects.equals(revision(source,balance),r.expectedRevision))throw new OperatorFailure(409,"STALE_SEGMENT","Le segment a changé. Actualisez.");if(balance.compareTo(r.quantity)<0)throw new BadRequestException("Quantité supérieure au stock du segment");
        if(source.account.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT&&r.quantity.stripTrailingZeros().scale()>0)throw new BadRequestException("Ce produit exige une quantité entière");
        if(!Set.of("UNASSESSED","AVAILABLE","QUARANTINE","BLOCKED","DAMAGED").contains(r.quality==null?"":r.quality))throw new BadRequestException("Qualité inconnue");
        ManufacturerLot lot=r.lotId==null?null:ManufacturerLot.find("id=?1 and store=?2 and product=?3",r.lotId,store,source.account.product).firstResult();
        ProductSerial serial=r.serialId==null?null:ProductSerial.find("id=?1 and store=?2 and product=?3",r.serialId,store,source.account.product).firstResult();
        PhysicalSupport support=r.supportId==null?null:PhysicalSupport.find("id=?1 and store=?2",r.supportId,store).firstResult();
        if(r.lotId!=null&&lot==null||r.serialId!=null&&serial==null||r.supportId!=null&&support==null)throw new BadRequestException("Lot, série ou support hors contexte");
        if(serial!=null){if(r.quantity.compareTo(BigDecimal.ONE)!=0)throw new BadRequestException("Une série représente exactement une unité");var other=StockMovement.getEntityManager().createQuery("select coalesce(sum(m.quantity),0) from StockMovement m where m.segment.serial=?1 and m.segment<>?2",BigDecimal.class).setParameter(1,serial).setParameter(2,source).getSingleResult();if(other.signum()>0)throw new OperatorFailure(409,"SERIAL_ALREADY_STOCKED","Cette série est déjà en stock");}
        TraceabilityService.validate(store,source.account.product,"RECEIPT",lot,serial);
        // A metadata correction cannot claim that stock physically moved into another support.
        if(!Objects.equals(source.support==null?null:id(source.support),r.supportId))throw new BadRequestException("Le changement de support passe par un mouvement physique");
        String key=field.hash(Arrays.asList(id(source.location),r.supportId,r.lotId,r.serialId,r.quality));
        StockSegment target=StockSegment.find("account=?1 and segmentKey=?2",source.account,key).firstResult();
        if(target==null){target=new StockSegment();target.account=source.account;target.location=source.location;target.support=support;target.lot=lot;target.serial=serial;target.quality=r.quality;target.segmentKey=key;target.receivedAt=source.receivedAt;target.persistAndFlush();}
        if(target.id.equals(source.id))throw new BadRequestException("Le segment cible est identique");
        for(int sign:new int[]{-1,1}){StockMovement movement=new StockMovement();movement.account=source.account;movement.location=source.location;movement.segment=sign<0?source:target;movement.quantity=r.quantity.multiply(BigDecimal.valueOf(sign));movement.eventKey="segment:"+r.requestKey+":"+sign;movement.operationKey="segment:"+r.requestKey;movement.reason="SEGMENT_RECLASSIFICATION";movement.effectiveAt=now;movement.persist();}
        BigDecimal unassigned=r.quantity;
        for(var allocation:PickedStockAllocation.<PickedStockAllocation>list("holding=?1 and shipped=false and quantity>0 order by id",source)){
            if(unassigned.signum()==0)break;BigDecimal amount=unassigned.min(allocation.quantity);var origin=ledger.segment(source.account,allocation.origin.location,allocation.origin.support,lot,serial,r.quality);origin.receivedAt=allocation.origin.receivedAt;
            if(amount.compareTo(allocation.quantity)==0){allocation.holding=target;allocation.origin=origin;}else{allocation.quantity=allocation.quantity.subtract(amount);var split=new PickedStockAllocation();split.state=allocation.state;split.origin=origin;split.holding=target;split.quantity=amount;split.persist();}unassigned=unassigned.subtract(amount);
        }
        if(!com.intermarche.backoffice.engine.stock.StockEligibility.qualityAllowed(target.quality))qualityService.notifySegment(target,r.reason,r.requestKey);
        source.account.alertDirty=true;field.flush();var result=Map.of("sourceId",source.id,"targetId",target.id,"quantity",r.quantity,"totalDelta",BigDecimal.ZERO,"reason",r.reason);
        receipt=new SupportOperation();receipt.store=store;receipt.actor=field.actor();receipt.requestKey=r.requestKey;receipt.fingerprint=fingerprint;receipt.action="SEGMENT_RECLASSIFICATION";receipt.result=field.encode(result);receipt.persist();return result;
    }
}
