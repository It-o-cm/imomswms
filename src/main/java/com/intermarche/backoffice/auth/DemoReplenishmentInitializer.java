package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class DemoReplenishmentInitializer {
    @Inject TemporalStockService stocks;
    @Inject EntityManager em;
    @Inject DemoWarehouseInitializer demoWarehouse;
    @Transactional
    public void seed(Store store) {
        em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        em.flush();
        em.refresh(store);
        StoreBuilding shop=StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();
        if(shop==null || store.buildings.stream().noneMatch(b -> b.id.equals(shop.id))) return;
        WarehouseBuilding warehouse=WarehouseBuilding.find("buildingCode","LYO-042-WH").firstResult();
        if(warehouse==null) {
            warehouse=new WarehouseBuilding(); warehouse.buildingCode="LYO-042-WH";
            warehouse.address=shop.address; warehouse.gpsPosition=shop.gpsPosition;
            warehouse.persist();store.buildings.add(warehouse);
        }
        em.flush();
        demoWarehouse.seed(warehouse);
        LocalDateTime now=DateTimeProvider.now();
        seedUnassigned(store,shop,warehouse,now);
        ReplenishmentOrder unloading=ReplenishmentOrder.find("code","REA-DEMO-CAM").firstResult();
        ReplenishmentOrder shelving=ReplenishmentOrder.find("code","REA-DEMO-RAY").firstResult();
        boolean addUnloading=unloading==null, addShelving=shelving==null;
        if(!addUnloading && !addShelving) {seedMixed(store,shop,warehouse,now);return;}
        if(addUnloading) unloading=create(store,"REA-DEMO-CAM",ReplenishmentOrder.Operation.TRUCK_UNLOADING,null,warehouse,now.plusMinutes(15),now.plusMinutes(45));
        if(addShelving) shelving=create(store,"REA-DEMO-RAY",ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,warehouse,shop,now.plusMinutes(60),now.plusMinutes(120));
        shelving.predecessor=unloading;
        // Separate reserve locations; goods remain expected until an actual receipt is recorded.
        List<StoreShelfLocation> shelves=shop.locations.stream().filter(StoreShelfLocation.class::isInstance)
            .map(StoreShelfLocation.class::cast).filter(l -> l.product!=null && l.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT)
            .filter(l -> !"3017620422003".equals(l.product.ean))
            .sorted(Comparator.comparing(l -> l.product.ean)).collect(java.util.stream.Collectors.toMap(l -> l.product.ean,l -> l,(a,b)->a,java.util.TreeMap::new)).values().stream().limit(8).toList();
        int index=0;
        for(StoreShelfLocation shelf:shelves) {
            Product product=shelf.product;
            WarehouseShelfLocation reserve=warehouse.locations.stream().filter(l -> l.product!=null && l.product.id.equals(product.id))
                .map(WarehouseShelfLocation.class::cast).findFirst().orElse(null);
            if(reserve==null) {
                reserve=new WarehouseShelfLocation();reserve.product=product;reserve.area=product.temperatureZone.name();
                reserve.aisle="R01";reserve.unit=String.format("B%02d",++index);reserve.elevation="1";reserve.position="1";
                reserve.persistAndFlush();warehouse.locations.add(reserve);
            }
            StockAccount account=stocks.account(store.id,product.id);
            BigDecimal incoming=BigDecimal.valueOf(48), toShelf=BigDecimal.valueOf(12);
            if(addUnloading) unloading.supplies.add(stocks.planSupply(account.id,"REA-DEMO-CAM-"+product.ean,null,reserve.id,incoming,unloading.requestedEnd));
            if(addShelving) shelving.supplies.add(stocks.planSupply(account.id,"REA-DEMO-RAY-"+product.ean,reserve.id,shelf.id,toShelf,shelving.requestedEnd));
        }
        seedMixed(store,shop,warehouse,now);
        em.flush();
    }
    private void seedMixed(Store store,StoreBuilding shop,WarehouseBuilding warehouse,LocalDateTime now) {
        if(ReplenishmentOrder.count("code","REA-MIX-CAM")>0) return;
        List<StoreShelfLocation> all=shop.locations.stream().map(l -> org.hibernate.Hibernate.unproxy(l,ShelfLocation.class)).filter(StoreShelfLocation.class::isInstance).map(StoreShelfLocation.class::cast)
            .filter(l -> l.product!=null && !"3017620422003".equals(l.product.ean)).sorted(Comparator.comparing(l -> l.product.ean)).toList();
        java.util.Map<String,StoreShelfLocation> unique=new java.util.LinkedHashMap<>();all.forEach(l -> unique.putIfAbsent(l.product.ean,l));
        List<StoreShelfLocation> selected=new java.util.ArrayList<>(unique.values().stream().filter(l -> l.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT).limit(6).toList());
        selected.addAll(unique.values().stream().filter(l -> l.product.productType==com.intermarche.backoffice.domain.order.ProductType.WEIGHT).limit(2).toList());
        if(selected.size()<8) return;
        var truck=create(store,"REA-MIX-CAM",ReplenishmentOrder.Operation.TRUCK_UNLOADING,null,warehouse,now.plusMinutes(20),now.plusMinutes(50));
        truck.truckReference="CAMION-MIXTE-02";
        var shelves=create(store,"REA-MIX-RAY",ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,warehouse,shop,now.plusMinutes(55),now.plusMinutes(90));shelves.predecessor=truck;
        String[] incoming={"36","18","60","24","9","42","12.750","7.250"},outgoing={"9","5","17","6","3","11","3.250","1.750"};
        for(int i=0;i<selected.size();i++) {
            var shelf=selected.get(i);Product product=org.hibernate.Hibernate.unproxy(shelf.product,Product.class);
            var reserve=warehouse.locations.stream().map(l -> org.hibernate.Hibernate.unproxy(l,ShelfLocation.class)).filter(l -> l.product!=null && org.hibernate.Hibernate.unproxy(l.product,Product.class).id.equals(product.id)).findFirst().orElseThrow();
            var account=stocks.account(store.id,product.id);
            truck.supplies.add(stocks.planSupply(account.id,truck.code+":"+product.ean,null,reserve.id,new BigDecimal(incoming[i]),truck.requestedEnd));
            shelves.supplies.add(stocks.planSupply(account.id,shelves.code+":"+product.ean,reserve.id,shelf.id,new BigDecimal(outgoing[i]),shelves.requestedEnd));
        }
    }
    private void seedUnassigned(Store store,StoreBuilding shop,WarehouseBuilding warehouse,LocalDateTime now){
        var shelves=StoreShelfLocation.<StoreShelfLocation>list("product is not null order by id").stream().filter(l->shop.locations.stream().anyMatch(x->x.id.equals(l.id))).filter(l->l.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && !"3017620422003".equals(l.product.ean)).limit(3).toList();
        if(shelves.isEmpty())return;
        for(int i=0;i<4;i++){
            String code="AFF-"+(i%2==0?"CAM-0":"RAY-0")+(i/2+1);if(ReplenishmentOrder.count("code",code)>0)continue;
            boolean truck=i%2==0;var start=now.plusMinutes(30L+i*45).withSecond(0).withNano(0);
            var order=create(store,code,truck?ReplenishmentOrder.Operation.TRUCK_UNLOADING:ReplenishmentOrder.Operation.SHELF_REPLENISHMENT,truck?null:warehouse,truck?warehouse:shop,start,start.plusMinutes(truck?30:45));order.assignee=null;if(truck)order.truckReference="CAMION-AFFECTER-"+(i/2+1);
            for(var shelf:shelves){var reserve=warehouse.locations.stream().filter(l->l instanceof WarehouseShelfLocation && l.product!=null && l.product.id.equals(shelf.product.id)).findFirst().orElse(null);if(reserve==null)continue;
                var account=stocks.account(store.id,shelf.product.id);order.supplies.add(stocks.planSupply(account.id,code+":"+shelf.product.ean,truck?null:reserve.id,truck?reserve.id:shelf.id,BigDecimal.valueOf(truck?18+i*3:3+i),order.requestedEnd));
            }
            em.flush();
        }
    }
    private ReplenishmentOrder create(Store store,String code,ReplenishmentOrder.Operation operation,Building source,Building destination,LocalDateTime start,LocalDateTime end) {
        ReplenishmentOrder order=new ReplenishmentOrder();order.store=store;order.code=code;order.operation=operation;
        order.sourceBuilding=source;order.destinationBuilding=destination;order.truckReference=operation==ReplenishmentOrder.Operation.TRUCK_UNLOADING ? "CAMION-DEMO-01" : null;
        order.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;order.requestedStart=start;order.requestedEnd=end;
        order.assignee=Employee.find("badgeId","100235").firstResult();order.persist();return order;
    }
}
