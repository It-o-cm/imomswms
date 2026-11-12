package com.intermarche.backoffice.engine.replenishment;
import com.intermarche.backoffice.domain.replenishment.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.annotation.security.RolesAllowed;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Path("/replenishments/definitions") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","SERVICE_CALLER"}) @RunOnVirtualThread @Transactional
public class ReplenishmentDefinitionResource {
    @Inject ReplenishmentResource work;
    @Inject TemporalStockService stocks;
    @Inject EntityManager em;
    @Inject com.fasterxml.jackson.databind.ObjectMapper mapper;
    public static class DefinitionLine {public Long productId;public BigDecimal quantity;}
    public static class DefinitionRequest {
        public Long storeId,sourceBuildingId,destinationBuildingId;
        public String requestKey,truckReference;
        public ReplenishmentOrder.Operation operation;
        public LocalDateTime start,end;
        public List<DefinitionLine> lines;
    }
    public static class Allocation {
        public Long productId,sourceLocationId,destinationLocationId,bufferId,bufferNodeId;
        public String toteCode;
        public BigDecimal quantity;
    }
    public static class HandlingRequest {public Long storeId;public String requestKey;public List<Allocation> allocations;}
    private BadRequestException invalid(String message){return new BadRequestException(jakarta.ws.rs.core.Response.status(400).entity(Map.of("message",message)).build());}
    private String hash(Object value){
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));}
        catch(Exception e){throw invalid("Demande invalide");}
    }
    private void key(String key){if(key==null || !key.matches("[a-zA-Z0-9-]{8,64}"))throw invalid("Référence de demande requise");}
    private Building building(Store store,Long id){
        Building result=store.buildings.stream().map(b->Hibernate.unproxy(b,Building.class)).filter(b->b.id.equals(id)).findFirst().orElseThrow(()->invalid("Bâtiment hors du magasin"));
        return result;
    }
    private ShelfLocation location(Store store,Long id,Building expected,Product product){
        for(var proxy:expected.locations){var l=Hibernate.unproxy(proxy,ShelfLocation.class);
            if(l.id.equals(id) && !(l instanceof PendingSupplyLocation) && !(l instanceof PickingToteLocation) && !(l instanceof BufferToteStockLocation)){
                if(l.product!=null && !Hibernate.unproxy(l.product,Product.class).id.equals(product.id))throw invalid("Cet emplacement est affecté à un autre produit");
                return l;
            }
        }
        throw invalid("Choisissez un emplacement de la topologie du bâtiment");
    }
    private PendingSupplyLocation pending(Building b){
        for(var p:b.locations){var l=Hibernate.unproxy(p,ShelfLocation.class);if(l instanceof PendingSupplyLocation waiting)return waiting;}
        var l=new PendingSupplyLocation();l.area="EN_ATTENTE";l.aisle="REAPPRO";l.unit="CIBLE";l.position="1";l.elevation="1";l.persistAndFlush();b.locations.add(l);em.flush();return l;
    }
    private ReplenishmentDefinition definition(Long id,Long storeId){
        var store=work.scope(storeId);var d=ReplenishmentDefinition.<ReplenishmentDefinition>findById(id);
        if(d==null || !Hibernate.unproxy(d.store,Store.class).id.equals(store.id))throw new NotFoundException();
        em.lock(store,LockModeType.PESSIMISTIC_WRITE);return d;
    }
    private String unit(Product p){return p.productType==ProductType.WEIGHT?"kg":p.productType==ProductType.VOLUME?"L":p.unitName==null?"pcs":p.unitName;}
    private Map<String,Object> view(ReplenishmentDefinition d){
        Map<String,Object> v=new LinkedHashMap<>();v.put("id",d.id);v.put("viewId","definition-"+d.id);v.put("code",d.code);v.put("operation",d.operation);
        v.put("truckReference",d.truckReference);v.put("start",d.start);v.put("end",d.end);v.put("status",d.handlingOrder==null?"TO_PLAN":d.handlingOrder.status);
        v.put("sourceBuildingId",d.sourceBuilding==null?null:Hibernate.unproxy(d.sourceBuilding,Building.class).id);
        v.put("destinationBuildingId",Hibernate.unproxy(d.destinationBuilding,Building.class).id);
        v.put("sourceBuildingCode",d.sourceBuilding==null?null:d.sourceBuilding.buildingCode);v.put("destinationBuildingCode",d.destinationBuilding.buildingCode);
        v.put("handlingOrder",d.handlingOrder==null?null:work.view(Hibernate.unproxy(d.handlingOrder,ReplenishmentOrder.class)));
        v.put("lines",d.lines.stream().map(l->{Product p=Hibernate.unproxy(l.product,Product.class);var line=new LinkedHashMap<String,Object>();line.put("id",l.id);line.put("productId",p.id);line.put("ean",p.ean);line.put("name",p.name);line.put("quantity",l.quantity);line.put("unit",unit(p));line.put("productType",p.productType);line.put("temperatureZone",p.temperatureZone);return line;}).toList());return v;
    }
    /** Handling order states after which a replenishment no longer needs anyone. */
    static final Set<String> CLOSED=Set.of("DONE","CANCELLED","FAILED");
    /**
     * Lists the replenishments of a store. By default only those still to handle, soonest first, so that the past
     * never floods the screen; the closed ones are read page by page, latest first.
     *
     * @param storeId the store
     * @param closed true for the done, cancelled or failed replenishments
     * @param offset the number of closed replenishments already shown
     * @param limit the page size of the closed replenishments, 1 to 100
     * @return the replenishments
     */
    @GET public List<Map<String,Object>> list(@QueryParam("storeId") Long storeId,@QueryParam("closed") @DefaultValue("false") boolean closed,@QueryParam("offset") @DefaultValue("0") int offset,@QueryParam("limit") @DefaultValue("30") int limit){
        if(offset<0||limit<1||limit>100)throw invalid("Pagination invalide");
        Store store=work.scope(storeId);var result=new ArrayList<Map<String,Object>>();
        for(var d:ReplenishmentDefinition.<ReplenishmentDefinition>list("store=?1 order by start desc",store))result.add(view(d));
        Set<Long> linked=new HashSet<>(em.createQuery("select d.handlingOrder.id from ReplenishmentDefinition d where d.store=?1 and d.handlingOrder is not null",Long.class).setParameter(1,store).getResultList());
        for(var old:work.list(storeId))if(!linked.contains(old.get("id"))){var v=new LinkedHashMap<>(old);v.put("viewId","order-"+old.get("id"));v.put("legacy",true);v.put("handlingOrder",old);result.add(v);}
        var chosen=result.stream().filter(v->CLOSED.contains(String.valueOf(v.get("status")))==closed);
        if(!closed)return chosen.sorted(Comparator.comparing((Map<String,Object> v)->(LocalDateTime)v.get("start"),Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        return chosen.sorted(Comparator.comparing((Map<String,Object> v)->(LocalDateTime)v.get("end"),Comparator.nullsLast(Comparator.reverseOrder()))).skip(offset).limit(limit).toList();
    }
    @GET @Path("/{id}") public Map<String,Object> get(@PathParam("id") Long id,@QueryParam("storeId") Long storeId){return view(definition(id,storeId));}
    @POST public Map<String,Object> create(DefinitionRequest r){
        if(r==null)throw invalid("Réapprovisionnement requis");Store store=work.scope(r.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);key(r.requestKey);
        String fingerprint=hash(r),requestKey=store.id+":"+r.requestKey;
        ReplenishmentDefinition existing=ReplenishmentDefinition.find("requestKey",requestKey).firstResult();
        if(existing!=null){if(!fingerprint.equals(existing.requestHash))throw new ClientErrorException("Référence déjà utilisée avec un autre contenu",409);return view(existing);}
        if(r.operation==null || r.operation==ReplenishmentOrder.Operation.STOCK_PROTECTION || r.start==null || r.end==null || !r.end.isAfter(r.start) || r.end.isBefore(DateTimeProvider.now()))throw invalid("Flux et période valides requis");
        if(r.lines==null || r.lines.isEmpty() || r.lines.size()>100)throw invalid("Sélectionnez de 1 à 100 produits");
        boolean truck=r.operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING;
        if(truck && (r.truckReference==null || r.truckReference.isBlank() || r.truckReference.length()>80))throw invalid("Référence camion requise");
        var dest=building(store,r.destinationBuildingId);var source=truck?null:building(store,r.sourceBuildingId);
        if(!truck && (!(source instanceof WarehouseBuilding) || !(dest instanceof StoreBuilding)))throw invalid("Le réachalandage va de l’entrepôt au magasin");
        var d=new ReplenishmentDefinition();d.store=store;d.code=HandlingOrderSequence.getNextCode(store,"APP");d.requestKey=requestKey;d.requestHash=fingerprint;d.operation=r.operation;d.truckReference=truck?r.truckReference.trim():null;d.sourceBuilding=source;d.destinationBuilding=dest;d.start=r.start;d.end=r.end;d.persistAndFlush();
        var destPending=pending(dest);var sourcePending=source==null?null:pending(source);var ids=new HashSet<Long>();
        for(var line:r.lines){
            if(line==null || line.productId==null || line.quantity==null || line.quantity.signum()<=0 || !ids.add(line.productId))throw invalid("Produits uniques et quantités positives requis");
            Product p=Product.findById(line.productId);if(p==null)throw invalid("Produit inconnu");
            if(p.productType==ProductType.UNIT && line.quantity.stripTrailingZeros().scale()>0)throw invalid("Quantité entière requise pour un produit à la pièce");
            var l=new ReplenishmentDefinitionLine();l.product=p;l.quantity=line.quantity;
            try{l.forecast=stocks.planSupply(stocks.account(store.id,p.id).id,d.code+":"+p.id,sourcePending==null?null:sourcePending.id,destPending.id,line.quantity,r.end);}
            catch(IllegalArgumentException e){throw invalid(e.getMessage());}
            d.lines.add(l);
        }
        em.flush();return view(d);
    }
    @POST @Path("/{id}/handling-orders")
    public Map<String,Object> createHandling(@PathParam("id") Long id,HandlingRequest r){
        if(r==null)throw invalid("Répartition requise");key(r.requestKey);var d=definition(id,r.storeId);String fingerprint=hash(r);
        if(d.handlingOrder!=null){if(!fingerprint.equals(d.handlingRequestHash))throw new ClientErrorException("L’ordre de manutention est déjà défini",409);return view(d);}
        if(r.allocations==null || r.allocations.isEmpty() || r.allocations.size()>300)throw invalid("Définissez la répartition des produits");
        Store store=Hibernate.unproxy(d.store,Store.class);Building dest=Hibernate.unproxy(d.destinationBuilding,Building.class),source=d.sourceBuilding==null?null:Hibernate.unproxy(d.sourceBuilding,Building.class);
        var totals=new HashMap<Long,BigDecimal>();var plan=new ReplenishmentResource.Plan();plan.storeId=store.id;plan.requestKey=r.requestKey;plan.operation=d.operation;plan.truckReference=d.truckReference;plan.start=d.start;plan.end=d.end;plan.lines=new ArrayList<>();
        var newLoads=new HashMap<String,LoadedTote>();var loadVolumes=new HashMap<String,BigDecimal>();var loadBuffers=new HashMap<String,Long>();
        for(var a:r.allocations){
            if(a==null || a.productId==null || a.quantity==null || a.quantity.signum()<=0)throw invalid("Quantité positive requise pour chaque affectation");
            var original=d.lines.stream().filter(l->Hibernate.unproxy(l.product,Product.class).id.equals(a.productId)).findFirst().orElseThrow(()->invalid("Produit absent du réapprovisionnement"));
            Product product=Hibernate.unproxy(original.product,Product.class);totals.merge(product.id,a.quantity,BigDecimal::add);
            var l=new ReplenishmentResource.Line();l.productId=product.id;l.quantity=a.quantity;
            if(source!=null)l.sourceLocationId=location(store,a.sourceLocationId,source,product).id;
            if(a.bufferId==null){
                if(a.toteCode!=null && !a.toteCode.isBlank())throw invalid("Le bac nécessite un buffer cible");
                l.destinationLocationId=location(store,a.destinationLocationId,dest,product).id;
            }else{
                if(a.destinationLocationId!=null)throw invalid("Choisissez une baie ou un bac en buffer");
                Buffer buffer=Buffer.findById(a.bufferId);if(buffer==null || !Hibernate.unproxy(buffer.building,Building.class).id.equals(dest.id) || !buffer.active)throw invalid("Buffer cible indisponible dans le bâtiment");
                if(a.bufferNodeId!=null){
                    PlanNode node=PlanNode.findById(a.bufferNodeId);
                    if(node==null || !Hibernate.unproxy(node.building,Building.class).id.equals(dest.id))throw invalid("Point du buffer hors du plan du bâtiment");
                    if(buffer.planNodeCode!=null && !buffer.planNodeCode.equals(node.code))throw invalid("Le buffer possède déjà un point de dépôt dans sa topologie");
                    buffer.planNodeCode=node.code;
                }
                if(buffer.getCapacityForZone(product.temperatureZone==TemperatureZone.HOUSEHOLD?TemperatureZone.AMBIENT:product.temperatureZone)<=0)throw invalid("Buffer incompatible avec la température");
                Tote tote=Tote.findByCode(a.toteCode);if(tote==null || !Hibernate.unproxy(tote.store,Store.class).id.equals(store.id) || !tote.isUsageAllowed(product.temperatureZone))throw invalid("Bac absent ou incompatible avec le produit");
                em.lock(tote,LockModeType.PESSIMISTIC_WRITE);
                Long previousBuffer=loadBuffers.putIfAbsent(tote.code,buffer.id);
                if(previousBuffer!=null && !previousBuffer.equals(buffer.id))throw invalid("Un même bac ne peut pas être rangé dans deux buffers");
                LoadedTote load=newLoads.get(tote.code);
                if(load==null){if(LoadedTote.count("tote=?1 and status=?2",tote,LoadedTote.Status.IN_USE)>0)throw invalid("Ce bac est déjà utilisé");load=LoadedTote.useAnEmptyTote(tote);load.storageType=product.temperatureZone;load.persistAndFlush();newLoads.put(tote.code,load);}
                if(load.storageType!=product.temperatureZone)throw invalid("Un bac ne peut pas mélanger ces températures");
                BigDecimal volume=product.productType==ProductType.VOLUME?a.quantity:product.referenceVolume==null?BigDecimal.ZERO:product.productType==ProductType.WEIGHT?(product.referenceWeight==null || product.referenceWeight.signum()==0?BigDecimal.ZERO:a.quantity.divide(product.referenceWeight,12,java.math.RoundingMode.HALF_UP).multiply(product.referenceVolume)):a.quantity.multiply(product.referenceVolume);
                loadVolumes.merge(tote.code,volume,BigDecimal::add);if(loadVolumes.get(tote.code).compareTo(tote.volume)>0)throw invalid("Volume du bac dépassé");
                var place=new BufferToteStockLocation();place.product=product;place.loadedTote=load;place.buffer=buffer;place.area="BUFFER";place.aisle="DEPOT";place.unit=String.valueOf(buffer.id);place.elevation="1";place.position=String.valueOf(product.id);place.persistAndFlush();dest.locations.add(place);em.flush();l.destinationLocationId=place.id;
            }
            plan.lines.add(l);
        }
        for(var line:d.lines)if(line.quantity.compareTo(totals.getOrDefault(Hibernate.unproxy(line.product,Product.class).id,BigDecimal.ZERO))!=0)throw invalid("La somme des affectations doit correspondre à la quantité de chaque produit");
        var orderView=work.create(plan);d.handlingOrder=ReplenishmentOrder.findById(orderView.get("id"));d.handlingRequestHash=fingerprint;
        for(var line:d.lines)stocks.reviseSupply(Hibernate.unproxy(line.forecast,ExpectedSupply.class).id,line.quantity,d.end,true);
        em.flush();return view(d);
    }
}
