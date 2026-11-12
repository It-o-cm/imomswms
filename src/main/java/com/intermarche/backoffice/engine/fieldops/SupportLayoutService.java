package com.intermarche.backoffice.engine.fieldops;

import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.engine.executing.*;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.*;

/** Slot occupancy is serialized with physical claims. Assignment is tied to the mission, never its current employee. */
@ApplicationScoped @Transactional
public class SupportLayoutService {
    @Inject FieldOperationsService field;@Inject StockCommands commandReceipts;
    @Inject OperatorExecutionService operator;
    @Inject MissionStateService missions;@Inject BufferOccupancyService occupancy;
    @org.eclipse.microprofile.openapi.annotations.media.Schema(name="CartLayoutInput")
    public static class Layout {
        public Long storeId,cartId;
        public int rows,columns;
        public String expectedRevision;
    }
    @org.eclipse.microprofile.openapi.annotations.media.Schema(name="CartAssignmentInput")
    public static class Assignment {
        public Long storeId,compartmentId,supportId,orderId,customerOrderId,bufferId,executionId,cabinetId;
        public Integer cellRow,cellColumn;
        public com.intermarche.backoffice.domain.store.TemperatureZone usage;
        public String expectedOrderRevision;
        public String requestKey,expectedRevision,destination;
    }
    @org.eclipse.microprofile.openapi.annotations.media.Schema(name="SupportFullInput")
    public static class Full {
        public Long storeId,loadedToteId;
        public String requestKey,reason,expectedRevision,replacementCode,expectedOrderRevision,cause;
    }
    private String revision(com.intermarche.backoffice.domain.BaseEntity entity){return field.hash(Arrays.asList(entity.id,entity.version));}
    private PhysicalSupport support(Store store,Long supportId){PhysicalSupport s=PhysicalSupport.find("id=?1 and store=?2",supportId,store).firstResult();if(s==null)throw new NotFoundException("Support inconnu");return s;}
    private Map<String,Object> slot(CartCompartment c){var v=new LinkedHashMap<String,Object>();v.put("id",c.id);v.put("row",c.rowNumber);v.put("column",c.columnNumber);v.put("label",c.label);v.put("scanCode","COMPARTMENT:"+c.id);v.put("revision",revision(c));v.put("supportId",c.occupant==null?null:id(c.occupant));v.put("supportKind",c.occupant==null?null:c.occupant.type.kind.name());v.put("supportCode",c.occupant==null?null:c.occupant.code);v.put("physicalToteId",c.occupant==null?null:id(c.occupant.tote));v.put("orderId",c.handlingOrder==null?null:id(c.handlingOrder));v.put("orderCode",c.handlingOrder==null?null:c.handlingOrder.code);v.put("customerOrderId",c.customerOrder==null?null:id(c.customerOrder));v.put("customerOrderCode",c.customerOrder==null?null:c.customerOrder.orderId);v.put("destination",c.destination);v.put("items",contents(c.occupant==null?null:c.occupant.tote));return v;}
    private List<Map<String,Object>> contents(com.intermarche.backoffice.domain.store.Tote tote) {
        if(tote==null)return List.of();
        return com.intermarche.backoffice.domain.executing.PickedItem.<com.intermarche.backoffice.domain.executing.PickedItem>list("select i from PickedItem i join fetch i.product join fetch i.loadedTote t where t.tote=?1 and t.status=?2 and i.status=?3 and i.quantity>0 order by i.id",tote,LoadedTote.Status.IN_USE,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED).stream().map(i->{
            var v=new LinkedHashMap<String,Object>();var p=i.product;
            v.put("productId",id(p));v.put("name",p.name);v.put("ean",p.ean);v.put("quantity",i.quantity);
            v.put("unit",p.productType==com.intermarche.backoffice.domain.order.ProductType.WEIGHT?"kg":p.productType==com.intermarche.backoffice.domain.order.ProductType.VOLUME?"L":p.unitName==null?"pcs":p.unitName);
            String customer=SupportReplacement.getEntityManager().createQuery("select l.fulfillment.order.orderId from Lot l join l.lines line where line=?1",String.class).setParameter(1,i.lotLine).getResultStream().findFirst().orElse(null);
            v.put("customerOrderCode",customer);return (Map<String,Object>)v;
        }).toList();
    }
    private String withdrawalCause(SupportReplacement replacement){if(replacement==null)return null;for(String cause:List.of("DAMAGED","UNSUITABLE"))if(replacement.reason.startsWith(cause+": "))return cause;return "FULL";}
    public Object read(Long storeId,Long cartId){Store store=field.store(storeId,false);PhysicalSupport cart=support(store,cartId);return Map.of("id",cart.id,"code",cart.code,"kind",cart.type.kind,"revision",revision(cart),"compartments",CartCompartment.<CartCompartment>list("select c from CartCompartment c left join fetch c.occupant left join fetch c.handlingOrder left join fetch c.customerOrder where c.cart=?1 order by c.rowNumber,c.columnNumber",cart).stream().map(this::slot).toList());}
    public Object configure(Layout r){if(r==null||r.rows<1||r.rows>10||r.columns<1||r.columns>10)throw new BadRequestException("Chariot de 1 à 10 rangées et colonnes");Store store=field.store(r.storeId,true);field.requireManager();PhysicalSupport cart=support(store,r.cartId);if(cart.type.kind!=SupportType.Kind.CART)throw new BadRequestException("Ce support n’est pas un chariot");if(!Objects.equals(r.expectedRevision,revision(cart)))throw new OperatorFailure(409,"STALE_CART","Le chariot a changé");
        var cells=CartCompartment.<CartCompartment>list("cart",cart);if(cells.stream().anyMatch(c->c.occupant!=null||c.handlingOrder!=null))throw new BadRequestException("Libérez les compartiments avant de modifier le chariot");
        CartCompartment.delete("cart",cart);for(int row=1;row<=r.rows;row++)for(int col=1;col<=r.columns;col++){var c=new CartCompartment();c.cart=cart;c.rowNumber=row;c.columnNumber=col;c.label=row+" / "+col;c.persist();}
        PhysicalSupport.getEntityManager().lock(cart,jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT);field.flush();return read(store.id,cart.id);
    }
    private SupportOperation replay(Store store,String key,String fingerprint){FieldOperationsService.key(key);SupportOperation receipt=SupportOperation.find("store=?1 and actor=?2 and requestKey=?3",store,field.actor(),key).firstResult();if(receipt!=null&&!receipt.fingerprint.equals(fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Référence utilisée pour une autre opération");return receipt;}
    private Object record(Store store,String key,String fingerprint,String action,Object result){var receipt=new SupportOperation();receipt.store=store;receipt.actor=field.actor();receipt.requestKey=key;receipt.fingerprint=fingerprint;receipt.action=action;receipt.result=field.encode(result);receipt.persist();return commandReceipts.record(store,key,fingerprint,action,result);}
    /**
     * Checks that the operator may empty a compartment of a mission: its assignee may do so even once the mission is closed,
     * since the totes it carried are still on the cart and must be put down.
     *
     * @param order the mission holding the compartment
     */
    private void releaser(BaseHandlingOrder order){if(!field.manager()&&(order.assignee==null||!order.assignee.badgeId.equals(field.actor())))throw new ForbiddenException("Mission affectée à un autre préparateur");if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED).contains(order.status))return;var command=new OperatorCommandInput();command.orderId=order.id;missions.requireActive(order.store,command);}
    private void owner(BaseHandlingOrder order){if(!field.manager()&&(order.assignee==null||!order.assignee.badgeId.equals(field.actor())))throw new ForbiddenException("Mission affectée à un autre préparateur");if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED).contains(order.status))throw new BadRequestException("Mission fermée");var command=new OperatorCommandInput();command.orderId=order.id;missions.requireActive(order.store,command);}
    public Object assign(Assignment r){if(r==null)throw new BadRequestException();Store store=field.store(r.storeId,true);String fingerprint=field.hash(r);var receipt=replay(store,r.requestKey,fingerprint);if(receipt!=null)return field.decode(receipt.result,Map.class);
        CartCompartment c=CartCompartment.find("id=?1 and cart.store=?2",r.compartmentId,store).firstResult();if(c==null)throw new NotFoundException();if(!Objects.equals(r.expectedRevision,revision(c)))throw new OperatorFailure(409,"STALE_COMPARTMENT","Le compartiment a changé. Actualisez.");
        if(c.handlingOrder!=null&&(r.supportId!=null||!field.manager())){if(r.supportId==null)releaser(c.handlingOrder);else owner(c.handlingOrder);}
        if(r.supportId==null){
            if(c.occupant!=null){
                var loads=LoadedTote.<LoadedTote>list("tote=?1 and status=?2",c.occupant.tote,LoadedTote.Status.IN_USE);
                for(var load:loads){
                    LoadedTote.getEntityManager().lock(load.tote,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                    if(r.bufferId!=null){
                        try{occupancy.deposit(load,r.bufferId,r.cabinetId,r.cellRow,r.cellColumn,store);}
                        catch(IllegalArgumentException invalid){throw new BadRequestException(invalid.getMessage());}
                    }
                    // Without a deposit target, detach only the physical slot.
                    // The active load keeps its custody, contents and stock unchanged.
                }
            }
            c.occupant=null;c.handlingOrder=null;c.customerOrder=null;c.destination=null;
        }
        else{PhysicalSupport.requireAvailable(c.cart.tote);if(c.occupant!=null)throw new OperatorFailure(409,"COMPARTMENT_OCCUPIED","Ce compartiment est occupé");PhysicalSupport child=support(store,r.supportId);if(child.type.kind==SupportType.Kind.CART||id(child).equals(id(c.cart)))throw new BadRequestException("Un compartiment reçoit un bac, carton ou palette");PhysicalSupport.requireAvailable(child.tote);
            if(CartCompartment.count("occupant",child)>0)throw new OperatorFailure(409,"SUPPORT_ALREADY_PLACED","Ce support occupe déjà un compartiment");
            BaseHandlingOrder order=field.order(store,r.orderId);owner(order);
            for(var active:LoadedTote.<LoadedTote>list("tote=?1 and status=?2",child.tote,LoadedTote.Status.IN_USE)){PickingExecution custody=execution(active);if(active.buffer!=null||custody==null||!id(custody.handlingOrder).equals(order.id))throw new BadRequestException("Ce support est déposé ou appartient à une autre mission");}
            // A trolley has one physical custodian, even if it carries several commercial orders.
            for(var peer:CartCompartment.<CartCompartment>list("cart=?1 and handlingOrder is not null",c.cart))if(peer.handlingOrder.assignee!=null&&order.assignee!=null&&!id(peer.handlingOrder.assignee).equals(id(order.assignee)))throw new BadRequestException("Les missions du chariot doivent avoir le même préparateur");
            Order customer=r.customerOrderId==null?null:Order.find("id=?1 and store=?2",r.customerOrderId,store).firstResult();if(r.customerOrderId!=null&&customer==null)throw new BadRequestException("Commande d’un autre magasin");
            if(customer!=null){var concrete=org.hibernate.Hibernate.unproxy(order,BaseHandlingOrder.class);if(!(concrete instanceof com.intermarche.backoffice.domain.wms.handling.PreparingOrder preparing)||preparing.lots.stream().noneMatch(l->l.fulfillment!=null&&l.fulfillment.order!=null&&id(l.fulfillment.order).equals(customer.id)))throw new BadRequestException("La commande ne fait pas partie de cette mission");}
            if(r.destination!=null&&r.destination.length()>150)throw new BadRequestException("Destination trop longue");
            if(r.executionId!=null){
                PickingExecution execution=PickingExecution.findById(r.executionId);
                if(execution==null||!Objects.equals(id(execution.handlingOrder),order.id))throw new BadRequestException("Collecte d’une autre mission");
                if(child.type.kind!=SupportType.Kind.TOTE)throw new BadRequestException("Choisissez un bac pour la collecte");
                if(execution.loadedTotes.stream().noneMatch(t->Objects.equals(id(t.tote),id(child.tote)))){
                    var command=new OperatorCommandInput();command.contractVersion=2;command.storeId=store.id;command.orderId=order.id;command.executionId=execution.id;command.action="ADD_TOTE";command.toteCode=child.code;command.usage=r.usage;command.expectedRevision=r.expectedOrderRevision;command.requestKey=UUID.nameUUIDFromBytes(("cart-attach:"+r.requestKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    operator.apply(command);
                }
            }
            c.occupant=child;c.handlingOrder=order;c.customerOrder=customer;c.destination=r.destination;
        }
        c.persistAndFlush();if(c.occupant!=null)requireCartCapacities(store,List.of(c.occupant.tote));return record(store,r.requestKey,fingerprint,"COMPARTMENT_ASSIGNMENT",slot(c));
    }
    private LoadedTote load(Store store,Long loadId){LoadedTote load=LoadedTote.find("id=?1 and tote.store=?2",loadId,store).firstResult();if(load==null)throw new NotFoundException();return load;}
    private PickingExecution execution(LoadedTote load){return PickingExecution.find("select p from PickingExecution p join p.loadedTotes t where t=?1",load).firstResult();}
    public Object mission(Long storeId,Long orderId){
        Store store=field.store(storeId,false);BaseHandlingOrder order=field.order(store,orderId);
        var loads=LoadedTote.<LoadedTote>list("select distinct t from PickingExecution p join p.loadedTotes t join fetch t.tote where p.handlingOrder=?1",order);
        var wrappers=new HashMap<Long,Long>();if(!loads.isEmpty())for(var wrapper:PhysicalSupport.<PhysicalSupport>list("store=?1 and tote in ?2",store,loads.stream().map(t->t.tote).toList()))wrappers.put(id(wrapper.tote),wrapper.id);
        var ids=loads.stream().map(t->t.id).toList();var full=new HashMap<Long,SupportReplacement>();if(!ids.isEmpty())for(var replacement:SupportReplacement.<SupportReplacement>list("source.id in ?1",ids))full.put(id(replacement.source),replacement);
        var result=new LinkedHashMap<String,Object>();result.put("orderId",order.id);result.put("loads",loads.stream().map(t->{var v=new LinkedHashMap<String,Object>();v.put("id",t.id);v.put("code",t.tote.code);v.put("physicalToteId",id(t.tote));v.put("supportId",wrappers.get(id(t.tote)));v.put("revision",revision(t));v.put("zone",t.storageType);v.put("status",t.status);v.put("full",full.containsKey(t.id));v.put("withdrawalCause",withdrawalCause(full.get(t.id)));v.put("withdrawalReason",full.containsKey(t.id)?full.get(t.id).reason:null);v.put("items",contents(t.tote));v.put("replacementCode",full.containsKey(t.id)&&full.get(t.id).replacement!=null?full.get(t.id).replacement.tote.code:null);v.put("buffer",t.buffer==null?null:t.buffer.bufferCode);return v;}).toList());
        result.put("compartments",CartCompartment.<CartCompartment>list("select c from CartCompartment c join fetch c.cart left join fetch c.occupant left join fetch c.customerOrder where c.handlingOrder=?1",order).stream().map(c->{var v=slot(c);v.put("cartCode",c.cart.code);return v;}).toList());
        var concrete=org.hibernate.Hibernate.unproxy(order,BaseHandlingOrder.class);
        result.put("customerOrders",concrete instanceof com.intermarche.backoffice.domain.wms.handling.PreparingOrder preparing?preparing.lots.stream().filter(l->l.fulfillment!=null&&l.fulfillment.order!=null).map(l->Map.of("id",id(l.fulfillment.order),"code",l.fulfillment.order.orderId)).distinct().toList():List.of());return result;
    }
    public Object depositTarget(Long storeId,Long orderId,String code){Store store=field.store(storeId,false);BaseHandlingOrder order=field.order(store,orderId);owner(order);return DepositTargetGuard.view(DepositTargetGuard.resolve(store,order,code));}
    public Object replacements(Long storeId,Long loadedId){
        Store store=field.store(storeId,false);LoadedTote load=load(store,loadedId);PickingExecution p=execution(load);if(p==null)throw new BadRequestException("Collecte active requise");owner(p.handlingOrder);
        var estimate=SupportLoadEstimate.estimate(p.itemsToPick.stream().filter(i->i.status==com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PENDING&&i.product.temperatureZone==load.storageType).map(i->new SupportLoadEstimate.Item(i.product.productType.name(),i.quantity,i.product.referenceWeight,i.product.referenceVolume)).toList());
        var candidates=PhysicalSupport.<PhysicalSupport>find("select s from PhysicalSupport s join fetch s.type join fetch s.tote where s.store=?1 and s.state='AVAILABLE' and s.type.kind<>?2 and not exists (select t.id from LoadedTote t where t.tote=s.tote and t.status=?3) order by s.code",store,SupportType.Kind.CART,LoadedTote.Status.IN_USE).range(0,99).list();
        var choices=new ArrayList<Map<String,Object>>();for(var s:candidates){var v=new LinkedHashMap<String,Object>();v.put("id",s.id);v.put("code",s.code);v.put("label",s.type.label);v.put("volumeLiters",s.tote.volume);if(!Arrays.asList(s.type.allowedZones.split(",")).contains(load.storageType.name())||!s.tote.isUsageAllowed(load.storageType)){v.put("compatible",false);v.put("reason","Conditionnement incompatible : alimentaire, entretien, froid et surgelé restent séparés");}else v.putAll(SupportLoadEstimate.fit(estimate,s.type.maxVolumeLiters==null?s.tote.volume:s.tote.volume.min(s.type.maxVolumeLiters),s.type.maxWeightKg));choices.add(v);}return Map.of("estimate",estimate,"items",choices);
    }
    public Object full(Full r){if(r==null)throw new BadRequestException();Store store=field.store(r.storeId,true);String fingerprint=field.hash(r);var replay=replay(store,r.requestKey,fingerprint);if(replay!=null)return field.decode(replay.result,Map.class);FieldOperationsService.text(r.reason,480);String cause=r.cause==null?"FULL":r.cause;if(!Set.of("FULL","DAMAGED","UNSUITABLE").contains(cause))throw new BadRequestException("Motif de remplacement invalide");
        LoadedTote load=load(store,r.loadedToteId);PickingExecution p=execution(load);if(p==null)throw new BadRequestException("Ce support ne dépend pas d’une collecte active");owner(p.handlingOrder);if(!Objects.equals(r.expectedRevision,revision(load)))throw new OperatorFailure(409,"STALE_SUPPORT_LOAD","Le contenu du support a changé");
        SupportReplacement full=SupportReplacement.find("source",load).firstResult();if(full!=null&&(full.replacement!=null||r.replacementCode==null||r.replacementCode.isBlank()))throw new BadRequestException("Ce support est déjà déclaré plein");if(full==null){if(!BufferOccupancyService.hasContents(load)&&"FULL".equals(cause))throw new BadRequestException("Un support vide ne peut pas être déclaré plein");full=new SupportReplacement();full.source=load;full.reason="FULL".equals(cause)?r.reason:cause+": "+r.reason;full.actor=field.actor();}
        if(r.replacementCode!=null&&!r.replacementCode.isBlank()){
            if(load.tote.code.equals(r.replacementCode))throw new BadRequestException("Choisissez un autre support");
            @SuppressWarnings("unchecked") var preview=(Map<String,Object>)replacements(store.id,load.id);
            @SuppressWarnings("unchecked") var options=(List<Map<String,Object>>)preview.get("items");
            var selected=options.stream().filter(v->r.replacementCode.equals(v.get("code"))).findFirst().orElseThrow(()->new BadRequestException("Support indisponible ou hors de la sélection courante"));if(!Boolean.TRUE.equals(selected.get("compatible")))throw new BadRequestException(String.valueOf(selected.get("reason")));
            var command=new OperatorCommandInput();command.contractVersion=2;command.storeId=store.id;command.orderId=id(p.handlingOrder);command.executionId=p.id;command.requestKey=r.requestKey;command.action="ADD_TOTE";command.toteCode=r.replacementCode;command.usage=load.storageType;command.expectedRevision=r.expectedOrderRevision;
            operator.apply(command);
            full.replacement=p.loadedTotes.stream().filter(t->t.tote.code.equals(r.replacementCode)).findFirst().orElseThrow(()->new BadRequestException("Support de remplacement absent"));
        }
        if("DAMAGED".equals(withdrawalCause(full))){PhysicalSupport physical=PhysicalSupport.find("tote",load.tote).firstResult();if(physical!=null)physical.state="BLOCKED";}
        full.persistAndFlush();var result=new LinkedHashMap<String,Object>();result.put("sourceId",load.id);result.put("sourceCode",load.tote.code);result.put("replacementId",full.replacement==null?null:full.replacement.id);result.put("state",withdrawalCause(full));result.put("message","Support écarté de la collecte ; son contenu est conservé. Déposez-le avant de terminer.");return record(store,r.requestKey,fingerprint,"SUPPORT_FULL",result);
    }
    public static void requireCartCapacities(Store store,Collection<Tote> changed){
        if(changed.isEmpty())return;
        var affected=CartCompartment.<CartCompartment>list("select c from CartCompartment c join fetch c.cart join fetch c.cart.type where c.cart.store=?1 and c.occupant.tote in ?2",store,changed);if(affected.isEmpty())return;
        var carts=affected.stream().map(c->c.cart).distinct().toList();var slots=CartCompartment.<CartCompartment>list("select c from CartCompartment c join fetch c.occupant where c.cart in ?1",carts);var byTote=new HashMap<Long,Long>();for(var slot:slots)if(slot.occupant!=null)byTote.put(id(slot.occupant.tote),id(slot.cart));
        if(byTote.isEmpty())return;var all=new HashMap<Long,List<SupportLoadEstimate.Item>>();
        for(var row:CartCompartment.getEntityManager().createQuery("select i.loadedTote.tote.id,i.product.productType,i.quantity,i.product.referenceWeight,i.product.referenceVolume from PickedItem i where i.loadedTote.tote.id in ?1 and i.loadedTote.status=?2 and i.status=?3 and i.quantity>0",Object[].class).setParameter(1,byTote.keySet()).setParameter(2,LoadedTote.Status.IN_USE).setParameter(3,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED).getResultList())all.computeIfAbsent(byTote.get((Long)row[0]),ignored->new ArrayList<>()).add(new SupportLoadEstimate.Item(row[1].toString(),(java.math.BigDecimal)row[2],(java.math.BigDecimal)row[3],(java.math.BigDecimal)row[4]));
        for(var cart:carts){var estimate=SupportLoadEstimate.estimate(all.getOrDefault(cart.id,List.of()));if(cart.type.maxWeightKg!=null&&estimate.kg().compareTo(cart.type.maxWeightKg)>0||cart.type.maxVolumeLiters!=null&&estimate.liters().compareTo(cart.type.maxVolumeLiters)>0)throw new OperatorFailure(409,"CART_CAPACITY_EXCEEDED","Capacité cumulée du chariot "+cart.code+" dépassée. Déposez un support ou choisissez un autre chariot.");}
    }
    public static Map<String,java.math.BigDecimal> fullContents(Store store){
        var result=new HashMap<String,java.math.BigDecimal>();
        for(var row:SupportReplacement.getEntityManager().createQuery("select i.loadedTote.id,i.id,i.quantity from PickedItem i where i.loadedTote.tote.store=?1 and i.loadedTote.status=?2 and i.status=?3 and exists(select f.id from SupportReplacement f where f.source=i.loadedTote)",Object[].class).setParameter(1,store).setParameter(2,LoadedTote.Status.IN_USE).setParameter(3,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED).getResultList())result.put(row[0]+":"+row[1],(java.math.BigDecimal)row[2]);return result;
    }
    public static void requireNoFullIncrease(Store store,Map<String,java.math.BigDecimal> before){for(var entry:fullContents(store).entrySet())if(entry.getValue().compareTo(before.getOrDefault(entry.getKey(),java.math.BigDecimal.ZERO))>0)throw new OperatorFailure(409,"SUPPORT_FULL","Le rapport ajoute un produit dans un support déclaré plein. Choisissez un support de remplacement.");}
    public static void requireWritable(LoadedTote load){var constraints=OperationalPolicyVersions.active(load.tote.store,"SupportConstraints");PhysicalSupport physical=PhysicalSupport.find("tote",load.tote).firstResult();String kind=physical==null?"TOTE":physical.type.kind.name();boolean allowed=false;for(var k:constraints.path("allowedKinds"))if(k.asText().equals(kind))allowed=true;if(!allowed)throw new OperatorFailure(409,"SUPPORT_POLICY_BLOCKED","Ce type de support est désactivé par la politique du magasin");if(CartCompartment.count("cart.tote",load.tote)>0)throw new OperatorFailure(409,"CART_REQUIRES_COMPARTMENT","Collectez dans le support du compartiment, pas directement dans le chariot");if(SupportReplacement.count("source",load)>0)throw new OperatorFailure(409,"SUPPORT_FULL","Ce support est déclaré plein. Utilisez son remplacement ou un autre support compatible.");}
    public static void requireHandoverTogether(BaseHandlingOrder order){for(var slot:CartCompartment.<CartCompartment>list("handlingOrder",order))if(CartCompartment.count("cart=?1 and handlingOrder is not null and handlingOrder<>?2",slot.cart,order)>0)throw new OperatorFailure(409,"CART_SHARED_CUSTODY","Le chariot porte plusieurs missions. Déposez ses supports avant de transmettre une mission isolément.");}
}
