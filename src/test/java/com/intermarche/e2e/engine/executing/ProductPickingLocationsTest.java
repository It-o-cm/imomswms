package com.intermarche.e2e.engine.executing;

import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.engine.executing.OperatorExecutionService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(com.intermarche.e2e.DemoFixtureProfile.class)
@QuarkusTest @TestTransaction @TestSecurity(user="100235",roles="PICKER")
class ProductPickingLocationsTest {
    @Inject DemoDataInitializer seed;
    @Inject OperatorExecutionService operator;
    @Inject com.intermarche.backoffice.engine.executing.ProductPickingLocations locations;
    @io.quarkus.test.InjectMock com.intermarche.backoffice.engine.stock.InventoryScopeGuard freezes;
    @io.quarkus.test.InjectMock com.intermarche.backoffice.auth.AuthenticationService authentication;
    @Test void productAndZoneCollectionsOfferRealWarehouseLocations() {
        seed.seedDemoData();var store=Store.findByCode("LYO-042");var employee=Employee.findByBadgeId("100235");
        org.mockito.Mockito.when(authentication.getConnectedAccount()).thenReturn(employee);
        for(var order:CollectingOrder.<CollectingOrder>listAll()) {
            if(!(order instanceof ProductCollectingOrder) && !(order instanceof ZoneCollectingOrder))continue;
            order.assignee=employee;
            var line=order.lots.stream().flatMap(lot->lot.lines.stream()).filter(l->l.product!=null).findFirst().orElse(null);
            if(line==null)continue;
            var product=Hibernate.unproxy(line.product,com.intermarche.backoffice.domain.order.Product.class);
            var planned=line.location!=null?line.location:line.fulfillmentLine.location;
            var choices=(List<Map<String,Object>>)operator.productLocations(store.id,order.id,product.id,product.id,planned.locationCode,"LYO-042-SHOP");
            assertFalse(choices.isEmpty());assertTrue(choices.stream().anyMatch(row->row.get("buildingCode").toString().endsWith("-WH")));
            assertTrue(choices.stream().anyMatch(row->Boolean.TRUE.equals(row.get("planned"))));
        }
    }
    @Test void frozenLocationStaysVisibleWithItsReason() {
        seed.seedDemoData();var store=Store.findByCode("LYO-042");
        var location=store.buildings.stream().flatMap(b->Hibernate.unproxy(b,Building.class).locations.stream()).filter(l->l.product!=null).findFirst().orElseThrow();
        var product=Hibernate.unproxy(location.product,com.intermarche.backoffice.domain.order.Product.class);
        org.mockito.Mockito.doThrow(new com.intermarche.backoffice.engine.operator.OperatorFailure(409,"INVENTORY_SCOPE_FROZEN","Comptage en cours")).when(freezes).location(org.mockito.ArgumentMatchers.eq(store),org.mockito.ArgumentMatchers.argThat(l->l!=null&&Objects.equals(l.id,location.id)));
        var choices=locations.choices(store,product,location.locationCode,null);
        var frozen=choices.stream().filter(r->Objects.equals(r.get("id"),location.id)).findFirst().orElseThrow();
        assertEquals(true,frozen.get("frozen"));assertEquals("Comptage en cours",frozen.get("unavailableReason"));
        assertTrue(choices.stream().filter(r->!Objects.equals(r.get("id"),location.id)).allMatch(r->Boolean.FALSE.equals(r.get("frozen"))));
    }
}
