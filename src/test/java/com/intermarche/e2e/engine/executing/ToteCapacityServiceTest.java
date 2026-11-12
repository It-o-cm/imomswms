package com.intermarche.e2e.engine.executing;

import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.engine.executing.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(ToteCapacityServiceTest.Isolated.class)
@QuarkusTest @TestTransaction
class ToteCapacityServiceTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_ToteCapacityServiceTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    @Inject DemoDataInitializer seed;
    @Inject ToteCapacityService service;
    @Inject ToteCapacityResource api;
    record Fixture(ProductCollectingOrder order,LotLine line) {}
    Fixture fixture(String ean) {
        seed.seedDemoData();
        ProductCollectingOrder order=ProductCollectingOrder.find("code","PRO-DEMO-OPT").firstResult();
        LotLine line=order.lots.stream().flatMap(l -> l.lines.stream()).filter(l -> ean.equals(l.product.ean)).findFirst().orElseThrow();
        return new Fixture(order,line);
    }
    ToteCapacityService.Request request(Fixture f,String tote,String qty) {
        var item=new ToteCapacityService.Item();item.sourceOrderId=f.order.id;item.pickedItemId=f.line.id;item.quantity=new BigDecimal(qty);
        var load=new ToteCapacityService.Load();load.code=tote;load.items=List.of(item);
        var request=new ToteCapacityService.Request();request.orderIds=List.of(f.order.id);request.totes=List.of(load);return request;
    }
    void equal(String expected,BigDecimal actual) {assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    @Test void actualToteVolumeDeterminesFillAndOverfill() {
        Fixture f=fixture("3274080005003"); // 1L milk, insulated 60L tote
        var half=service.preview(request(f,"TOTE-1042-03","30")).get(0);
        equal("60",half.capacityLiters);equal("30",half.usedLiters);equal("50",half.fillPercent);assertTrue(half.volumeComplete);assertFalse(half.full);
        var full=service.preview(request(f,"TOTE-1042-03","60")).get(0);assertTrue(full.full);assertFalse(full.overCapacity);
        var over=service.preview(request(f,"TOTE-1042-03","61")).get(0);assertTrue(over.overCapacity);equal("100",over.barPercent);
    }
    @Test void missingVolumeIsReportedInsteadOfInvented() {
        Fixture f=fixture("3017620422003"); // Nutella has weight but no volume
        var result=service.preview(request(f,"TOTE-1042-01","3")).get(0);
        assertFalse(result.volumeComplete);equal("0",result.usedLiters);assertFalse(result.full);
    }
    @Test void bulkVolumeAndWeightUseTheProductBaseUnit() {
        Fixture f=fixture("3245412630003");
        f.line.product.referenceWeight=BigDecimal.ONE;f.line.product.referenceVolume=new BigDecimal("2");
        var result=service.preview(request(f,"TOTE-1042-03","1.25")).get(0);equal("2.5",result.usedLiters);
        f.line.product.productType=ProductType.VOLUME;
        result=service.preview(request(f,"TOTE-1042-03","1.25")).get(0);equal("1.25",result.usedLiters);
    }
    @Test void incompatibleToteIsRejected() {
        Fixture f=fixture("3274080005003");
        assertThrows(IllegalArgumentException.class,() -> service.preview(request(f,"TOTE-1042-01","1")));
    }
    @Test @TestSecurity(user="capacity-manager",roles="MANAGER")
    void managerCannotPreviewAnotherStoresOrders() {
        Fixture f=fixture("3274080005003");Store other=new Store();other.code="CAPACITY-OTHER";other.name="Other";other.persist();
        Employee e=new Employee();e.badgeId="capacity-manager";e.store=other;e.firstName="Other";e.lastName="Manager";e.email="capacity-other@example.test";e.password="x".repeat(60);e.role=Employee.EmployeeRole.MANAGER;e.persistAndFlush();
        assertThrows(ForbiddenException.class,() -> api.preview(request(f,"TOTE-1042-03","1")));
    }
}
