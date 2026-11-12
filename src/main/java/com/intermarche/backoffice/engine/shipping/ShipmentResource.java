package com.intermarche.backoffice.engine.shipping;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.shipping.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.oms.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.annotation.security.RolesAllowed;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.quarkus.security.identity.SecurityIdentity;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.util.*;
@Path("/shipments") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER"}) @RunOnVirtualThread @Transactional
public class ShipmentResource {
    @Inject com.intermarche.backoffice.engine.stock.InventoryScopeGuard freezes;
    @Inject com.intermarche.backoffice.engine.stock.PickedStockCoordinator stockTrace;
    @Inject StoreOperationsResource access;@Inject TemporalStockService stocks;@Inject EntityManager em;@Inject SecurityIdentity identity;@Inject com.fasterxml.jackson.databind.ObjectMapper mapper;
    private BadRequestException bad(String m){return new BadRequestException(jakarta.ws.rs.core.Response.status(400).entity(Map.of("message",m)).build());}
    private List<FulfillmentLine> lines(Order o){return em.createQuery("select distinct l from FulfillmentChunk c join c.lines l where c.fulfillment.order=:order",FulfillmentLine.class).setParameter("order",o).getResultList();}
    private List<PickedItem> picks(List<FulfillmentLine> lines){if(lines.isEmpty())return List.of();return PickedItem.list("lotLine.fulfillmentLine in ?1 and status=?2 and quantity>0",lines,PickedItem.PickStatus.PICKED);}
    private Order order(Long id,Store store){Order o=Order.findById(id);if(o==null || !Hibernate.unproxy(o.store,Store.class).id.equals(store.id))throw new NotFoundException();return o;}
    private String unit(Product p){return p.productType==ProductType.WEIGHT?"kg":p.productType==ProductType.VOLUME?"L":p.unitName==null?"pcs":p.unitName;}
    private Map<String,Object> view(Shipment s){var v=new LinkedHashMap<String,Object>();v.put("id",s.id);v.put("code",s.code);v.put("orderId",s.order.orderId);v.put("orderEntityId",Hibernate.unproxy(s.order,Order.class).id);v.put("recipient",s.recipient);v.put("mode",s.mode);v.put("transportReference",s.transportReference);v.put("dispatchedAt",s.dispatchedAt);v.put("totes",s.lines.stream().filter(l->l.loadedTote!=null).map(l->Map.of("id",Hibernate.unproxy(l.loadedTote,LoadedTote.class).id,"code",l.toteCode,"returned",l.toteReturnedAt!=null)).distinct().toList());v.put("parcels",s.lines.stream().filter(l->l.parcel!=null).map(l->Map.of("id",Hibernate.unproxy(l.parcel,Parcel.class).id,"code",l.parcel.code)).distinct().toList());return v;}
    @GET public List<Map<String,Object>> list(@QueryParam("storeId") Long storeId){Store store=access.scope(storeId);var ids=em.createQuery("select s.id from Shipment s where s.store=?1 order by s.id desc",Long.class).setParameter(1,store).setMaxResults(200).getResultList();return fetched(ids).stream().map(this::view).toList();}
    private List<Shipment> fetched(List<Long> ids){if(ids.isEmpty())return List.of();return em.createQuery("select distinct s from Shipment s join fetch s.order left join fetch s.lines l left join fetch l.loadedTote left join fetch l.parcel where s.id in ?1 order by s.id desc",Shipment.class).setParameter(1,ids).getResultList();}
    @GET @Path("/page") public Object page(@QueryParam("storeId")Long storeId,@QueryParam("before")@DefaultValue("0")long before,@QueryParam("limit")@DefaultValue("30")int limit){Store store=access.scope(storeId);if(limit<1||limit>100||before<0)throw bad("Pagination invalide");var ids=em.createQuery("select s.id from Shipment s where s.store=?1 and (?2=0 or s.id<?2) order by s.id desc",Long.class).setParameter(1,store).setParameter(2,before).setMaxResults(limit+1).getResultList();var selected=ids.stream().limit(limit).toList();return Map.of("items",fetched(selected).stream().map(this::view).toList(),"hasMore",ids.size()>limit,"nextBefore",selected.isEmpty()?before:selected.getLast());}
    @GET @Path("/{id}") public Object detail(@PathParam("id")Long id,@QueryParam("storeId")Long storeId){Store store=access.scope(storeId);Shipment shipment=Shipment.find("id=?1 and store=?2",id,store).firstResult();if(shipment==null)throw new NotFoundException();shipment=fetched(List.of(id)).getFirst();var result=new LinkedHashMap<String,Object>(view(shipment));var lines=em.createQuery("select l from ShipmentLine l join fetch l.pickedItem p join fetch p.product where l in (select x from Shipment s join s.lines x where s=?1)",ShipmentLine.class).setParameter(1,shipment).getResultList();var itemIds=lines.stream().map(l->Hibernate.unproxy(l.pickedItem,PickedItem.class).id).toList();var traces=new HashMap<Long,List<Map<String,Object>>>();if(!itemIds.isEmpty())for(Object[] a:em.createQuery("select a.state.itemId,a.quantity,l.code,se.code,h.quality from PickedStockAllocation a join a.holding h left join h.lot l left join h.serial se where a.state.itemId in ?1 and a.shipped=true",Object[].class).setParameter(1,itemIds).getResultList()){var t=new LinkedHashMap<String,Object>();t.put("quantity",a[1]);t.put("lot",a[2]);t.put("serial",a[3]);t.put("quality",a[4]);traces.computeIfAbsent((Long)a[0],unused->new ArrayList<>()).add(t);}result.put("lines",lines.stream().map(l->{var row=new LinkedHashMap<String,Object>();row.put("id",l.id);row.put("productId",Hibernate.unproxy(l.pickedItem.product,Product.class).id);row.put("name",l.productName);row.put("quantity",l.quantity);row.put("unit",l.unit);row.put("toteCode",l.toteCode);row.put("parcelCode",l.parcel==null?null:l.parcel.code);row.put("sourceToteCode",l.sourceToteCode);row.put("departureBuffer",l.departureBuffer);row.put("toteReturnedAt",l.toteReturnedAt);row.put("trace",traces.getOrDefault(Hibernate.unproxy(l.pickedItem,PickedItem.class).id,List.of()));return row;}).toList());return result;}

    /**
     * Lists the orders to ship: those with prepared totes not shipped yet, earliest deadline first. Their number is
     * bounded by the totes in use, a tote holding the products of a single order. With a code, returns that order of
     * the store whatever its state (or nothing if it does not exist), so that a view can preselect it.
     *
     * @param storeId store identifier
     * @param code business identifier of one order, optional
     * @return the orders with their customer, status, deadline, whether a tote is left to ship, and their lines with
     *         the prepared totes
     */
    @GET @Path("/orders") public List<Map<String,Object>> orders(@QueryParam("storeId") Long storeId,@QueryParam("code") String code){
        Store store=access.scope(storeId);
        List<Order> selected=code!=null && !code.isBlank()
            ?Order.<Order>list("select o from Order o left join fetch o.customer where o.store=?1 and o.orderId=?2",store,code.trim())
            :em.createQuery("select distinct o from Order o left join fetch o.customer where o.id in (select c.fulfillment.order.id from FulfillmentChunk c join c.lines l, PickedItem p"
                +" where p.lotLine.fulfillmentLine=l and p.status=:picked and (p.loadedTote is not null or exists(select pc.id from ParcelContent pc where pc.pickedItem=p and pc.parcel.materialized=true)) and c.fulfillment.order.store=:store"
                +" and not exists (select s.id from ShipmentLine s where s.pickedItem=p))",Order.class)
                .setParameter("picked",PickedItem.PickStatus.PICKED).setParameter("store",store).getResultList();
        if(selected.isEmpty())return List.of();
        var ids=selected.stream().map(o->o.id).toList();
        var deadlines=new HashMap<Long,java.time.LocalDateTime>();var linesOf=new HashMap<Long,List<FulfillmentLine>>();
        for(Object[] row:em.createQuery("select c.fulfillment.order.id,min(c.deliveryDate) from FulfillmentChunk c where c.fulfillment.order.id in :ids group by c.fulfillment.order.id",Object[].class).setParameter("ids",ids).getResultList())
            if(row[1]!=null)deadlines.put((Long)row[0],(java.time.LocalDateTime)row[1]);
        for(Object[] row:em.createQuery("select distinct c.fulfillment.order.id,l from FulfillmentChunk c join c.lines l join fetch l.product left join fetch l.location where c.fulfillment.order.id in :ids",Object[].class).setParameter("ids",ids).getResultList())
            linesOf.computeIfAbsent((Long)row[0],k->new ArrayList<>()).add((FulfillmentLine)row[1]);
        var allLines=linesOf.values().stream().flatMap(List::stream).toList();
        var picksOf=new HashMap<Long,List<PickedItem>>();var shipped=new HashSet<Long>();
        if(!allLines.isEmpty()){
            var picked=em.createQuery("select p from PickedItem p join fetch p.lotLine ll left join fetch p.loadedTote t left join fetch t.tote left join fetch t.buffer left join fetch t.loadingDeparture where ll.fulfillmentLine in :lines and p.status=:picked",PickedItem.class)
                .setParameter("lines",allLines).setParameter("picked",PickedItem.PickStatus.PICKED).getResultList();
            for(var p:picked)picksOf.computeIfAbsent(Hibernate.unproxy(p.lotLine.fulfillmentLine,FulfillmentLine.class).id,k->new ArrayList<>()).add(p);
            if(!picked.isEmpty())shipped.addAll(em.createQuery("select s.pickedItem.id from ShipmentLine s where s.pickedItem in :picked",Long.class).setParameter("picked",picked).getResultList());
        }
        var packedByItem=new HashMap<Long,Parcel>();var pickedIds=picksOf.values().stream().flatMap(List::stream).map(p->p.id).toList();if(!pickedIds.isEmpty())for(var c:ParcelContent.<ParcelContent>list("select c from ParcelContent c join fetch c.parcel where c.pickedItem.id in ?1 and c.parcel.materialized=true",pickedIds))packedByItem.put(Hibernate.unproxy(c.pickedItem,PickedItem.class).id,c.parcel);
        var plannedTotes=new HashMap<Long,String>();var toteIds=picksOf.values().stream().flatMap(List::stream).filter(p->p.loadedTote!=null).map(p->Hibernate.unproxy(p.loadedTote,LoadedTote.class).id).distinct().toList();if(!toteIds.isEmpty()){
            for(Object[] v:em.createQuery("select u.loadedTote.id,u.departure.code from DepartureUnit u where u.departure.store=?1 and u.state in ('SELECTED','LOADED') and u.loadedTote.id in ?2",Object[].class).setParameter(1,store).setParameter(2,toteIds).getResultList())plannedTotes.put((Long)v[0],(String)v[1]);
            for(Object[] v:em.createQuery("select c.pickedItem.loadedTote.id,u.departure.code from ParcelContent c,DepartureUnit u where u.parcel=c.parcel and u.departure.store=?1 and u.state in ('SELECTED','LOADED') and c.pickedItem.loadedTote.id in ?2",Object[].class).setParameter(1,store).setParameter(2,toteIds).getResultList())plannedTotes.put((Long)v[0],(String)v[1]);
        }
        var result=new ArrayList<Map<String,Object>>();
        for(Order o:selected){
            var rows=new ArrayList<Map<String,Object>>();boolean shippable=false;boolean parcelReady=false;
            for(FulfillmentLine l:linesOf.getOrDefault(o.id,List.of()).stream().sorted(Comparator.comparingInt(x->x.lineNumber)).toList()){
                var p=Hibernate.unproxy(l.product,Product.class);var row=new LinkedHashMap<String,Object>();row.put("id",l.id);row.put("name",p.name);row.put("quantity",l.requestedQuantity);row.put("unit",unit(p));row.put("shelf",l.location==null?null:l.location.locationCode);
                var totes=new ArrayList<Map<String,Object>>();var parcelRows=new ArrayList<Map<String,Object>>();
                for(var x:picksOf.getOrDefault(l.id,List.of())){if(x.loadedTote==null){var parcel=packedByItem.get(x.id);if(parcel!=null){var v=new LinkedHashMap<String,Object>();v.put("id",parcel.id);v.put("code",parcel.code);v.put("quantity",x.quantity);v.put("state",parcel.state);v.put("address",parcel.currentAddress);v.put("shipped",shipped.contains(x.id));parcelRows.add(v);parcelReady|=!shipped.contains(x.id);}continue;}LoadedTote t=Hibernate.unproxy(x.loadedTote,LoadedTote.class);var v=new LinkedHashMap<String,Object>();v.put("id",t.id);v.put("code",t.tote.code);v.put("quantity",x.quantity);v.put("buffer",t.buffer==null?null:t.buffer.bufferCode);v.put("address",t.loadingDeparture==null?null:"Chargé · "+t.loadingDeparture.transportReference);v.put("departureCode",plannedTotes.get(t.id));v.put("shipped",shipped.contains(x.id));totes.add(v);shippable|=!shipped.contains(x.id);}
                row.put("totes",totes);row.put("parcels",parcelRows);rows.add(row);
            }
            var item=new LinkedHashMap<String,Object>();item.put("id",o.id);item.put("code",o.orderId);item.put("customer",o.customer==null?"":o.customer.firstName+" "+o.customer.lastName);item.put("status",o.status==null?"":o.status);
            item.put("deadline",deadlines.get(o.id));item.put("shippable",shippable);item.put("parcelReady",parcelReady);item.put("lines",rows);result.add(item);
        }
        result.sort(Comparator.comparing((Map<String,Object> m)->(java.time.LocalDateTime)m.get("deadline"),Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(m->(String)m.get("code")));
        return result;
    }
    public Map<String,Object> lineTracking(Long storeId,String code,int number,String ean){return lineTracking(storeId,null,code,number,ean);}
    @GET @Path("/line-tracking") public Map<String,Object> lineTracking(@QueryParam("storeId") Long storeId,@QueryParam("storeCode") String storeCode,@QueryParam("orderCode") String code,@QueryParam("lineNumber") int number,@QueryParam("ean") String ean){
        if(storeId==null && storeCode!=null){Store target=Store.find("code",storeCode).firstResult();if(target==null)throw new NotFoundException();storeId=target.id;}
        Store store=access.scope(storeId);if(code==null || code.isBlank() || number<1 || ean==null || ean.isBlank())throw bad("Commande et ligne requises");
        Order o=Order.find("store=?1 and orderId=?2",store,code).firstResult();
        if(o==null)return Map.of("state","NOT_PICKED","totes",List.of(),"sources",List.of(),"message","Le picking n’a pas encore eu lieu.");
        var fl=lines(o).stream().filter(l->l.lineNumber==number && ean.equals(l.product.ean)).toList();var picked=picks(fl);var totes=new ArrayList<Map<String,Object>>();BigDecimal pickedQty=BigDecimal.ZERO,shippedQty=BigDecimal.ZERO;
        var parcelByItem=new HashMap<Long,Parcel>();var sentByItem=new HashMap<Long,List<ShipmentLine>>();if(!picked.isEmpty()){var ids=picked.stream().map(p->p.id).toList();for(var c:ParcelContent.<ParcelContent>list("select c from ParcelContent c join fetch c.parcel p left join fetch p.currentBuffer b left join fetch b.building where c.pickedItem.id in ?1 and p.materialized=true and p.state<>'CANCELLED'",ids))parcelByItem.put(Hibernate.unproxy(c.pickedItem,PickedItem.class).id,c.parcel);for(var line:ShipmentLine.<ShipmentLine>list("pickedItem.id in ?1",ids))sentByItem.computeIfAbsent(Hibernate.unproxy(line.pickedItem,PickedItem.class).id,ignored->new ArrayList<>()).add(line);}
        for(var item:picked){var shipments=sentByItem.getOrDefault(item.id,List.of());boolean shipped=!shipments.isEmpty();var row=new LinkedHashMap<String,Object>();row.put("quantity",item.quantity);row.put("shipped",shipped);if(item.loadedTote!=null){var t=Hibernate.unproxy(item.loadedTote,LoadedTote.class);row.put("kind","TOTE");row.put("id",t.id);row.put("code",t.tote.code);row.put("buffer",!shipped&&t.buffer!=null?t.buffer.bufferCode:null);row.put("building",!shipped&&t.buffer!=null?t.buffer.building.buildingCode:null);row.put("cabinet",!shipped&&t.cabinet!=null?t.cabinet.code:null);row.put("cellRow",shipped?null:t.cellRow);row.put("cellColumn",shipped?null:t.cellColumn);row.put("address",!shipped&&t.loadingDeparture!=null?"Chargé · "+t.loadingDeparture.transportReference:null);}else{var parcel=parcelByItem.get(item.id);row.put("kind","PARCEL");row.put("id",parcel==null?null:parcel.id);row.put("code",parcel==null?"Colis à identifier":parcel.code);row.put("address",parcel==null?"Localisation à vérifier":parcel.currentAddress);row.put("buffer",parcel==null||parcel.currentBuffer==null?null:parcel.currentBuffer.bufferCode);row.put("building",parcel==null||parcel.currentBuffer==null?null:parcel.currentBuffer.building.buildingCode);row.put("cabinet",null);row.put("cellRow",null);row.put("cellColumn",null);}row.put("departureBuffer",shipped?shipments.getFirst().departureBuffer:null);totes.add(row);pickedQty=pickedQty.add(item.quantity);shippedQty=shippedQty.add(shipments.stream().map(l->l.quantity).reduce(BigDecimal.ZERO,BigDecimal::add));}
        BigDecimal requested=fl.stream().map(l->l.requestedQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
        boolean legacyShipped="SHIPPED".equals(o.status) && picked.isEmpty();
        String state=legacyShipped || requested.signum()>0 && shippedQty.compareTo(requested)>=0?"SHIPPED":shippedQty.signum()>0?"PARTIALLY_SHIPPED":pickedQty.signum()>0?"PICKED":"NOT_PICKED";
        var result=new LinkedHashMap<String,Object>();result.put("state",state);result.put("totes",totes);result.put("pickedQuantity",pickedQty);result.put("shippedQuantity",legacyShipped?requested:shippedQty);result.put("remainingToPick",requested.subtract(pickedQty).max(BigDecimal.ZERO));result.put("sources",fl.stream().filter(l->l.location!=null).map(l->l.location.locationCode).distinct().toList());result.put("message",legacyShipped?"Commande déclarée expédiée ; détail historique des bacs non disponible.":state.equals("NOT_PICKED")?"Le picking n’a pas encore eu lieu.":state.equals("SHIPPED")?"Cette ligne a déjà été expédiée.":state.equals("PARTIALLY_SHIPPED")?"Cette ligne est partiellement expédiée.":"Produits prélevés : emplacement des bacs ci-dessous.");return result;
    }
    public static class Dispatch {public Long storeId,orderId;public String requestKey,mode,recipient,transportReference;public List<Long> toteIds;@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) public Long departureId;@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) public List<Long> parcelIds;}
    @POST public Map<String,Object> dispatch(Dispatch r){if(r!=null&&(r.departureId!=null||r.parcelIds!=null))throw bad("Utilisez le contrôle du chargement et la remise du départ pour ces éléments");return performDispatch(r,false);}
    public Map<String,Object> dispatchCargo(Dispatch r){return performDispatch(r,true);}
    private Map<String,Object> performDispatch(Dispatch r,boolean controlledDeparture){
        if(r==null)throw bad("Expédition requise");Store store=access.scope(r.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        if(r.requestKey==null || !r.requestKey.matches("[a-zA-Z0-9-]{8,64}"))throw bad("Référence de demande requise");
        String fingerprint;try{fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(r)));}catch(Exception e){throw bad("Demande invalide");}
        Shipment old=Shipment.find("requestKey",store.id+":"+r.requestKey).firstResult();if(old!=null){if(!fingerprint.equals(old.requestHash))throw new ClientErrorException("Référence déjà utilisée avec un autre contenu",409);return view(old);}
        if(!Set.of("CARRIER","CUSTOMER").contains(r.mode==null?"":r.mode) || r.recipient==null || r.recipient.isBlank() || r.recipient.length()>160 || r.transportReference==null || r.transportReference.isBlank() || r.transportReference.length()>160)throw bad("Mode, destinataire et référence de transport ou de remise requis");
        var chosenTotes=r.toteIds==null?List.<Long>of():r.toteIds;var chosenParcels=r.parcelIds==null?List.<Long>of():r.parcelIds;
        if(chosenTotes.size()+chosenParcels.size()<1||chosenTotes.size()+chosenParcels.size()>100||new HashSet<>(chosenTotes).size()!=chosenTotes.size()||new HashSet<>(chosenParcels).size()!=chosenParcels.size())throw bad("Sélectionnez de 1 à 100 bacs ou colis distincts");
        LogisticsDeparture departure=null;if(controlledDeparture){departure=LogisticsDeparture.find("id=?1 and store=?2",r.departureId,store).firstResult();if(departure==null||!Hibernate.unproxy(departure.order,Order.class).id.equals(r.orderId)||!Set.of("LOADING","PARTIALLY_HANDED_OVER").contains(departure.state))throw bad("Départ de chargement introuvable ou terminé");if(!identity.hasRole("MANAGER")&&!identity.hasRole("ADMIN")&&!departure.owner.badgeId.equals(identity.getPrincipal().getName()))throw new ForbiddenException();}
        Order order=order(r.orderId,store);var fl=lines(order);var picked=picks(fl).stream().filter(p->p.loadedTote!=null).toList();var validIds=new HashSet<Long>();for(var p:picked)validIds.add(Hibernate.unproxy(p.loadedTote,LoadedTote.class).id);
        com.intermarche.backoffice.engine.fieldops.ParcelContentGuard.requireUnchanged(store,picked.stream().map(p->Hibernate.unproxy(p.lotLine,com.intermarche.backoffice.domain.wms.LotLine.class).id).distinct().toList());
        if(!controlledDeparture&&!chosenTotes.isEmpty()&&DepartureUnit.count("departure.store=?1 and state in ('SELECTED','LOADED') and (loadedTote.id in ?2 or parcel.id in (select c.parcel.id from ParcelContent c where c.pickedItem.loadedTote.id in ?2))",store,chosenTotes)>0)throw new com.intermarche.backoffice.engine.operator.OperatorFailure(409,"CARGO_ALREADY_PLANNED","Des marchandises appartiennent à un chargement actif : confirmez leur remise depuis ce départ.");
        var parcelContents=em.createQuery("select c from ParcelContent c join fetch c.parcel where c.pickedItem.loadedTote.id in ?1 and c.parcel.store=?2 and c.parcel.state<>'CANCELLED'",ParcelContent.class).setParameter(1,chosenTotes.isEmpty()?List.of(-1L):chosenTotes).setParameter(2,store).getResultList();
        for(var content:parcelContents){if(!"CLOSED".equals(content.parcel.state))throw bad("Un colis utilisant ces marchandises est encore ouvert : terminez son contrôle avant le départ");var requiredTotes=em.createQuery("select distinct c.pickedItem.loadedTote.id from ParcelContent c where c.parcel=?1",Long.class).setParameter(1,content.parcel).getResultList();if(!chosenTotes.containsAll(requiredTotes))throw bad("Un colis est constitué depuis plusieurs bacs : sélectionnez tous ses bacs pour le même départ");}
        var shipment=new Shipment();shipment.store=store;shipment.order=order;shipment.requestKey=store.id+":"+r.requestKey;shipment.requestHash=fingerprint;shipment.code="EXP-"+r.requestKey.substring(0,8).toUpperCase(Locale.ROOT);shipment.mode=r.mode;shipment.recipient=r.recipient.trim();shipment.transportReference=r.transportReference.trim();shipment.performedBy=identity.getPrincipal().getName();shipment.dispatchedAt=DateTimeProvider.now();shipment.persistAndFlush();
        for(Long toteId:chosenTotes){
            if(!validIds.contains(toteId))throw bad("Bac absent de la préparation de cette commande");
            LoadedTote tote=LoadedTote.findById(toteId);if(tote.status!=LoadedTote.Status.IN_USE || (!controlledDeparture&&tote.buffer==null))throw bad("Déposez les bacs dans un buffer avant de confirmer le départ");if(controlledDeparture){if(tote.loadingDeparture==null||!Hibernate.unproxy(tote.loadingDeparture,LogisticsDeparture.class).id.equals(departure.id)||DepartureUnit.count("departure=?1 and loadedTote=?2 and state='LOADED'",departure,tote)!=1)throw bad("Ce bac n’est pas chargé dans ce départ");}else if(tote.loadingDeparture!=null)throw bad("Ce bac est déjà chargé : confirmez sa remise depuis son départ");
            if(BufferToteStockLocation.count("select count(loc) from BufferToteStockLocation loc where loc.loadedTote=?1 and (select sum(m.quantity) from StockMovement m where m.location=loc and m.effectiveAt<=?2)>0",tote,DateTimeProvider.now())>0)throw bad("Ce bac contient des produits de stock non rattachés à la commande. Répartissez ces produits avant l’expédition");
            if(ShipmentLine.count("loadedTote",tote)>0)throw bad("Ce bac a déjà été expédié");
            var toteItems=PickedItem.<PickedItem>list("loadedTote=?1 and status=?2 and quantity>0",tote,PickedItem.PickStatus.PICKED);
            if(!toteItems.isEmpty()&&com.intermarche.backoffice.domain.fieldops.CancelledMissionLine.count("line.id in ?1",toteItems.stream().map(i->Hibernate.unproxy(i.lotLine,com.intermarche.backoffice.domain.wms.LotLine.class).id).toList())>0)throw bad("Ce bac contient des produits annulés : terminez leur retour, réaffectation ou isolement avant l’expédition.");
            for(var item:toteItems){
                if(!picked.contains(item))throw bad("Ce bac contient des produits d’une autre commande");
                Product p=Hibernate.unproxy(item.product,Product.class);
                stockTrace.ship(store,item,"shipment:"+shipment.id+":"+item.id);
                var line=new ShipmentLine();line.pickedItem=item;line.loadedTote=tote;line.toteCode=tote.tote.code;line.departureBuffer=tote.buffer==null?"Chargé · "+r.transportReference:tote.buffer.bufferCode;line.productName=p.name;line.quantity=item.quantity;line.unit=unit(p);shipment.lines.add(line);
            }
            freezes.tote(store,tote);
            tote.clearCell();tote.buffer=null;tote.loadingDeparture=null; // Remains locked IN_USE until its physical empty return is recorded.
        }
        for(Long parcelId:chosenParcels){Parcel parcel=Parcel.find("id=?1 and store=?2 and order=?3",parcelId,store,order).firstResult();if(!controlledDeparture||parcel==null||!parcel.materialized||!"LOADED".equals(parcel.state)||DepartureUnit.count("departure=?1 and parcel=?2 and state='LOADED'",departure,parcel)!=1)throw bad("Ce colis n’est pas chargé dans le départ contrôlé");var contents=ParcelContent.<ParcelContent>list("select c from ParcelContent c join fetch c.pickedItem i join fetch i.product where c.parcel=?1",parcel);if(contents.isEmpty())throw bad("Colis vide");for(var c:contents){var item=c.pickedItem;if(item.loadedTote!=null||item.status!=PickedItem.PickStatus.PICKED||item.quantity.compareTo(c.quantity)!=0||ShipmentLine.count("pickedItem",item)>0)throw bad("Le contenu physique du colis a changé ou est déjà remis");if(com.intermarche.backoffice.domain.fieldops.CancelledMissionLine.count("line",item.lotLine)>0)throw bad("La commande de ces produits est annulée : déchargez et traitez leur orientation");stockTrace.ship(store,item,"shipment:"+shipment.id+":"+item.id);var line=new ShipmentLine();line.pickedItem=item;line.parcel=parcel;line.sourceToteCode=c.sourceToteCode;line.departureBuffer=parcel.currentAddress;line.productName=item.product.name;line.quantity=c.quantity;line.unit=unit(item.product);shipment.lines.add(line);}parcel.state="DISPATCHED";parcel.currentBuffer=null;parcel.currentAddress="Remis à "+r.recipient;parcel.revisionNumber++;}
        parcelContents.stream().map(c->c.parcel).distinct().forEach(p->{p.state="DISPATCHED";p.revisionNumber++;});
        em.flush();
        boolean complete=!fl.isEmpty() && fl.stream().allMatch(l->{BigDecimal sent=shipmentQuantity(l);return sent.compareTo(l.requestedQuantity)>=0;});
        order.status=complete?"SHIPPED":"PARTIALLY_SHIPPED";
        Event.publishInCurrentTx("Commande expédiée",shipment.code+" — "+order.orderId+" — "+shipment.recipient+" — "+shipment.transportReference,Event.UrgencyLevel.MEDIUM,store,shipment,shipment.code);
        return view(shipment);
    }
    private BigDecimal shipmentQuantity(FulfillmentLine line){return ShipmentLine.<ShipmentLine>list("pickedItem.lotLine.fulfillmentLine",line).stream().map(l->l.quantity).reduce(BigDecimal.ZERO,BigDecimal::add);}
    public static class Return {public Long storeId,toteId;}
    @POST @Path("/{id}/returns") public Map<String,Object> returnTote(@PathParam("id") Long id,Return r){
        if(r==null)throw bad("Bac requis");Store store=access.scope(r.storeId);em.lock(store,LockModeType.PESSIMISTIC_WRITE);Shipment shipment=Shipment.findById(id);if(shipment==null || !Hibernate.unproxy(shipment.store,Store.class).id.equals(store.id))throw new NotFoundException();
        var lines=shipment.lines.stream().filter(l->l.loadedTote!=null&&Hibernate.unproxy(l.loadedTote,LoadedTote.class).id.equals(r.toteId)).toList();if(lines.isEmpty())throw bad("Bac absent de cette expédition");
        for(var line:lines)if(line.toteReturnedAt==null){line.toteReturnedAt=DateTimeProvider.now();line.toteReturnedBy=identity.getPrincipal().getName();line.loadedTote.status=LoadedTote.Status.DELIVERED;}
        em.flush();return view(shipment);
    }
}
