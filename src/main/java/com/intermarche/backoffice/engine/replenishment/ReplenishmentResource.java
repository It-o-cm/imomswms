package com.intermarche.backoffice.engine.replenishment;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.domain.wms.handling.StockProtectionOrder;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.hibernate.Hibernate;

@Path("/replenishments")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER","SERVICE_CALLER"}) @RunOnVirtualThread
public class ReplenishmentResource implements GraphQLTrait {
    @jakarta.inject.Inject com.intermarche.backoffice.engine.executing.SheetTotePreparation sheetTotes;
    @Inject SecurityIdentity identity;
    @Inject com.intermarche.backoffice.engine.storeops.EmployeeTransitService employeeTransit;
    @Inject TemporalStockService stocks;
    @Inject com.intermarche.backoffice.engine.catalog.ProductInformationService productInformation;
    @Inject com.intermarche.backoffice.engine.stock.StockProtectionService protection;
    @Inject com.intermarche.backoffice.engine.executing.StorePickingRoutePlanner planner;
    @Inject UnloadingCartService carts;
    @Inject EntityManager em;
    @Inject com.intermarche.backoffice.engine.executing.BufferOccupancyService bufferOccupancy;
    @Inject com.fasterxml.jackson.databind.ObjectMapper mapper;
    public SecurityIdentity getIdentity(){return identity;}
    private BadRequestException invalid(String message) {
        return new BadRequestException(jakarta.ws.rs.core.Response.status(400).type(MediaType.APPLICATION_JSON)
            .entity(Map.of("code","REPLENISHMENT_VALIDATION","message",message)).build());
    }
    private BigDecimal physical(ExpectedSupply supply) {
        if(supply.source==null) return null;
        return com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>list("account = ?1 and location = ?2 and effectiveAt <= ?3",supply.account,supply.source,DateTimeProvider.now()).stream().map(m -> m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private String unit(Product product) {
        return product.productType==com.intermarche.backoffice.domain.order.ProductType.WEIGHT?"kg":product.productType==com.intermarche.backoffice.domain.order.ProductType.VOLUME?"L":product.unitName==null?"pcs":product.unitName;
    }
    Store scope(Long id) {
        Store own=getStoreContext();
        if(!identity.hasRole("ADMIN")) {
            if(own==null || (id!=null && !own.id.equals(id))) throw new ForbiddenException("Magasin hors de votre périmètre");
            return Hibernate.unproxy(own,Store.class);
        }
        Store store=id==null?null:Store.findById(id);
        if(store==null) throw invalid("Magasin requis");return store;
    }
    @GET @Path("/stores") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public List<Map<String,Object>> stores() {
        List<Store> stores=identity.hasRole("ADMIN")?Store.listAll():List.of(scope(null));
        return stores.stream().map(s -> Map.<String,Object>of("id",s.id,"code",s.code,"name",s.name)).toList();
    }
    /**
     * Returns the light reference data of the replenishment editors for a store: its buildings with their plan
     * nodes, the occupancy of its buffers and its free totes. Products and locations are searched separately, on
     * demand, through {@link #products} and {@link #locations}.
     *
     * @param storeId store identifier, optional for a manager
     * @return the buildings, buffers and free totes
     */
    @GET @Path("/catalog") @RolesAllowed({"ADMIN","MANAGER","PICKER","SERVICE_CALLER"}) @Transactional
    public Map<String,Object> catalog(@QueryParam("storeId") Long storeId) {
        Store store=scope(storeId);
        List<Map<String,Object>> buildings=new ArrayList<>();
        for(Building proxy:store.buildings) {
            Building b=Hibernate.unproxy(proxy,Building.class);
            buildings.add(Map.of("id",b.id,"code",b.buildingCode,"type",b instanceof WarehouseBuilding?"WAREHOUSE":"STORE","nodes",PlanNode.listByBuilding(b).stream().map(n->Map.of("id",n.id,"code",n.code,"label",n.label==null?n.code:n.label)).toList()));
        }
        var totes=em.createQuery("select t from Tote t where t.store=:store and not exists (select l.id from LoadedTote l where l.tote=t and l.status=:inUse) order by t.code",Tote.class)
            .setParameter("store",store).setParameter("inUse",com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE).getResultList();
        return Map.of("buildings",buildings,"buffers",bufferOccupancy.occupancy(store),"totes",totes.stream().map(t->Map.of("code",t.code,"type",t.toteType,"volume",t.volume,"allowedZones",Arrays.stream(TemperatureZone.values()).filter(t::isUsageAllowed).map(Enum::name).toList())).toList());
    }
    /** Largest number of products returned by one search. */
    static final int PRODUCT_PAGE=100;
    /**
     * Searches the products to replenish, by name or EAN (case-insensitive), or returns given products. At most
     * {@value #PRODUCT_PAGE} products are returned; {@code more} tells that the search has other results.
     *
     * @param storeId store identifier, optional for a manager
     * @param query text searched in the name or the EAN; blank for the first products by name
     * @param ids comma-separated product identifiers, replacing the search when given
     * @param eans comma-separated EANs, replacing the search when given without identifiers
     * @param limit number of products wanted, from 1 to {@value #PRODUCT_PAGE} (50 by default)
     * @return the products ({@code items}) and whether the search has other results ({@code more})
     */
    @GET @Path("/catalog/products") @RolesAllowed({"ADMIN","MANAGER","PICKER","SERVICE_CALLER"}) @Transactional
    public Map<String,Object> products(@QueryParam("storeId") Long storeId,@QueryParam("q") String query,@QueryParam("ids") String ids,@QueryParam("eans") String eans,@QueryParam("limit") Integer limit) {
        Store catalogueStore=scope(storeId);
        int size=limit==null?50:Math.max(1,Math.min(PRODUCT_PAGE,limit));
        List<Product> found;
        if(ids!=null && !ids.isBlank()) {
            List<Long> wanted;
            try{wanted=Arrays.stream(ids.split(",")).map(String::trim).filter(v->!v.isEmpty()).map(Long::valueOf).distinct().toList();}catch(NumberFormatException e){throw invalid("Identifiants de produits invalides");}
            if(wanted.size()>PRODUCT_PAGE)throw invalid("Au plus "+PRODUCT_PAGE+" produits à la fois");
            found=wanted.isEmpty()?List.of():em.createQuery("select p from Product p where p.id in :ids order by p.name",Product.class).setParameter("ids",wanted).getResultList();
            size=Math.max(size,found.size());
        } else if(eans!=null && !eans.isBlank()) {
            var wanted=Arrays.stream(eans.split(",")).map(String::trim).filter(v->!v.isEmpty()).distinct().toList();
            if(wanted.size()>PRODUCT_PAGE)throw invalid("Au plus "+PRODUCT_PAGE+" produits à la fois");
            found=wanted.isEmpty()?List.of():em.createQuery("select p from Product p where p.ean in :eans order by p.name",Product.class).setParameter("eans",wanted).getResultList();
            size=Math.max(size,found.size());
        } else {
            String text=query==null?"":query.trim().toLowerCase(Locale.ROOT);
            found=em.createQuery("select p from Product p where lower(p.name) like :text escape '!' or p.ean like :text escape '!' or lower(p.characteristics) like :text escape '!' or lower(p.brand) like :text escape '!' or exists (select i.id from ProductIdentifier i where i.product=p and i.store=:catalogueStore and i.active=true and lower(i.value) like :text escape '!') order by p.name,p.id",Product.class)
                .setParameter("catalogueStore",catalogueStore).setParameter("text","%"+text.replace("!","!!").replace("%","!%").replace("_","!_")+"%").setMaxResults(size+1).getResultList();
        }
        var shown=found.stream().limit(size).toList();
        var packagings=shown.isEmpty()?List.<com.intermarche.backoffice.domain.catalog.ProductPackaging>of():com.intermarche.backoffice.domain.catalog.ProductPackaging.<com.intermarche.backoffice.domain.catalog.ProductPackaging>list("store=?1 and product in ?2 and active=true order by code",catalogueStore,shown);
        var packagingLabels=packagings.stream().collect(java.util.stream.Collectors.groupingBy(x->x.product.id,LinkedHashMap::new,java.util.stream.Collectors.mapping(x->x.label,java.util.stream.Collectors.joining(" · "))));
        return Map.of("items",found.stream().limit(size).map(p -> {Map<String,Object> v=new LinkedHashMap<>();v.put("id",p.id);v.put("ean",p.ean);v.put("name",p.name);v.put("unit",unit(p));v.put("productType",p.productType);v.put("temperatureZone",p.temperatureZone);v.put("brand",p.brand);v.put("characteristics",p.characteristics);v.put("description",p.description);v.put("active",p.active);v.put("packaging",packagingLabels.get(p.id));return v;}).toList(),"more",found.size()>size);
    }
    /**
     * Returns the addresses of a building of the store usable for given products: the addresses of these products and
     * the addresses without assigned product.
     *
     * @param storeId store identifier, optional for a manager
     * @param buildingId building of the store
     * @param productIds comma-separated product identifiers
     * @return the addresses with their identifier, code, building and product
     */
    @GET @Path("/catalog/locations") @RolesAllowed({"ADMIN","MANAGER","SERVICE_CALLER"}) @Transactional
    public List<Map<String,Object>> locations(@QueryParam("storeId") Long storeId,@QueryParam("buildingId") Long buildingId,@QueryParam("productIds") String productIds) {
        Store store=scope(storeId);
        Building building=store.buildings.stream().map(b->Hibernate.unproxy(b,Building.class)).filter(b->b.id.equals(buildingId)).findFirst().orElseThrow(()->invalid("Bâtiment hors du magasin"));
        List<Long> wanted;
        try{wanted=productIds==null?List.of():Arrays.stream(productIds.split(",")).map(String::trim).filter(v->!v.isEmpty()).map(Long::valueOf).distinct().toList();}catch(NumberFormatException e){throw invalid("Identifiants de produits invalides");}
        if(wanted.size()>PRODUCT_PAGE)throw invalid("Au plus "+PRODUCT_PAGE+" produits à la fois");
        var rows=em.createQuery("select l from Building b join b.locations l left join fetch l.product where b=:building and (l.product is null or l.product.id in :ids) order by l.locationCode",ShelfLocation.class)
            .setParameter("building",building).setParameter("ids",wanted.isEmpty()?List.of(-1L):wanted).getResultList();
        List<Map<String,Object>> result=new ArrayList<>();
        for(ShelfLocation l:rows) {
            if(l instanceof PickingToteLocation || l instanceof ParcelStockLocation || l instanceof ReturnTransitLocation || l instanceof PendingSupplyLocation || l instanceof BufferToteStockLocation)continue;
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",l.id);item.put("code",l.locationCode);item.put("buildingId",building.id);
            item.put("productId",l.product==null?null:Hibernate.unproxy(l.product,Product.class).id);result.add(item);
        }
        return result;
    }
    private boolean pickerVisible(ReplenishmentOrder order) {
        if(!identity.hasRole("PICKER") || identity.hasRole("MANAGER") || identity.hasRole("ADMIN"))return true;
        String badge=identity.getPrincipal().getName();
        var es=com.intermarche.backoffice.domain.handling.HandlingExecution.<com.intermarche.backoffice.domain.handling.HandlingExecution>list("handlingOrder",order);
        return order.assignee!=null && badge.equals(order.assignee.badgeId) || es.stream().anyMatch(e->badge.equals(e.employee.badgeId)) || order.assignee==null && es.isEmpty();
    }
    private void pickerMayOperate(ReplenishmentOrder order) {
        if(!identity.hasRole("PICKER") || identity.hasRole("MANAGER") || identity.hasRole("ADMIN"))return;
        String badge=identity.getPrincipal().getName();
        if(order.assignee!=null && !badge.equals(order.assignee.badgeId))throw new ForbiddenException("Mission affectée à un autre opérateur");
        for(var e:com.intermarche.backoffice.domain.handling.HandlingExecution.<com.intermarche.backoffice.domain.handling.HandlingExecution>list("handlingOrder",order))
            if((e.status==com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.PENDING || e.status==com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS) && !badge.equals(e.employee.badgeId))throw new ForbiddenException("Mission prise en charge par un autre opérateur");
    }
    @GET @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public List<Map<String,Object>> list(@QueryParam("storeId") Long storeId) {
        Store store=scope(storeId);
        return ReplenishmentOrder.<ReplenishmentOrder>list("store = ?1 order by requestedStart desc",store).stream().filter(o -> !(o instanceof StockProtectionOrder)).filter(this::pickerVisible).map(this::view).toList();
    }
    Map<String,Object> view(ReplenishmentOrder o) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",o.id);result.put("code",o.code);result.put("status",o.status);result.put("operation",o.operation);
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("store",Map.of("id",Hibernate.unproxy(o.store,Store.class).id));
        metadata.put("generationReason",o.generationReason);
        metadata.put("assignee",o.assignee==null?null:Map.of("badgeId",o.assignee.badgeId,"firstName",o.assignee.firstName,"lastName",o.assignee.lastName));
        metadata.put("predecessor",o.predecessor==null?null:Map.of("code",o.predecessor.code,"status",o.predecessor.status));
        metadata.put("sourceBuilding",o.sourceBuilding==null?null:Map.of("buildingCode",o.sourceBuilding.buildingCode));
        metadata.put("destinationBuilding",Map.of("buildingCode",o.destinationBuilding.buildingCode));
        if(o instanceof StockProtectionOrder secure)metadata.put("protectionBuffer",Map.of("id",secure.protectionBuffer.id,"bufferCode",secure.protectionBuffer.bufferCode));
        result.put("metadata",metadata);
        result.put("truckReference",o.truckReference);result.put("start",o.requestedStart);result.put("end",o.requestedEnd);
        result.put("sourceBuildingId",o.sourceBuilding==null?null:Hibernate.unproxy(o.sourceBuilding,Building.class).id);
        result.put("destinationBuildingId",Hibernate.unproxy(o.destinationBuilding,Building.class).id);
        result.put("lines",o.supplies.stream().map(s -> {
            Map<String,Object> line=new LinkedHashMap<>();line.put("id",s.id);line.put("name",s.account.product.name);line.put("quantity",s.quantity);line.put("received",s.received);line.put("picked",s.pickedQuantity());line.put("inTransit",s.pickedQuantity().subtract(s.received).max(BigDecimal.ZERO));
            Product product=Hibernate.unproxy(s.account.product,Product.class);line.put("productId",product.id);line.put("ean",product.ean);line.put("unit",unit(product));line.put("productType",product.productType);line.put("temperatureZone",product.temperatureZone);line.put("sourceAvailable",physical(s));
            if(o instanceof StockProtectionOrder secure){line.put("bufferId",Hibernate.unproxy(secure.protectionBuffer,Buffer.class).id);var placements=com.intermarche.backoffice.domain.stock.StockProtectionPlacement.<com.intermarche.backoffice.domain.stock.StockProtectionPlacement>list("supply",s);line.put("protectedTotes",placements.stream().map(p -> Map.of("code",Hibernate.unproxy(p.destination.protectedTote.tote,Tote.class).code,"quantity",p.quantity)).toList());}
            if(Hibernate.unproxy(s.destination,BaseLocation.class) instanceof BufferToteStockLocation place){line.put("bufferId",Hibernate.unproxy(place.buffer,Buffer.class).id);line.put("destinationToteCode",Hibernate.unproxy(Hibernate.unproxy(place.loadedTote,com.intermarche.backoffice.domain.executing.LoadedTote.class).tote,Tote.class).code);}
            line.put("source",s.source==null?o.truckReference:s.source.locationCode);line.put("destination",s.destination.locationCode);return line;
        }).toList());return result;
    }
    public static class Line { public Long productId,sourceLocationId,destinationLocationId;public BigDecimal quantity; }
    public static class Plan { public Long storeId;public String requestKey,truckReference;public ReplenishmentOrder.Operation operation;public LocalDateTime start,end;public List<Line> lines; }
    private Building locationBuilding(Store store,Long locationId) {
        if(locationId==null) throw invalid("Emplacement requis");
        List<Building> buildings=Building.list("select b from Store s join s.buildings b join b.locations l where s.id = ?1 and l.id = ?2",store.id,locationId);
        if(buildings.size()!=1) throw invalid("Emplacement hors du magasin");return Hibernate.unproxy(buildings.get(0),Building.class);
    }
    @POST @RolesAllowed({"ADMIN","MANAGER","SERVICE_CALLER"}) @Transactional
    public Map<String,Object> create(Plan plan) {
        if(plan==null) throw invalid("Réapprovisionnement requis");
        Store store=scope(plan.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        if(plan.requestKey==null || !plan.requestKey.matches("[a-zA-Z0-9-]{8,64}")) throw invalid("Référence de demande invalide");
        ReplenishmentOrder existing=ReplenishmentOrder.find("replenishmentRequestKey",store.id+":"+plan.requestKey).firstResult();
        String fingerprint;
        try {fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(plan)));}
        catch(Exception e) {throw invalid("Demande invalide");}
        if(existing!=null) {
            if(!fingerprint.equals(existing.replenishmentRequestHash)) throw new ClientErrorException("Cette demande a déjà été enregistrée avec un contenu différent",409);
            return view(existing);
        }
        if(plan.operation==ReplenishmentOrder.Operation.STOCK_PROTECTION)throw invalid("La mise en sécurité doit être déclenchée par sa règle de manutention");
        if(plan.operation==null || plan.start==null || plan.end==null || !plan.end.isAfter(plan.start) || plan.end.isBefore(DateTimeProvider.now())) throw invalid("Période de réapprovisionnement invalide");
        if(plan.lines==null || plan.lines.isEmpty() || plan.lines.size()>100) throw invalid("Définissez de 1 à 100 lignes");
        boolean truck=plan.operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING;
        if(truck && (plan.truckReference==null || plan.truckReference.isBlank() || plan.truckReference.length()>80)) throw invalid("Référence du camion requise (80 caractères maximum)");
        Building source=null,destination=null;Set<Long> products=new HashSet<>();Set<Boolean> foodClasses=new HashSet<>();
        for(Line line:plan.lines) {
            if(line==null || line.productId==null || line.quantity==null || line.quantity.signum()<=0 || line.quantity.scale()>6 || line.quantity.precision()-line.quantity.scale()>13) throw invalid("Produit et quantité positive requis pour chaque ligne");
            Product product=Product.findById(line.productId);if(product==null) throw invalid("Produit inconnu");
            foodClasses.add(TemperatureZone.isFood(product.temperatureZone));
            if(product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && line.quantity.stripTrailingZeros().scale()>0) throw invalid("La quantité doit être entière pour un produit à la pièce");
            Building dest=locationBuilding(store,line.destinationLocationId);
            ShelfLocation target=Hibernate.unproxy(ShelfLocation.findById(line.destinationLocationId),ShelfLocation.class);
            if(target instanceof ParcelStockLocation || target instanceof ReturnTransitLocation || target instanceof PendingSupplyLocation || target instanceof PickingToteLocation || (target.product!=null && !Hibernate.unproxy(target.product,Product.class).id.equals(product.id)))throw invalid("Emplacement de rangement incompatible avec le produit");

            if(destination!=null && !destination.id.equals(dest.id)) throw invalid("Un seul bâtiment de destination par réapprovisionnement");destination=dest;
            if(truck) {if(line.sourceLocationId!=null) throw invalid("Un camion n’a pas d’emplacement de stock source");}
            else {
                Building src=locationBuilding(store,line.sourceLocationId);
                BaseLocation origin=Hibernate.unproxy(BaseLocation.findById(line.sourceLocationId),BaseLocation.class);
                if(origin instanceof ParcelStockLocation || origin instanceof ReturnTransitLocation || origin instanceof PendingSupplyLocation || origin instanceof PickingToteLocation held&&held.traceOnly || origin.product!=null&&!Objects.equals(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(origin.product),product.id))throw invalid("Source incompatible ou déjà affectée à une commande");
                if(plan.operation!=ReplenishmentOrder.Operation.STOCK_TRANSFER && (!(src instanceof WarehouseBuilding) || !(dest instanceof StoreBuilding) || src.id.equals(dest.id))) throw invalid("Le réachalandage doit aller d’un entrepôt vers le magasin");
                if(source!=null && !source.id.equals(src.id)) throw invalid("Un seul entrepôt source par réapprovisionnement");source=src;
            }
        }
        if(plan.operation==ReplenishmentOrder.Operation.SHELF_REPLENISHMENT && foodClasses.size()>1) throw invalid("Séparez les produits alimentaires et non alimentaires dans des réachalandages distincts");
        ReplenishmentOrder order=new ReplenishmentOrder();order.store=store;order.code=HandlingOrderSequence.getNextCode(store,"REA");
        order.replenishmentRequestHash=fingerprint;order.replenishmentRequestKey=store.id+":"+plan.requestKey;order.operation=plan.operation;order.truckReference=truck?plan.truckReference.trim():null;
        order.sourceBuilding=source;order.destinationBuilding=destination;order.requestedStart=plan.start;order.requestedEnd=plan.end;order.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;order.persistAndFlush();
        int allocationIndex=0;
        for(Line line:plan.lines) {
            var account=stocks.account(store.id,line.productId);
            try {order.supplies.add(stocks.planSupply(account.id,order.code+":"+line.productId+":"+(allocationIndex++),truck?null:line.sourceLocationId,line.destinationLocationId,line.quantity,plan.end));}
            catch(IllegalArgumentException e) {throw invalid(e.getMessage());}
        }
        em.flush();return view(order);
    }
    private ReplenishmentOrder scopedOrder(Long id, Long storeId) {
        Store store=scope(storeId);
        ReplenishmentOrder order=ReplenishmentOrder.findById(id);
        if(order==null || !store.id.equals(Hibernate.unproxy(order.store,Store.class).id)) throw new NotFoundException();
        if(!pickerVisible(order))throw new ForbiddenException("Mission hors de votre périmètre");
        return order;
    }
    @GET @Path("/{id}") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> detail(@PathParam("id") Long id,@QueryParam("storeId") Long storeId){return view(scopedOrder(id,storeId));}
    @POST @Path("/{id}/start") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> start(@PathParam("id") Long id, @QueryParam("storeId") Long storeId) {
        ReplenishmentOrder order=scopedOrder(id,storeId);
        em.lock(Hibernate.unproxy(order.store,Store.class),LockModeType.PESSIMISTIC_WRITE);pickerMayOperate(order);com.intermarche.backoffice.domain.fieldops.MissionOperationalState.requireActive(id);
        if(order.status!=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED && order.status!=BaseHandlingOrder.HandlingOrderStatus.PROCESSING)
            throw invalid("Ordre fermé");
        if(com.intermarche.backoffice.domain.handling.HandlingExecution.count("handlingOrder",order)==0) {
            Employee employee=Employee.findByBadgeId(identity.getPrincipal().getName());
            if(employee==null) throw invalid("Un préparateur identifié est requis");
            try{employeeTransit.require(employee,order,com.intermarche.backoffice.engine.storeops.GlobalPlanningService.startBuilding(order));}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
            var first=(com.intermarche.backoffice.domain.executing.ReplenishmentExecution)order.createExecution(employee);first.phase=order.sourceBuilding!=null && !(order instanceof StockProtectionOrder)?"SOURCE":"DESTINATION";first.persist();
            if("SOURCE".equals(first.phase)){var second=(com.intermarche.backoffice.domain.executing.ReplenishmentExecution)order.createExecution(employee);second.phase="DESTINATION";second.status=com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.PENDING;second.persist();}
        }
        if(order.sourceBuilding!=null && !(order instanceof StockProtectionOrder)) {
            var executions=com.intermarche.backoffice.domain.executing.ReplenishmentExecution.<com.intermarche.backoffice.domain.executing.ReplenishmentExecution>list("handlingOrder",order);
            for(var execution:executions)if(execution.phase==null)execution.phase="SOURCE";
            if(executions.stream().noneMatch(e -> "DESTINATION".equals(e.phase))){
                Employee employee=Employee.findByBadgeId(identity.getPrincipal().getName());
                if(employee==null)throw invalid("Un préparateur identifié est requis");
                var destination=(com.intermarche.backoffice.domain.executing.ReplenishmentExecution)order.createExecution(employee);destination.phase="DESTINATION";destination.status=com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.PENDING;destination.persist();
            }
        }
        order.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        return view(order);
    }
    /**
     * Returns the execution phases of an order with their optimised routes; for a loaded unloading cart, only the
     * put-away of what is still on that cart.
     *
     * @param id the order
     * @param storeId the store
     * @param cartLoadId the loaded cart to put away, or null for the whole order
     * @return the order and its phases
     */
    @GET @Path("/{id}/route") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> route(@PathParam("id") Long id,@QueryParam("storeId") Long storeId,@QueryParam("cartLoadId") Long cartLoadId) {
        ReplenishmentOrder order=scopedOrder(id,storeId);
        if(cartLoadId!=null){
            Map<Long,BigDecimal> limits;String code;
            try{limits=carts.routeLimits(order,cartLoadId);code=carts.cartCode(order,cartLoadId);}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
            var phase=new LinkedHashMap<>(routePhase(order,order.destinationBuilding,false,limits));phase.put("label","Ranger le chariot "+code);phase.put("cartLoadId",cartLoadId);
            return Map.of("order",view(order),"phases",List.of(phase));
        }
        List<Map<String,Object>> phases=new ArrayList<>();
        if(order instanceof StockProtectionOrder secure) return Map.of("order",view(order),"phases",List.of(routePhase(order,order.sourceBuilding,true,null)),"protectionTotes",protection.totes(secure));
        if(order.sourceBuilding!=null) phases.add(routePhase(order,order.sourceBuilding,true,null));
        phases.add(routePhase(order,order.destinationBuilding,false,null));
        return Map.of("order",view(order),"phases",phases);
    }
    /**
     * Returns the execution phases of the whole order with their optimised routes.
     *
     * @param id the order
     * @param storeId the store
     * @return the order and its phases
     */
    public Map<String,Object> route(Long id,Long storeId){return route(id,storeId,null);}
    /**
     * Plans one phase of an order: the steps of its open lines in a building, ordered by the store route planner.
     *
     * @param order the order
     * @param proxy the building of the phase
     * @param source whether the phase picks at the source rather than puts away at the destination
     * @param limits the maximum quantity per delivery line, restricting the phase to those lines, or null for all lines
     * @return the phase key, label and building plan
     */
    private Map<String,Object> routePhase(ReplenishmentOrder order,Building proxy,boolean source,Map<Long,BigDecimal> limits) {
        Building building=Hibernate.unproxy(proxy,Building.class);
        List<com.intermarche.backoffice.engine.executing.PickingPlanService.RouteStepDTO> steps=new ArrayList<>();
        for(ExpectedSupply supply:order.supplies) {
            if(supply.status!=ExpectedSupply.SupplyStatus.EXPECTED || limits!=null && !limits.containsKey(supply.id)) continue;
            BaseLocation location=Hibernate.unproxy(source?supply.source:supply.destination,BaseLocation.class);
            if(!(location instanceof ShelfLocation shelf)) throw invalid("Emplacement sans adresse de rayon");
            Product product=Hibernate.unproxy(supply.account.product,Product.class);
            var step=new com.intermarche.backoffice.engine.executing.PickingPlanService.RouteStepDTO();
            step.pickedItemId=supply.id;step.sourceOrderId=order.id;step.sourceOrderCode=order.code;
            BigDecimal remaining=order instanceof StockProtectionOrder || order.sourceBuilding==null?supply.quantity.subtract(supply.received):source?supply.quantity.subtract(supply.pickedQuantity()):supply.pickedQuantity().subtract(supply.received);
            if(limits!=null)remaining=remaining.min(limits.get(supply.id));
            if(remaining.signum()<=0)continue;
            step.productId=product.id;step.productName=product.name;step.quantity=remaining;step.unitLabel=unit(product);step.productType=product.productType.name();step.sourceAvailable=physical(supply);
            step.temperatureZone=product.temperatureZone;step.aisle=shelf.aisle;step.unit=shelf.unit;step.area=shelf.area;step.position=shelf.position;
            if(shelf instanceof BufferToteStockLocation bufferPlace){
                Buffer buffer=Hibernate.unproxy(bufferPlace.buffer,Buffer.class);
                step.unit=buffer.bufferCode;step.area="BUFFER";step.aisle="BUFFER";
                step.planNodeCode=buffer.planNodeCode;
            }
            steps.add(step);
        }
        var result=planner.plan(building,steps,null);
        if(result==null) throw invalid("Définissez le plan du bâtiment "+building.buildingCode+" pour optimiser le parcours");
        var plan=new com.intermarche.backoffice.engine.executing.PickingPlanService.PickingPlanResponse();
        plan.buildingId=building.id;plan.buildingCode=building.buildingCode;plan.buildingType=building.getBuildingType();plan.route=result.route();plan.mapRoute=result.geometry();
        plan.summary=new com.intermarche.backoffice.engine.executing.PickingPlanService.PlanSummaryDTO();
        plan.totePreparation=sheetTotes.estimate(order.store,steps);
        plan.summary.totalItems=steps.size();plan.summary.estimatedDistanceMeters=result.geometry().distanceMeters==null?0:(int)Math.round(result.geometry().distanceMeters);
        return Map.of("key",order instanceof StockProtectionOrder?"DESTINATION":source?"SOURCE":"DESTINATION","label",order instanceof StockProtectionOrder?"Retirer des rayons et sécuriser en bac":source?(order.operation==ReplenishmentOrder.Operation.STOCK_TRANSFER?"Prélever à la source":"Prélever dans l’entrepôt"):"Ranger à destination","plan",Map.of("buildingPlans",Map.of(building.id,plan)));
    }
    /** The picker may choose only an address actually assigned to this product in this store. */
    private BaseLocation confirmationLocation(ReplenishmentOrder order,ExpectedSupply supply,boolean source,Long locationId) {
        BaseLocation planned=Hibernate.unproxy(source?supply.source:supply.destination,BaseLocation.class);
        if(locationId==null || planned!=null && Objects.equals(planned.id,locationId))return planned;
        if(order instanceof StockProtectionOrder || planned instanceof BufferToteStockLocation || planned instanceof PickingToteLocation)
            throw invalid("Cette opération impose son emplacement de rangement");
        BaseLocation target=BaseLocation.findById(locationId);
        if(target==null)throw invalid("Emplacement inconnu");
        target=Hibernate.unproxy(target,BaseLocation.class);
        if(!(target instanceof ShelfLocation) || target instanceof BufferToteStockLocation || target instanceof PickingToteLocation || target instanceof ParcelStockLocation || target instanceof ReturnTransitLocation || target instanceof PendingSupplyLocation || target.product==null || !Objects.equals(Hibernate.unproxy(target.product,Product.class).id,Hibernate.unproxy(supply.account.product,Product.class).id))
            throw invalid("Cet emplacement n’est pas affecté à ce produit");
        var buildings=BaseLocation.findBuildingsByLocations(List.of(target));
        if(buildings.size()!=1 || order.store.buildings.stream().noneMatch(b->Objects.equals(b.id,buildings.get(0).id)))throw invalid("Emplacement hors du magasin");
        return target;
    }
    @GET @Path("/{id}/lines/{lineId}/locations") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public List<Map<String,Object>> confirmationLocations(@PathParam("id") Long id,@PathParam("lineId") Long lineId,@QueryParam("storeId") Long storeId,@QueryParam("phase") String phase) {
        ReplenishmentOrder order=scopedOrder(id,storeId);
        if(!Set.of("SOURCE","DESTINATION").contains(phase))throw invalid("Phase inconnue");
        boolean source="SOURCE".equals(phase);
        ExpectedSupply supply=order.supplies.stream().filter(l->l.id.equals(lineId)).findFirst().orElseThrow(()->invalid("Ligne hors de cet ordre"));
        BaseLocation planned=confirmationLocation(order,supply,source,null);
        if(planned==null)return List.of();
        var plannedBuildings=BaseLocation.findBuildingsByLocations(List.of(planned));
        Long preferred=plannedBuildings.isEmpty()?null:plannedBuildings.get(0).id;
        List<Map<String,Object>> result=new ArrayList<>();
        for(Building proxy:order.store.buildings) {
            Building building=Hibernate.unproxy(proxy,Building.class);
            for(BaseLocation raw:building.locations) {
                BaseLocation location=Hibernate.unproxy(raw,BaseLocation.class);
                try { confirmationLocation(order,supply,source,location.id); } catch(BadRequestException e) {continue;}
                if(location.id.equals(planned.id) || location.product!=null && Objects.equals(Hibernate.unproxy(location.product,Product.class).id,Hibernate.unproxy(supply.account.product,Product.class).id)) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("id",location.id);row.put("code",location.locationCode);row.put("buildingId",building.id);row.put("buildingCode",building.buildingCode);
                    row.put("planned",location.id.equals(planned.id));row.put("sameBuilding",Objects.equals(building.id,preferred));
                    if(location instanceof ShelfLocation shelf) {row.put("area",shelf.area);row.put("aisle",shelf.aisle);row.put("bay",shelf.unit);row.put("level",shelf.elevation);row.put("position",shelf.position);}
                    row.put("access",com.intermarche.backoffice.engine.executing.LocationAccessGuidance.of(location));row.put("buildingType",building.getBuildingType());
                    row.put("knownStock",com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>list("account=?1 and location=?2 and effectiveAt<=?3",supply.account,location,DateTimeProvider.now()).stream().map(m->m.quantity).reduce(BigDecimal.ZERO,BigDecimal::add));
                    result.add(row);
                }
            }
        }
        result.sort(Comparator.comparing((Map<String,Object> row)->!(Boolean)row.get("sameBuilding")).thenComparing(row->!(Boolean)row.get("planned")).thenComparing(row->row.get("buildingCode").toString()).thenComparing(row->Objects.toString(row.get("code"),"")));
        return result;
    }
    public static class ReceiptLine {public com.intermarche.backoffice.engine.catalog.ProductMeasure.Input measurement;public Long cartLineId;public Long stockSegmentId,manufacturerLotId,serialId;public String scanId;public Long locationId;public Long cabinetId;public Integer cellRow,cellColumn;public Long supplyId;public BigDecimal quantity;public String toteCode;}
    private static com.intermarche.backoffice.engine.executing.OperatorCommandInput traceHint(ReceiptLine line){var hint=new com.intermarche.backoffice.engine.executing.OperatorCommandInput();hint.stockSegmentId=line.stockSegmentId;hint.manufacturerLotId=line.manufacturerLotId;hint.serialId=line.serialId;hint.scanId=line.scanId;return hint;}
    private void prepareReceiptLine(ReplenishmentOrder order,ExpectedSupply supply,Receipt receipt,ReceiptLine line){if(line.scanId==null&&line.measurement==null)return;Employee actor=Employee.findByBadgeId(identity.getPrincipal().getName());if(actor==null)throw invalid("Une identification physique exige une session employé");var input=traceHint(line);input.measurement=line.measurement;input.quantity=line.quantity;input.requestKey=receipt.eventKey;productInformation.preparePick(input,supply.account.product,order.store,actor);line.quantity=input.quantity;}
    public static class Receipt {public Long storeId;public String eventKey;public List<ReceiptLine> lines;}
    @POST @Path("/{id}/picks") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> pick(@PathParam("id") Long id,Receipt receipt) {
        if(receipt==null || receipt.eventKey==null || !receipt.eventKey.matches("[a-zA-Z0-9-]{8,64}") || receipt.lines==null || receipt.lines.isEmpty())throw invalid("Référence et prélèvements requis");
        ReplenishmentOrder order=scopedOrder(id,receipt.storeId);em.lock(Hibernate.unproxy(order.store,Store.class),LockModeType.PESSIMISTIC_WRITE);pickerMayOperate(order);com.intermarche.backoffice.domain.fieldops.MissionOperationalState.requireActive(id);
        if(order instanceof StockProtectionOrder || order.sourceBuilding==null || order.status!=BaseHandlingOrder.HandlingOrderStatus.PROCESSING)throw invalid("Démarrez le picking de cet ordre");
        start(order.id,receipt.storeId);
        Set<Long> ids=new HashSet<>();
        for(ReceiptLine line:receipt.lines){
            if(line==null || !ids.add(line.supplyId))throw invalid("Ligne invalide");
            ExpectedSupply supply=order.supplies.stream().filter(v -> v.id.equals(line.supplyId)).findFirst().orElseThrow(() -> invalid("Ligne hors de cet ordre"));
            String key="picking:"+receipt.eventKey+":"+supply.id;
            var previous=com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>find("account = ?1 and eventKey = ?2",supply.account,key).firstResult();
            if(previous==null)try{employeeTransit.require(Employee.findByBadgeId(identity.getPrincipal().getName()),order,order.sourceBuilding);}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
            if(previous==null)prepareReceiptLine(order,supply,receipt,line);
            try{stocks.pick(supply.id,receipt.eventKey+":"+supply.id,line.quantity,previous==null?DateTimeProvider.now():previous.effectiveAt,confirmationLocation(order,supply,true,line.locationId).id,traceHint(line));}catch(IllegalArgumentException e){throw invalid("Prélèvement refusé : vérifiez la quantité restante et le stock physique de l’entrepôt.");}
        }
        if(order.supplies.stream().allMatch(v -> v.pickedQuantity().compareTo(v.quantity)>=0))
            for(var execution:com.intermarche.backoffice.domain.executing.ReplenishmentExecution.<com.intermarche.backoffice.domain.executing.ReplenishmentExecution>list("handlingOrder",order))execution.status="SOURCE".equals(execution.phase)?com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE:com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS;
        em.flush();return view(order);
    }
    /**
     * Returns the cart work of a truck delivery: the proposed filling of the free carts and the carts already loaded.
     *
     * @param id the truck unloading order
     * @param storeId the store
     * @return the proposal and the loaded carts
     */
    @GET @Path("/{id}/carts") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> carts(@PathParam("id") Long id,@QueryParam("storeId") Long storeId){
        ReplenishmentOrder order=scopedOrder(id,storeId);
        try{return carts.board(order);}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
    }
    /**
     * Records the carts the picker actually loaded from the truck.
     *
     * @param id the truck unloading order
     * @param confirmation the loaded carts and their contents
     * @return the cart work after confirmation, with capacity warnings
     */
    @POST @Path("/{id}/carts") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> loadCarts(@PathParam("id") Long id,UnloadingCartService.Confirmation confirmation){
        if(confirmation==null)throw invalid("Chargement requis");
        ReplenishmentOrder order=scopedOrder(id,confirmation.storeId);em.lock(Hibernate.unproxy(order.store,Store.class),LockModeType.PESSIMISTIC_WRITE);pickerMayOperate(order);com.intermarche.backoffice.domain.fieldops.MissionOperationalState.requireActive(id);
        try{return carts.confirm(order,confirmation,identity.getPrincipal().getName());}
        catch(IllegalArgumentException e){throw invalid(e.getMessage());}
        catch(IllegalStateException e){throw new jakarta.ws.rs.WebApplicationException(jakarta.ws.rs.core.Response.status(409).type(MediaType.APPLICATION_JSON).entity(Map.of("code","UNLOADING_CART_REQUEST_REUSED","message",e.getMessage())).build());}
    }
    /**
     * Releases a loaded cart: what is still on it becomes available for another cart.
     *
     * @param id the truck unloading order
     * @param loadId the loaded cart
     * @param storeId the store
     * @return the cart work after release
     */
    @POST @Path("/{id}/carts/{loadId}/release") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> releaseCart(@PathParam("id") Long id,@PathParam("loadId") Long loadId,@QueryParam("storeId") Long storeId){
        ReplenishmentOrder order=scopedOrder(id,storeId);em.lock(Hibernate.unproxy(order.store,Store.class),LockModeType.PESSIMISTIC_WRITE);pickerMayOperate(order);com.intermarche.backoffice.domain.fieldops.MissionOperationalState.requireActive(id);
        try{return carts.release(order,loadId);}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
    }
    public static class ReopenRequest {public Long storeId,supplyId;public String eventKey,phase;}
    @POST @Path("/{id}/reopen") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> reopen(@PathParam("id") Long id,ReopenRequest request) {
        if(request==null || request.eventKey==null || !request.eventKey.matches("[a-zA-Z0-9-]{8,64}") || !Set.of("SOURCE","DESTINATION").contains(request.phase==null?"":request.phase))throw invalid("Phase, ligne et référence de correction requises");
        ReplenishmentOrder order=scopedOrder(id,request.storeId);em.lock(Hibernate.unproxy(order.store,Store.class),LockModeType.PESSIMISTIC_WRITE);pickerMayOperate(order);com.intermarche.backoffice.domain.fieldops.MissionOperationalState.requireActive(id);
        if(order instanceof StockProtectionOrder || order.status==BaseHandlingOrder.HandlingOrderStatus.CANCELLED || order.status==BaseHandlingOrder.HandlingOrderStatus.FAILED)throw invalid("Cet ordre ne peut pas être réouvert ici");
        ExpectedSupply supply=order.supplies.stream().filter(v -> v.id.equals(request.supplyId)).findFirst().orElseThrow(() -> invalid("Ligne hors de cet ordre"));
        boolean replay=com.intermarche.backoffice.domain.stock.StockMovement.count("account = ?1 and eventKey = ?2",supply.account,"reopen:"+request.eventKey+":"+supply.id)>0;
        try{stocks.reopen(supply.id,request.eventKey+":"+supply.id,"SOURCE".equals(request.phase),DateTimeProvider.now());}catch(IllegalArgumentException e){throw invalid("Correction refusée : "+e.getMessage()+". Le stock déjà consommé ne peut pas être retiré.");}
        if(replay)return view(order);
        order.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        boolean picked=order.sourceBuilding==null || order.supplies.stream().allMatch(v -> v.pickedQuantity().compareTo(v.quantity)>=0);
        for(var execution:com.intermarche.backoffice.domain.executing.ReplenishmentExecution.<com.intermarche.backoffice.domain.executing.ReplenishmentExecution>list("handlingOrder",order))execution.status="SOURCE".equals(execution.phase)?(picked?com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE:com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS):(picked?com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.IN_PROGRESS:com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.PENDING);
        em.flush();return view(order);
    }
    @POST @Path("/{id}/receipts") @RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
    public Map<String,Object> receive(@PathParam("id") Long id,Receipt receipt) {return receiveObserved(id,receipt,Employee.findByBadgeId(identity.getPrincipal().getName()));}
    /** Manager acceptance acknowledges the physical count already reported by the assigned receiver. */
    @RolesAllowed({"ADMIN","MANAGER"}) @Transactional
    public Map<String,Object> acceptObservedDiscrepancy(Long id,Receipt receipt,String reportedBy){return receiveObserved(id,receipt,Employee.findByBadgeId(reportedBy));}
    private Map<String,Object> receiveObserved(Long id,Receipt receipt,Employee physicalReceiver) {
        if(receipt==null) throw invalid("Réception requise");
        Store store=scope(receipt.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        ReplenishmentOrder order=ReplenishmentOrder.findById(id);
        if(order==null || !store.id.equals(Hibernate.unproxy(order.store,Store.class).id)) throw new NotFoundException();
        pickerMayOperate(order);com.intermarche.backoffice.domain.fieldops.MissionOperationalState.requireActive(id);
        if(receipt.eventKey==null || !receipt.eventKey.matches("[a-zA-Z0-9-]{8,64}") || receipt.lines==null || receipt.lines.isEmpty()) throw invalid("Référence de réception et quantités requises");
        if(order.status==BaseHandlingOrder.HandlingOrderStatus.CANCELLED || order.status==BaseHandlingOrder.HandlingOrderStatus.FAILED) throw invalid("Ordre fermé");
        Set<Long> ids=new HashSet<>();LocalDateTime now=DateTimeProvider.now();
        for(ReceiptLine line:receipt.lines) {
            if(line==null || !ids.add(line.supplyId) || line.quantity==null || line.quantity.signum()<=0 || line.quantity.scale()>6 || line.quantity.precision()-line.quantity.scale()>13) throw invalid("Quantité reçue invalide");
            ExpectedSupply supply=order.supplies.stream().filter(s -> s.id.equals(line.supplyId)).findFirst().orElseThrow(() -> invalid("Ligne hors de cet ordre"));
            if(order instanceof StockProtectionOrder secure) {
                try{employeeTransit.require(physicalReceiver,order,order.destinationBuilding);}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
                try{protection.confirm(secure,supply,receipt.eventKey,line.quantity,line.toteCode);
                    var placement=com.intermarche.backoffice.domain.stock.StockProtectionPlacement.<com.intermarche.backoffice.domain.stock.StockProtectionPlacement>find("supply=?1 and eventKey=?2",supply,receipt.eventKey).firstResult();
                    // A tote carried on this mission's cart stays there: the picker puts it down once the round is over.
                    var protectedTote=placement.destination.protectedTote;
                    boolean onCart=com.intermarche.backoffice.domain.fieldops.CartCompartment.count("handlingOrder=?1 and occupant.tote=?2",order,protectedTote.tote)>0;
                    if(!onCart)bufferOccupancy.deposit(protectedTote,Hibernate.unproxy(secure.protectionBuffer,Buffer.class).id,line.cabinetId,line.cellRow,line.cellColumn,store);
                }catch(IllegalArgumentException | IllegalStateException e){throw invalid(e.getMessage());}
                continue;
            }
            // Duplicate receipts are handled by the stock ledger's idempotency key; reuse its original timestamp.
            String key="receipt:"+receipt.eventKey+":"+supply.id;
            var previous=com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>find("account = ?1 and eventKey = ?2",supply.account,key).firstResult();
            if(supply.account.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && line.quantity.stripTrailingZeros().scale()>0) throw invalid("La quantité reçue doit être entière pour un produit à la pièce");
            if(previous==null) {
                try{employeeTransit.require(physicalReceiver,order,order.destinationBuilding);}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
                Product product=Hibernate.unproxy(supply.account.product,Product.class);
                BigDecimal remaining=supply.quantity.subtract(supply.received);
                if(supply.status!=ExpectedSupply.SupplyStatus.EXPECTED || line.quantity.compareTo(remaining)>0)
                    throw invalid(product.name+" : il reste "+remaining.stripTrailingZeros().toPlainString()+" "+unit(product)+" à ranger. Actualisez l’ordre avant de confirmer.");
                if(order.sourceBuilding!=null && order.supplies.stream().anyMatch(v -> v.pickedQuantity().compareTo(v.quantity)<0))throw invalid("Terminez le picking dans l’entrepôt avant de commencer la mise en rayon.");
                if(order.sourceBuilding!=null && line.quantity.compareTo(supply.pickedQuantity().subtract(supply.received))>0)throw invalid("Cette quantité n’a pas été prélevée dans l’entrepôt.");
                BigDecimal available=order.sourceBuilding!=null?supply.pickedQuantity().subtract(supply.received):physical(supply);
                if(available!=null && available.compareTo(line.quantity)<0)
                    throw invalid(product.name+" : stock physique insuffisant à "+supply.source.locationCode+" ("+available.stripTrailingZeros().toPlainString()+" "+unit(product)+" disponibles pour "+line.quantity.stripTrailingZeros().toPlainString()+" demandés). Réceptionnez d’abord la livraison dans l’entrepôt, ou réduisez la quantité rangée.");
            }
            var target=confirmationLocation(order,supply,false,line.locationId);
            if(target instanceof BufferToteStockLocation place){
                var load=Hibernate.unproxy(place.loadedTote,com.intermarche.backoffice.domain.executing.LoadedTote.class);
                try{bufferOccupancy.deposit(load,Hibernate.unproxy(place.buffer,Buffer.class).id,line.cabinetId,line.cellRow,line.cellColumn,Hibernate.unproxy(order.store,Store.class));}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
            }
            com.intermarche.backoffice.domain.fieldops.UnloadingCartLine cartLine=null;
            if(previous==null && line.cartLineId!=null)try{cartLine=carts.requirePutAway(order,line.cartLineId,supply,line.quantity);}catch(IllegalArgumentException e){throw invalid(e.getMessage());}
            if(previous==null)prepareReceiptLine(order,supply,receipt,line);
            try {stocks.receive(supply.id,receipt.eventKey+":"+supply.id,line.quantity,previous==null?now:previous.effectiveAt,false,target.id,traceHint(line));}
            catch(IllegalArgumentException e) {throw invalid("Rangement non enregistré : les quantités ou le stock ont changé. Actualisez l’ordre et vérifiez la quantité restante.");}
            if(cartLine!=null)carts.recordPutAway(cartLine,line.quantity);
        }
        order.status=order.supplies.stream().allMatch(s -> s.status!=ExpectedSupply.SupplyStatus.EXPECTED)?BaseHandlingOrder.HandlingOrderStatus.DONE:BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        var execution=com.intermarche.backoffice.domain.handling.HandlingExecution.<com.intermarche.backoffice.domain.handling.HandlingExecution>find("handlingOrder",order).firstResult();
        if(order.status==BaseHandlingOrder.HandlingOrderStatus.DONE) for(var item:com.intermarche.backoffice.domain.handling.HandlingExecution.<com.intermarche.backoffice.domain.handling.HandlingExecution>list("handlingOrder",order))item.status=com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE;
        em.flush();return view(order);
    }
}
