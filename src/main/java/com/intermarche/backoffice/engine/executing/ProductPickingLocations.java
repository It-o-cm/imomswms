package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import org.hibernate.Hibernate;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/** Real product addresses only; a zero known stock never removes a physical address. */
@ApplicationScoped
public class ProductPickingLocations {
    @jakarta.inject.Inject com.intermarche.backoffice.engine.stock.InventoryScopeGuard freezes;
    public BaseLocation validate(Store store,Product product,Long locationId) {
        if(locationId==null)return null;
        BaseLocation raw=BaseLocation.findById(locationId);
        if(raw==null)throw new IllegalArgumentException("Emplacement inconnu");
        BaseLocation location=Hibernate.unproxy(raw,BaseLocation.class);
        if(!(location instanceof ShelfLocation) || location instanceof PickingToteLocation picking && (picking.traceOnly||picking.protectedTote==null) || location instanceof PendingSupplyLocation || location.product==null || !Objects.equals(Hibernate.unproxy(location.product,Product.class).id,Hibernate.unproxy(product,Product.class).id))throw new IllegalArgumentException("Cet emplacement n’est pas affecté au produit prélevé");
        var buildings=BaseLocation.findBuildingsByLocations(List.of(location));
        if(buildings.size()!=1 || store.buildings.stream().noneMatch(b->Objects.equals(Hibernate.unproxy(b,Building.class).id,buildings.get(0).id)))throw new IllegalArgumentException("Emplacement hors du magasin");
        return location;
    }
    public List<Map<String,Object>> choices(Store store,Product product,String plannedCode,String preferredBuilding) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Building raw:store.buildings) {
            Building building=Hibernate.unproxy(raw,Building.class);
            for(ShelfLocation candidate:building.locations) {
                if(candidate.product==null || !Objects.equals(Hibernate.unproxy(candidate.product,Product.class).id,product.id))continue;
                BaseLocation checked;
                try{checked=validate(store,product,candidate.id);}catch(IllegalArgumentException e){continue;}
                ShelfLocation location=(ShelfLocation)checked;
                Map<String,Object> row=new LinkedHashMap<>();row.put("id",location.id);row.put("code",location.locationCode);row.put("buildingId",building.id);row.put("buildingCode",building.buildingCode);row.put("buildingType",building.getBuildingType());
                row.put("area",location.area);row.put("aisle",location.aisle);row.put("bay",location.unit);row.put("level",location.elevation);row.put("position",location.position);
                row.put("frozen",false);
                try { freezes.location(store,location); }
                catch(com.intermarche.backoffice.engine.operator.OperatorFailure failure) {
                    Object payload=failure.getResponse().getEntity();
                    if(!(payload instanceof Map<?,?> detail) || !"INVENTORY_SCOPE_FROZEN".equals(detail.get("code")))throw failure;
                    row.put("frozen",true);row.put("unavailableReason",detail.get("message"));
                }
                row.put("access",LocationAccessGuidance.of(location));row.put("planned",Objects.equals(location.locationCode,plannedCode));row.put("sameBuilding",Objects.equals(building.buildingCode,preferredBuilding));result.add(row);
            }
        }
        result.sort(Comparator.comparing((Map<String,Object> row)->!(Boolean)row.get("sameBuilding")).thenComparing(row->!(Boolean)row.get("planned")).thenComparing(row->row.get("buildingCode").toString()).thenComparing(row->Objects.toString(row.get("code"),"")));
        return result;
    }
}
