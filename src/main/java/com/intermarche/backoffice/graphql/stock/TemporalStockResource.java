package com.intermarche.backoffice.graphql.stock;

import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.*;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Managers and integration accounts operate only their store; administrators may address any store. */
@GraphQLApi @ApplicationScoped @RunOnVirtualThread @Transactional
@RolesAllowed({"ADMIN","MANAGER","SERVICE_CALLER"})
public class TemporalStockResource implements GraphQLTrait {
    @Inject SecurityIdentity identity;
    @Inject TemporalStockService stocks;
    @Override public SecurityIdentity getIdentity() {return identity;}
    private void scope(Store store) {
        if (store==null) throw new NoSuchElementException("Store not found");
        if (identity.hasRole("ADMIN")) return;
        Store context=getStoreContext();
        if (context==null || !store.id.equals(context.id)) throw new SecurityException("Stock belongs to another store");
    }
    private StockAccount account(Long id) {
        StockAccount account=StockAccount.findById(id);
        if (account==null) throw new NoSuchElementException("Stock account not found");scope(account.store);return account;
    }
    private ExpectedSupply supply(Long id) {
        ExpectedSupply supply=ExpectedSupply.findById(id);
        if (supply==null) throw new NoSuchElementException("Supply not found");account(supply.account.id);return supply;
    }
    private StockCommitment commitment(Long id) {
        StockCommitment commitment=StockCommitment.findById(id);
        if (commitment==null) throw new NoSuchElementException("Commitment not found");account(commitment.account.id);return commitment;
    }
    @Query public List<StockAccount> stockAccounts(@Name("storeCode") String storeCode) {
        Store store=Store.findByCode(storeCode);scope(store);return StockAccount.list("store",store);
    }
    @Query public List<StockAccount> stockAccountsInTension(@Name("storeCode") String storeCode,@Name("buildingId") Long buildingId) {
        Store store=Store.findByCode(storeCode);scope(store);return stocks.accountsInTension(store.id,buildingId);
    }
    public static class StockOverviewRow {
        public Long id,productId,accountId;
        public boolean stockKnown=true;
        public Product product;
        public BigDecimal physical,availableToPromise,safetyMargin,inTransit,committed,quantityToProtect,unsupportedTransferQuantity;
        public LocalDateTime asOf,nextAvailableAt,constrainedAt;
        public boolean inTension;
        public String availabilityStatus;
        public LocalDateTime unavailableAt;
        public BigDecimal balanceNow;
        public List<StockAddressOption> placements=new ArrayList<>();
    }
    /** One snapshot for the product overview; all availability decisions remain in Java. */
    @Query public List<StockOverviewRow> stockOverview(@Name("storeCode") String storeCode,@Name("buildingId") Long buildingId,@Name("tensionOnly") @DefaultValue("false") boolean tensionOnly) {
        Store store=Store.findByCode(storeCode);scope(store);
        if(buildingId!=null){var building=com.intermarche.backoffice.domain.store.Building.findStoreByBuildingId(buildingId);if(building==null || !building.id.equals(store.id))throw new SecurityException("Building outside requested store");}
        LocalDateTime now=DateTimeProvider.now();
        List<StockOverviewRow> rows=new ArrayList<>();
        for(StockAccount a:StockAccount.<StockAccount>list("store",store)){
            var timeline=stocks.availability(a.id,buildingId,now);
            var row=new StockOverviewRow();row.id=a.id;row.product=a.product;row.physical=timeline.physical;row.availableToPromise=timeline.periods.get(0).availableToPromise;
            row.safetyMargin=timeline.safetyMargin;row.inTransit=timeline.inTransit;row.committed=timeline.periods.get(0).committed;row.quantityToProtect=timeline.quantityToProtect;row.unsupportedTransferQuantity=timeline.unsupportedTransferQuantity;
            row.asOf=now;row.nextAvailableAt=timeline.nextAvailableAt;row.constrainedAt=timeline.constrainedAt;row.inTension=row.availableToPromise.signum()==0 || row.unsupportedTransferQuantity.signum()>0;
            if(!tensionOnly || row.inTension)rows.add(row);
        }
        rows.sort(Comparator.comparing((StockOverviewRow row)->!row.inTension).thenComparing(row->row.product.name,String.CASE_INSENSITIVE_ORDER));
        return rows;
    }
    public static class StockPage {
        public List<StockOverviewRow> items=new ArrayList<>();
        public Long nextCursor;
        public boolean hasMore;
        public int scanned;
    }
    private StockOverviewRow overviewRow(StockAccount a,StockAvailability.Timeline t,LocalDateTime now,int horizonHours) {
        var r=new StockOverviewRow();r.id=a.id;r.accountId=a.id;r.productId=org.hibernate.Hibernate.unproxy(a.product,Product.class).id;r.product=org.hibernate.Hibernate.unproxy(a.product,Product.class);
        r.physical=t.physical;r.availableToPromise=t.periods.get(0).availableToPromise;r.balanceNow=t.periods.get(0).balance;r.safetyMargin=t.safetyMargin;r.inTransit=t.inTransit;r.committed=t.periods.get(0).committed;
        r.quantityToProtect=t.quantityToProtect;r.unsupportedTransferQuantity=t.unsupportedTransferQuantity;r.asOf=now;r.nextAvailableAt=t.nextAvailableAt;r.constrainedAt=t.constrainedAt;
        r.unavailableAt=t.periods.stream().filter(p->p.balance.signum()<=0).map(p->p.from).findFirst().orElse(null);
        r.availabilityStatus=r.balanceNow.signum()<=0?"UNAVAILABLE":r.unavailableAt!=null && !r.unavailableAt.isAfter(now.plusHours(horizonHours))?"TENSION":"AVAILABLE";
        r.inTension="TENSION".equals(r.availabilityStatus);return r;
    }
    /** Keyset paging: at most 200 candidate projections per request, never the whole catalogue. */
    @Query public StockPage stockPage(@Name("storeCode") String storeCode,@Name("buildingId") Long buildingId,@Name("search") String search,@Name("situation") String situation,@Name("temperature") String temperature,@Name("productType") String productType,@Name("brand") String brand,@Name("location") String location,@Name("area") String area,@Name("aisle") String aisle,@Name("bay") String bay,@Name("bufferId") Long bufferId,@Name("physicalOnly") @DefaultValue("false") boolean physicalOnly,@Name("horizonHours") @DefaultValue("24") int horizonHours,@Name("afterId") Long afterId,@Name("limit") @DefaultValue("30") int limit) {
        Store store=Store.findByCode(storeCode);scope(store);
        if(buildingId!=null){var owner=com.intermarche.backoffice.domain.store.Building.findStoreByBuildingId(buildingId);if(owner==null || !owner.id.equals(store.id))throw new SecurityException("Building outside requested store");}
        if(limit<1 || limit>50 || horizonHours<1 || horizonHours>720 || (afterId!=null && afterId<0))throw new IllegalArgumentException("Pagination ou horizon invalide");
        String state=situation==null || situation.isBlank()?"ALL":situation;
        var states=new HashSet<>(Arrays.asList(state.split(",")));if(!Set.of("ALL","NONE","UNAVAILABLE","TENSION","AVAILABLE","UNKNOWN").containsAll(states))throw new IllegalArgumentException("Situation inconnue");
        if(states.contains("NONE"))return new StockPage();
        var args=new HashMap<String,Object>();args.put("store",store);args.put("after",afterId==null?0L:afterId);
        StringBuilder query=new StringBuilder("select p,a from Product p left join StockAccount a on a.product=p and a.store=:store where p.active=true and p.id>:after");
        if(search!=null && !search.isBlank()){if(search.length()>120)throw new IllegalArgumentException("Recherche trop longue");query.append(" and (lower(p.name) like :search escape '!' or p.ean like :ean escape '!')");String term=search.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");args.put("search","%"+term+"%");args.put("ean",term+"%");}
        if(brand!=null && !brand.isBlank()){if(brand.length()>100)throw new IllegalArgumentException("Marque trop longue");query.append(" and lower(p.brand) like :brand escape '!'");args.put("brand","%"+brand.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(location!=null && !location.isBlank()){
            if(location.length()>120)throw new IllegalArgumentException("Emplacement trop long");
            query.append(" and exists (select l.id from Store ls join ls.buildings lb join lb.locations l where ls=:store and lower(l.locationCode) like :location escape '!'");
            if(buildingId!=null){query.append(" and lb.id=:locationBuilding");args.put("locationBuilding",buildingId);}
            query.append(" and (l.product=p or (select sum(m.quantity) from StockMovement m where m.account=a and m.location=l and m.effectiveAt<=:locationNow)>0))");
            args.put("location","%"+location.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
            args.put("locationNow",DateTimeProvider.now());
        }
        if((area!=null && !area.isBlank()) || (aisle!=null && !aisle.isBlank()) || (bay!=null && !bay.isBlank()) || bufferId!=null){
            query.append(" and (exists (select sl.id from Store ps join ps.buildings pb join pb.locations sl where ps=:store");
            if(buildingId!=null){query.append(" and pb.id=:placementBuilding");args.put("placementBuilding",buildingId);}
            if(area!=null && !area.isBlank()){query.append(" and sl.area=:area");args.put("area",area);}
            if(aisle!=null && !aisle.isBlank()){query.append(" and sl.aisle=:aisle");args.put("aisle",aisle);}
            if(bay!=null && !bay.isBlank()){query.append(" and sl.unit=:bay");args.put("bay",bay);}
            if(bufferId!=null){
                var buffer=com.intermarche.backoffice.domain.store.Buffer.<com.intermarche.backoffice.domain.store.Buffer>findById(bufferId);
                if(buffer==null)throw new IllegalArgumentException("Buffer introuvable");
                var owner=com.intermarche.backoffice.domain.store.Building.findStoreByBuildingId(org.hibernate.Hibernate.unproxy(buffer.building,com.intermarche.backoffice.domain.store.Building.class).id);
                if(owner==null || !owner.id.equals(store.id))throw new SecurityException("Buffer hors du magasin");
                query.append(" and (sl.id in (select bl.id from BufferToteStockLocation bl where bl.buffer=:placementBuffer) or sl.id in (select tl.id from PickingToteLocation tl where tl.protectedTote.buffer=:placementBuffer))");args.put("placementBuffer",buffer);
            }
            query.append(" and (sl.product=p or (select sum(sm.quantity) from StockMovement sm where sm.account=a and sm.location=sl and sm.effectiveAt<=:placementNow)>0))");
            if(bufferId!=null && (area==null || area.isBlank()) && (aisle==null || aisle.isBlank()) && (bay==null || bay.isBlank())){
                query.append(" or exists (select pi.id from LoadedTote lt join lt.items pi where lt.buffer=:placementBuffer and lt.status=:placementInUse and pi.product=p");
                if(buildingId!=null)query.append(" and lt.buffer.building.id=:placementBuilding");
                query.append(")");args.put("placementInUse",com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE);
            }
            query.append(")");args.put("placementNow",DateTimeProvider.now());
        }
        if(temperature!=null && !temperature.isBlank()){query.append(" and p.temperatureZone=:temperature");args.put("temperature",com.intermarche.backoffice.domain.store.TemperatureZone.valueOf(temperature));}
        if(productType!=null && !productType.isBlank()){query.append(" and p.productType=:kind");args.put("kind",com.intermarche.backoffice.domain.order.ProductType.valueOf(productType));}
        if("TENSION".equals(state))query.append(" and (exists (select c.id from StockCommitment c where c.account=a and c.status=:active) or exists (select x.id from ExpectedSupply x where x.account=a and x.source is not null and x.status=:expected))");
        if("TENSION".equals(state)){args.put("active",StockCommitment.CommitmentStatus.ACTIVE);args.put("expected",ExpectedSupply.SupplyStatus.EXPECTED);}
        // active=true makes this the same cursor order, while explicitly matching
        // (is_active,id) lets H2 stop at the page boundary instead of sorting all matches.
        query.append(" order by p.active,p.id");
        var selection=StockAccount.getEntityManager().createQuery(query.toString(),Object[].class);args.forEach(selection::setParameter);
        List<Object[]> candidates=selection.setMaxResults(states.contains("ALL") && !physicalOnly ? limit+1 : 201).getResultList();var result=new StockPage();LocalDateTime now=DateTimeProvider.now();
        var projectedAccounts=candidates.stream().limit(states.contains("ALL") && !physicalOnly ? limit : 200).map(c->(StockAccount)c[1]).filter(Objects::nonNull).toList();
        var timelines=stocks.availabilityBatch(projectedAccounts,buildingId,now);
        for(Object[] candidate:candidates){
            if(result.scanned>=200 || result.items.size()>=limit)break;
            Product product=(Product)candidate[0];StockAccount account=(StockAccount)candidate[1];result.scanned++;result.nextCursor=product.id;
            StockOverviewRow row;
            if(account!=null)row=overviewRow(account,timelines.get(account.id),now,horizonHours);
            else {row=new StockOverviewRow();row.id=-product.id;row.productId=product.id;row.stockKnown=false;row.product=product;row.availabilityStatus="UNKNOWN";row.asOf=now;}
            if((states.contains("ALL") || states.contains(row.availabilityStatus)) && (!physicalOnly || row.physical!=null && row.physical.signum()>0))result.items.add(row);
        }
        displayPlacements(result.items,store,buildingId);
        result.hasMore=result.scanned<candidates.size();if(!result.hasMore)result.nextCursor=null;return result;
    }
    public static class StockAddressOption {
        public Long buildingId;public String buildingCode,area,aisle,bay;public Double aislePosition;public int spatialOrder;
    }
    public static class StockPlacementOptions {
        public List<StockAddressOption> addresses=new ArrayList<>();
        public List<com.intermarche.backoffice.engine.executing.BufferOccupancyService.BufferOccupancy> buffers=new ArrayList<>();
    }
    private List<StockAddressOption> addressOptions(Store store,Long buildingId) {
        var query=StockAccount.getEntityManager().createQuery("select distinct b.id,b.buildingCode,l.area,l.aisle,l.unit,l.aislePosition from Store s join s.buildings b join b.locations l where s=:store and type(l) not in (PendingSupplyLocation,BufferToteStockLocation,PickingToteLocation)"+(buildingId==null?"":" and b.id=:building"),Object[].class).setParameter("store",store);
        if(buildingId!=null)query.setParameter("building",buildingId);
        var groups=new TreeMap<String,List<StockAddressOption>>();
        Comparator<String> natural=(a,b)->{
            var pa=java.util.regex.Pattern.compile("(\\d+|\\D+)").matcher(a==null?"":a);var pb=java.util.regex.Pattern.compile("(\\d+|\\D+)").matcher(b==null?"":b);
            while(pa.find() && pb.find()){String x=pa.group(),y=pb.group();int c=Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))?new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y)):x.compareTo(y);if(c!=0)return c;}return String.valueOf(a).compareTo(String.valueOf(b));
        };
        for(var row:query.getResultList()){
            var a=new StockAddressOption();a.buildingId=(Long)row[0];a.buildingCode=(String)row[1];a.area=(String)row[2];a.aisle=(String)row[3];a.bay=(String)row[4];a.aislePosition=(Double)row[5];groups.computeIfAbsent(a.buildingCode+"|"+a.aisle,k->new ArrayList<>()).add(a);
        }
        var result=new ArrayList<StockAddressOption>();
        for(var group:groups.values()){
            group.sort(Comparator.comparing(a->a.bay,natural));int stations=(group.size()+1)/2;
            for(int i=0;i<group.size();i++)if(group.get(i).aislePosition==null)group.get(i).aislePosition=(i/2+0.5)/Math.max(1,stations);
            group.sort(Comparator.comparingDouble(a->a.aislePosition));
            for(int i=0;i<group.size();i++){group.get(i).spatialOrder=i;result.add(group.get(i));}
        }
        return result;
    }
    private void displayPlacements(List<StockOverviewRow> rows,Store store,Long buildingId) {
        if(rows.isEmpty())return;
        var topology=addressOptions(store,buildingId);
        Map<String,StockAddressOption> addresses=new HashMap<>();
        for(var a:topology)addresses.put(a.buildingId+"|"+a.area+"|"+a.aisle+"|"+a.bay,a);
        var ids=rows.stream().map(r->org.hibernate.Hibernate.unproxy(r.product,Product.class).id).toList();
        var query=StockAccount.getEntityManager().createQuery("select distinct l.product.id,b.id,l.area,l.aisle,l.unit from Store s join s.buildings b join b.locations l where s=:store and l.product.id in :products and type(l) not in (PendingSupplyLocation,BufferToteStockLocation,PickingToteLocation)"+(buildingId==null?"":" and b.id=:building"),Object[].class).setParameter("store",store).setParameter("products",ids);
        if(buildingId!=null)query.setParameter("building",buildingId);
        for(var entry:query.getResultList()){var placement=addresses.get(entry[1]+"|"+entry[2]+"|"+entry[3]+"|"+entry[4]);if(placement!=null)for(var row:rows)if(org.hibernate.Hibernate.unproxy(row.product,Product.class).id.equals(entry[0]))row.placements.add(placement);}
    }
    @Inject com.intermarche.backoffice.engine.executing.BufferOccupancyService bufferOccupancy;
    @Query public StockPlacementOptions stockPlacementOptions(@Name("storeCode") String storeCode,@Name("buildingId") Long buildingId) {
        var store=Store.findByCode(storeCode);scope(store);
        if(buildingId!=null){var owner=com.intermarche.backoffice.domain.store.Building.findStoreByBuildingId(buildingId);if(owner==null || !owner.id.equals(store.id))throw new SecurityException("Bâtiment hors du magasin");}
        var result=new StockPlacementOptions();
        result.addresses=addressOptions(store,buildingId);
        result.buffers=bufferOccupancy.occupancy(store).stream().filter(b->buildingId==null || org.hibernate.Hibernate.unproxy(com.intermarche.backoffice.domain.store.Buffer.<com.intermarche.backoffice.domain.store.Buffer>findById(b.id).building,com.intermarche.backoffice.domain.store.Building.class).id.equals(buildingId)).toList();
        return result;
    }
    @Query public StockAvailability.Timeline stockAvailability(@Name("accountId") Long accountId,@Name("buildingId") Long buildingId) {
        account(accountId);return stocks.availability(accountId,buildingId,DateTimeProvider.now());
    }
    @Query public TemporalStockService.Promise stockPromise(@Name("accountId") Long accountId,@Name("buildingId") Long buildingId,
        @Name("quantity") BigDecimal quantity,@Name("preparationAt") LocalDateTime preparationAt) {
        account(accountId);return stocks.quote(accountId,buildingId,quantity,preparationAt);
    }
    @Query public List<TemporalStockService.Position> stockPositions(@Name("accountId") Long accountId) {
        account(accountId);return stocks.positions(accountId);
    }
    @Query public List<StockMovement> stockMovements(@Name("accountId") Long accountId) {
        account(accountId);return stocks.movements(accountId);
    }
    @Query public List<ExpectedSupply> stockExpectedSupplies(@Name("accountId") Long accountId) {
        account(accountId);return ExpectedSupply.list("account.id",accountId);
    }
    @Query public List<StockCommitment> stockCommitments(@Name("accountId") Long accountId) {
        account(accountId);return StockCommitment.list("account.id",accountId);
    }
    @Query public List<TemporalStockService.Position> stockAdjustmentLocations(@Name("accountId") Long accountId,@Name("buildingId") Long buildingId) {
        StockAccount a=account(accountId);
        var positions=stocks.positions(accountId);
        List<TemporalStockService.Position> result=new ArrayList<>();
        for(var proxy:a.store.buildings){var b=org.hibernate.Hibernate.unproxy(proxy,com.intermarche.backoffice.domain.store.Building.class);if(buildingId!=null && !buildingId.equals(b.id))continue;
            for(var p:b.locations){var l=org.hibernate.Hibernate.unproxy(p,com.intermarche.backoffice.domain.store.ShelfLocation.class);
                if(l instanceof com.intermarche.backoffice.domain.store.PickingToteLocation || l instanceof com.intermarche.backoffice.domain.store.PendingSupplyLocation || l instanceof com.intermarche.backoffice.domain.store.BufferToteStockLocation || (l.product!=null && !org.hibernate.Hibernate.unproxy(l.product,Product.class).id.equals(org.hibernate.Hibernate.unproxy(a.product,Product.class).id)))continue;
                var row=new TemporalStockService.Position();row.locationId=l.id;row.locationCode=l.locationCode;row.buildingId=b.id;row.buildingCode=b.buildingCode;row.quantity=positions.stream().filter(v->v.locationId.equals(l.id)).map(v->v.quantity).findFirst().orElse(BigDecimal.ZERO);result.add(row);
            }
        }return result;
    }
    @Query public List<StockInventoryCorrection> stockInventoryCorrections(@Name("accountId") Long accountId) {account(accountId);return StockInventoryCorrection.list("account.id",accountId);}
    @Mutation @RolesAllowed({"ADMIN","MANAGER"}) public StockInventoryCorrection setPhysicalStock(@Name("accountId") Long accountId,@Name("locationId") Long locationId,@Name("eventKey") String eventKey,@Name("expectedQuantity") BigDecimal expectedQuantity,@Name("quantity") BigDecimal quantity,@Name("reason") String reason) {
        account(accountId);var correction=stocks.setPhysical(accountId,locationId,eventKey,expectedQuantity,quantity,reason);if(correction.performedBy==null)correction.performedBy=identity.getPrincipal().getName();return correction;
    }
    @Mutation public StockAccount openStockAccount(@Name("storeCode") String storeCode,@Name("ean") String ean) {
        Store store=Store.findByCode(storeCode);scope(store);Product product=Product.findByEan(ean);
        if(product==null) throw new NoSuchElementException("Product not found");return stocks.account(store.id,product.id);
    }
    @Mutation public StockAccount setStockSafetyMargin(@Name("accountId") Long accountId,@Name("quantity") BigDecimal quantity) {
        account(accountId);return stocks.setMargin(accountId,quantity);
    }
    @Mutation public StockMovement adjustStock(@Name("accountId") Long accountId,@Name("locationId") Long locationId,
        @Name("eventKey") String eventKey,@Name("quantity") BigDecimal quantity,@Name("effectiveAt") LocalDateTime effectiveAt) {
        account(accountId);return stocks.adjust(accountId,locationId,eventKey,quantity,effectiveAt);
    }
    @Mutation public ExpectedSupply planStockSupply(@Name("accountId") Long accountId,@Name("reference") String reference,
        @Name("sourceId") Long sourceId,@Name("destinationId") Long destinationId,@Name("quantity") BigDecimal quantity,@Name("availableAt") LocalDateTime availableAt) {
        account(accountId);return stocks.planSupply(accountId,reference,sourceId,destinationId,quantity,availableAt);
    }
    @Mutation public ExpectedSupply reviseStockSupply(@Name("supplyId") Long supplyId,@Name("quantity") BigDecimal quantity,
        @Name("availableAt") LocalDateTime availableAt,@Name("cancel") boolean cancel) {
        supply(supplyId);return stocks.reviseSupply(supplyId,quantity,availableAt,cancel);
    }
    @Mutation public ExpectedSupply receiveStockSupply(@Name("supplyId") Long supplyId,@Name("eventKey") String eventKey,
        @Name("quantity") BigDecimal quantity,@Name("effectiveAt") LocalDateTime effectiveAt,@Name("finalReceipt") boolean finalReceipt) {
        supply(supplyId);return stocks.receive(supplyId,eventKey,quantity,effectiveAt,finalReceipt);
    }
    @Mutation public StockCommitment reserveStock(@Name("accountId") Long accountId,@Name("reference") String reference,
        @Name("buildingId") Long buildingId,@Name("quantity") BigDecimal quantity,@Name("preparationAt") LocalDateTime preparationAt,
        @Name("enforceAvailability") @DefaultValue("true") boolean enforceAvailability) {
        account(accountId);return stocks.reserve(accountId,reference,buildingId,quantity,preparationAt,enforceAvailability);
    }
    @Mutation public StockCommitment reviseStockCommitment(@Name("commitmentId") Long commitmentId,@Name("quantity") BigDecimal quantity,
        @Name("preparationAt") LocalDateTime preparationAt,@Name("enforceAvailability") @DefaultValue("true") boolean enforceAvailability) {
        commitment(commitmentId);return stocks.reviseCommitment(commitmentId,quantity,preparationAt,enforceAvailability);
    }
    @Mutation public StockCommitment cancelStockCommitment(@Name("commitmentId") Long commitmentId) {
        commitment(commitmentId);return stocks.cancelCommitment(commitmentId);
    }
    @Mutation public StockCommitment prepareStock(@Name("commitmentId") Long commitmentId,@Name("locationId") Long locationId,
        @Name("eventKey") String eventKey,@Name("quantity") BigDecimal quantity,@Name("effectiveAt") LocalDateTime effectiveAt) {
        commitment(commitmentId);return stocks.prepare(commitmentId,locationId,eventKey,quantity,effectiveAt);
    }
}
