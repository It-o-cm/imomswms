package com.intermarche.backoffice.engine.storeops;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.hibernate.Hibernate;
import java.time.*;
import java.util.*;
@Path("/store-operations") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER"}) @RunOnVirtualThread @Transactional
public class StoreOperationsResource implements GraphQLTrait {
    @Inject com.intermarche.backoffice.engine.stock.PickedStockCoordinator stockTrace;
    @Inject com.intermarche.backoffice.engine.executing.BufferOccupancyService occupancy;
    @Inject com.intermarche.backoffice.engine.stock.TemporalStockService stocks;
    @Inject com.fasterxml.jackson.databind.ObjectMapper json;
    @Inject PlanningAssignmentService assignments;
    @Inject GlobalPlanningService globalPlanning;
    @Inject EmployeeTransitService employeeTransit;
    @Inject SecurityIdentity identity;@Inject EntityManager em;
    public SecurityIdentity getIdentity(){return identity;}
    @RolesAllowed({"ADMIN","MANAGER","PICKER"}) public Store scope(Long id){
        Store own=getStoreContext();
        if(!identity.hasRole("ADMIN")){if(own==null || id!=null && !Hibernate.unproxy(own,Store.class).id.equals(id))throw new ForbiddenException("Magasin hors de votre périmètre");return Hibernate.unproxy(own,Store.class);}
        Store s=id==null?null:Store.findById(id);if(s==null)throw bad("Magasin requis");return s;
    }
    private BadRequestException bad(String m){return new BadRequestException(jakarta.ws.rs.core.Response.status(400).entity(Map.of("message",m)).build());}
    private String text(String v,int max,String field){if(v==null || v.isBlank() || v.length()>max)throw bad(field+" requis (maximum "+max+" caractères)");return v.trim();}
    private Building building(Store s,Long id){return s.buildings.stream().map(b->Hibernate.unproxy(b,Building.class)).filter(b->b.id.equals(id)).findFirst().orElseThrow(()->bad("Bâtiment hors du magasin"));}
    private Employee employee(Store s,Long id){Employee e=id==null?null:Employee.findById(id);if(e==null || !Hibernate.unproxy(e.store,Store.class).id.equals(s.id))throw bad("Employé hors du magasin");if(identity.hasRole("PICKER") && !identity.hasRole("MANAGER") && !identity.hasRole("ADMIN") && !e.badgeId.equals(identity.getPrincipal().getName()))throw new ForbiddenException("Seule votre position est accessible");return e;}
    @GET @Path("/places") @RolesAllowed({"ADMIN","MANAGER","PICKER"})  public Map<String,Object> places(@QueryParam("storeId") Long storeId){
        Store s=scope(storeId);var bs=s.buildings.stream().map(b->Hibernate.unproxy(b,Building.class)).sorted(Comparator.comparing(b->b.buildingCode)).map(b->Map.<String,Object>of("id",b.id,"code",b.buildingCode,"type",b instanceof WarehouseBuilding?"WAREHOUSE":"STORE","nodes",PlanNode.listByBuilding(b).stream().map(n->Map.of("id",n.id,"code",n.code,"label",n.label==null?n.code:n.label)).toList())).toList();
        var buffers=Buffer.<Buffer>list("select b from Buffer b where b.building.id in (select x.id from Store s join s.buildings x where s=?1) order by b.bufferCode",s).stream().map(b->{var v=new LinkedHashMap<String,Object>();v.put("id",b.id);v.put("code",b.bufferCode);v.put("buildingId",Hibernate.unproxy(b.building,Building.class).id);v.put("description",b.locationDescription);v.put("active",b.active);v.put("ambientCapacity",b.ambientCapacity);v.put("chilledCapacity",b.chilledCapacity);v.put("frozenCapacity",b.frozenCapacity);v.put("planNodeCode",b.planNodeCode);return v;}).toList();
        return Map.of("buildings",bs,"buffers",buffers);
    }
    @GET @Path("/employees/{id}/position") @RolesAllowed({"ADMIN","MANAGER","PICKER"})  public Object employeePosition(@PathParam("id") Long id,@QueryParam("storeId") Long storeId){return employeeTransit.view(employee(scope(storeId),id));}
    public static class PositionInput{public Long storeId,buildingId;}
    @POST @Path("/employees/{id}/position") @RolesAllowed({"ADMIN","MANAGER","PICKER"})  public Object moveEmployee(@PathParam("id") Long id,PositionInput r){if(r==null)throw bad("Position requise");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);try{return employeeTransit.move(employee(s,id),building(s,r.buildingId));}catch(IllegalArgumentException e){throw bad(e.getMessage());}}
    @GET @RolesAllowed({"ADMIN","MANAGER"}) @Path("/transits") public Object transits(@QueryParam("storeId") Long storeId){Store s=scope(storeId);return com.intermarche.backoffice.domain.workforce.BuildingTransit.<com.intermarche.backoffice.domain.workforce.BuildingTransit>list("store=?1 order by id",s).stream().map(t->Map.of("originId",Hibernate.unproxy(t.origin,Building.class).id,"destinationId",Hibernate.unproxy(t.destination,Building.class).id,"minutes",t.minutes)).toList();}
    public static class TransitInput{public Long storeId,originId,destinationId;public int minutes;}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/transits") public Object saveTransit(TransitInput r){if(r==null)throw bad("Trajet requis");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);Building from=building(s,r.originId),to=building(s,r.destinationId);if(from.id.equals(to.id)||r.minutes<0||r.minutes>1440)throw bad("Trajet distinct, de 0 à 1440 minutes");var t=com.intermarche.backoffice.domain.workforce.BuildingTransit.<com.intermarche.backoffice.domain.workforce.BuildingTransit>find("store=?1 and origin=?2 and destination=?3",s,from,to).firstResult();if(t==null)t=new com.intermarche.backoffice.domain.workforce.BuildingTransit();t.store=s;t.origin=from;t.destination=to;t.minutes=r.minutes;t.persistAndFlush();return transits(s.id);}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/planning/solutions") public Object planningSolutions(GlobalPlanningService.Request r){if(r==null)throw bad("Période requise");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);try{return globalPlanning.preview(s,r);}catch(IllegalArgumentException e){throw bad(e.getMessage());}}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/planning/solutions/apply") public Object applyPlanning(GlobalPlanningService.Apply r){if(r==null)throw bad("Solution requise");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);try{var result=globalPlanning.apply(s,r,identity.getPrincipal().getName());em.flush();return result;}catch(IllegalArgumentException e){throw bad(e.getMessage());}}
    public static class BuildingInput{public Long storeId,id;public String code,type;}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/buildings") public Map<String,Object> saveBuilding(BuildingInput r){
        if(r==null)throw bad("Bâtiment requis");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);String code=text(r.code,50,"Code");
        if(!Set.of("STORE","WAREHOUSE").contains(r.type))throw bad("Type de bâtiment invalide");
        Building b=r.id==null?("WAREHOUSE".equals(r.type)?new WarehouseBuilding():new StoreBuilding()):building(s,r.id);
        if(r.id!=null && (b instanceof WarehouseBuilding)!=r.type.equals("WAREHOUSE"))throw bad("Le type d’un bâtiment existant ne peut pas être changé");
        Building other=Building.find("buildingCode",code).firstResult();if(other!=null && !Objects.equals(other.id,b.id))throw bad("Code de bâtiment déjà utilisé");
        b.buildingCode=code;b.persistAndFlush();s.buildings.add(b);em.flush();return places(s.id);
    }
    public static class BufferInput{public Long storeId,id,buildingId;public String code,description,planNodeCode;public boolean active=true;public int ambientCapacity,chilledCapacity,frozenCapacity;}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/buffers") public Map<String,Object> saveBuffer(BufferInput r){
        if(r==null)throw bad("Buffer requis");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);Building target=building(s,r.buildingId);
        if(r.ambientCapacity<0 || r.chilledCapacity<0 || r.frozenCapacity<0)throw bad("Les capacités doivent être positives ou nulles");
        Buffer b=r.id==null?new Buffer():Buffer.findById(r.id);if(b==null)throw bad("Buffer introuvable");
        if(r.id!=null)building(s,Hibernate.unproxy(b.building,Building.class).id);
        String code=text(r.code,100,"Code du buffer");Buffer other=Buffer.findByCode(code);if(other!=null && !Objects.equals(other.id,b.id))throw bad("Code de buffer déjà utilisé");
        if(r.id!=null && !Hibernate.unproxy(b.building,Building.class).id.equals(target.id) && (com.intermarche.backoffice.domain.executing.LoadedTote.count("buffer=?1 and status=?2",b,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE)>0 || BufferToteStockLocation.count("buffer",b)>0))throw bad("Un buffer occupé ou affecté à un rangement ne peut pas changer de bâtiment");
        if(r.planNodeCode!=null && !r.planNodeCode.isBlank() && PlanNode.findByCode(target,r.planNodeCode)==null)throw bad("Point absent du plan de ce bâtiment");
        b.building=target;b.bufferCode=code;b.locationDescription=r.description==null?"":r.description.trim();if(b.locationDescription.length()>100)throw bad("Description trop longue");
        b.active=r.active;b.ambientCapacity=r.ambientCapacity;b.chilledCapacity=r.chilledCapacity;b.frozenCapacity=r.frozenCapacity;b.planNodeCode=r.planNodeCode==null || r.planNodeCode.isBlank()?null:r.planNodeCode;b.persistAndFlush();return places(s.id);
    }
    public static class CellInput {public Long storeId,bufferId,toteId,cabinetId,physicalToteId;public Integer cellRow,cellColumn;public String zone;}
    public List<Map<String,Object>> findTotes(Long storeId,String search){return findTotes(storeId,search,false);}
    @GET @Path("/buffer-totes") @RolesAllowed({"ADMIN","MANAGER","PICKER"})  public List<Map<String,Object>> findTotes(@QueryParam("storeId") Long storeId,@QueryParam("search") String search,@QueryParam("nonEmptyOnly") @DefaultValue("true") boolean nonEmptyOnly){
        Store store=scope(storeId);String term=search==null?"":search.trim().toLowerCase(Locale.ROOT);
        String query="select t from Tote t where t.store=?1 and lower(t.code) like ?2 and not exists (select charged.id from LoadedTote charged where charged.tote=t and charged.loadingDeparture is not null)";
        if(nonEmptyOnly)query+=" and exists (select l.id from LoadedTote l where l.tote=t and l.status=?3 and not exists (select sh.id from ShipmentLine sh where sh.loadedTote=l and sh.toteReturnedAt is null) and (exists (select p.id from PickedItem p where p.loadedTote=l and p.quantity>0 and p.status=?5) or exists (select loc.id from BufferToteStockLocation loc where loc.loadedTote=l and (select sum(m.quantity) from StockMovement m where m.location=loc and m.effectiveAt<=?4)>0) or exists (select loc.id from PickingToteLocation loc where loc.traceOnly=false and loc.protectedTote=l and (select sum(m.quantity) from StockMovement m where m.location=loc and m.effectiveAt<=?4)>0)))";
        Object[] args=nonEmptyOnly?new Object[]{store,"%"+term+"%",com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE,DateTimeProvider.now(),com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED}:new Object[]{store,"%"+term+"%"};
        var totes=Tote.<Tote>find(query+" order by t.code",args).page(0,50).list();if(totes.isEmpty())return List.of();
        var loads=com.intermarche.backoffice.domain.executing.LoadedTote.<com.intermarche.backoffice.domain.executing.LoadedTote>list("select l from LoadedTote l left join fetch l.buffer where l.tote in ?1 and l.status=?2",totes,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE);
        var nonEmpty=com.intermarche.backoffice.engine.executing.BufferOccupancyService.nonEmptyIds(loads);var byTote=new HashMap<Long,com.intermarche.backoffice.domain.executing.LoadedTote>();for(var l:loads)byTote.put(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(l.tote),l);
        var types=new HashMap<Long,com.intermarche.backoffice.domain.fieldops.PhysicalSupport>();for(var support:com.intermarche.backoffice.domain.fieldops.PhysicalSupport.<com.intermarche.backoffice.domain.fieldops.PhysicalSupport>list("select s from PhysicalSupport s join fetch s.type where s.tote in ?1",totes))types.put(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(support.tote),support);
        return totes.stream().map(t->{var load=byTote.get(t.id);var support=types.get(t.id);
            Map<String,Object> v=new LinkedHashMap<>();v.put("physicalToteId",t.id);v.put("nonEmpty",load!=null&&nonEmpty.contains(load.id));v.put("type",t.toteType);v.put("supportKind",support==null?"TOTE":support.type.kind);v.put("supportLabel",support==null?"Bac":support.type.label);v.put("id",load==null?null:load.id);v.put("code",t.code);v.put("zone",load==null?null:load.storageType);v.put("buffer",load==null || load.buffer==null?null:load.buffer.bufferCode);return v;
        }).toList();
    }
    @GET @Path("/buffer-totes/{id}") @RolesAllowed({"ADMIN","MANAGER","PICKER"})  public Map<String,Object> toteContents(@PathParam("id") Long id,@QueryParam("storeId") Long storeId){
        Store store=scope(storeId);Tote physical=Tote.findById(id);if(physical==null || !Hibernate.unproxy(physical.store,Store.class).id.equals(store.id))throw new NotFoundException();
        var load=com.intermarche.backoffice.domain.executing.LoadedTote.<com.intermarche.backoffice.domain.executing.LoadedTote>find("tote=?1 and status=?2",physical,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE).firstResult();
        var items=new ArrayList<Map<String,Object>>();var traceItems=new ArrayList<Map<String,Object>>();if(load!=null){for(var item:load.items)if(item.status==com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED && item.quantity.signum()>0){
            var lots=com.intermarche.backoffice.domain.stock.PickedStockAllocation.<com.intermarche.backoffice.domain.stock.PickedStockAllocation>list("state.store=?1 and state.itemId=?2 and quantity>0 and shipped=false",store,item.id).stream().map(a->a.holding.lot).filter(Objects::nonNull).map(l->l.code).distinct().sorted().toList();
            items.add(toteContentLine(item.id,"PICKED",item.product,item.quantity,lots));
            var allocations=com.intermarche.backoffice.domain.stock.PickedStockAllocation.<com.intermarche.backoffice.domain.stock.PickedStockAllocation>list("state.store=?1 and state.itemId=?2 and quantity>0 and shipped=false",store,item.id);
            var traced=java.math.BigDecimal.ZERO;
            for(var allocation:allocations){appendToteTrace(traceItems,item.id,"PICKED",item.product,allocation.quantity,allocation.holding);traced=traced.add(allocation.quantity);}
            if(item.quantity.compareTo(traced)>0)appendToteTrace(traceItems,item.id,"PICKED",item.product,item.quantity.subtract(traced),null);
        }
            var stockLocations=new ArrayList<ShelfLocation>();stockLocations.addAll(BufferToteStockLocation.<BufferToteStockLocation>list("loadedTote",load));stockLocations.addAll(PickingToteLocation.<PickingToteLocation>list("protectedTote=?1 and traceOnly=false",load));
            for(var location:stockLocations){
                java.math.BigDecimal quantity=com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>list("location=?1 and effectiveAt<=?2",location,DateTimeProvider.now()).stream().map(m->m.quantity).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
                if(quantity.signum()>0){
                    var lots=com.intermarche.backoffice.domain.stock.StockSegment.<com.intermarche.backoffice.domain.stock.StockSegment>list("location",location).stream().filter(segment->com.intermarche.backoffice.engine.stock.StockSegments.balance(segment,DateTimeProvider.now()).signum()>0).map(segment->segment.lot).filter(Objects::nonNull).map(l->l.code).distinct().sorted().toList();
                    items.add(toteContentLine(location.id,"STOCK",location.product,quantity,lots));
                    var traced=java.math.BigDecimal.ZERO;
                    for(var segment:com.intermarche.backoffice.domain.stock.StockSegment.<com.intermarche.backoffice.domain.stock.StockSegment>list("location",location)){
                        var amount=com.intermarche.backoffice.engine.stock.StockSegments.balance(segment,DateTimeProvider.now());
                        if(amount.signum()>0){appendToteTrace(traceItems,location.id,"STOCK",location.product,amount,segment);traced=traced.add(amount);}
                    }
                    if(quantity.compareTo(traced)>0)appendToteTrace(traceItems,location.id,"STOCK",location.product,quantity.subtract(traced),null);
                }
            }
        }var supportKind=em.createQuery("select s.type.kind from PhysicalSupport s where s.tote=?1",com.intermarche.backoffice.domain.fieldops.SupportType.Kind.class).setParameter(1,physical).getResultStream().findFirst().orElse(com.intermarche.backoffice.domain.fieldops.SupportType.Kind.TOTE);var result=new LinkedHashMap<String,Object>(Map.of("supportKind",supportKind,"physicalToteId",physical.id,"sessionId",load==null?0L:load.id,"zone",load==null?"":load.storageType.name(),"code",physical.code,"type",physical.toteType,"items",items));
        var slot=com.intermarche.backoffice.domain.fieldops.CartCompartment.<com.intermarche.backoffice.domain.fieldops.CartCompartment>find("occupant.tote",physical).firstResult();
        if(load!=null){var executions=com.intermarche.backoffice.client.PickingExecution.<com.intermarche.backoffice.client.PickingExecution>list("select distinct e from PickingExecution e join e.loadedTotes t where t.id=?1",load.id);result.put("missionFinished",!executions.isEmpty()&&executions.stream().allMatch(e->e.status==com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE));}
        result.put("traceItems",traceItems);if(slot!=null)result.put("cartSlot",Map.of("cartId",slot.cart.id,"cartCode",slot.cart.code,"compartmentId",slot.id));return result;
    }
    /** Read-only lot projection; quantities come from allocations/ledger, never split from lot names. */
    @SuppressWarnings("unchecked")
    private void appendToteTrace(List<Map<String,Object>> rows,Long source,String kind,com.intermarche.backoffice.domain.order.Product product,java.math.BigDecimal amount,com.intermarche.backoffice.domain.stock.StockSegment segment){
        if(amount.signum()<=0)return;
        var lot=segment==null?null:segment.lot;String lotCode=lot==null?null:lot.code;
        var row=rows.stream().filter(r->Objects.equals(r.get("productId"),product.id)&&Objects.equals(r.get("manufacturerLotCode"),lotCode)).findFirst().orElse(null);
        if(row==null){row=new LinkedHashMap<>(toteContentLine(source,kind,product,java.math.BigDecimal.ZERO,lotCode==null?List.of():List.of(lotCode)));row.put("serialCodes",new ArrayList<String>());
            if(lot!=null){row.put("manufacturerLotCode",lotCode);if(lot.useBy!=null)row.put("useBy",lot.useBy);if(lot.bestBefore!=null)row.put("bestBefore",lot.bestBefore);}
            rows.add(row);
        }
        row.put("quantity",((java.math.BigDecimal)row.get("quantity")).add(amount));
        if(segment!=null&&segment.serial!=null){var serials=(List<String>)row.get("serialCodes");if(!serials.contains(segment.serial.code))serials.add(segment.serial.code);}
    }
    /** Display metadata only; manufacturer lots stay tied to their actual stock segments. */
    private Map<String,Object> toteContentLine(Long id,String kind,com.intermarche.backoffice.domain.order.Product product,java.math.BigDecimal quantity,List<String> lots){
        var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("kind",kind);row.put("productId",product.id);row.put("productName",product.name);row.put("ean",product.ean);row.put("quantity",quantity);row.put("zone",product.temperatureZone);row.put("productType",product.productType);row.put("unit",com.intermarche.backoffice.engine.catalog.ProductInformationService.unit(product));row.put("lotCodes",lots);return row;
    }
    @GET @RolesAllowed({"ADMIN","MANAGER"}) @Path("/buffer-products") public List<Map<String,Object>> bufferProducts(@QueryParam("storeId") Long storeId,@QueryParam("search") String search){
        scope(storeId);String term=search==null?"":search.trim();if(term.length()<2)return List.of();if(term.length()>100)throw bad("Recherche trop longue");String escaped=term.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");
        return com.intermarche.backoffice.domain.order.Product.<com.intermarche.backoffice.domain.order.Product>find("active=true and (lower(name) like ?1 escape '!' or ean like ?2 escape '!') order by name","%"+escaped+"%",escaped+"%").page(0,30).list().stream().map(p->Map.<String,Object>of("id",p.id,"name",p.name,"ean",p.ean,"zone",p.temperatureZone,"productType",p.productType)).toList();
    }
    public static class ToteCorrectionLine {public Long id;public String kind;public java.math.BigDecimal expectedQuantity,quantity;}
    public static class ToteCorrection {public Long storeId,sessionId;public String requestKey,reason;public List<ToteCorrectionLine> lines;}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/buffer-totes/{id}/contents") public Map<String,Object> correctTote(@PathParam("id") Long id,ToteCorrection r){
        if(r==null)throw bad("Correction requise");Store store=scope(r.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        Tote physical=em.find(Tote.class,id,LockModeType.PESSIMISTIC_WRITE);if(physical==null || !Hibernate.unproxy(physical.store,Store.class).id.equals(store.id))throw new NotFoundException();
        String key=text(r.requestKey,40,"Référence"),reason=text(r.reason,200,"Motif");if(r.lines==null || r.lines.isEmpty() || r.lines.size()>500)throw bad("Lignes de correction requises");
        String payload;try{payload=json.writeValueAsString(r);}catch(Exception e){throw bad("Correction invalide");}
        var old=com.intermarche.backoffice.domain.executing.ToteContentCorrection.<com.intermarche.backoffice.domain.executing.ToteContentCorrection>find("store=?1 and requestKey=?2",store,key).firstResult();
        if(old!=null){if(!old.payload.equals(payload) || !Hibernate.unproxy(old.loadedTote.tote,Tote.class).id.equals(id))throw bad("Référence de correction déjà utilisée");return toteContents(id,store.id);}
        var load=com.intermarche.backoffice.domain.executing.LoadedTote.<com.intermarche.backoffice.domain.executing.LoadedTote>find("tote=?1 and status=?2",physical,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE).firstResult();
        if(load==null || !load.id.equals(r.sessionId))throw bad("Le bac a changé de session. Fermez puis rouvrez son contenu");
        em.lock(load,LockModeType.PESSIMISTIC_WRITE);
        if(load.loadingDeparture!=null)throw new com.intermarche.backoffice.engine.operator.OperatorFailure(409,"TOTE_LOADED","Déchargez le bac avant de corriger son contenu");
        if(com.intermarche.backoffice.domain.shipping.ShipmentLine.count("loadedTote",load)>0)throw bad("Le contenu d’un bac expédié ne peut plus être corrigé");
        var seen=new HashSet<String>();
        for(var line:r.lines){
            if(line==null || line.id==null || line.kind==null || !seen.add(line.kind+":"+line.id) || line.quantity==null || line.expectedQuantity==null || line.quantity.signum()<0 || line.expectedQuantity.signum()<0 || line.quantity.precision()>12 || line.quantity.scale()>3)throw bad("Quantité invalide (positive ou nulle, 3 décimales maximum)");
            com.intermarche.backoffice.domain.order.Product product;java.math.BigDecimal before;
            if("PICKED".equals(line.kind)){
                var item=em.find(com.intermarche.backoffice.domain.executing.PickedItem.class,line.id,LockModeType.PESSIMISTIC_WRITE);
                if(item==null || item.loadedTote==null || !Hibernate.unproxy(item.loadedTote,com.intermarche.backoffice.domain.executing.LoadedTote.class).id.equals(load.id) || item.status!=com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED)throw bad("Produit absent du bac");
                product=Hibernate.unproxy(item.product,com.intermarche.backoffice.domain.order.Product.class);before=item.quantity;
                if(before.compareTo(line.expectedQuantity)!=0)throw bad("Le contenu a changé. Fermez puis rouvrez le bac");
                java.math.BigDecimal others=com.intermarche.backoffice.domain.executing.PickedItem.<com.intermarche.backoffice.domain.executing.PickedItem>list("lotLine.fulfillmentLine=?1 and id<>?2 and status=?3",item.lotLine.fulfillmentLine,item.id,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED).stream().map(x->x.quantity).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
                if(line.quantity.add(others).compareTo(item.lotLine.fulfillmentLine.requestedQuantity)>0)throw bad("La quantité dépasse celle de la ligne de préparation");
                item.quantity=line.quantity;
            }else if("ADD".equals(line.kind)){
                product=com.intermarche.backoffice.domain.order.Product.findById(line.id);
                if(product==null || !product.active)throw bad("Produit introuvable ou inactif");
                if(load.buffer==null)throw bad("Placez d’abord le bac dans un buffer avant d’ajouter un produit");
                if(product.temperatureZone!=load.storageType || !physical.isUsageAllowed(product.temperatureZone))throw bad("Le conditionnement du bac est incompatible avec ce produit");
                if(line.expectedQuantity.signum()!=0 || line.quantity.signum()<=0)throw bad("La quantité ajoutée doit être strictement positive");
                if(product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && line.quantity.stripTrailingZeros().scale()>0)throw bad("Une quantité entière est requise pour "+product.name);
                if(com.intermarche.backoffice.domain.executing.PickedItem.count("loadedTote=?1 and product=?2 and status=?3 and quantity>0",load,product,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED)>0)throw bad("Ce produit est déjà prélevé dans le bac. Modifiez sa ligne existante");
                BufferToteStockLocation location=BufferToteStockLocation.find("loadedTote=?1 and product=?2",load,product).firstResult();
                if(location!=null && com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>list("location=?1 and effectiveAt<=?2",location,DateTimeProvider.now()).stream().map(m->m.quantity).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add).signum()>0)throw bad("Ce produit est déjà présent. Rouvrez le bac pour modifier sa quantité");
                if(location==null){location=new BufferToteStockLocation();location.product=product;location.loadedTote=load;location.buffer=load.buffer;location.area="BUFFER";location.aisle="DEPOT";location.unit=String.valueOf(Hibernate.unproxy(load.buffer,Buffer.class).id);location.elevation="1";location.position=String.valueOf(product.id);location.persistAndFlush();load.buffer.building.locations.add(location);}
                var account=stocks.account(store.id,product.id);stocks.adjust(account.id,location.id,"tote-add:"+load.id+":"+key+":"+product.id,line.quantity,DateTimeProvider.now());
            }else if("STOCK".equals(line.kind)){
                BaseLocation loc=Hibernate.unproxy(BaseLocation.findById(line.id),BaseLocation.class);
                boolean belongs=loc instanceof BufferToteStockLocation b && Hibernate.unproxy(b.loadedTote,com.intermarche.backoffice.domain.executing.LoadedTote.class).id.equals(load.id) || loc instanceof PickingToteLocation protectedLocation && !protectedLocation.traceOnly && protectedLocation.protectedTote!=null && Hibernate.unproxy(protectedLocation.protectedTote,com.intermarche.backoffice.domain.executing.LoadedTote.class).id.equals(load.id);
                if(!belongs)throw bad("Adresse absente du bac");product=Hibernate.unproxy(loc.product,com.intermarche.backoffice.domain.order.Product.class);
                before=com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>list("location=?1 and effectiveAt<=?2",loc,DateTimeProvider.now()).stream().map(m->m.quantity).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
                if(before.compareTo(line.expectedQuantity)!=0)throw bad("Le contenu a changé. Fermez puis rouvrez le bac");
                if(line.quantity.compareTo(before)!=0){var account=stocks.account(store.id,product.id);stocks.adjust(account.id,loc.id,"tote:"+load.id+":"+key+":"+line.id,line.quantity.subtract(before),DateTimeProvider.now());}
            }else throw bad("Type de ligne inconnu");
            if(product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && line.quantity.stripTrailingZeros().scale()>0)throw bad("Une quantité entière est requise pour "+product.name);
        }
        stockTrace.syncTotes(store,List.of(load),"tote-correction:"+key);
        var audit=new com.intermarche.backoffice.domain.executing.ToteContentCorrection();audit.store=store;audit.loadedTote=load;audit.requestKey=key;audit.reason=reason;audit.performedBy=identity.getPrincipal().getName();audit.payload=payload;audit.persistAndFlush();return toteContents(id,store.id);
    }
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/buffer-deposits") public Map<String,Object> positionTote(CellInput r){
        if(r==null)throw bad("Dépôt requis");Store store=scope(r.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        var tote=r.toteId==null?null:em.find(com.intermarche.backoffice.domain.executing.LoadedTote.class,r.toteId,LockModeType.PESSIMISTIC_WRITE);
        if(r.physicalToteId!=null){Tote physical=em.find(Tote.class,r.physicalToteId,LockModeType.PESSIMISTIC_WRITE);if(physical==null || !Hibernate.unproxy(physical.store,Store.class).id.equals(store.id))throw bad("Bac hors du magasin");
            tote=com.intermarche.backoffice.domain.executing.LoadedTote.find("tote=?1 and status=?2",physical,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE).firstResult();
            if(tote==null){BufferCabinet cabinet=BufferCabinet.findById(r.cabinetId);if(cabinet==null)throw bad("Choisissez une cellule");TemperatureZone usage=cabinet.zone;
                if(r.zone!=null){try{usage=TemperatureZone.valueOf(r.zone);}catch(IllegalArgumentException invalid){throw bad("Température d’utilisation inconnue");}}
                if(!physical.isUsageAllowed(usage)||(usage==TemperatureZone.HOUSEHOLD?TemperatureZone.AMBIENT:usage)!=cabinet.zone)throw bad("Ce type de bac est incompatible avec la température de l’armoire");tote=com.intermarche.backoffice.domain.executing.LoadedTote.useAnEmptyTote(physical);tote.storageType=usage;tote.persistAndFlush();}
        }
        if(tote==null || tote.status!=com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE)throw bad("Bac introuvable");
        if(com.intermarche.backoffice.domain.shipping.ShipmentLine.count("loadedTote=?1 and toteReturnedAt is null",tote)>0)throw bad("Ce bac est expédié : enregistrez son retour avant de le déplacer");
        Buffer target=Buffer.findById(r.bufferId);if(target==null)throw bad("Buffer introuvable");building(store,Hibernate.unproxy(target.building,Building.class).id);

        try{occupancy.deposit(tote,r.bufferId,r.cabinetId,r.cellRow,r.cellColumn,store);stockTrace.syncTotes(store,List.of(tote),"buffer-position:"+java.util.UUID.randomUUID());}catch(IllegalArgumentException e){throw bad(e.getMessage());}return Map.of("positioned",true);
    }
    public static class CabinetInput {public Long storeId,bufferId,id;public String code,zone;public int columns,rows;}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/buffer-cabinets") public Map<String,Object> saveCabinet(CabinetInput r){
        if(r==null)throw bad("Armoire requise");Store store=scope(r.storeId);
        Buffer buffer=em.find(Buffer.class,r.bufferId,LockModeType.PESSIMISTIC_WRITE);if(buffer==null)throw bad("Buffer introuvable");building(store,Hibernate.unproxy(buffer.building,Building.class).id);
        if(r.columns<1 || r.columns>30 || r.rows<1 || r.rows>30)throw bad("Dimensions requises entre 1 et 30 cellules");
        TemperatureZone zone;try{zone=TemperatureZone.valueOf(r.zone);}catch(Exception e){throw bad("Température invalide");}if(zone==TemperatureZone.HOUSEHOLD)throw bad("Utilisez une armoire ambiante pour les produits ménagers");
        String code=text(r.code,80,"Code de l’armoire");BufferCabinet c=r.id==null?new BufferCabinet():BufferCabinet.findById(r.id);
        if(c==null || r.id!=null && !Hibernate.unproxy(c.buffer,Buffer.class).id.equals(buffer.id))throw bad("Armoire hors du buffer");
        if(BufferCabinet.count("buffer=?1 and code=?2 and id<>?3",buffer,code,r.id==null?-1L:r.id)>0)throw bad("Code d’armoire déjà utilisé");
        if(r.id!=null && com.intermarche.backoffice.domain.executing.LoadedTote.count("cabinet=?1 and buffer=?2 and status=?3 and (cellRow>?4 or cellColumn>?5 or storageType not in ?6)",c,buffer,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE,r.rows,r.columns,zone==TemperatureZone.AMBIENT?List.of(zone,TemperatureZone.HOUSEHOLD):List.of(zone))>0)throw bad("Déplacez les bacs concernés avant de modifier cette armoire");
        c.buffer=buffer;c.code=code;c.zone=zone;c.columns=r.columns;c.rows=r.rows;c.persistAndFlush();return Map.of("id",c.id);
    }
    private Map<String,Object> person(Employee e){return Map.of("id",e.id,"badgeId",e.badgeId,"firstName",e.firstName,"lastName",e.lastName,"email",e.email,"role",e.role,"active",e.active);}
    @GET @Path("/employees") @RolesAllowed({"ADMIN","MANAGER","PICKER"})  public List<Map<String,Object>> employees(@QueryParam("storeId") Long storeId){return Employee.<Employee>list("store=?1 order by lastName,firstName",scope(storeId)).stream().filter(e->!identity.hasRole("PICKER") || identity.hasRole("MANAGER") || identity.hasRole("ADMIN") || e.badgeId.equals(identity.getPrincipal().getName())).map(this::person).toList();}
    public static class EmployeeInput{public Long storeId,id;public String badgeId,firstName,lastName,email,password,role;public boolean active=true;}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/employees") public Map<String,Object> saveEmployee(EmployeeInput r){
        if(r==null)throw bad("Employé requis");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);Employee e=r.id==null?new Employee():employee(s,r.id);
        String badge=text(r.badgeId,20,"Badge"),email=text(r.email,150,"Email");
        var other=Employee.findByBadgeId(badge);if(other!=null && !Objects.equals(other.id,e.id))throw bad("Badge déjà utilisé");
        Employee mail=Employee.find("email",email).firstResult();if(mail!=null && !Objects.equals(mail.id,e.id))throw bad("Email déjà utilisé");
        Employee.EmployeeRole role;try{role=Employee.EmployeeRole.valueOf(r.role);}catch(Exception ex){throw bad("Rôle invalide");}
        if(!identity.hasRole("ADMIN") && (role==Employee.EmployeeRole.ADMIN || role==Employee.EmployeeRole.MANAGER) && (r.id==null || e.role!=role))throw new ForbiddenException("Ce rôle est réservé à l’administration");
        if(!identity.hasRole("ADMIN") && e.role==Employee.EmployeeRole.ADMIN)throw new ForbiddenException("Compte administrateur");
        if(!identity.hasRole("ADMIN") && r.id!=null && e.role==Employee.EmployeeRole.MANAGER && !e.badgeId.equals(identity.getPrincipal().getName()))throw new ForbiddenException("Seule l’administration modifie la fiche d’un autre responsable");
        if(r.id==null){if(r.password==null || r.password.length()<8 || r.password.length()>100)throw bad("Mot de passe initial de 8 à 100 caractères requis");e.password=org.mindrot.jbcrypt.BCrypt.hashpw(r.password,org.mindrot.jbcrypt.BCrypt.gensalt());}
        e.store=s;e.badgeId=badge;e.firstName=text(r.firstName,80,"Prénom");e.lastName=text(r.lastName,80,"Nom");e.email=email;e.role=role;e.active=r.active;e.persistAndFlush();return person(e);
    }
    public static class PlanningInput{public Long storeId,id,employeeId,orderId,buildingId;public String kind,title,requiredRole;public boolean validatedAssignment;public LocalDateTime start,end;}
    private Map<String,Object> entry(PlanningEntry e){return Map.of("buildingId",e.building==null?"":Hibernate.unproxy(e.building,Building.class).id,"id","entry-"+e.id,"entryId",e.id,"employeeId",Hibernate.unproxy(e.employee,Employee.class).id,"kind",e.kind,"requiredRole",e.requiredRole==null?"":e.requiredRole,"title",e.title,"start",e.startsAt,"end",e.endsAt);}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/planning/assignment-options") public Map<String,Object> assignmentOptions(PlanningInput r){
        if(r==null)throw bad("Créneau requis");Store store=scope(r.storeId);
        if(r.id!=null){PlanningEntry entry=PlanningEntry.findById(r.id);if(entry==null)throw new NotFoundException();employee(store,Hibernate.unproxy(entry.employee,Employee.class).id);}
        if(r.orderId!=null){BaseHandlingOrder order=BaseHandlingOrder.findById(r.orderId);if(order==null || !Hibernate.unproxy(order.store,Store.class).id.equals(store.id))throw new NotFoundException();if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(order.status))throw bad("Cet ordre est fermé");}
        try{var candidates=assignments.candidates(store,r.start,r.end,r.id,r.orderId,r.kind,r.requiredRole);return Map.of("candidates",candidates,"message",candidates.isEmpty()?"Aucun employé ne couvre ce créneau sans conflit. Vérifiez les horaires, absences et compétences.":"Employés présents sur tout le créneau, sans absence ni tâche concurrente ; priorité à la charge la plus faible.");}catch(IllegalArgumentException e){throw bad(e.getMessage());}
    }
    private void validateAssignment(Store store,PlanningInput r,Long orderId){
        if(!r.validatedAssignment || r.employeeId==null)return;
        try{if(assignments.candidates(store,r.start,r.end,r.id,orderId,r.kind,r.requiredRole).stream().noneMatch(c->c.employeeId().equals(r.employeeId)))throw bad("Cette affectation n’est plus disponible. Actualisez les propositions");}catch(IllegalArgumentException e){throw bad(e.getMessage());}
    }
    @GET @RolesAllowed({"ADMIN","MANAGER"}) @Path("/planning") public Map<String,Object> planning(@QueryParam("storeId") Long storeId,@QueryParam("start") String startText,@QueryParam("end") String endText){
        LocalDateTime start,end;try{start=LocalDateTime.parse(startText);end=LocalDateTime.parse(endText);}catch(Exception ex){throw bad("Dates de planning invalides");}
        Store s=scope(storeId);if(start==null || end==null || !end.isAfter(start) || end.isAfter(start.plusDays(62)))throw bad("Période de 62 jours maximum");
        var missionStates=com.intermarche.backoffice.domain.fieldops.MissionOperationalState.<com.intermarche.backoffice.domain.fieldops.MissionOperationalState>list("store",s).stream().collect(java.util.stream.Collectors.toMap(x->com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(x.handlingOrder),com.intermarche.backoffice.engine.fieldops.MissionStateService::view));
        var operatorStates=com.intermarche.backoffice.domain.operator.OperatorProfile.<com.intermarche.backoffice.domain.operator.OperatorProfile>list("store",s).stream().map(p->Map.of("badgeId",p.principal,"state",p.state)).toList();
        var handovers=com.intermarche.backoffice.domain.fieldops.MissionHandover.<com.intermarche.backoffice.domain.fieldops.MissionHandover>find("store=?1 and state='OFFERED' and expiresAt>?2 order by id",s,DateTimeProvider.now()).range(0,99).list().stream().map(h->Map.of("id",h.id,"orderId",com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(h.handlingOrder),"orderCode",h.handlingOrder.code,"sourceName",h.source.getFullName(),"targetName",h.target.getFullName(),"reason",h.reason)).toList();
        // An operator may reserve work before the planning assignee is filled in.
        // Show the execution's employee without rewriting the original assignment.
        var executionEmployees=new HashMap<Long,Long>();
        for(var row:em.createQuery("select e.handlingOrder.id,e.employee.id from HandlingExecution e where e.handlingOrder.store=?1 and e.handlingOrder.requestedStart<?2 and e.handlingOrder.requestedEnd>?3 and e.status in ?4 order by e.id desc",Object[].class).setParameter(1,s).setParameter(2,end).setParameter(3,start).setParameter(4,List.of(com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.PENDING,com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS,com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE)).getResultList())executionEmployees.putIfAbsent((Long)row[0],(Long)row[1]);
        var entries=new ArrayList<Map<String,Object>>();for(var e:PlanningEntry.<PlanningEntry>list("employee.store=?1 and startsAt<?2 and endsAt>?3 and cancelled=false",s,end,start))entries.add(entry(e));
        for(var o:BaseHandlingOrder.<BaseHandlingOrder>list("store=?1 and requestedStart<?2 and requestedEnd>?3 and status<>?4",s,end,start,BaseHandlingOrder.HandlingOrderStatus.CANCELLED)){var v=new LinkedHashMap<String,Object>();v.put("id","order-"+o.id);v.put("orderId",o.id);v.put("employeeId",o.assignee==null?executionEmployees.get(o.id):Hibernate.unproxy(o.assignee,Employee.class).id);v.put("kind","HANDLING");v.put("operational",missionStates.getOrDefault(o.id,Map.of("state","ACTIVE")));var startBuilding=GlobalPlanningService.startBuilding(o);v.put("buildingId",startBuilding==null?null:Hibernate.unproxy(startBuilding,Building.class).id);v.put("title",o.code);v.put("start",o.requestedStart);v.put("end",o.requestedEnd);v.put("status",o.status);entries.add(v);}
        var conflicts=new ArrayList<String>();
        for(int i=0;i<entries.size();i++)for(int j=i+1;j<entries.size();j++){var a=entries.get(i);var b=entries.get(j);if(a.get("employeeId")==null || !a.get("employeeId").equals(b.get("employeeId")) || a.get("kind").equals("SHIFT") || b.get("kind").equals("SHIFT"))continue;if(((LocalDateTime)a.get("start")).isBefore((LocalDateTime)b.get("end")) && ((LocalDateTime)b.get("start")).isBefore((LocalDateTime)a.get("end")))conflicts.add(a.get("title")+" chevauche "+b.get("title"));}
        for(var a:entries)if(a.get("employeeId")!=null && Set.of("TASK","HANDLING").contains(a.get("kind")) && entries.stream().noneMatch(b->b.get("kind").equals("SHIFT") && Objects.equals(a.get("employeeId"),b.get("employeeId")) && !((LocalDateTime)b.get("start")).isAfter((LocalDateTime)a.get("start")) && !((LocalDateTime)b.get("end")).isBefore((LocalDateTime)a.get("end"))))conflicts.add(a.get("title")+" : en dehors des horaires renseignés");
        return Map.of("entries",entries,"conflicts",conflicts,"operatorStates",operatorStates,"handovers",handovers);
    }
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/planning") public Map<String,Object> savePlanning(PlanningInput r){
        if(r==null)throw bad("Créneau requis");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);
        if(r.start==null || r.end==null || !r.end.isAfter(r.start) || r.end.isAfter(r.start.plusDays(31)))throw bad("Période invalide");
        Employee e=employee(s,r.employeeId);
        if(!Set.of("SHIFT","ABSENCE","TASK").contains(r.kind))throw bad("Type de créneau invalide");
        var p=r.id==null?new PlanningEntry():PlanningEntry.<PlanningEntry>findById(r.id);if(p==null)throw bad("Créneau introuvable");if(r.id!=null)employee(s,Hibernate.unproxy(p.employee,Employee.class).id);
        validateAssignment(s,r,null);
        p.building=r.buildingId==null?null:building(s,r.buildingId);p.requiredRole=r.requiredRole;p.cancelled=false;p.employee=e;p.kind=r.kind;p.title=text(r.title,160,"Libellé");p.startsAt=r.start;p.endsAt=r.end;p.persistAndFlush();return entry(p);
    }
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/planning/{id}/cancel") public Map<String,Object> cancelEntry(@PathParam("id") Long id,@QueryParam("storeId") Long storeId){
        Store s=scope(storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);var e=PlanningEntry.<PlanningEntry>findById(id);if(e==null)throw new NotFoundException();employee(s,Hibernate.unproxy(e.employee,Employee.class).id);e.cancelled=true;return Map.of("cancelled",true);
    }
    public static class CopyWeek{public Long storeId,employeeId;public LocalDateTime weekStart;}
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/planning/copy-week") public Map<String,Object> copyWeek(CopyWeek r){
        if(r==null || r.weekStart==null)throw bad("Semaine source requise");Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);if(r.employeeId!=null)employee(s,r.employeeId);
        int copied=0;
        for(var p:PlanningEntry.<PlanningEntry>list("employee.store=?1 and kind=?2 and startsAt>=?3 and startsAt<?4 and cancelled=false",s,"SHIFT",r.weekStart,r.weekStart.plusDays(7))){
            if(r.employeeId!=null && !Hibernate.unproxy(p.employee,Employee.class).id.equals(r.employeeId))continue;
            var start=p.startsAt.plusDays(7);var end=p.endsAt.plusDays(7);
            if(PlanningEntry.count("employee=?1 and kind=?2 and startsAt=?3 and endsAt=?4 and cancelled=false",p.employee,"SHIFT",start,end)>0)continue;
            var next=new PlanningEntry();next.building=p.building;next.employee=p.employee;next.kind=p.kind;next.title=p.title;next.startsAt=start;next.endsAt=end;next.persist();copied++;
        }
        return Map.of("copied",copied);
    }
    @POST @RolesAllowed({"ADMIN","MANAGER"}) @Path("/handling/{id}/schedule") public Map<String,Object> scheduleOrder(@PathParam("id") Long id,PlanningInput r){
        Store s=scope(r.storeId);em.lock(s,LockModeType.PESSIMISTIC_WRITE);BaseHandlingOrder o=BaseHandlingOrder.findById(id);if(o==null || !Hibernate.unproxy(o.store,Store.class).id.equals(s.id))throw new NotFoundException();
        if(Set.of(BaseHandlingOrder.HandlingOrderStatus.DONE,BaseHandlingOrder.HandlingOrderStatus.CANCELLED,BaseHandlingOrder.HandlingOrderStatus.FAILED).contains(o.status))throw bad("Cet ordre est fermé");
        if(r.start==null || r.end==null || !r.end.isAfter(r.start))throw bad("Période invalide");
        if(r.validatedAssignment){r.kind="HANDLING";r.id=null;}validateAssignment(s,r,id);
        if(!Objects.equals(o.assignee==null?null:Hibernate.unproxy(o.assignee,Employee.class).id,r.employeeId)&&com.intermarche.backoffice.domain.handling.HandlingExecution.count("handlingOrder=?1 and status in ?2",o,List.of(com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.PENDING,com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS))>0)throw bad("Mission déjà démarrée : proposez une transmission avec acceptation du destinataire.");
        o.assignee=r.employeeId==null?null:employee(s,r.employeeId);o.requestedStart=r.start;o.requestedEnd=r.end;em.flush();return Map.of("scheduled",true);
    }
}
