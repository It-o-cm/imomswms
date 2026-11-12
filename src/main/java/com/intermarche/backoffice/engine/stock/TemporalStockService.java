package com.intermarche.backoffice.engine.stock;

import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Transactional stock ledger. A store lock serializes writers, including creation and idempotency checks. */
@ApplicationScoped
@Transactional
public class TemporalStockService {
    @Inject EntityManager em;
    @Inject InventoryScopeGuard freezes;
    @Inject StockTraceLedger traceLedger;
    @Inject PickedStockCoordinator pickedTrace;

    public StockAccount account(Long storeId, Long productId) {
        Store store=required(Store.findById(storeId),"Store");
        em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        Product product=required(Product.findById(productId),"Product");
        StockAccount account=StockAccount.find("store = ?1 and product = ?2",store,product).firstResult();
        if (account==null) { account=new StockAccount();account.store=store;account.product=product;account.persistAndFlush(); }
        return account;
    }
    private StockAccount locked(Long id) {
        StockAccount account=required(StockAccount.findById(id),"Stock account");
        em.lock(account.store,LockModeType.PESSIMISTIC_WRITE);
        // Preserve prior operations in this transaction before refreshing lock-protected state.
        em.flush();
        em.refresh(account);
        return account;
    }
    public StockAccount setMargin(Long id,BigDecimal margin) {
        nonNegative(margin);StockAccount account=locked(id);account.safetyMargin=margin;account.alertDirty=true;return account;
    }
    private Building building(StockAccount account,BaseLocation location) {
        required(location,"Location");
        List<? extends Building> buildings=BaseLocation.findBuildingsByLocations(List.of(location));
        if (buildings.size()!=1) throw new IllegalArgumentException("Location must belong to exactly one building");
        Building building=buildings.get(0);checkBuilding(account,building);
        if (location.product!=null && !persistentId(location.product).equals(persistentId(account.product))) throw new IllegalArgumentException("Location assigned to another product");
        return building;
    }
    private void checkBuilding(StockAccount account,Building building) {
        required(building,"Building"); Store owner=Building.findStoreByBuildingId(persistentId(building));
        if (owner==null || !persistentId(owner).equals(persistentId(account.store))) throw new IllegalArgumentException("Building " + building.buildingCode + " outside stock account store " + account.store.code);
    }
    private BigDecimal balance(StockAccount account,BaseLocation location,LocalDateTime at) {
        return StockMovement.<StockMovement>list("account = ?1 and location = ?2 and effectiveAt <= ?3",account,location,at)
                .stream().map(m -> m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private void write(StockAccount account,BaseLocation location,String key,BigDecimal delta,LocalDateTime at,String reason) {
        account.alertDirty=true;
        if(delta.signum()>=0){writeSegment(account,location,StockSegments.legacy(account,location),key,key,delta,at,reason);return;}
        // Consume actual segments rather than making the historical unqualified segment negative.
        var rows=em.createQuery("select s,coalesce(sum(m.quantity),0) from StockSegment s left join StockMovement m on m.segment=s and m.effectiveAt<=?3 where s.account=?1 and s.location=?2 group by s order by s.id",Object[].class).setParameter(1,account).setParameter(2,location).setParameter(3,at).getResultList();
        var later=new HashMap<Long,List<BigDecimal>>();
        for(var movement:em.createQuery("select m.segment.id,m.quantity from StockMovement m where m.account=?1 and m.location=?2 and m.effectiveAt>?3 order by m.effectiveAt,m.id",Object[].class).setParameter(1,account).setParameter(2,location).setParameter(3,at).getResultList())later.computeIfAbsent((Long)movement[0],ignored->new ArrayList<>()).add((BigDecimal)movement[1]);
        BigDecimal remaining=delta.negate();boolean first=true;
        for(var row:rows){BigDecimal balance=(BigDecimal)row[1];if(balance.signum()<=0)continue;var segment=(StockSegment)row[0];BigDecimal running=balance;for(var change:later.getOrDefault(segment.id,List.of())){running=running.add(change);balance=balance.min(running);}if(balance.signum()<=0)continue;BigDecimal amount=remaining.min(balance);String event=first?key:"part:"+com.intermarche.backoffice.engine.operator.CommandCanonical.hash(com.fasterxml.jackson.databind.node.TextNode.valueOf(key).toString())+":"+segment.id;writeSegment(account,location,segment,event,key,amount.negate(),at,reason);remaining=remaining.subtract(amount);first=false;if(remaining.signum()==0)break;}
        if(remaining.signum()>0){
            if(!reason.startsWith("PICKING_"))throw new IllegalArgumentException("Stock segmenté insuffisant à la date du mouvement");
            // A confirmed physical observation must survive an incomplete imported inventory.
            // Its unqualified negative balance exposes the discrepancy; it never invents a manufacturer lot.
            var unknown=StockSegments.legacy(account,location);String event=first?key:"part:"+com.intermarche.backoffice.engine.operator.CommandCanonical.hash(com.fasterxml.jackson.databind.node.TextNode.valueOf(key).toString())+":unknown";writeSegment(account,location,unknown,event,key,remaining.negate(),at,reason);
        }
    }
    private void writeSegment(StockAccount account,BaseLocation location,StockSegment segment,String event,String operation,BigDecimal delta,LocalDateTime at,String reason){
        freezes.location(account.store,location);
        if(delta.signum()>0&&(segment.receivedAt==null||segment.receivedAt.isAfter(at)))segment.receivedAt=at;
        StockMovement movement=new StockMovement();movement.account=account;movement.location=location;movement.segment=segment;movement.eventKey=event;movement.operationKey=operation;movement.quantity=delta;movement.effectiveAt=at;movement.reason=reason;movement.persist();
    }
    private boolean duplicate(StockAccount account,String key,BaseLocation location,BigDecimal delta,LocalDateTime at,String reason) {
        text(key,160);var previous=StockMovement.<StockMovement>list("account=?1 and (eventKey=?2 or operationKey=?2)",account,key);
        if(previous.isEmpty())return false;
        if(previous.stream().anyMatch(m->!persistentId(m.location).equals(location.id)||!m.effectiveAt.equals(at)||!m.reason.equals(reason))||previous.stream().map(m->m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(delta)!=0)throw new IllegalArgumentException("Event key already used for different stock operation");
        return true;
    }
    private void actualTime(LocalDateTime at) {
        if (at==null || at.isAfter(DateTimeProvider.now())) throw new IllegalArgumentException("Physical movement cannot be in the future");
    }
    public StockInventoryCorrection setPhysical(Long accountId,Long locationId,String eventKey,BigDecimal expectedQuantity,BigDecimal quantity,String reason) {
        nonNegative(quantity);nonNegative(expectedQuantity);text(eventKey,100);text(reason,200);
        StockAccount account=locked(accountId);BaseLocation location=required(BaseLocation.findById(locationId),"Location");building(account,location);
        if(org.hibernate.Hibernate.unproxy(location) instanceof PickingToteLocation || org.hibernate.Hibernate.unproxy(location) instanceof ParcelStockLocation || org.hibernate.Hibernate.unproxy(location) instanceof ReturnTransitLocation || org.hibernate.Hibernate.unproxy(location) instanceof PendingSupplyLocation || org.hibernate.Hibernate.unproxy(location) instanceof BufferToteStockLocation)throw new IllegalArgumentException("Le stock des bacs se corrige via leur manutention");
        if(account.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && quantity.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Une quantité entière est requise pour ce produit");
        StockInventoryCorrection old=StockInventoryCorrection.find("account = ?1 and eventKey = ?2",account,eventKey).firstResult();
        if(old!=null){if(!persistentId(old.location).equals(locationId) || old.quantity.compareTo(quantity)!=0 || old.previousQuantity.compareTo(expectedQuantity)!=0 || !old.reason.equals(reason))throw new IllegalArgumentException("Référence de correction déjà utilisée");return old;}
        LocalDateTime now=DateTimeProvider.now();BigDecimal previous=balance(account,location,now);
        if(previous.compareTo(expectedQuantity)!=0)throw new IllegalArgumentException("Le stock a changé depuis l’ouverture. Actualisez avant de corriger");
        BigDecimal delta=quantity.subtract(previous);
        if(delta.signum()!=0){ensureNonNegativeHistory(account,location,delta,now);write(account,location,"inventory:"+eventKey,delta,now,"INVENTORY_CORRECTION");}
        StockInventoryCorrection correction=new StockInventoryCorrection();correction.account=account;correction.location=location;correction.eventKey=eventKey;correction.previousQuantity=previous;correction.quantity=quantity;correction.reason=reason;correction.persist();return correction;
    }
    /** Approved count for one physical address and exact manufacturer identity. */
    public StockInventoryCorrection countedIdentity(Long storeId,Long productId,Collection<Long> locationIds,ManufacturerLot lot,ProductSerial serial,BigDecimal expected,BigDecimal quantity,String key,String actor,String reason) {
        nonNegative(quantity);text(key,100);text(reason,200);StockAccount account=account(storeId,productId);
        if(locationIds.isEmpty())throw new IllegalArgumentException("Une adresse physique est requise avant application");
        var rows=em.createQuery("select s,sum(m.quantity) from StockMovement m join m.segment s where m.account=?1 and m.location.id in ?2 and ((?3 is null and s.lot is null) or s.lot.id=?3) and ((?4 is null and s.serial is null) or s.serial.id=?4) and m.effectiveAt<=?5 group by s order by s.id",Object[].class).setParameter(1,account).setParameter(2,locationIds).setParameter(3,lot==null?null:persistentId(lot)).setParameter(4,serial==null?null:persistentId(serial)).setParameter(5,DateTimeProvider.now()).getResultList();
        BigDecimal current=rows.stream().map(r->(BigDecimal)r[1]).reduce(BigDecimal.ZERO,BigDecimal::add);if(current.compareTo(expected)!=0)throw new IllegalArgumentException("Le stock a changé depuis l’approbation");
        var old=StockInventoryCorrection.<StockInventoryCorrection>find("account=?1 and eventKey=?2",account,key).firstResult();if(old!=null)return old;
        if(account.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT&&quantity.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Quantité entière requise");
        if(serial!=null){if(quantity.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("Une série représente au plus une unité");BigDecimal global=em.createQuery("select coalesce(sum(m.quantity),0) from StockMovement m where m.segment.serial=?1",BigDecimal.class).setParameter(1,serial).getSingleResult();if(global.subtract(current).add(quantity).compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("Cette série est déjà présente à une autre adresse : corrigez sa position");}
        BaseLocation location=BaseLocation.findById(locationIds.iterator().next());building(account,location);BigDecimal delta=quantity.subtract(current);LocalDateTime now=DateTimeProvider.now();
        var protectedQuantities=new HashMap<Long,BigDecimal>();var segmentIds=rows.stream().map(r->persistentId((StockSegment)r[0])).toList();if(!segmentIds.isEmpty())for(var allocation:em.createQuery("select a.holding.id,sum(a.quantity) from PickedStockAllocation a where a.holding.id in ?1 and a.shipped=false group by a.holding.id",Object[].class).setParameter(1,segmentIds).getResultList())protectedQuantities.put((Long)allocation[0],(BigDecimal)allocation[1]);
        // Reconcile negative book subdivisions first; the approved total is unchanged by these paired entries.
        for(var row:rows){BigDecimal book=(BigDecimal)row[1];if(book.signum()<0){StockSegment segment=(StockSegment)row[0];traceLedger.entry(segment,book.negate(),"inventory:"+key,"debt:"+segment.id,"INVENTORY_CORRECTION",now);delta=delta.add(book);row[1]=BigDecimal.ZERO;}}
        if(delta.signum()<0){BigDecimal remaining=delta.negate();for(var row:rows){BigDecimal balance=(BigDecimal)row[1];StockSegment segment=(StockSegment)row[0];BigDecimal removable=balance.subtract(protectedQuantities.getOrDefault(persistentId(segment),BigDecimal.ZERO)).max(BigDecimal.ZERO);if(removable.signum()==0)continue;BigDecimal take=remaining.min(removable);traceLedger.entry(segment,take.negate(),"inventory:"+key,"out:"+segment.id,"INVENTORY_CORRECTION",now);remaining=remaining.subtract(take);if(remaining.signum()==0)break;}if(remaining.signum()>0)throw new IllegalArgumentException("Les quantités conservées pour les commandes ne permettent pas cette correction. Actualisez les affectations.");}
        if(delta.signum()>0){var segment=traceLedger.segment(account,location,null,lot,serial,"UNASSESSED");traceLedger.entry(segment,delta,"inventory:"+key,"in","INVENTORY_CORRECTION",now);}
        var result=new StockInventoryCorrection();result.account=account;result.location=location;result.eventKey=key;result.previousQuantity=current;result.quantity=quantity;result.reason=reason;result.performedBy=actor;result.persist();account.alertDirty=true;return result;
    }
    /** Initial inventory, POS sale, loss or inventory correction. Delta is signed. */
    public StockMovement adjust(Long id,Long locationId,String eventKey,BigDecimal delta,LocalDateTime at) {
        amount(delta); if (delta.signum()==0) throw new IllegalArgumentException("Zero movement");actualTime(at);
        StockAccount account=locked(id);BaseLocation location=required(BaseLocation.findById(locationId),"Location");building(account,location);
        if (!duplicate(account,eventKey,location,delta,at,"ADJUSTMENT")) {
            ensureNonNegativeHistory(account,location,delta,at);write(account,location,eventKey,delta,at,"ADJUSTMENT");
        }
        return StockMovement.find("account = ?1 and eventKey = ?2",account,eventKey).firstResult();
    }
    // Backdated corrections may not make any subsequent physical balance negative either.
    private void ensureNonNegativeHistory(StockAccount account,BaseLocation location,BigDecimal delta,LocalDateTime at) {
        BigDecimal value=balance(account,location,at).add(delta);
        if (value.signum()<0) throw new IllegalArgumentException("Insufficient physical stock");
        for (StockMovement m:StockMovement.<StockMovement>list("account = ?1 and location = ?2 and effectiveAt > ?3 order by effectiveAt,id",account,location,at)) {
            value=value.add(m.quantity);if (value.signum()<0) throw new IllegalArgumentException("Correction makes later stock negative");
        }
    }
    /** Atomic relocation: preserves global and building stock, with an idempotent pair of ledger entries. */
    public void moveToProtection(Long accountId,Long sourceId,Long destinationId,String eventKey,BigDecimal qty,LocalDateTime at) {
        positive(qty);actualTime(at);text(eventKey,130);StockAccount account=locked(accountId);
        BaseLocation source=required(BaseLocation.findById(sourceId),"Source"),destination=required(BaseLocation.findById(destinationId),"Destination");
        source=org.hibernate.Hibernate.unproxy(source,BaseLocation.class);destination=org.hibernate.Hibernate.unproxy(destination,BaseLocation.class);
        if(!(source instanceof StoreShelfLocation) || !(destination instanceof PickingToteLocation tote) || tote.protectedTote==null)throw new IllegalArgumentException("Un rayon source et un bac de picking affecté sont requis");
        if(!building(account,source).id.equals(building(account,destination).id))throw new IllegalArgumentException("La mise en sécurité doit rester dans le même bâtiment");
        if(duplicate(account,eventKey+":in",destination,qty,at,"STOCK_PROTECTION"))return;
        ensureNonNegativeHistory(account,source,qty.negate(),at);int part=0;
        for(var slice:traceLedger.allocate(account.store,account.product,source,qty,null,null,null,"PICKING")){
            var target=traceLedger.segment(account,destination,null,slice.segment().lot,slice.segment().serial,slice.segment().quality);target.receivedAt=slice.segment().receivedAt;
            String suffix=part++==0?"":":"+part;
            writeSegment(account,source,slice.segment(),eventKey+":out"+suffix,eventKey+":out",slice.quantity().negate(),at,"STOCK_PROTECTION");
            writeSegment(account,destination,target,eventKey+":in"+suffix,eventKey+":in",slice.quantity(),at,"STOCK_PROTECTION");
        }
    }
    public ExpectedSupply planSupply(Long id,String reference,Long sourceId,Long destinationId,BigDecimal qty,LocalDateTime availableAt) {
        positive(qty);Objects.requireNonNull(availableAt);text(reference,120);
        StockAccount account=locked(id);BaseLocation destination=required(BaseLocation.findById(destinationId),"Destination");building(account,destination);
        BaseLocation source=sourceId==null?null:required(BaseLocation.findById(sourceId),"Source");
        if (source!=null) {building(account,source);if (source.id.equals(destination.id)) throw new IllegalArgumentException("Identical transfer endpoints");}
        ExpectedSupply old=ExpectedSupply.find("account = ?1 and reference = ?2",account,reference).firstResult();
        if (old!=null) {
            if (!Objects.equals(old.source,source)||!old.destination.equals(destination)||old.quantity.compareTo(qty)!=0||!old.availableAt.equals(availableAt))
                throw new IllegalArgumentException("Supply reference already exists; use reviseSupply");
            return old;
        }
        ExpectedSupply supply=new ExpectedSupply();supply.account=account;supply.reference=reference;supply.source=source;supply.destination=destination;
        supply.quantity=qty;supply.availableAt=availableAt;supply.persist();return dirty(supply);
    }
    public ExpectedSupply reviseSupply(Long supplyId,BigDecimal expectedTotal,LocalDateTime availableAt,boolean cancel) {
        ExpectedSupply supply=required(ExpectedSupply.findById(supplyId),"Supply");locked(supply.account.id);em.refresh(supply);
        nonNegative(expectedTotal);Objects.requireNonNull(availableAt);
        if (supply.status!=ExpectedSupply.SupplyStatus.EXPECTED || expectedTotal.compareTo(supply.pickedQuantity())<0) throw new IllegalArgumentException("Supply is closed or quantity below receipts");
        supply.quantity=expectedTotal;supply.availableAt=availableAt;
        if (cancel || expectedTotal.compareTo(supply.received)==0) supply.status=cancel?ExpectedSupply.SupplyStatus.CANCELLED:ExpectedSupply.SupplyStatus.RECEIVED;
        return dirty(supply);
    }
    /** Warehouse issue is separate from shelf receipt; the outstanding picked quantity is in transit. */
    public ExpectedSupply pick(Long supplyId,String eventKey,BigDecimal qty,LocalDateTime at) {return pick(supplyId,eventKey,qty,at,null);}
    public ExpectedSupply pick(Long supplyId,String eventKey,BigDecimal qty,LocalDateTime at,Long locationId) {return pick(supplyId,eventKey,qty,at,locationId,null);}
    public ExpectedSupply pick(Long supplyId,String eventKey,BigDecimal qty,LocalDateTime at,Long locationId,com.intermarche.backoffice.engine.executing.OperatorCommandInput hint) {
        positive(qty);actualTime(at);text(eventKey,130);
        ExpectedSupply supply=required(ExpectedSupply.findById(supplyId),"Supply");StockAccount account=locked(persistentId(supply.account));em.refresh(supply);
        if(supply.source==null)throw new IllegalArgumentException("Prélèvement réservé aux transferts internes");
        if(account.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && qty.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Quantité entière requise");
        BaseLocation actual=locationId==null?supply.source:required(BaseLocation.findById(locationId),"Location");building(account,actual);
        String key="picking:"+eventKey;
        if(duplicate(account,key,actual,qty.negate(),at,"PICKING_"+supply.id)){verifyTraceReplay(account,key,hint);return dirty(supply);}
        if(supply.status!=ExpectedSupply.SupplyStatus.EXPECTED || supply.pickedQuantity().add(qty).compareTo(supply.quantity)>0)throw new IllegalArgumentException("Quantité supérieure au prélèvement restant");
        // An operator confirms an observed physical pick. A stale ledger balance is
        // a stock discrepancy to expose, not grounds to reject the real movement.
        // Keep the signed movement and its idempotency key; do not invent an inventory adjustment.
        var identity=pickedTrace.identity(account.store,account.product,hint);int part=0;
        for(var slice:traceLedger.allocate(account.store,account.product,actual,qty,hint==null?null:hint.stockSegmentId,identity.lot(),identity.serial(),"PICKING")){
            writeSegment(account,actual,slice.segment(),part++==0?key:key+":"+part,key,slice.quantity().negate(),at,"PICKING_"+supply.id);
            var allocation=new SupplyStockAllocation();allocation.supply=supply;allocation.origin=slice.segment();allocation.quantity=slice.quantity();allocation.eventKey=key;allocation.persist();
        }
        supply.picked=supply.pickedQuantity().add(qty);return dirty(supply);
    }
    private void verifyTraceReplay(StockAccount account,String key,com.intermarche.backoffice.engine.executing.OperatorCommandInput hint){
        if(hint==null)return;var identity=pickedTrace.identity(account.store,account.product,hint);
        for(var movement:StockMovement.<StockMovement>list("account=?1 and (eventKey=?2 or operationKey=?2)",account,key)){
            var segment=movement.segment;if(hint.stockSegmentId!=null&&key.startsWith("picking:")&&!Objects.equals(persistentId(segment),hint.stockSegmentId)||identity.lot()!=null&&!Objects.equals(segment.lot==null?null:persistentId(segment.lot),persistentId(identity.lot()))||identity.serial()!=null&&!Objects.equals(segment.serial==null?null:persistentId(segment.serial),persistentId(identity.serial())))throw new IllegalArgumentException("Cette confirmation a déjà désigné un autre lot, segment ou numéro de série");
        }
    }
    /** Correct an accidental confirmation using compensating immutable ledger movements. */
    public ExpectedSupply reopen(Long supplyId,String eventKey,boolean source,LocalDateTime at) {
        text(eventKey,130);actualTime(at);
        ExpectedSupply supply=required(ExpectedSupply.findById(supplyId),"Supply");StockAccount account=locked(persistentId(supply.account));em.refresh(supply);
        String key="reopen:"+eventKey;
        StockMovement previous=StockMovement.find("account = ?1 and eventKey = ?2",account,key).firstResult();
        if(previous!=null){if(!previous.reason.equals(source?"PICKING_"+supply.id:"SUPPLY_"+supply.id+"_PARTIAL"))throw new IllegalArgumentException("Référence déjà utilisée");return dirty(supply);}
        BigDecimal quantity=source?supply.pickedQuantity():supply.received;
        if(quantity.signum()<=0)throw new IllegalArgumentException("Aucune confirmation à réouvrir");
        if(source){
            if(supply.source==null || supply.received.signum()>0)throw new IllegalArgumentException("Réouvrez d’abord la mise en rayon avant le prélèvement");
            reverseSupplyLocations(account,supply,key,at,true);supply.picked=BigDecimal.ZERO;
        }else{
            reverseSupplyLocations(account,supply,key,at,false);
            // Legacy transfers made before the two-stage workflow already debited the warehouse.
            if(supply.source!=null && (supply.picked==null || supply.picked.signum()==0)){
                write(account,supply.source,key+":legacy",quantity,at,"REOPEN_LEGACY");
            }
            supply.received=BigDecimal.ZERO;
        }
        supply.status=ExpectedSupply.SupplyStatus.EXPECTED;return dirty(supply);
    }
    private void reverseSupplyLocations(StockAccount account,ExpectedSupply supply,String key,LocalDateTime at,boolean source) {
        String reason=source?"PICKING_"+supply.id:"SUPPLY_"+supply.id+"_PARTIAL";
        Map<Long,BigDecimal> balances=new LinkedHashMap<>();Map<Long,StockSegment> segments=new HashMap<>();
        for(StockMovement movement:StockMovement.<StockMovement>list("account=?1 and reason in ?2 order by id",account,source?List.of(reason):List.of(reason,"SUPPLY_"+supply.id+"_FINAL"))) {
            if(!source && movement.eventKey.endsWith(":out"))continue;Long segmentId=persistentId(movement.segment);balances.merge(segmentId,movement.quantity,BigDecimal::add);segments.put(segmentId,movement.segment);
        }
        boolean first=true;
        for(var entry:balances.entrySet())if(entry.getValue().signum()!=0) {
            var segment=segments.get(entry.getKey());BaseLocation location=segment.location;BigDecimal delta=entry.getValue().negate();
            if(!source){ensureNonNegativeHistory(account,location,delta,at);if(StockSegments.balance(segment,at).compareTo(delta.negate())<0)throw new IllegalArgumentException("Les unités de ce lot ont déjà été déplacées ; corrigez leur emplacement avant de rouvrir");}
            String event=first?key:key+":"+entry.getKey();writeSegment(account,location,segment,event,key,delta,at,reason);first=false;
        }
        for(var allocation:SupplyStockAllocation.<SupplyStockAllocation>list("supply",supply)){if(source)allocation.quantity=BigDecimal.ZERO;allocation.received=BigDecimal.ZERO;}
    }
    private BigDecimal transitAt(ExpectedSupply supply,LocalDateTime at) {
        BigDecimal picked=StockMovement.<StockMovement>list("account = ?1 and reason = ?2 and effectiveAt <= ?3",supply.account,"PICKING_"+supply.id,at).stream().map(m -> m.quantity.negate()).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(picked.signum()==0)return BigDecimal.ZERO;
        BigDecimal received=StockMovement.<StockMovement>list("account = ?1 and effectiveAt <= ?2 and (reason = ?3 or reason = ?4)",supply.account,at,"SUPPLY_"+supply.id+"_PARTIAL","SUPPLY_"+supply.id+"_FINAL").stream().filter(m->!m.eventKey.endsWith(":out")).map(m -> m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
        return picked.subtract(received).max(BigDecimal.ZERO);
    }
    /** Partial receipts keep the outstanding ETA; finalReceipt closes any shortfall. Transfers are atomic. */
    public ExpectedSupply receive(Long supplyId,String eventKey,BigDecimal qty,LocalDateTime at,boolean finalReceipt) {return receive(supplyId,eventKey,qty,at,finalReceipt,null);}
    public ExpectedSupply receive(Long supplyId,String eventKey,BigDecimal qty,LocalDateTime at,boolean finalReceipt,Long locationId) {return receive(supplyId,eventKey,qty,at,finalReceipt,locationId,null);}
    public ExpectedSupply receive(Long supplyId,String eventKey,BigDecimal qty,LocalDateTime at,boolean finalReceipt,Long locationId,com.intermarche.backoffice.engine.executing.OperatorCommandInput hint) {
        positive(qty);actualTime(at);text(eventKey,130);
        ExpectedSupply supply=required(ExpectedSupply.findById(supplyId),"Supply");StockAccount account=locked(supply.account.id);em.refresh(supply);
        BaseLocation actual=locationId==null?supply.destination:required(BaseLocation.findById(locationId),"Location");building(account,actual);
        String key="receipt:"+eventKey;
        String reason="SUPPLY_"+supply.id+(finalReceipt?"_FINAL":"_PARTIAL");
        if (duplicate(account,key,actual,qty,at,reason)){verifyTraceReplay(account,key,hint);return dirty(supply);}
        if (supply.status!=ExpectedSupply.SupplyStatus.EXPECTED || supply.received.add(qty).compareTo(supply.quantity)>0) throw new IllegalArgumentException("Supply closed or receipt exceeds expected quantity");
        if (supply.source!=null && supply.picked!=null && supply.picked.signum()>0) {
            if(supply.received.add(qty).compareTo(supply.pickedQuantity())>0)throw new IllegalArgumentException("Quantité non prélevée dans l’entrepôt");
        } else if (supply.source!=null) {
            ensureNonNegativeHistory(account,supply.source,qty.negate(),at);int part=0;
            for(var slice:traceLedger.allocate(account.store,account.product,supply.source,qty,hint==null?null:hint.stockSegmentId,null,null,"PICKING")){
                String event=part++==0?key+":out":key+":part-"+part+":out";writeSegment(account,supply.source,slice.segment(),event,key+":out",slice.quantity().negate(),at,reason);
                var allocation=new SupplyStockAllocation();allocation.supply=supply;allocation.origin=slice.segment();allocation.quantity=slice.quantity();allocation.eventKey=key+":out";allocation.persist();
            }
        }
        var allocations=SupplyStockAllocation.<SupplyStockAllocation>list("select a from SupplyStockAllocation a join fetch a.origin where a.supply=?1 and a.quantity>a.received order by a.id",supply);
        if(!allocations.isEmpty()){
            BigDecimal remaining=qty;int part=0;
            for(var allocation:allocations){if(remaining.signum()==0)break;StockQualityRules.requireSegment(allocation.origin,"RECEIPT");BigDecimal amount=remaining.min(allocation.quantity.subtract(allocation.received));var destination=traceLedger.segment(account,actual,null,allocation.origin.lot,allocation.origin.serial,allocation.origin.quality);destination.receivedAt=allocation.origin.receivedAt;writeSegment(account,actual,destination,part++==0?key:key+":"+part,key,amount,at,reason);allocation.received=allocation.received.add(amount);remaining=remaining.subtract(amount);}
            if(remaining.signum()>0)throw new IllegalArgumentException("Traçabilité de transit insuffisante");
        }else{
            var identity=pickedTrace.identity(account.store,account.product,hint);var destination=traceLedger.segment(account,actual,null,identity.lot(),identity.serial(),"UNASSESSED");StockQualityRules.requireSegment(destination,"RECEIPT");
            if(identity.serial()!=null&&qty.compareTo(BigDecimal.ONE)!=0)throw new IllegalArgumentException("Une série représente une seule unité");
            if(identity.serial()!=null&&StockTraceLedger.serialOccupied(identity.serial(),null))throw new IllegalArgumentException("Cette série est déjà présente en stock");
            writeSegment(account,actual,destination,key,key,qty,at,reason);
        }
        supply.received=supply.received.add(qty);
        if (finalReceipt || supply.received.compareTo(supply.quantity)==0) supply.status=ExpectedSupply.SupplyStatus.RECEIVED;
        return dirty(supply);
    }
    /**
     * Enters produced finished goods into stock under a manufacturer lot, at a location (catering F13,
     * T3.046). The lot (store, product, code) is created on first use; the quantity is added as a
     * positive movement on the lot segment. Idempotent on the event key.
     *
     * @param accountId      the finished-product stock account id.
     * @param locationId     the destination location (the support's internal address).
     * @param lotCode        the manufacturer lot code.
     * @param manufacturedOn the production date.
     * @param useBy          the use-by date.
     * @param qty            the produced quantity.
     * @param eventKey       the idempotency key.
     * @param at             the action instant.
     * @return the manufacturer lot the stock was entered under.
     */
    public ManufacturerLot produce(Long accountId,Long locationId,String lotCode,java.time.LocalDate manufacturedOn,java.time.LocalDate useBy,BigDecimal qty,String eventKey,LocalDateTime at){
        positive(qty);actualTime(at);text(eventKey,130);
        StockAccount account=locked(accountId);
        BaseLocation location=required(BaseLocation.findById(locationId),"Location");building(account,location);
        Product product=org.hibernate.Hibernate.unproxy(account.product,Product.class);
        ManufacturerLot lot=ManufacturerLot.<ManufacturerLot>find("store=?1 and product=?2 and code=?3",account.store,product,lotCode).firstResult();
        if(lot==null){lot=new ManufacturerLot();lot.store=account.store;lot.product=product;lot.code=lotCode;lot.manufacturedOn=manufacturedOn;lot.useBy=useBy;lot.bestBefore=useBy;lot.persist();}
        String key="produce:"+eventKey,reason="FABRICATION";
        if(duplicate(account,key,location,qty,at,reason))return lot;
        StockSegment segment=traceLedger.segment(account,location,null,lot,null,"UNASSESSED");
        writeSegment(account,location,segment,key,key,qty,at,reason);
        return lot;
    }
    /** Import an existing commitment even in shortage, or enforce ATP for a new promise. */
    public StockCommitment reserve(Long id,String reference,Long buildingId,BigDecimal qty,LocalDateTime preparationAt,boolean enforceAvailability) {
        positive(qty);Objects.requireNonNull(preparationAt);text(reference,120);
        StockAccount account=locked(id);Building building=required(Building.findById(buildingId),"Building");checkBuilding(account,building);
        StockCommitment old=StockCommitment.find("account = ?1 and reference = ?2",account,reference).firstResult();
        if (old!=null) {
            if (!old.building.equals(building)||old.quantity.compareTo(qty)!=0||!old.preparationAt.equals(preparationAt)) throw new IllegalArgumentException("Commitment reference already exists");
            return old;
        }
        if (enforceAvailability) {
            if (preparationAt.isBefore(DateTimeProvider.now())) throw new IllegalArgumentException("Preparation deadline is in the past");
            var timeline=availability(id,buildingId,DateTimeProvider.now());
            BigDecimal available=timeline.periods.get(0).availableToPromise;
            for (var p:timeline.periods) {if (p.from.isAfter(preparationAt)) break;available=p.availableToPromise;}
            if (available.compareTo(qty)<0) throw new IllegalArgumentException("Insufficient availability at preparation deadline");
        }
        StockCommitment c=new StockCommitment();c.account=account;c.reference=reference;c.building=building;c.quantity=qty;c.remainingQuantity=qty;c.preparationAt=preparationAt;c.persist();return dirty(c);
    }
    public StockCommitment cancelCommitment(Long commitmentId) {
        StockCommitment c=required(StockCommitment.findById(commitmentId),"Commitment");locked(c.account.id);em.refresh(c);
        if (c.status==StockCommitment.CommitmentStatus.ACTIVE) {c.status=StockCommitment.CommitmentStatus.CANCELLED;c.remainingQuantity=BigDecimal.ZERO;}
        return dirty(c);
    }
    /** Reservation consumption and physical issue are one transaction: no transient false shortage. */
    public StockCommitment prepare(Long commitmentId,Long locationId,String eventKey,BigDecimal qty,LocalDateTime at) {
        positive(qty);actualTime(at);text(eventKey,130);
        StockCommitment c=required(StockCommitment.findById(commitmentId),"Commitment");StockAccount account=locked(c.account.id);em.refresh(c);
        BaseLocation location=required(BaseLocation.findById(locationId),"Location");
        if (!building(account,location).id.equals(c.building.id)) throw new IllegalArgumentException("Preparation in wrong building");
        String key="prepare:"+eventKey,reason="COMMITMENT_"+c.id;
        if (duplicate(account,key,location,qty.negate(),at,reason)) return dirty(c);
        if (c.status!=StockCommitment.CommitmentStatus.ACTIVE || c.remainingQuantity.compareTo(qty)<0) throw new IllegalArgumentException("Commitment closed or preparation exceeds remaining quantity");
        ensureNonNegativeHistory(account,location,qty.negate(),at);int part=0;
        for(var slice:traceLedger.allocate(account.store,account.product,location,qty,null,null,null,"PICKING")){writeSegment(account,location,slice.segment(),part++==0?key:key+":"+part,key,slice.quantity().negate(),at,reason);}
        c.remainingQuantity=c.remainingQuantity.subtract(qty);
        if (c.remainingQuantity.signum()==0) c.status=StockCommitment.CommitmentStatus.PREPARED;
        return dirty(c);
    }
    public static class Position {
        public Long locationId;
        public String locationCode;
        public Long buildingId;
        public String buildingCode;
        public BigDecimal quantity;
    }
    public List<Position> positions(Long id) {
        StockAccount account=locked(id);
        List<Position> result=new ArrayList<>();
        Map<Long,BigDecimal> balances=new HashMap<>();
        for(StockMovement m:StockMovement.<StockMovement>list("account = ?1 and effectiveAt <= ?2",account,DateTimeProvider.now()))
            balances.merge(persistentId(m.location),m.quantity,BigDecimal::add);
        if(balances.isEmpty())return result;
        for(Object[] row:em.createQuery("select l.id,l.locationCode,b.id,b.buildingCode from Store s join s.buildings b join b.locations l where s = ?1 and l.id in ?2",Object[].class).setParameter(1,account.store).setParameter(2,balances.keySet()).getResultList()) {
            BigDecimal qty=balances.getOrDefault((Long)row[0],BigDecimal.ZERO);if(qty.signum()==0)continue;
            Position p=new Position();p.locationId=(Long)row[0];p.locationCode=(String)row[1];p.buildingId=(Long)row[2];p.buildingCode=(String)row[3];p.quantity=qty;result.add(p);
        }
        result.sort(Comparator.comparing((Position p) -> p.buildingCode).thenComparing(p -> p.locationCode));
        return result;
    }

    /** Adjust an unprepared commitment, or revise the remaining part of a partially prepared line. */
    public StockCommitment reviseCommitment(Long commitmentId,BigDecimal totalQuantity,LocalDateTime preparationAt,boolean enforceAvailability) {
        positive(totalQuantity);Objects.requireNonNull(preparationAt);
        StockCommitment c=required(StockCommitment.findById(commitmentId),"Commitment");locked(c.account.id);em.refresh(c);
        if (c.status!=StockCommitment.CommitmentStatus.ACTIVE) throw new IllegalArgumentException("Commitment is closed");
        BigDecimal prepared=c.quantity.subtract(c.remainingQuantity);
        if (totalQuantity.compareTo(prepared)<0) throw new IllegalArgumentException("Quantity below already prepared units");
        BigDecimal remaining=totalQuantity.subtract(prepared);
        if (enforceAvailability && remaining.signum()>0) {
            if (preparationAt.isBefore(DateTimeProvider.now())) throw new IllegalArgumentException("Preparation deadline is in the past");
            var timeline=project(c.account.id,c.building.id,DateTimeProvider.now(),c.id);
            BigDecimal available=timeline.periods.get(0).availableToPromise;
            for (var p:timeline.periods) {if(p.from.isAfter(preparationAt)) break;available=p.availableToPromise;}
            if(available.compareTo(remaining)<0) throw new IllegalArgumentException("Insufficient availability at revised deadline");
        }
        c.quantity=totalQuantity;c.remainingQuantity=remaining;c.preparationAt=preparationAt;
        if(remaining.signum()==0) c.status=StockCommitment.CommitmentStatus.PREPARED;
        return dirty(c);
    }

    /** A scope is under pressure when it cannot promise another unit, or has unsupported transfers. */
    public List<StockAccount> accountsInTension(Long storeId,Long buildingId) {
        Store store=required(Store.findById(storeId),"Store");
        em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        if(buildingId!=null) {
            Building building=required(Building.findById(buildingId),"Building");
            Store owner=Building.findStoreByBuildingId(persistentId(building));
            if(owner==null || !owner.id.equals(storeId)) throw new IllegalArgumentException("Building outside requested store");
        }
        return StockAccount.<StockAccount>list("store",store).stream().filter(account -> {
            var timeline=availability(account.id,buildingId,DateTimeProvider.now());
            return timeline.periods.get(0).availableToPromise.signum()==0 || timeline.unsupportedTransferQuantity.signum()>0;
        }).toList();
    }

    public static class Promise {
        public BigDecimal requestedQuantity;
        public LocalDateTime preparationAt;
        public BigDecimal availableToPromise;
        public boolean canPromise;
        public LocalDateTime nextAvailableAt;
    }
    /** Preview only. reserve(..., true) repeats this check under the writer lock when accepting an order. */
    public Promise quote(Long accountId,Long buildingId,BigDecimal quantity,LocalDateTime preparationAt) {
        positive(quantity);Objects.requireNonNull(preparationAt);
        LocalDateTime now=DateTimeProvider.now();
        if(preparationAt.isBefore(now)) throw new IllegalArgumentException("Preparation deadline is in the past");
        var timeline=availability(accountId,buildingId,now);
        Promise result=new Promise();result.requestedQuantity=quantity;result.preparationAt=preparationAt;
        result.availableToPromise=timeline.periods.get(0).availableToPromise;
        for(var p:timeline.periods) {
            if(!p.from.isAfter(preparationAt)) result.availableToPromise=p.availableToPromise;
        }
        result.canPromise=result.availableToPromise.compareTo(quantity)>=0;
        if(result.canPromise) result.nextAvailableAt=preparationAt;
        else for(var p:timeline.periods) if(p.from.isAfter(preparationAt) && p.availableToPromise.compareTo(quantity)>=0) {
            result.nextAvailableAt=p.from;break;
        }
        return result;
    }

    public List<StockMovement> movements(Long id) {
        required(StockAccount.findById(id),"Stock account");return StockMovement.list("account.id = ?1 order by effectiveAt,id",id);
    }
    /** As-of is a projection origin, not an audit reconstruction of past reservation revisions. */
    public StockAvailability.Timeline availability(Long id,Long buildingId,LocalDateTime asOf) {
        return project(id,buildingId,asOf,null);
    }
    private record LedgerMovement(Long accountId,Long locationId,String reason,String eventKey,BigDecimal quantity,LocalDateTime effectiveAt,Long segmentId) {}
    private record SegmentProjection(Long id,Long product,Long lot,Long serial,String quality,java.time.LocalDate useBy,java.time.LocalDate bestBefore,boolean customerHeld) {}
    private record PromisePolicy(boolean lot,boolean serial,boolean date,int days,boolean strict) {}
    private record SupplyProjection(Long id,Long accountId,Long sourceId,Long destinationId,LocalDateTime availableAt,BigDecimal quantity,BigDecimal received,ExpectedSupply.SupplyStatus status) {}
    private record TransitProjection(Long supply,Long segment,BigDecimal pending) {}
    private record CommitmentProjection(Long id,Long accountId,Long buildingId,LocalDateTime preparationAt,BigDecimal remainingQuantity) {}
    private record ProjectionInput(StockAccount account,BigDecimal safetyMargin,List<LedgerMovement> movements,
            List<SupplyProjection> supplies,List<CommitmentProjection> commitments,Map<Long,Long> places,List<Long> buildings,Map<Long,SegmentProjection> segments,PromisePolicy policy,List<QualityCase> holds,List<TransitProjection> transit) {}

    /** One store snapshot and a fixed set of bulk queries, irrespective of the page size. */
    public Map<Long,StockAvailability.Timeline> availabilityBatch(List<StockAccount> accounts,Long buildingId,LocalDateTime asOf) {
        Objects.requireNonNull(asOf);
        var result=new HashMap<Long,StockAvailability.Timeline>();
        for(var input:projectionInputs(accounts,buildingId))result.put(input.account.id,calculateProjection(input,buildingId,asOf,null));
        return result;
    }
    private List<ProjectionInput> projectionInputs(List<StockAccount> accounts,Long buildingId) {
        if(accounts.isEmpty())return List.of();
        StockAccount first=accounts.getFirst();Long storeId=persistentId(first.store);
        for(var a:accounts)if(!storeId.equals(persistentId(a.store)))throw new IllegalArgumentException("Stock accounts from different stores");
        // Same row lock as writers, without an entity refresh cascading through the topology.
        em.createNativeQuery("select id from stores where id = ?1 for update").setParameter(1,storeId).getSingleResult();
        em.flush();
        if(buildingId!=null)checkBuilding(first,required(Building.findById(buildingId),"Building"));
        var ids=accounts.stream().map(a->a.id).toList();
        Map<Long,BigDecimal> margins=new HashMap<>();
        for(var row:em.createQuery("select a.id,a.safetyMargin from StockAccount a where a.id in :ids",Object[].class).setParameter("ids",ids).getResultList())margins.put((Long)row[0],(BigDecimal)row[1]);
        // Scalar projections deliberately bypass eager associations on location subclasses
        // (totes, buffers, etc.). Their graphs are irrelevant to availability arithmetic.
        List<LedgerMovement> movements=em.createQuery("select m.account.id,m.location.id,m.reason,m.eventKey,m.quantity,m.effectiveAt,m.segment.id from StockMovement m where m.account.id in :ids",Object[].class).setParameter("ids",ids).getResultList().stream().map(r->new LedgerMovement((Long)r[0],(Long)r[1],(String)r[2],(String)r[3],(BigDecimal)r[4],(LocalDateTime)r[5],(Long)r[6])).toList();
        var segments=new HashMap<Long,SegmentProjection>();
        for(var r:em.createQuery("select s.id,s.account.product.id,l.id,s.serial.id,s.quality,l.useBy,l.bestBefore,case when type(s.location)=ParcelStockLocation then true when type(s.location)=PickingToteLocation and treat(s.location as PickingToteLocation).traceOnly=true then true else false end from StockSegment s left join s.lot l left join s.serial where s.account.id in :ids",Object[].class).setParameter("ids",ids).getResultList())segments.put((Long)r[0],new SegmentProjection((Long)r[0],(Long)r[1],(Long)r[2],(Long)r[3],(String)r[4],(java.time.LocalDate)r[5],(java.time.LocalDate)r[6],Boolean.TRUE.equals(r[7])));
        var products=accounts.stream().map(a->persistentId(a.product)).toList();
        var policies=TraceabilityPolicy.<TraceabilityPolicy>list("store.id=?1 and product.id in ?2 and context in ?3",storeId,products,List.of("PICKING","SHIPPING"));
        var holds=QualityCase.<QualityCase>list("store.id=?1 and product.id in ?2 and state='ACTIVE'",storeId,products);
        List<SupplyProjection> supplies=em.createQuery("select x.id,x.account.id,src.id,x.destination.id,x.availableAt,x.quantity,x.received,x.status from ExpectedSupply x left join x.source src where x.account.id in :ids order by x.availableAt,x.id",Object[].class).setParameter("ids",ids).getResultList().stream().map(r->new SupplyProjection((Long)r[0],(Long)r[1],(Long)r[2],(Long)r[3],(LocalDateTime)r[4],(BigDecimal)r[5],(BigDecimal)r[6],(ExpectedSupply.SupplyStatus)r[7])).toList();
        var transit=new HashMap<Long,List<TransitProjection>>();
        for(var row:em.createQuery("select a.supply.account.id,a.supply.id,a.origin.id,sum(a.quantity-a.received) from SupplyStockAllocation a where a.supply.account.id in :ids and a.quantity>a.received group by a.supply.account.id,a.supply.id,a.origin.id",Object[].class).setParameter("ids",ids).getResultList())transit.computeIfAbsent((Long)row[0],unused->new ArrayList<>()).add(new TransitProjection((Long)row[1],(Long)row[2],(BigDecimal)row[3]));
        List<CommitmentProjection> commitments=em.createQuery("select c.id,c.account.id,c.building.id,c.preparationAt,c.remainingQuantity from StockCommitment c where c.account.id in :ids and c.status = :active",Object[].class).setParameter("ids",ids).setParameter("active",StockCommitment.CommitmentStatus.ACTIVE).getResultList().stream().map(r->new CommitmentProjection((Long)r[0],(Long)r[1],(Long)r[2],(LocalDateTime)r[3],(BigDecimal)r[4])).toList();
        Set<Long> locationIds=new HashSet<>();
        Map<Long,List<LedgerMovement>> movementsByAccount=new HashMap<>();
        Map<Long,List<SupplyProjection>> suppliesByAccount=new HashMap<>();
        Map<Long,List<CommitmentProjection>> commitmentsByAccount=new HashMap<>();
        for(var m:movements){locationIds.add(m.locationId);movementsByAccount.computeIfAbsent(m.accountId,k->new ArrayList<>()).add(m);}
        for(var x:supplies){if(x.sourceId!=null)locationIds.add(x.sourceId);locationIds.add(x.destinationId);suppliesByAccount.computeIfAbsent(x.accountId,k->new ArrayList<>()).add(x);}
        for(var c:commitments)commitmentsByAccount.computeIfAbsent(c.accountId,k->new ArrayList<>()).add(c);
        Map<Long,Long> places=new HashMap<>();
        if(!locationIds.isEmpty())for(var row:em.createQuery("select l.id,b.id from Store s join s.buildings b join b.locations l where s.id = :store and l.id in :locations",Object[].class).setParameter("store",storeId).setParameter("locations",locationIds).getResultList())places.put((Long)row[0],(Long)row[1]);
        List<Long> buildings=em.createQuery("select b.id from Store s join s.buildings b where s.id = ?1",Long.class).setParameter(1,storeId).getResultList();
        return accounts.stream().map(a->new ProjectionInput(a,margins.get(a.id),movementsByAccount.getOrDefault(a.id,List.of()),suppliesByAccount.getOrDefault(a.id,List.of()),commitmentsByAccount.getOrDefault(a.id,List.of()),places,buildings,segments,promisePolicy(policies,persistentId(a.product)),holds.stream().filter(h->persistentId(h.product).equals(persistentId(a.product))).toList(),transit.getOrDefault(a.id,List.of()))).toList();
    }
    private StockAvailability.Timeline project(Long id,Long buildingId,LocalDateTime asOf,Long excludedCommitment) {
        Objects.requireNonNull(asOf);
        return calculateProjection(projectionInputs(List.of(required(StockAccount.findById(id),"Stock account")),buildingId).getFirst(),buildingId,asOf,excludedCommitment);
    }
    private StockAvailability.Timeline calculateProjection(ProjectionInput input,Long buildingId,LocalDateTime asOf,Long excludedCommitment) {
        if(needsDatedProjection(input))return datedProjection(input,buildingId,asOf,excludedCommitment);
        BigDecimal safetyMargin=input.safetyMargin;Map<Long,Long> places=input.places;
        List<LedgerMovement> movements=input.movements;List<SupplyProjection> allSupplies=input.supplies;
        Map<Long,BigDecimal> physical=new HashMap<>();
        Map<Long,List<StockAvailability.Change>> changes=new HashMap<>();
        for(Long building:input.buildings){physical.put(building,BigDecimal.ZERO);changes.put(building,new ArrayList<>());}
        for (LedgerMovement m:movements) {
            Long owner=places.get(m.locationId);
            if(owner==null) throw new IllegalStateException("Stock location no longer belongs to store");
            if (!m.effectiveAt.isAfter(asOf)) physical.merge(owner,m.quantity,BigDecimal::add);
            else changes.get(owner).add(new StockAvailability.Change(m.effectiveAt,m.quantity,BigDecimal.ZERO));
        }
        List<SupplyProjection> supplies=allSupplies.stream().filter(s -> s.status==ExpectedSupply.SupplyStatus.EXPECTED).toList();
        for(SupplyProjection s:supplies) if(s.sourceId==null && !s.availableAt.isBefore(asOf))
            changes.get(places.get(s.destinationId)).add(new StockAvailability.Change(s.availableAt,s.quantity.subtract(s.received),BigDecimal.ZERO));
        for (CommitmentProjection c:input.commitments)
            if(!Objects.equals(c.id,excludedCommitment)) changes.get(c.buildingId).add(new StockAvailability.Change(c.preparationAt,BigDecimal.ZERO,c.remainingQuantity));
        BigDecimal unsupportedTransfers=BigDecimal.ZERO;
        // Internal transfers can only promise quantities still available at the source, including its later obligations.
        // Evaluate in arrival order; same-time chains without a proven upstream supply remain conservatively unavailable.
        for(SupplyProjection s:supplies) if(s.sourceId!=null && !s.availableAt.isBefore(asOf)) {
            Long source=places.get(s.sourceId),destination=places.get(s.destinationId);
            // Relocation within a building changes its addresses, not its aggregate availability.
            if (source.equals(destination)) continue;
            BigDecimal transit=transitAt(s,asOf,movements);
            if(transit.signum()>0)changes.get(destination).add(new StockAvailability.Change(s.availableAt,transit,BigDecimal.ZERO));
            BigDecimal requested=s.quantity.subtract(s.received).subtract(transit);
            StockAvailability.Timeline origin=StockAvailability.calculate(asOf,physical.get(source),safetyMargin,changes.get(source));
            BigDecimal capacity=origin.periods.get(0).availableToPromise;
            for(var p:origin.periods) {if(p.from.isAfter(s.availableAt)) break;capacity=p.availableToPromise;}
            BigDecimal supported=requested.min(capacity);
            if(buildingId==null || buildingId.equals(destination)) unsupportedTransfers=unsupportedTransfers.add(requested.subtract(supported));
            changes.get(source).add(new StockAvailability.Change(s.availableAt,supported.negate(),BigDecimal.ZERO));
            changes.get(destination).add(new StockAvailability.Change(s.availableAt,supported,BigDecimal.ZERO));
        }
        BigDecimal total=BigDecimal.ZERO;List<StockAvailability.Change> selected=new ArrayList<>();
        for(Long owner:physical.keySet()) if(buildingId==null || owner.equals(buildingId)) {total=total.add(physical.get(owner));selected.addAll(changes.get(owner));}
        var result=StockAvailability.calculate(asOf,total,safetyMargin,selected);
        result.inTransit=allSupplies.stream().filter(s -> s.sourceId!=null).map(s -> transitAt(s,asOf,movements)).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(buildingId==null)result.physical=result.physical.add(result.inTransit);
        result.unsupportedTransferQuantity=unsupportedTransfers;
        return result;
    }

    private static PromisePolicy promisePolicy(List<TraceabilityPolicy> policies,Long product){
        boolean lot=false,serial=false,date=false,strict=false;int days=0;
        for(var p:policies)if(persistentId(p.product).equals(product)){lot|=p.lotRequired;serial|=p.serialRequired;date|=p.dateRequired;strict|=p.bestBeforeStrict;days=Math.max(days,p.minimumRemainingDays);}
        return new PromisePolicy(lot,serial,date,days,strict);
    }
    private boolean needsDatedProjection(ProjectionInput input){
        if(!input.holds.isEmpty()||input.policy.lot||input.policy.serial||input.policy.date||input.policy.days>0||input.policy.strict)return true;
        return input.movements.stream().map(m->input.segments.get(m.segmentId)).filter(Objects::nonNull).anyMatch(s->s.useBy!=null||s.customerHeld||!StockEligibility.qualityAllowed(s.quality));
    }
    private boolean promiseAllowed(SegmentProjection s,ProjectionInput input){
        var p=input.policy;if(s==null||s.customerHeld||!StockEligibility.qualityAllowed(s.quality)||p.lot&&s.lot==null||p.serial&&s.serial==null||p.date&&s.useBy==null&&s.bestBefore==null)return false;
        return input.holds.stream().noneMatch(h->StockQualityRules.matches(h,s.product,s.lot,s.serial));
    }
    private StockAvailability.Timeline datedProjection(ProjectionInput input,Long requestedBuilding,LocalDateTime asOf,Long excludedCommitment){
        var batches=new HashMap<Long,List<DatedStockAvailability.Batch>>();var demands=new HashMap<Long,List<DatedStockAvailability.Demand>>();var physical=new HashMap<Long,BigDecimal>();
        for(Long b:input.buildings){batches.put(b,new ArrayList<>());demands.put(b,new ArrayList<>());physical.put(b,BigDecimal.ZERO);}
        var balances=new HashMap<Long,BigDecimal>();var owners=new HashMap<Long,Long>();
        for(var m:input.movements){Long owner=input.places.get(m.locationId);if(owner==null)throw new IllegalStateException("Stock location no longer belongs to store");owners.put(m.segmentId,owner);
            if(!m.effectiveAt.isAfter(asOf)){physical.merge(owner,m.quantity,BigDecimal::add);balances.merge(m.segmentId,m.quantity,BigDecimal::add);}
            else if(m.quantity.signum()>0){var s=input.segments.get(m.segmentId);if(promiseAllowed(s,input))batches.get(owner).add(new DatedStockAvailability.Batch("movement:"+m.eventKey,m.effectiveAt,StockEligibility.cutoff(s.useBy,s.bestBefore,input.policy.days,input.policy.strict),m.quantity));}
            else demands.get(owner).add(new DatedStockAvailability.Demand("movement:"+m.eventKey,m.effectiveAt,m.quantity.negate(),false,null));
        }
        for(var entry:balances.entrySet()){var s=input.segments.get(entry.getKey());Long owner=owners.get(entry.getKey());if(entry.getValue().signum()>0){if(promiseAllowed(s,input))batches.get(owner).add(new DatedStockAvailability.Batch("segment:"+entry.getKey(),asOf,StockEligibility.cutoff(s.useBy,s.bestBefore,input.policy.days,input.policy.strict),entry.getValue()));}else if(entry.getValue().signum()<0)demands.get(owner).add(new DatedStockAvailability.Demand("deficit:"+entry.getKey(),asOf,entry.getValue().negate(),false,null));}
        for(var c:input.commitments)if(!Objects.equals(c.id,excludedCommitment))demands.get(c.buildingId).add(new DatedStockAvailability.Demand("commitment:"+c.id,c.preparationAt.isBefore(asOf)?asOf:c.preparationAt,c.remainingQuantity,true,null));
        BigDecimal unsupported=BigDecimal.ZERO;
        for(var supply:input.supplies)if(supply.status==ExpectedSupply.SupplyStatus.EXPECTED&&!supply.availableAt.isBefore(asOf)){
            Long destination=input.places.get(supply.destinationId);BigDecimal remaining=supply.quantity.subtract(supply.received);
            if(supply.sourceId==null){
                // An unqualified supplier promise cannot satisfy a mandatory lot/date/serial policy.
                boolean held=input.holds.stream().anyMatch(h->h.lot==null&&h.serial==null||h.includeUnknownLots);
                if(!held&&!input.policy.lot&&!input.policy.serial&&!input.policy.date)batches.get(destination).add(new DatedStockAvailability.Batch("supply:"+supply.id,supply.availableAt,null,remaining));
            }else{
                Long source=input.places.get(supply.sourceId);if(source.equals(destination))continue;
                BigDecimal transit=transitAt(supply,asOf,input.movements);BigDecimal requested=remaining.subtract(transit).max(BigDecimal.ZERO);
                var extra=DatedStockAvailability.maximumExtra(batches.get(source),demands.get(source),supply.availableAt,input.safetyMargin);BigDecimal supported=requested.min(extra.quantity());BigDecimal left=supported;
                for(var selection:extra.batches().entrySet()){if(left.signum()==0)break;BigDecimal quantity=left.min(selection.getValue());var original=batches.get(source).stream().filter(b->b.key().equals(selection.getKey())).findFirst().orElseThrow();String key="transfer:"+supply.id+":"+selection.getKey();demands.get(source).add(new DatedStockAvailability.Demand(key,supply.availableAt,quantity,false,Set.of(selection.getKey())));batches.get(destination).add(new DatedStockAvailability.Batch(key,supply.availableAt,original.cutoff(),quantity));left=left.subtract(quantity);}
                BigDecimal identified=BigDecimal.ZERO;
                for(var parcel:input.transit)if(parcel.supply.equals(supply.id)&&identified.compareTo(transit)<0){var segment=input.segments.get(parcel.segment);if(promiseAllowed(segment,input)){BigDecimal amount=parcel.pending.min(transit.subtract(identified));batches.get(destination).add(new DatedStockAvailability.Batch("transit:"+supply.id+":"+parcel.segment,supply.availableAt,StockEligibility.cutoff(segment.useBy,segment.bestBefore,input.policy.days,input.policy.strict),amount));identified=identified.add(amount);}}
                // Unknown / blocked physical transit remains visible but cannot become a dated promise.
                if(requestedBuilding==null||requestedBuilding.equals(destination))unsupported=unsupported.add(requested.subtract(supported)).add(transit.subtract(identified));
            }
        }
        var selectedBatches=new ArrayList<DatedStockAvailability.Batch>();var selectedDemands=new ArrayList<DatedStockAvailability.Demand>();BigDecimal gross=BigDecimal.ZERO;
        for(Long b:input.buildings)if(requestedBuilding==null||requestedBuilding.equals(b)){selectedBatches.addAll(batches.get(b));selectedDemands.addAll(demands.get(b));gross=gross.add(physical.get(b));}
        var result=DatedStockAvailability.calculate(asOf,gross,input.safetyMargin,selectedBatches,selectedDemands);
        result.inTransit=input.supplies.stream().filter(s->s.sourceId!=null).map(s->transitAt(s,asOf,input.movements)).reduce(BigDecimal.ZERO,BigDecimal::add);if(requestedBuilding==null)result.physical=result.physical.add(result.inTransit);result.unsupportedTransferQuantity=unsupported;result.eligibilityApplied=true;result.eligiblePhysical=selectedBatches.stream().filter(b->!b.availableAt().isAfter(asOf)&&(b.cutoff()==null||asOf.isBefore(b.cutoff()))).map(DatedStockAvailability.Batch::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);result.excludedPhysical=result.physical.subtract(result.eligiblePhysical).max(BigDecimal.ZERO);return result;
    }

    private BigDecimal transitAt(SupplyProjection supply,LocalDateTime at,List<LedgerMovement> movements) {
        BigDecimal picked=BigDecimal.ZERO,received=BigDecimal.ZERO;
        String picking="PICKING_"+supply.id,partial="SUPPLY_"+supply.id+"_PARTIAL",complete="SUPPLY_"+supply.id+"_FINAL";
        for(var m:movements)if(!m.effectiveAt.isAfter(at)) {
            if(picking.equals(m.reason))picked=picked.subtract(m.quantity);
            else if((partial.equals(m.reason)||complete.equals(m.reason))&&!m.eventKey.endsWith(":out"))received=received.add(m.quantity);
        }
        return picked.signum()==0?BigDecimal.ZERO:picked.subtract(received).max(BigDecimal.ZERO);
    }

    private ExpectedSupply dirty(ExpectedSupply supply){org.hibernate.Hibernate.unproxy(supply.account,StockAccount.class).alertDirty=true;return supply;}
    private StockCommitment dirty(StockCommitment commitment){org.hibernate.Hibernate.unproxy(commitment.account,StockAccount.class).alertDirty=true;return commitment;}
    private static Long persistentId(com.intermarche.backoffice.domain.BaseEntity entity){return com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(entity);}
    private static <T> T required(T entity,String name) { if(entity==null) throw new NoSuchElementException(name+" not found");return entity; }
    private static void text(String text,int length) { if(text==null||text.isBlank()||text.length()>length) throw new IllegalArgumentException("Invalid reference"); }
    private static void amount(BigDecimal qty) {if(qty==null||qty.scale()>6||qty.precision()-qty.scale()>13) throw new IllegalArgumentException("Invalid quantity (13 integer and 6 decimal digits maximum)");}
    private static void nonNegative(BigDecimal qty) {amount(qty);if(qty.signum()<0) throw new IllegalArgumentException("Negative quantity");}
    private static void positive(BigDecimal qty) {nonNegative(qty);if(qty.signum()==0) throw new IllegalArgumentException("Quantity must be positive");}
}
