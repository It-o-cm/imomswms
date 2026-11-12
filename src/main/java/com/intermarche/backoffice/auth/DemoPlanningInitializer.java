package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.workforce.*;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.hibernate.Hibernate;

/** Additive, repeatable three-building planning example, separate from user-entered employee hours. */
@ApplicationScoped
public class DemoPlanningInitializer {
    @Inject EntityManager em;
    @Inject DemoWarehouseInitializer warehouseSeed;
    @Inject TemporalStockService stocks;

    @Transactional
    public void seed(Store store) {
        if(!"LYO-042".equals(store.code))return;
        em.lock(store,LockModeType.PESSIMISTIC_WRITE);
        StoreBuilding shop=StoreBuilding.find("buildingCode","LYO-042-SHOP").firstResult();
        WarehouseBuilding main=WarehouseBuilding.find("buildingCode","LYO-042-WH").firstResult();
        if(shop==null || main==null)return;
        WarehouseBuilding annex=WarehouseBuilding.find("buildingCode","LYO-042-ANNEXE").firstResult();
        if(annex==null){annex=new WarehouseBuilding();annex.buildingCode="LYO-042-ANNEXE";annex.address=shop.address;annex.gpsPosition=shop.gpsPosition;annex.persistAndFlush();store.buildings.add(annex);em.flush();}
        if(!store.buildings.contains(annex))throw new IllegalStateException("L’annexe de démonstration appartient à un autre magasin");
        warehouseSeed.seed(annex);
        transit(store,shop,main,8);transit(store,main,shop,9);
        transit(store,shop,annex,12);transit(store,annex,shop,14);
        transit(store,main,annex,5);transit(store,annex,main,6);

        Employee first=employee(store,"DEMO-P01","Alice","Moreau");
        Employee second=employee(store,"DEMO-P02","Karim","Petit");
        // The first seeded presence fixes the scenario's date. Reseeding never rolls or overwrites it.
        PlanningEntry marker=PlanningEntry.find("employee=?1 and kind='SHIFT' and title=?2",first,"Démo optimisation · présence magasin").firstResult();
        LocalDate day=marker==null?DateTimeProvider.now().toLocalDate().plusDays(1):marker.startsAt.toLocalDate();
        entry(first,"SHIFT","Démo optimisation · présence magasin",shop,day.atTime(8,0),day.atTime(18,0),null);
        entry(second,"SHIFT","Démo optimisation · présence entrepôt",main,day.atTime(8,0),day.atTime(18,0),null);
        entry(first,"ABSENCE","Démo optimisation · pause Alice",shop,day.atTime(13,30),day.atTime(14,0),null);
        entry(second,"ABSENCE","Démo optimisation · pause Karim",main,day.atTime(14,0),day.atTime(14,30),null);
        entry(first,"SHIFT","Démo · présence des ordres du jour",shop,day.minusDays(1).atTime(8,0),day.minusDays(1).atTime(18,0),null);
        entry(second,"SHIFT","Démo · présence des ordres du jour",main,day.minusDays(1).atTime(8,0),day.minusDays(1).atTime(18,0),null);
        entry(first,"ABSENCE","Démo · pause du jour Alice",shop,day.minusDays(1).atTime(12,0),day.minusDays(1).atTime(13,0),null);
        entry(second,"ABSENCE","Démo · pause du jour Karim",main,day.minusDays(1).atTime(13,0),day.minusDays(1).atTime(14,0),null);
        entry(first,"ABSENCE","Démo · formation du jour Alice",shop,day.minusDays(1).atTime(16,0),day.minusDays(1).atTime(16,30),null);
        entry(second,"ABSENCE","Démo · briefing du jour Karim",main,day.minusDays(1).atTime(17,0),day.minusDays(1).atTime(17,30),null);
        entry(first,"ABSENCE","Démo · formation sécurité Alice",shop,day.atTime(9,0),day.atTime(9,30),null);
        entry(second,"ABSENCE","Démo · briefing Karim",main,day.atTime(9,15),day.atTime(9,45),null);
        entry(first,"ABSENCE","Démo · indisponibilité Alice",shop,day.atTime(15,0),day.atTime(16,0),null);
        entry(second,"ABSENCE","Démo · indisponibilité Karim",main,day.atTime(16,0),day.atTime(17,0),null);
        // Crossed original assignments deliberately incur avoidable journeys.
        entry(first,"TASK","Démo · inventaire entrepôt principal",main,day.atTime(10,0),day.atTime(10,20),"PICKER");
        entry(second,"TASK","Démo · contrôle des rayons magasin",shop,day.atTime(10,0),day.atTime(10,20),"PICKER");
        entry(first,"TASK","Démo · contrôle annexe",annex,day.atTime(10,45),day.atTime(11,5),"PICKER");
        entry(second,"TASK","Démo · préparation annexe",annex,day.atTime(11,15),day.atTime(11,35),"PICKER");
        replenishments(store,shop,annex,day);
        em.flush();
    }
    private Employee employee(Store store,String badge,String first,String last){
        Employee employee=Employee.findByBadgeId(badge);if(employee!=null){if(!Hibernate.unproxy(employee.store,Store.class).id.equals(store.id))throw new IllegalStateException("Badge de démonstration déjà utilisé");return employee;}
        employee=new Employee();employee.store=store;employee.badgeId=badge;employee.firstName=first;employee.lastName=last;employee.email=badge.toLowerCase()+"@demo.storepilot.test";employee.role=Employee.EmployeeRole.PICKER;employee.active=true;
        employee.password=org.mindrot.jbcrypt.BCrypt.hashpw("1234",org.mindrot.jbcrypt.BCrypt.gensalt());employee.persistAndFlush();return employee;
    }
    private void transit(Store store,Building from,Building to,int minutes){
        if(BuildingTransit.count("store=?1 and origin=?2 and destination=?3",store,from,to)>0)return;
        BuildingTransit t=new BuildingTransit();t.store=store;t.origin=from;t.destination=to;t.minutes=minutes;t.persist();
    }
    private void entry(Employee employee,String kind,String title,Building building,LocalDateTime start,LocalDateTime end,String role){
        if(PlanningEntry.count("employee=?1 and title=?2",employee,title)>0)return;
        PlanningEntry e=new PlanningEntry();e.employee=employee;e.kind=kind;e.title=title;e.building=building;e.startsAt=start;e.endsAt=end;e.requiredRole=role;e.persist();
    }
    private ReplenishmentOrder order(Store store,String code,Building from,Building to,LocalDateTime start,LocalDateTime end){
        if(ReplenishmentOrder.count("code",code)>0)return null;
        ReplenishmentOrder o=new ReplenishmentOrder();o.store=store;o.code=code;o.sourceBuilding=from;o.destinationBuilding=to;o.operation=from==null?ReplenishmentOrder.Operation.TRUCK_UNLOADING:ReplenishmentOrder.Operation.SHELF_REPLENISHMENT;o.truckReference=from==null?"CAMION-DEMO-ANNEXE":null;o.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;o.requestedStart=start;o.requestedEnd=end;o.assignee=null;o.persistAndFlush();return o;
    }
    /** Remove only an untouched early demo seed that accidentally altered the documented Nutella scenario. */
    private boolean repairProtectedScenario(Store store,WarehouseBuilding annex){
        var movement=com.intermarche.backoffice.domain.stock.StockMovement.<com.intermarche.backoffice.domain.stock.StockMovement>find("account.store=?1 and eventKey=?2",store,"demo-annexe-stock-3017620422003").firstResult();
        if(movement==null || com.intermarche.backoffice.domain.stock.StockMovement.count("account=?1 and location=?2",movement.account,movement.location)!=1)return false;
        var orders=ReplenishmentOrder.<ReplenishmentOrder>list("store=?1 and code in ?2",store,List.of("OPT-ANN-CAM","OPT-ANN-RAY"));
        if(orders.size()!=2 || orders.stream().anyMatch(o->o.status!=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED || com.intermarche.backoffice.domain.handling.HandlingExecution.count("handlingOrder",o)>0))return false;
        var lines=orders.stream().flatMap(o->o.supplies.stream()).filter(s->"3017620422003".equals(s.account.product.ean)).toList();
        if(lines.size()!=2 || lines.stream().anyMatch(s->s.received.signum()!=0 || s.pickedQuantity().signum()!=0 || s.status!=com.intermarche.backoffice.domain.stock.ExpectedSupply.SupplyStatus.EXPECTED))return false;
        for(var o:orders){for(var line:lines)if(o.supplies.remove(line))stocks.reviseSupply(line.id,line.quantity,line.availableAt,true);}
        stocks.adjust(movement.account.id,movement.location.id,"demo-annexe-remove-protected-stock",movement.quantity.negate(),DateTimeProvider.now());return true;
    }
    private void replenishments(Store store,StoreBuilding shop,WarehouseBuilding annex,LocalDate day){
        boolean repaired=repairProtectedScenario(store,annex);
        var truck=order(store,"OPT-ANN-CAM",null,annex,day.atTime(11,45),day.atTime(12,15));
        var shelving=order(store,"OPT-ANN-RAY",annex,shop,day.atTime(12,30),day.atTime(13,15));
        if(shelving!=null)shelving.predecessor=truck==null?ReplenishmentOrder.find("code","OPT-ANN-CAM").firstResult():truck;
        if(truck==null&&shelving==null&&!repaired)return;
        if(repaired){if(truck==null)truck=ReplenishmentOrder.find("code","OPT-ANN-CAM").firstResult();if(shelving==null)shelving=ReplenishmentOrder.find("code","OPT-ANN-RAY").firstResult();}
        var shelves=shop.locations.stream().map(l->Hibernate.unproxy(l,ShelfLocation.class)).filter(l->l.product!=null && !"3017620422003".equals(l.product.ean)).sorted(Comparator.comparing(l->l.product.ean)).toList();
        var selected=new LinkedHashMap<Long,ShelfLocation>();
        for(var shelf:shelves)if(selected.size()<3 && shelf.product.productType==ProductType.UNIT)selected.putIfAbsent(shelf.product.id,shelf);
        for(var shelf:shelves)if(shelf.product.productType==ProductType.WEIGHT){selected.putIfAbsent(shelf.product.id,shelf);break;}
        int index=0;
        for(var shelf:selected.values()){
            Product product=Hibernate.unproxy(shelf.product,Product.class);
            var reserve=annex.locations.stream().map(l->Hibernate.unproxy(l,ShelfLocation.class)).filter(l->l.product!=null&&Hibernate.unproxy(l.product,Product.class).id.equals(product.id)).findFirst().orElseThrow();
            var account=stocks.account(store.id,product.id);boolean weighted=product.productType==ProductType.WEIGHT;
            // Real physical seed stock makes the second leg executable independently of the truck demo.
            String stockKey="demo-annexe-stock-"+product.ean;
            if(com.intermarche.backoffice.domain.stock.StockMovement.count("account=?1 and eventKey=?2",account,stockKey)==0)stocks.adjust(account.id,reserve.id,stockKey,weighted?new BigDecimal("15.500"):BigDecimal.valueOf(30+index*6),DateTimeProvider.now());
            if(truck!=null&&com.intermarche.backoffice.domain.stock.ExpectedSupply.count("account=?1 and reference=?2",account,truck.code+":"+product.ean)==0)truck.supplies.add(stocks.planSupply(account.id,truck.code+":"+product.ean,null,reserve.id,weighted?new BigDecimal("12.750"):BigDecimal.valueOf(18+index*6),truck.requestedEnd));
            if(shelving!=null&&com.intermarche.backoffice.domain.stock.ExpectedSupply.count("account=?1 and reference=?2",account,shelving.code+":"+product.ean)==0)shelving.supplies.add(stocks.planSupply(account.id,shelving.code+":"+product.ean,reserve.id,shelf.id,weighted?new BigDecimal("3.250"):BigDecimal.valueOf(3+index*2),shelving.requestedEnd));
            index++;
        }
    }
}
