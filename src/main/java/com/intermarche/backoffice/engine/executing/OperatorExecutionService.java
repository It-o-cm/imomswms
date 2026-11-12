package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.auth.AuthenticationService;
import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.storeops.*;
import com.intermarche.backoffice.engine.replenishment.ReplenishmentResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Adaptation d'exécution : mêmes entités/opérations, avec appartenance, reprise et acquittement atomique. */
@ApplicationScoped
public class OperatorExecutionService {
    @Inject com.intermarche.backoffice.engine.catering.CateringFabricationMission cateringMission;
    @Inject com.intermarche.backoffice.engine.catering.CateringFabricationExecution cateringExecution;
    @Inject MixedPreparationService mixedPreparation;
    @Inject ProductPickingLocations productLocations;
    @Inject AuthenticationService authentication;
    @Inject StoreOperationsResource stores;
    @Inject EmployeeTransitService transit;
    @Inject HandlingOrderService handling;
    @Inject ReplenishmentResource replenishment;
    @Inject BufferOccupancyService occupancy;
    @Inject PickingResolutionService resolutions;
    @Inject ShortageCorrectionService shortageCorrections;
    @Inject ToteCapacityService capacities;
    @Inject com.intermarche.backoffice.engine.stock.StockProtectionService protection;
    @Inject EntityManager em;
    @Inject ObjectMapper mapper;

    private BadRequestException bad(String message){return new BadRequestException(Response.status(400).entity(Map.of("message",message)).build());}
    private ClientErrorException conflict(String message){return new ClientErrorException(Response.status(409).entity(Map.of("message",message)).build());}
    private Long id(Object entity){return entity==null?null:((com.intermarche.backoffice.domain.BaseEntity)Hibernate.unproxy(entity)).id;}
    private Employee actor(Store store){
        Account account=authentication.getConnectedAccount();
        if(!(account instanceof Employee employee) || !employee.active || !Objects.equals(id(employee.store),store.id))throw new ForbiddenException("Un employé actif du magasin doit prendre en charge l’opération.");
        return employee;
    }
    private BaseHandlingOrder scoped(Store store,Long id){
        BaseHandlingOrder o=id==null?null:BaseHandlingOrder.findById(id);
        if(o==null || !Objects.equals(id(o.store),store.id))throw new NotFoundException("Ordre introuvable dans ce magasin");
        if(!visible(o,executions(o)))throw new ForbiddenException("Mission hors de votre périmètre");
        return Hibernate.unproxy(o,BaseHandlingOrder.class);
    }
    private List<HandlingExecution> executions(BaseHandlingOrder order){return HandlingExecution.list("handlingOrder=?1 order by id",order);}
    private boolean active(HandlingExecution e){return e.status==HandlingExecution.ExecutionStatus.IN_PROGRESS || e.status==HandlingExecution.ExecutionStatus.PENDING;}
    private void assignment(BaseHandlingOrder o,Employee employee){if(o.assignee!=null && !Objects.equals(id(o.assignee),employee.id))throw conflict("Cet ordre est affecté à un autre opérateur.");}
    private void own(HandlingExecution e,Employee employee){if(!Objects.equals(id(e.employee),employee.id))throw conflict("Cette exécution est déjà prise en charge par un autre opérateur.");}
    private HandlingExecution ownedExecution(Store store,Employee employee,Long executionId){
        HandlingExecution e=executionId==null?null:HandlingExecution.findById(executionId);
        if(e==null)throw new NotFoundException("Exécution introuvable");var order=scoped(store,id(e.handlingOrder));em.lock(order,LockModeType.PESSIMISTIC_WRITE);em.lock(e,LockModeType.PESSIMISTIC_WRITE);own(e,employee);
        if(!active(e))throw conflict("Cette exécution est terminée ou annulée.");
        openOrder(Hibernate.unproxy(e.handlingOrder,BaseHandlingOrder.class));
        assignment(e.handlingOrder,employee);return Hibernate.unproxy(e,HandlingExecution.class);
    }
    @Inject com.intermarche.backoffice.engine.fieldops.MissionClosureService closure;
    private void openOrder(BaseHandlingOrder o){if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(o.status))throw conflict("Cet ordre est fermé. Aucune opération d’exécution ne peut le rouvrir implicitement.");}
    @jakarta.inject.Inject com.intermarche.backoffice.engine.fieldops.MissionStateService missionStates;
    private String revision(BaseHandlingOrder raw){
        BaseHandlingOrder o=Hibernate.unproxy(raw,BaseHandlingOrder.class);
        var parts=new ArrayList<Object>();parts.add(Arrays.asList("order",o.id,o.version,o.status,id(o.assignee),o.requestedStart,o.requestedEnd));
        if(o instanceof PreparingOrder preparing)preparing.lots.stream().sorted(Comparator.comparing(l->l.id)).forEach(lot->{
            parts.add(Arrays.asList("lot",lot.id,lot.version,lot.status,id(lot.chunk)));
            lot.lines.stream().sorted(Comparator.comparing(line->line.id)).forEach(line->parts.add(Arrays.asList("line",line.id,line.version,line.quantity,id(line.product),id(line.location),id(line.fulfillmentLine))));
        });
        for(var e:executions(o)){
            parts.add(Arrays.asList("execution",e.id,e.version,e.status,id(e.employee)));
            if(Hibernate.unproxy(e,HandlingExecution.class) instanceof PickingExecution p)
                p.itemsToPick.stream().sorted(Comparator.comparing(i->i.id)).forEach(i->parts.add(Arrays.asList("item",i.id,i.version,i.quantity,i.status,id(i.product),id(i.loadedTote),id(i.sourceLocation))));
            totes(Hibernate.unproxy(e,HandlingExecution.class)).stream().sorted(Comparator.comparing(t->t.id)).forEach(t->{
                parts.add(Arrays.asList("tote",t.id,t.version,id(t.tote),Hibernate.unproxy(t.tote,Tote.class).version,t.status,id(t.buffer),id(t.cabinet),t.cellRow,t.cellColumn));
                t.items.stream().sorted(Comparator.comparing(i->i.id)).forEach(i->parts.add(Arrays.asList("contents",i.id,i.version,i.quantity,i.status,id(i.product))));
            });
        }
        if(o instanceof ReplenishmentOrder r)r.supplies.stream().sorted(Comparator.comparing(x->x.id)).forEach(x->parts.add(Arrays.asList("supply",x.id,x.version,x.quantity,x.pickedQuantity(),x.received)));
        return com.intermarche.backoffice.engine.operator.CommandCanonical.hash(json(parts));
    }
    private void verifyRevision(OperatorCommandInput input,Store store){
        if(input.contractVersion==null || input.contractVersion==1)return;
        if(input.contractVersion!=2)throw new com.intermarche.backoffice.engine.operator.OperatorFailure(400,"UNSUPPORTED_CONTRACT","Version de contrat non prise en charge.");
        Set<Long> ids=new TreeSet<>();
        if(input.orderId!=null)ids.add(input.orderId);
        if(input.executionId!=null){HandlingExecution e=HandlingExecution.findById(input.executionId);if(e==null)throw new NotFoundException();ids.add(id(e.handlingOrder));}
        if(input.report!=null && input.report.orderIds!=null)ids.addAll(input.report.orderIds);
        if(ids.isEmpty())throw bad("Ordre ou exécution requis.");
        for(Long orderId:ids){var order=scoped(store,orderId);em.lock(order,LockModeType.PESSIMISTIC_WRITE);
            String expected=input.expectedRevisions==null?null:input.expectedRevisions.get(orderId);
            if(expected==null && ids.size()==1)expected=input.expectedRevision;
            if(expected==null || expected.isBlank())throw new com.intermarche.backoffice.engine.operator.OperatorFailure(400,"REVISION_REQUIRED","Actualisez la mission avant de confirmer.");
            if(!expected.equals(revision(order)))throw new com.intermarche.backoffice.engine.operator.OperatorFailure(409,"STALE_REVISION","La mission a changé. Actualisez-la avant de confirmer cette action.");
        }
    }
    private String unit(Product p){return p.productType==ProductType.WEIGHT?"kg":p.productType==ProductType.VOLUME?"L":p.unitName==null?"pcs":p.unitName;}
    private Map<String,Object> building(Building b){if(b==null)return null;b=Hibernate.unproxy(b,Building.class);return Map.of("id",b.id,"code",b.buildingCode,"type",b.getBuildingType());}
    private Building sourceBuilding(BaseHandlingOrder order,PickedItem item){
        if(item.sourceLocation!=null) {
            var buildings=BaseLocation.findBuildingsByLocations(List.of(Hibernate.unproxy(item.sourceLocation,BaseLocation.class)));
            if(buildings.size()==1)return buildings.get(0);
        }
        return plannedSourceBuilding(order,item);
    }
    private Building plannedSourceBuilding(BaseHandlingOrder order,PickedItem item){
        order=Hibernate.unproxy(order,BaseHandlingOrder.class);
        if(order instanceof PreparingOrder p)for(Lot lot:p.lots)if(lot.lines.stream().anyMatch(l->Objects.equals(l.id,id(item.lotLine))))return lot.chunk==null?null:lot.chunk.building;
        return GlobalPlanningService.startBuilding(order);
    }
    private void markCollected(CollectingOrder order){
        for(var lot:order.lots)if(Set.of(Lot.LotStatus.PENDING,Lot.LotStatus.PLANNED_TO_BE_COLLECTED,Lot.LotStatus.PLANNED_TO_BE_PACKED).contains(lot.status))lot.status=Lot.LotStatus.COLLECTED;
    }
    private Building requiredBuilding(BaseHandlingOrder o){
        if(o instanceof ReplenishmentOrder r)return r.sourceBuilding!=null && !(r instanceof StockProtectionOrder) && r.supplies.stream().anyMatch(line->line.pickedQuantity().compareTo(line.quantity)<0)?r.sourceBuilding:r.destinationBuilding;
        return GlobalPlanningService.startBuilding(o);
    }
    private Map<String,Object> item(BaseHandlingOrder order,PickedItem i,Map<Long,List<Map<String,Object>>> trace){
        var out=new LinkedHashMap<String,Object>();Product p=Hibernate.unproxy(i.product,Product.class);
        var line=Hibernate.unproxy(i.lotLine,LotLine.class);var rawPlanned=line.location!=null?line.location:line.fulfillmentLine==null?null:line.fulfillmentLine.location;var planned=rawPlanned==null?null:Hibernate.unproxy(rawPlanned,BaseLocation.class);var location=i.sourceLocation!=null?Hibernate.unproxy(i.sourceLocation,BaseLocation.class):planned;
        Product requested=Hibernate.unproxy(line.product,Product.class);
        out.put("requestedProductId",requested.id);out.put("requestedName",requested.name);out.put("requestedEan",requested.ean);
        out.put("id",i.id);out.put("lotLineId",line.id);out.put("productId",p.id);out.put("name",p.name);out.put("ean",p.ean);
        out.put("traceability",trace.getOrDefault(i.id,List.of()));
        out.put("quantity",i.quantity);out.put("productType",p.productType);out.put("unit",unit(p));out.put("zone",p.temperatureZone);out.put("status",i.status);
        out.put("plannedSourceBuilding",building(plannedSourceBuilding(order,i)));out.put("plannedSourceLocation",planned==null?null:planned.locationCode);out.put("sourceLocationId",id(i.sourceLocation));out.put("sourceLocation",location==null?null:location.locationCode);out.put("sourceBuilding",building(sourceBuilding(order,i)));return out;
    }
    private List<LoadedTote> totes(HandlingExecution e){
        if(e instanceof PickingExecution p)return new ArrayList<>(p.loadedTotes);
        if(e instanceof GroupingExecution g)return new ArrayList<>(g.loadedTotes);
        return List.of();
    }
    private Map<String,Object> tote(BaseHandlingOrder order,LoadedTote t,Map<Long,List<Map<String,Object>>> trace){
        var out=new LinkedHashMap<String,Object>();Tote physical=Hibernate.unproxy(t.tote,Tote.class);
        out.put("id",t.id);out.put("physicalToteId",physical.id);out.put("code",physical.code);out.put("zone",t.storageType);out.put("status",t.status);
        out.put("bufferId",id(t.buffer));out.put("bufferCode",t.buffer==null?null:t.buffer.bufferCode);
        out.put("cabinetId",id(t.cabinet));out.put("cellRow",t.cellRow);out.put("cellColumn",t.cellColumn);
        out.put("items",t.items.stream().filter(i->i.status==PickedItem.PickStatus.PICKED && i.quantity.signum()>0).sorted(Comparator.comparing(i->i.id)).map(i->item(order,i,trace)).toList());return out;
    }
    /** Shared by compatibility routes: releasing may only erase an untouched reservation. */
    public boolean canRelease(HandlingExecution e){
        if(!active(e) || Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(e.handlingOrder.status))return false;
        if(OperatorCommandReceipt.count("executionId=?1 and action in ?2",e.id,List.of("PICK","MOVE_ITEM","RETURN_ITEM","MISSING","SUBSTITUTE","DEPOSIT_TOTE","RETRIEVE_TOTE","CONCLUDE"))>0)return false;
        if(activityQuantities.hasLegacyWork(e))return false;
        if(e instanceof com.intermarche.backoffice.domain.inventory.InventoryExecution inventory)return !Boolean.TRUE.equals(inventory.countStarted);
        if(MixedPreparationService.supports(Hibernate.unproxy(e.handlingOrder,BaseHandlingOrder.class)))return totes(e).isEmpty() && (!(e instanceof PickingExecution p) || p.itemsToPick.isEmpty());
        if(e instanceof PickingExecution p)return p.loadedTotes.stream().noneMatch(BufferOccupancyService::hasContents) && p.itemsToPick.stream().allMatch(i->i.status==PickedItem.PickStatus.PENDING && i.quantity.compareTo(i.lotLine.quantity)==0 && Objects.equals(id(i.product),id(i.lotLine.product)));
        if(Hibernate.unproxy(e.handlingOrder,BaseHandlingOrder.class) instanceof ReplenishmentOrder r)return OperatorCommandReceipt.count("orderId=?1 and action in ?2",r.id,List.of("PICK_SUPPLIES","RECEIVE_SUPPLIES","REOPEN_SUPPLY"))==0 && r.supplies.stream().allMatch(s->s.pickedQuantity().signum()==0 && s.received.signum()==0);
        return false;
    }
    private Map<String,Object> executionView(HandlingExecution raw,String principal){return executionView(raw,principal,stockTrace.details(raw.handlingOrder.store,List.of(id(raw.handlingOrder))));}
    private Map<String,Object> executionView(HandlingExecution raw,String principal,Map<Long,List<Map<String,Object>>> trace){
        var e=Hibernate.unproxy(raw,HandlingExecution.class);var out=new LinkedHashMap<String,Object>();BaseHandlingOrder o=Hibernate.unproxy(e.handlingOrder,BaseHandlingOrder.class);
        out.put("lineDirectives",com.intermarche.backoffice.engine.fieldops.LineDirectiveService.projection(o.id,principal));out.put("revision",revision(o));out.put("id",e.id);out.put("orderId",o.id);out.put("orderCode",o.code);out.put("type",e.getClass().getSimpleName());out.put("status",e.status);
        out.put("badgeId",e.employee.badgeId);out.put("employeeName",e.employee.getFullName());out.put("mine",principal.equals(e.employee.badgeId));out.put("createdAt",e.createdAt);
        out.put("mixed",MixedPreparationService.supports(o));out.put("phase",e instanceof ReplenishmentExecution r?r.phase:null);
        out.put("canRelease",principal.equals(e.employee.badgeId) && canRelease(e));out.put("requiredBuilding",building(requiredBuilding(o)));
        var account=authentication.getConnectedAccount();out.put("resolutions",shortageCorrections.view(o,account instanceof Employee employee?employee:null));
        out.put("items",e instanceof PickingExecution p?p.itemsToPick.stream().sorted(Comparator.comparing(i->i.id)).map(i->item(o,i,trace)).toList():List.of());
        if(e instanceof PickingExecution p && !MixedPreparationService.supports(o)){
            try{out.put("capacities",capacities.preview(capacityRequest(p)));}catch(IllegalArgumentException unavailable){out.put("capacities",List.of());out.put("capacityWarning",unavailable.getMessage());}
        }
        if(o instanceof com.intermarche.backoffice.domain.wms.handling.InventoryOrder inventory)out.put("inventory",java.util.Map.of("scopeKind",inventory.scopeKind,"scopeLabel",inventory.scopeLabel,"referenceAt",inventory.referenceAt,"blind",inventory.blind));
        out.put("totes",totes(e).stream().sorted(Comparator.comparing(t->t.tote.code)).map(t->tote(o,t,trace)).toList());
        return out;
    }
    private List<String> allowedActions(BaseHandlingOrder order,List<HandlingExecution> executions){
        var account=authentication.getConnectedAccount();
        if(!(account instanceof Employee employee) || !employee.active || !Objects.equals(id(employee.store),id(order.store)) || order.assignee!=null && !Objects.equals(id(order.assignee),employee.id))return List.of();
        if(Set.of(BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(order.status))return List.of();
        if(order.status==BaseHandlingOrder.HandlingOrderStatus.DONE){
            if(order instanceof ReplenishmentOrder && executions.stream().anyMatch(e->Objects.equals(id(e.employee),employee.id)))return List.of("REOPEN_SUPPLY");
            return shortageCorrections.events(order).stream().anyMatch(event->shortageCorrections.unavailable(order,event,employee)==null)?List.of("CORRECT_SHORTAGE"):List.of();
        }
        if(executions.stream().anyMatch(e->active(e) && !Objects.equals(id(e.employee),employee.id)))return List.of();
        var actions=new ArrayList<String>();
        if(executions.stream().noneMatch(this::active) && order.status==BaseHandlingOrder.HandlingOrderStatus.SCHEDULED)actions.add("RESERVE");
        for(var raw:executions)if(active(raw) && Objects.equals(id(raw.employee),employee.id)){
            var e=Hibernate.unproxy(raw,HandlingExecution.class);
            if(canRelease(e))actions.add("RELEASE");
            if(e instanceof com.intermarche.backoffice.domain.inventory.InventoryExecution)actions.addAll(List.of("START_INVENTORY","COUNT_INVENTORY","SUBMIT_INVENTORY","RECOUNT_INVENTORY"));
            if(order instanceof ReplenishmentOrder){actions.addAll(List.of("PICK_SUPPLIES","RECEIVE_SUPPLIES","REOPEN_SUPPLY"));if(order instanceof ReplenishmentOrder supplyOrder&&supplyOrder.operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING)actions.add("REPORT_DELIVERY_DISCREPANCY");if(order instanceof StockProtectionOrder)actions.add("RECONCILE_PROTECTION");}
            else if(e instanceof PickingExecution && !MixedPreparationService.supports(order))actions.addAll(List.of("ADD_TOTE","REMOVE_TOTE","PICK","MOVE_ITEM","RETURN_ITEM","MISSING","SUBSTITUTE","RETRIEVE_TOTE","DEPOSIT_TOTE","CONCLUDE"));
            else if(e instanceof com.intermarche.backoffice.domain.executing.FabricationExecution)actions.add("FABRICATE");
        }
        if(order instanceof PreparingOrder)actions.addAll(List.of("VALIDATE_REPORT","CORRECT_SHORTAGE"));
        return actions.stream().distinct().toList();
    }
    private Map<String,Object> orderView(BaseHandlingOrder raw,List<HandlingExecution> es,String principal){
        return orderView(raw,es,principal,com.intermarche.backoffice.domain.fieldops.MissionOperationalState.find("handlingOrder",raw).firstResult());
    }
    private Map<String,Object> orderView(BaseHandlingOrder raw,List<HandlingExecution> es,String principal,com.intermarche.backoffice.domain.fieldops.MissionOperationalState operational){return orderView(raw,es,principal,operational,stockTrace.details(raw.store,List.of(id(raw))));}
    private Map<String,Object> orderView(BaseHandlingOrder raw,List<HandlingExecution> es,String principal,com.intermarche.backoffice.domain.fieldops.MissionOperationalState operational,Map<Long,List<Map<String,Object>>> trace){
        BaseHandlingOrder o=Hibernate.unproxy(raw,BaseHandlingOrder.class);var out=new LinkedHashMap<String,Object>();
        out.put("allowedActions",allowedActions(o,es));out.put("revision",revision(o));out.put("id",o.id);out.put("code",o.code);out.put("kind",o.getClass().getSimpleName());out.put("status",o.status);
        out.put("operational",com.intermarche.backoffice.engine.fieldops.MissionStateService.view(operational));
        var stateActor=authentication.getConnectedAccount();out.put("canChangeOperationalState",stateActor.getRoles().stream().anyMatch(role->Set.of("ADMIN","MANAGER").contains(role)) || o.assignee!=null && principal.equals(o.assignee.badgeId) || es.stream().anyMatch(e->principal.equals(e.employee.badgeId)));
        if(operational!=null&&!operational.state.equals("ACTIVE"))out.put("allowedActions",List.of());
        out.put("preparation",com.intermarche.backoffice.engine.fieldops.MissionPreparationService.projection(o.id));
        out.put("operation",o instanceof ReplenishmentOrder r?r.operation:null);
        out.put("start",o.requestedStart);out.put("end",o.requestedEnd);out.put("assigneeBadge",o.assignee==null?null:o.assignee.badgeId);out.put("assigneeName",o.assignee==null?null:o.assignee.getFullName());
        out.put("requiredBuilding",building(requiredBuilding(o)));
        out.put("available",(operational==null || operational.state.equals("ACTIVE")) && o.status==BaseHandlingOrder.HandlingOrderStatus.SCHEDULED && es.stream().noneMatch(this::active) && es.stream().noneMatch(e->e.status==HandlingExecution.ExecutionStatus.DONE));
        if(o instanceof com.intermarche.backoffice.domain.wms.handling.FabricationOrder fabrication)out.put("fabricationLines",cateringMission.lines(fabrication));
        out.put("executions",es.stream().map(e->executionView(e,principal,trace)).toList());return out;
    }
    private boolean visible(BaseHandlingOrder order,List<HandlingExecution> es){
        var account=authentication.getConnectedAccount();var roles=account.getRoles();
        if(!roles.contains("PICKER") || roles.contains("MANAGER") || roles.contains("ADMIN"))return true;
        String badge=account.getUsername();
        return order.assignee!=null && badge.equals(order.assignee.badgeId) || es.stream().anyMatch(e->badge.equals(e.employee.badgeId)) || order.assignee==null && order.status==BaseHandlingOrder.HandlingOrderStatus.SCHEDULED && es.stream().noneMatch(this::active);
    }
    public Object orders(Long storeId){
        Store store=stores.scope(storeId);String principal=authentication.getConnectedAccount().getUsername();
        List<BaseHandlingOrder> orders=BaseHandlingOrder.<BaseHandlingOrder>find("store=?1 and id not in (select fo.id from FabricationOrder fo where fo.proposalState = 'PROPOSED') order by requestedStart,id",store).range(0,199).list();
        List<HandlingExecution> all=orders.isEmpty()?List.of():HandlingExecution.list("handlingOrder.id in ?1 order by id",orders.stream().map(o->o.id).toList());
        // The store board also lists other assignees. scoped(), assignment() and own()
        // retain their existing restrictions on individual access and all mutations.
        var operational=com.intermarche.backoffice.domain.fieldops.MissionOperationalState.<com.intermarche.backoffice.domain.fieldops.MissionOperationalState>list("store=?1 and handlingOrder.id in ?2",store,orders.stream().map(o->o.id).toList()).stream().collect(java.util.stream.Collectors.toMap(x->id(x.handlingOrder),x->x));
        var trace=stockTrace.details(store,orders.stream().map(o->o.id).toList());
        return orders.stream().map(o->orderView(o,all.stream().filter(e->Objects.equals(id(e.handlingOrder),o.id)).toList(),principal,operational.get(o.id),trace)).toList();
    }
    /** Internal transaction collaborator: never exposed as a separate HTTP operation. */
    public String currentRevisionForHandover(BaseHandlingOrder order){return revision(order);}
    public void validateExecutionCapacity(PickingExecution execution){for(var capacity:capacities.preview(capacityRequest(execution)))if(capacity.overCapacity)throw bad("Capacité dépassée pour le support "+capacity.code);com.intermarche.backoffice.engine.fieldops.SupportLayoutService.requireCartCapacities(execution.handlingOrder.store,execution.loadedTotes.stream().map(t->t.tote).toList());}
    public Object productLocations(Long storeId,Long orderId,Long requestedProductId,Long productId,String plannedCode,String preferredBuilding) {
        Store store=stores.scope(storeId);BaseHandlingOrder order=scoped(store,orderId);
        if(!(order instanceof CollectingOrder collecting))throw bad("Ordre de collecte requis");
        Product requested=collecting.lots.stream().flatMap(lot->lot.lines.stream()).filter(line->Objects.equals(id(line.product),requestedProductId)).map(line->Hibernate.unproxy(line.product,Product.class)).findFirst().orElseThrow(()->bad("Produit hors de la mission"));
        Product product=resolutions.replacement(store,requested,productId==null?requestedProductId:productId);
        return productLocations.choices(store,product,plannedCode,preferredBuilding);
    }
    public Object order(Long storeId,Long id){Store store=stores.scope(storeId);BaseHandlingOrder o=scoped(store,id);String actor=authentication.getConnectedAccount().getUsername();var result=orderView(o,executions(o),actor);result.put("lineDirectives",com.intermarche.backoffice.engine.fieldops.LineDirectiveService.projection(o.id,actor));result.put("canManageDirectives",authentication.getConnectedAccount().getRoles().stream().anyMatch(r->Set.of("ADMIN","MANAGER").contains(r)));var concrete=Hibernate.unproxy(o,BaseHandlingOrder.class);result.put("directiveLines",concrete instanceof PreparingOrder p?p.lots.stream().flatMap(lot->lot.lines.stream().map(l->Map.of("id",l.id,"kind","LOT_LINE","name",l.product.name,"label",l.product.name+" · "+l.quantity.stripTrailingZeros().toPlainString()+" "+unit(l.product)+(lot.fulfillment==null||lot.fulfillment.order==null?"":" · "+lot.fulfillment.order.orderId)+(l.location==null?"":" · "+l.location.locationCode)))).distinct().toList():concrete instanceof ReplenishmentOrder r?r.supplies.stream().map(l->Map.of("id",l.id,"kind","EXPECTED_SUPPLY","name",l.account.product.name)).toList():List.of());if(concrete instanceof PreparingOrder preparing&&MixedPreparationService.supports(concrete)){var aliases=new LinkedHashMap<String,Long>();if(!Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(preparing.status))for(var source:mixedPreparation.sources(preparing))aliases.put(Long.toString(source.key()),id(source.line()));result.put("directiveSourceLines",aliases);}return result;}
    public Object execution(Long storeId,Long id){Store store=stores.scope(storeId);HandlingExecution e=HandlingExecution.findById(id);if(e==null)throw new NotFoundException();scoped(store,id(e.handlingOrder));return executionView(e,authentication.getConnectedAccount().getUsername());}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Réponse opérateur non sérialisable",e);}}
    private String principal(){return authentication.getConnectedAccount().getUsername();}
    private void key(String key){if(key==null || !key.matches("[a-zA-Z0-9-]{8,64}"))throw bad("Référence de confirmation requise");}
    public String receipt(Long storeId,String key){
        key(key);Store store=stores.scope(storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        OperatorCommandReceipt r=OperatorCommandReceipt.find("store=?1 and performedBy=?2 and requestKey=?3",store,principal(),key).firstResult();
        return r==null?"{\"found\":false}":"{\"found\":true,\"result\":"+r.result+"}";
    }
    private void legacyReport(HandlingOrderService.BatchValidationRequest report){
        if(report==null || report.orderIds==null || report.orderIds.isEmpty() || new HashSet<>(report.orderIds).size()!=report.orderIds.size())throw bad("Rapport historique et ordres distincts requis.");
    }
    private Map<String,Object> legacyState(BaseHandlingOrder o,Employee employee){
        var es=executions(o);var state=new LinkedHashMap<String,Object>();state.put("id",o.id);state.put("code",o.code);state.put("status",o.status);
        String resolution;
        if(o.status==BaseHandlingOrder.HandlingOrderStatus.DONE || es.stream().anyMatch(e->e.status==HandlingExecution.ExecutionStatus.DONE))resolution="COMPLETED_OBSERVED";
        else if(o.status==BaseHandlingOrder.HandlingOrderStatus.CANCELLED || o.status==BaseHandlingOrder.HandlingOrderStatus.FAILED)resolution="CLOSED";
        else if(es.stream().anyMatch(e->active(e) && !Objects.equals(id(e.employee),employee.id)) || o.assignee!=null && !Objects.equals(id(o.assignee),employee.id))resolution="TAKEN_BY_OTHER";
        else if(es.isEmpty() && Set.of(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,BaseHandlingOrder.HandlingOrderStatus.PROCESSING).contains(o.status))resolution="REPLAYABLE";
        else if(MixedPreparationService.supports(o) && es.stream().allMatch(this::active))resolution="REPLAYABLE";
        else resolution="EXECUTION_AVAILABLE";
        state.put("resolution",resolution);state.put("executions",es.stream().map(e->executionView(e,employee.badgeId)).toList());return state;
    }
    public Object inspectLegacy(OperatorCommandInput input){
        if(input==null)throw bad("Rapport requis.");legacyReport(input.report);Store store=stores.scope(input.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);Employee employee=actor(store);
        var states=input.report.orderIds.stream().map(id->legacyState(scoped(store,id),employee)).toList();
        return Map.of("orders",states,"canRecover",states.stream().allMatch(s->Set.of("REPLAYABLE","COMPLETED_OBSERVED").contains(s.get("resolution"))),"sameHistoricalReportProven",false);
    }
    @jakarta.inject.Inject com.intermarche.backoffice.engine.catalog.ProductInformationService productInformation;
    @jakarta.inject.Inject com.intermarche.backoffice.engine.stock.PickedStockCoordinator stockTrace;
    @Inject com.intermarche.backoffice.engine.fieldops.InventoryCountingService inventories;
    @Inject com.intermarche.backoffice.engine.replenishment.ReceiptDiscrepancyResource deliveryDiscrepancies;
    @Inject com.intermarche.backoffice.engine.stock.InventoryScopeGuard freezes;
    @Inject com.intermarche.backoffice.engine.operator.OperationQuantities activityQuantities;
    public String apply(OperatorCommandInput input){
        if(input==null || input.action==null)throw bad("Action requise");key(input.requestKey);
        Store store=stores.scope(input.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);Employee employee=actor(store);
        String envelope=json(input);String fingerprint=com.intermarche.backoffice.engine.operator.CommandCanonical.hash(envelope);
        OperatorCommandReceipt old=OperatorCommandReceipt.find("store=?1 and performedBy=?2 and requestKey=?3",store,employee.badgeId,input.requestKey).firstResult();
        if(old!=null){
            boolean same=old.fingerprint.equals(fingerprint) || old.payload!=null && com.intermarche.backoffice.engine.operator.CommandCanonical.hash(old.payload).equals(fingerprint);
            if(!same)throw new com.intermarche.backoffice.engine.operator.OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence a déjà confirmé une autre action. Utilisez une nouvelle référence pour une autre intention.");
            return old.result;
        }
        if(input.orderId!=null)em.lock(scoped(store,input.orderId),LockModeType.PESSIMISTIC_WRITE);
        if(input.report!=null && input.report.orderIds!=null)for(Long orderId:new TreeSet<>(input.report.orderIds))em.lock(scoped(store,orderId),LockModeType.PESSIMISTIC_WRITE);
        if((input.measurement!=null||input.scanId!=null)&&!Set.of("PICK","PICK_SUPPLIES","RECEIVE_SUPPLIES").contains(input.action))throw bad("Scan et mesure réservés à un prélèvement ou une réception unitaire.");
        missionStates.requireActive(store,input);
        verifyRevision(input,store);
        var quantityBefore=activityQuantities.before(input);
        var fullContents=com.intermarche.backoffice.engine.fieldops.SupportLayoutService.fullContents(store);
        Object inventoryResult=null;Object discrepancyResult=null;
        Set<Long> changed=new LinkedHashSet<>();
        if("RESERVE".equals(input.action)){
            BaseHandlingOrder o=scoped(store,input.orderId);openOrder(o);assignment(o,employee);changed.add(o.id);
            List<HandlingExecution> es=executions(o);for(var e:es)if(active(e))own(e,employee);
            if(es.stream().noneMatch(this::active)){
                if(es.stream().anyMatch(e->e.status==HandlingExecution.ExecutionStatus.DONE))throw conflict("Cette manutention a déjà été exécutée.");
                if(o instanceof ReplenishmentOrder)replenishment.start(o.id,store.id);
                else {
                    if(o.status!=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED)throw conflict("Seul un ordre planifié peut être réservé.");
                    if(MixedPreparationService.supports(o)) {
                        transit.require(employee,o,GlobalPlanningService.startBuilding(o));
                        HandlingExecution e=o instanceof GroupingOrder?new GroupingExecution():new PickingExecution();
                        e.handlingOrder=o;e.employee=employee;e.persist();
                    } else handling.reserveOrder(o.id,employee.badgeId);
                    o.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
                }
            }
        }else if("RELEASE".equals(input.action)){
            BaseHandlingOrder o=scoped(store,input.orderId);openOrder(o);assignment(o,employee);List<HandlingExecution> es=executions(o);
            if(es.isEmpty())throw conflict("Aucune réservation à libérer.");
            for(var e:es){own(e,employee);if(!canRelease(Hibernate.unproxy(e,HandlingExecution.class)))throw conflict("L’exécution contient des opérations déjà confirmées. Reprenez-la sans effacer son avancement.");}
            for(var raw:es){var e=Hibernate.unproxy(raw,HandlingExecution.class);
                if(e instanceof PickingExecution p){
                    if(MixedPreparationService.supports(o))p.loadedTotes.clear();
                    else {for(var t:p.loadedTotes){t.status=LoadedTote.Status.DELIVERED;freezes.tote(store,t);t.clearCell();t.buffer=null;}p.loadedTotes.clear();}
                }else if(e instanceof GroupingExecution g)g.loadedTotes.clear();
                e.delete();
            }
            o.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;changed.add(o.id);
        }else if(Set.of("PICK_SUPPLIES","RECEIVE_SUPPLIES","REOPEN_SUPPLY").contains(input.action)){
            BaseHandlingOrder o=scoped(store,input.orderId);assignment(o,employee);
            if(!(o instanceof ReplenishmentOrder))throw bad("Ordre d’approvisionnement requis.");
            for(var execution:executions(o))if(active(execution))own(execution,employee);
            if("REOPEN_SUPPLY".equals(input.action)){
                if(input.reopen==null || !Objects.equals(input.reopen.storeId,store.id))throw bad("Correction du magasin sélectionné requise.");
                replenishment.reopen(o.id,input.reopen);
            }else {
                if(input.receipt==null || !Objects.equals(input.receipt.storeId,store.id))throw bad("Confirmation du magasin sélectionné requise.");
                if(input.measurement!=null||input.scanId!=null){
                    if(input.receipt.lines==null||input.receipt.lines.size()!=1)throw bad("Une mesure ou un scan concerne une seule ligne à la fois.");
                    var line=input.receipt.lines.getFirst();var supply=com.intermarche.backoffice.domain.stock.ExpectedSupply.<com.intermarche.backoffice.domain.stock.ExpectedSupply>findById(line.supplyId);
                    if(supply==null||((ReplenishmentOrder)o).supplies.stream().noneMatch(x->x.id.equals(supply.id)))throw bad("Ligne absente de cet ordre");
                    line.measurement=input.measurement;line.scanId=input.scanId;line.stockSegmentId=input.stockSegmentId;line.manufacturerLotId=input.manufacturerLotId;line.serialId=input.serialId;
                }
                if(input.receipt.lines!=null)for(var row:input.receipt.lines)com.intermarche.backoffice.engine.fieldops.LineDirectiveService.requireReady(o,"EXPECTED_SUPPLY",row.supplyId);
                if("PICK_SUPPLIES".equals(input.action))replenishment.pick(o.id,input.receipt);else replenishment.receive(o.id,input.receipt);
            }
            changed.add(o.id);
        }else if(Set.of("RECOVER_REPORT","RESOLVE_LEGACY_REPORT").contains(input.action)){
            legacyReport(input.report);
            if(input.reason==null || input.reason.isBlank() || input.reason.length()>300)throw bad("Motif de résolution historique requis (300 caractères maximum).");
            List<Long> replayable=new ArrayList<>();
            for(Long id:input.report.orderIds){var o=scoped(store,id);if(!(o instanceof PreparingOrder))throw bad("Rapport historique de préparation requis.");changed.add(id);
                var state=legacyState(o,employee);String resolution=(String)state.get("resolution");
                if("RECOVER_REPORT".equals(input.action)){
                    if("REPLAYABLE".equals(resolution))replayable.add(id);
                    else if(!"COMPLETED_OBSERVED".equals(resolution))throw conflict("L’état de "+o.code+" impose de reprendre l’exécution réelle ou de classer le dossier historique, sans renvoyer le rapport.");
                }
            }
            if(!replayable.isEmpty()){
                var report=new HandlingOrderService.BatchValidationRequest();report.orderIds=replayable;report.bufferId=input.report.bufferId;
                report.reports=input.report.reports==null?null:input.report.reports.stream().filter(r->r!=null && replayable.contains(r.orderId)).toList();
                handling.createAndConcludeBatch(report,"operator:"+employee.badgeId+":"+input.requestKey);
                for(Long id:replayable)for(var execution:executions(scoped(store,id)))if(Hibernate.unproxy(execution,HandlingExecution.class) instanceof PickingExecution picking && !MixedPreparationService.supports(scoped(store,id)))
                    validateExecutionCapacity(picking);
                for(Long id:replayable){var o=scoped(store,id);o.status=BaseHandlingOrder.HandlingOrderStatus.DONE;if(o instanceof CollectingOrder collecting)markCollected(collecting);}
            }
        }else if("CORRECT_SHORTAGE".equals(input.action)){
            BaseHandlingOrder o=scoped(store,input.orderId);assignment(o,employee);shortageCorrections.correct(o,employee,input);changed.add(o.id);
        }else if("REPORT_DELIVERY_DISCREPANCY".equals(input.action)){
            BaseHandlingOrder order=scoped(store,input.orderId);assignment(order,employee);if(!(order instanceof ReplenishmentOrder r)||r.operation!=ReplenishmentOrder.Operation.TRUCK_UNLOADING)throw bad("Arrivée fournisseur requise");for(var execution:executions(order))if(active(execution))own(execution,employee);if(input.deliveryDiscrepancy==null)throw bad("Décrivez l’écart de livraison");input.deliveryDiscrepancy.storeId=store.id;input.deliveryDiscrepancy.orderId=order.id;input.deliveryDiscrepancy.requestKey=input.requestKey;discrepancyResult=deliveryDiscrepancies.report(input.deliveryDiscrepancy);changed.add(order.id);
        }else if(Set.of("START_INVENTORY","COUNT_INVENTORY","SUBMIT_INVENTORY","RECOUNT_INVENTORY").contains(input.action)){
            HandlingExecution raw=ownedExecution(store,employee,input.executionId);BaseHandlingOrder order=Hibernate.unproxy(raw.handlingOrder,BaseHandlingOrder.class);if(!(order instanceof com.intermarche.backoffice.domain.wms.handling.InventoryOrder))throw bad("Mission d’inventaire requise");
            if(input.inventory==null){if("COUNT_INVENTORY".equals(input.action))throw bad("Résultat du comptage requis");input.inventory=new com.intermarche.backoffice.engine.fieldops.InventoryCountingService.CountInput();}
            input.inventory.storeId=store.id;input.inventory.orderId=order.id;input.inventory.requestKey=input.requestKey;
            inventoryResult=switch(input.action){case "START_INVENTORY"->inventories.start(input.inventory);case "COUNT_INVENTORY"->inventories.count(input.inventory);case "SUBMIT_INVENTORY"->inventories.submit(input.inventory);case "RECOUNT_INVENTORY"->inventories.recount(input.inventory);default->throw bad("Action d’inventaire inconnue");};changed.add(order.id);
        }else if("RECONCILE_PROTECTION".equals(input.action)){
            BaseHandlingOrder o=scoped(store,input.orderId);openOrder(o);assignment(o,employee);
            if(!(o instanceof StockProtectionOrder secure))throw bad("Cette action concerne uniquement la mise en sécurité.");
            for(var execution:executions(o))if(active(execution))own(execution,employee);
            protection.reconcile(secure);changed.add(o.id);
        }else if("VALIDATE_REPORT".equals(input.action)){
            if(input.report==null || input.report.orderIds==null)throw bad("Rapport requis");
            for(Long id:input.report.orderIds){BaseHandlingOrder o=scoped(store,id);openOrder(o);assignment(o,employee);
                if(!(o instanceof PreparingOrder) || !Set.of(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,BaseHandlingOrder.HandlingOrderStatus.PROCESSING).contains(o.status))throw bad("Seuls les ordres de préparation planifiés ou en cours acceptent un rapport.");for(var e:executions(o))if(active(e))own(e,employee);changed.add(id);}
            Set<String> reportTotes=new HashSet<>();
            if(input.report.reports!=null)for(var report:input.report.reports)if(report!=null && report.totes!=null)for(var tote:report.totes)if(tote!=null && !reportTotes.add(tote.code))throw bad("Un bac ne peut pas être partagé entre les ordres d’un rapport.");
            handling.createAndConcludeBatch(input.report,"operator:"+employee.badgeId+":"+input.requestKey);
            for(Long id:changed)for(var execution:executions(scoped(store,id)))if(Hibernate.unproxy(execution,HandlingExecution.class) instanceof PickingExecution picking && !MixedPreparationService.supports(scoped(store,id)))
                validateExecutionCapacity(picking);
            for(Long id:changed){var o=scoped(store,id);if(executions(o).stream().allMatch(e->e.status==HandlingExecution.ExecutionStatus.DONE)){o.status=BaseHandlingOrder.HandlingOrderStatus.DONE;if(o instanceof CollectingOrder collecting)markCollected(collecting);}}
        }else{
            HandlingExecution raw=ownedExecution(store,employee,input.executionId);BaseHandlingOrder o=Hibernate.unproxy(raw.handlingOrder,BaseHandlingOrder.class);changed.add(o.id);
            if(raw instanceof com.intermarche.backoffice.domain.executing.FabricationExecution fab){
                try { cateringExecution.apply(fab,input,employee,store); } catch(IllegalArgumentException e) { throw bad(e.getMessage()); }
            }
            else if(!(raw instanceof PickingExecution p) || MixedPreparationService.supports(o))throw bad("Utilisez le rapport complet pour cette préparation mixte.");
            else { try { applyPicking(p,input,employee,store); } catch(IllegalArgumentException e) { throw bad(e.getMessage()); } }
        }
        if(Set.of("PICK","MOVE_ITEM","RETURN_ITEM","VALIDATE_REPORT","RECOVER_REPORT","CONCLUDE","DEPOSIT_TOTE","RETRIEVE_TOTE").contains(input.action))stockTrace.synchronize(store,changed,input,"operator:"+employee.badgeId+":"+input.requestKey);
        if(Set.of("CONCLUDE","VALIDATE_REPORT").contains(input.action))for(Long orderId:changed)closure.requireFinal(scoped(store,orderId),input.finalScans);
        em.flush();com.intermarche.backoffice.engine.fieldops.SupportLayoutService.requireNoFullIncrease(store,fullContents);var result=new LinkedHashMap<String,Object>();result.put("requestKey",input.requestKey);result.put("action",input.action);result.put("appliedAt",DateTimeProvider.now());
        result.put("orders",changed.stream().map(id->{var o=scoped(store,id);return orderView(o,executions(o),employee.badgeId);}).toList());
        if(inventoryResult!=null)result.put("inventory",inventoryResult);if(discrepancyResult!=null)result.put("deliveryDiscrepancy",discrepancyResult);
        result.put("contractVersion",2);result.put("outcome","CONFIRMED");
        result.put("trackingId",UUID.randomUUID().toString());
        var facts=new LinkedHashMap<String,Object>();facts.put("confirmedAction",input.action);facts.put("orderIds",changed);
        facts.put("measurement",input.measurement);facts.put("scanId",input.scanId);facts.put("itemId",input.itemId);facts.put("quantity",input.quantity);facts.put("toteCode",input.toteCode);facts.put("targetToteCode",input.targetToteCode);
        facts.put("bufferId",input.bufferId);facts.put("cabinetId",input.cabinetId);facts.put("cellRow",input.cellRow);facts.put("cellColumn",input.cellColumn);result.put("facts",facts);
        var warnings=new ArrayList<Map<String,String>>();
        if(input.contractVersion==null || input.contractVersion==1)warnings.add(Map.of("code","LEGACY_WITHOUT_REVISION","message","Client historique : la révision attendue n’a pas été fournie."));
        for(Long orderId:changed)for(var execution:executions(scoped(store,orderId)))if(Hibernate.unproxy(execution,HandlingExecution.class) instanceof PickingExecution picking)
            for(var capacity:capacities.preview(capacityRequest(picking)))if(!capacity.volumeComplete)warnings.add(Map.of("code","VOLUME_UNKNOWN","message","Volume incomplet pour le bac "+capacity.code+" : vérifiez son remplissage."));
        result.put("warnings",warnings);result.put("nextActions",List.of("REFRESH_MISSION","CHECK_RECEIPT"));
        String output=json(result);
        var receipt=new OperatorCommandReceipt();receipt.store=store;receipt.performedBy=employee.badgeId;receipt.requestKey=input.requestKey;receipt.action=input.action;receipt.executionId=input.executionId;receipt.orderId=input.orderId==null&&changed.size()==1?changed.iterator().next():input.orderId;receipt.fingerprint=fingerprint;receipt.payload=envelope;receipt.result=output;
        if("PICK".equals(input.action)&&input.itemId!=null){var item=PickedItem.<PickedItem>findById(input.itemId);if(item!=null&&input.quantity!=null){Product actual=input.productId==null?item.product:Product.findById(input.productId);receipt.activityQuantity=input.quantity;receipt.activityUnit=com.intermarche.backoffice.engine.catalog.ProductInformationService.unit(actual);receipt.activityLineId=id(item.lotLine);receipt.activityProductId=id(actual);}}
        receipt.quantityProjectionVersion=Set.of("VALIDATE_REPORT","RECOVER_REPORT").contains(input.action)?2:1;receipt.persistAndFlush();
        activityQuantities.save(store,employee.badgeId,"MISSION",receipt.id,input.action,activityQuantities.after(input,quantityBefore));return output;
    }
    private ToteCapacityService.Request capacityRequest(PickingExecution p){
        var request=new ToteCapacityService.Request();request.orderIds=List.of(id(p.handlingOrder));request.totes=new ArrayList<>();
        for(var tote:p.loadedTotes){var load=new ToteCapacityService.Load();load.code=tote.tote.code;load.items=new ArrayList<>();
            for(var i:tote.items)if(i.status==PickedItem.PickStatus.PICKED && i.quantity.signum()>0){var item=new ToteCapacityService.Item();item.sourceOrderId=id(p.handlingOrder);item.pickedItemId=i.id;item.quantity=i.quantity;load.items.add(item);}request.totes.add(load);
        }return request;
    }
    private void held(LoadedTote tote){if(tote.buffer!=null)throw bad("Reprenez physiquement le bac déposé avant de modifier son contenu.");}
    private LoadedTote ownedTote(PickingExecution p,String code){return p.loadedTotes.stream().filter(t->t.tote.code.equals(code) && t.status==LoadedTote.Status.IN_USE).findFirst().orElseThrow(()->bad("Bac absent de cette exécution"));}
    private PickedItem pending(PickingExecution p,Long id){return p.itemsToPick.stream().filter(i->i.id.equals(id) && i.status==PickedItem.PickStatus.PENDING).findFirst().orElseThrow(()->bad("Article absent de la liste restant à traiter"));}
    private void quantity(Product p,BigDecimal quantity){if(quantity==null || quantity.signum()<=0 || quantity.stripTrailingZeros().scale()>3 || quantity.precision()>10 || p.productType==ProductType.UNIT && quantity.stripTrailingZeros().scale()>0)throw bad("Quantité positive, pièces entières, trois décimales maximum pour kg/L");}
    private void applyPicking(PickingExecution p,OperatorCommandInput c,Employee employee,Store store){
        // Gate the physical bins too: these may be targeted through manager/buffer APIs.
        var physical=new TreeSet<Long>();
        for(String code:Arrays.asList(c.toteCode,c.targetToteCode))if(code!=null){Tote tote=Tote.findByCode(code);if(tote!=null)physical.add(tote.id);}
        for(Long toteId:physical)em.find(Tote.class,toteId,LockModeType.PESSIMISTIC_WRITE);
        if(c.itemId!=null){PickedItem item=em.find(PickedItem.class,c.itemId,LockModeType.PESSIMISTIC_WRITE);if(item!=null)em.refresh(item);}
        switch(c.action){
            case "ADD_TOTE" -> {
                Tote t=Tote.findByCode(c.toteCode);if(t==null || !Objects.equals(id(t.store),store.id) || c.usage==null)throw bad("Bac du magasin et température requis");
                p.addTote(t,c.usage);
            }
            case "REMOVE_TOTE" -> {var t=ownedTote(p,c.toteCode);if(BufferOccupancyService.hasContents(t))throw bad("Ce bac contient encore des produits.");p.removeEmptyTote(t.tote);t.status=LoadedTote.Status.DELIVERED;freezes.tote(store,t);t.clearCell();t.buffer=null;}
            case "PICK" -> {
                com.intermarche.backoffice.engine.fieldops.SupportLayoutService.requireWritable(ownedTote(p,c.toteCode));
                var i=pending(p,c.itemId);com.intermarche.backoffice.engine.fieldops.LineDirectiveService.requireReady(p.handlingOrder,"LOT_LINE",id(i.lotLine));com.intermarche.backoffice.engine.fieldops.CancellationDispositionService.requireUncancelled(i);Product product=resolutions.replacement(store,i.lotLine.product,c.productId==null?i.product.id:c.productId);productInformation.preparePick(c,product,store,employee);quantity(product,c.quantity);
                if(c.quantity.compareTo(i.quantity)>0)throw bad("La quantité dépasse le reliquat");
                var tote=ownedTote(p,c.toteCode);held(tote);if(tote.storageType!=product.temperatureZone || !tote.tote.isUsageAllowed(product.temperatureZone))throw bad("Bac incompatible avec le produit");
                com.intermarche.backoffice.engine.fieldops.DepositTargetGuard.require(store,p.handlingOrder,c.toteCode,c.depositScanCode,i.lotLine);
                var actualSource=productLocations.validate(store,product,c.sourceLocationId);
                var actualBuildings=actualSource==null?java.util.List.<Building>of():BaseLocation.findBuildingsByLocations(java.util.List.of(actualSource)).stream().map(b->(Building)b).toList();
                transit.require(employee,p.handlingOrder,actualSource==null?sourceBuilding(p.handlingOrder,i):actualBuildings.getFirst());
                if(!Objects.equals(product.id,id(i.lotLine.product)))resolutions.record(p.handlingOrder,i.lotLine,employee,c.quantity,product,c.reason);
                p.addItemToTote(tote,i,c.quantity,product,actualSource);
            }
            case "MOVE_ITEM" -> {if(Objects.equals(c.toteCode,c.targetToteCode))throw bad("Choisissez un autre bac de destination.");var from=ownedTote(p,c.toteCode);var to=ownedTote(p,c.targetToteCode);held(from);held(to);com.intermarche.backoffice.engine.fieldops.SupportLayoutService.requireWritable(to);var i=from.items.stream().filter(x->x.id.equals(c.itemId)).findFirst().orElseThrow(()->bad("Article absent du bac source"));if(to.storageType!=i.product.temperatureZone || !to.tote.isUsageAllowed(i.product.temperatureZone))throw bad("Bac incompatible avec la température");com.intermarche.backoffice.engine.fieldops.CancellationDispositionService.requireUncancelled(i);p.moveItemToTote(from,to,i);}
            case "RETURN_ITEM" -> {var from=ownedTote(p,c.toteCode);held(from);var i=from.items.stream().filter(x->x.id.equals(c.itemId)).findFirst().orElseThrow(()->bad("Article absent du bac source"));com.intermarche.backoffice.engine.fieldops.CancellationDispositionService.requireUncancelled(i);p.removeItemFromTote(from,i);}
            case "MISSING" -> {var i=pending(p,c.itemId);resolutions.record(p.handlingOrder,i.lotLine,employee,i.quantity,null,c.reason);i.quantity=BigDecimal.ZERO;p.cancelItem(i);}
            case "SUBSTITUTE" -> {var i=pending(p,c.itemId);i.product=resolutions.replacement(store,i.lotLine.product,c.productId);}
            case "RETRIEVE_TOTE" -> {var t=ownedTote(p,c.toteCode);if(t.buffer==null)throw bad("Ce bac n’est pas déposé.");transit.require(employee,p.handlingOrder,t.buffer.building);freezes.tote(store,t);t.clearCell();t.buffer=null;}
            case "DEPOSIT_TOTE" -> {var t=ownedTote(p,c.toteCode);Buffer b=c.bufferId==null?null:Buffer.findById(c.bufferId);if(b==null)throw bad("Buffer requis");transit.require(employee,p.handlingOrder,b.building);occupancy.deposit(t,c.bufferId,c.cabinetId,c.cellRow,c.cellColumn,store);}
            case "CONCLUDE" -> {
                if(p.itemsToPick.stream().anyMatch(i->i.status==PickedItem.PickStatus.PENDING))throw bad("Il reste des articles à traiter");
                for(var t:p.loadedTotes)if(BufferOccupancyService.hasContents(t)){
                    if(t.buffer==null)throw bad("Déposez tous les bacs avant de terminer");occupancy.deposit(t,id(t.buffer),id(t.cabinet),t.cellRow,t.cellColumn,store);
                }
                for(var t:p.loadedTotes)if(!BufferOccupancyService.hasContents(t)){t.status=LoadedTote.Status.DELIVERED;freezes.tote(store,t);t.clearCell();t.buffer=null;}
                p.status=HandlingExecution.ExecutionStatus.DONE;p.handlingOrder.status=BaseHandlingOrder.HandlingOrderStatus.DONE;
                if(Hibernate.unproxy(p.handlingOrder,BaseHandlingOrder.class) instanceof CollectingOrder collecting)markCollected(collecting);
            }
            default -> throw bad("Action opérateur inconnue");
        }
        if(Set.of("PICK","MOVE_ITEM","CONCLUDE").contains(c.action))validateExecutionCapacity(p);

    }
}
