package com.intermarche.e2e.engine.stock;

import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Real independent transactions, not a mocked repository or a shared test transaction. */
@io.quarkus.test.junit.TestProfile(TemporalStockConcurrencyTest.Isolated.class)
@QuarkusTest
class TemporalStockConcurrencyTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_TemporalStockConcurrencyTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

    @Inject TemporalStockService service;
    @Inject EntityManager em;
    record Fixture(Long account,Long building,Long store,Long product) {}
    @Test @TestSecurity(user="stock-admin",roles="ADMIN")
    void simultaneousPromisesCannotOversell() throws Exception {
        Fixture f=QuarkusTransaction.requiringNew().call(() -> {
            Product p=new Product();p.ean="9988776655001";p.name="Concurrent stock";p.productType=ProductType.UNIT;p.temperatureZone=TemperatureZone.AMBIENT;p.persist();
            Store store=new Store();store.code="STOCK-CONCURRENT";store.name="Concurrency";
            StoreBuilding building=new StoreBuilding();building.buildingCode="STOCK-CONCURRENT";
            StoreShelfLocation location=new StoreShelfLocation();location.product=p;location.area="TEST";location.aisle="A1";location.unit="B1";location.elevation="1";location.position="1";location.persist();
            building.locations.add(location);building.persist();store.buildings.add(building);store.persistAndFlush();
            StockAccount a=service.account(store.id,p.id);
            service.adjust(a.id,location.id,"opening",BigDecimal.TEN,DateTimeProvider.now());
            return new Fixture(a.id,building.id,store.id,p.id);
        });
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<2;i++) {
                String ref="order-"+i;
                attempts.add(pool.submit(() -> {
                    ready.countDown();if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    try {
                        QuarkusTransaction.requiringNew().run(() -> service.reserve(f.account,ref,f.building,new BigDecimal("7"),DateTimeProvider.now().plusHours(1),true));
                        return true;
                    } catch(RuntimeException e) {
                        Throwable cause=e;while(cause.getCause()!=null) cause=cause.getCause();
                        if(!(cause instanceof IllegalArgumentException) || !cause.getMessage().contains("Insufficient availability")) throw e;
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            int accepted=0;for(Future<Boolean> attempt:attempts) if(attempt.get(20,TimeUnit.SECONDS)) accepted++;
            assertEquals(1,accepted);
            QuarkusTransaction.requiringNew().run(() -> {
                assertEquals(1,StockCommitment.count("account.id",f.account));
                assertEquals(0,new BigDecimal("3").compareTo(service.availability(f.account,f.building,DateTimeProvider.now()).periods.get(0).availableToPromise));
            });
            var response=given().contentType(ContentType.JSON)
                .body(Map.of("query","{ stockAvailability(accountId: "+f.account+", buildingId: "+f.building+") { physical periods { availableToPromise } } }"))
                .post("/graphql").then().statusCode(200).body("errors",nullValue()).extract().jsonPath();
            assertEquals(0,BigDecimal.TEN.compareTo(new BigDecimal(response.getString("data.stockAvailability.physical"))));
            assertEquals(0,new BigDecimal("3").compareTo(new BigDecimal(response.getString("data.stockAvailability.periods[0].availableToPromise"))));
        } finally {
            pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);
            QuarkusTransaction.requiringNew().run(() -> {
                StockCommitment.delete("account.id",f.account);StockMovement.delete("account.id",f.account);
                ExpectedSupply.delete("account.id",f.account);StockSegment.delete("account.id",f.account);StockAccount.deleteById(f.account);
                em.remove(Store.findById(f.store));em.flush();Product.deleteById(f.product);
            });
        }
    }
    @Test @TestSecurity(user="stock-admin",roles="ADMIN")
    void stockGraphqlOperationsArePublished() {
        given().contentType(ContentType.JSON).body(Map.of("query","{ __schema { queryType { fields { name } } } }"))
            .post("/graphql").then().statusCode(200).body("errors",nullValue())
            .body("data.__schema.queryType.fields.name",hasItems("stockAvailability","stockPositions","stockMovements"));
        given().contentType(ContentType.JSON).body(Map.of("query","{ __schema { mutationType { fields { name } } } }"))
            .post("/graphql").then().statusCode(200).body("errors",nullValue())
            .body("data.__schema.mutationType.fields.name",hasItems("reserveStock","prepareStock","receiveStockSupply","reviseStockCommitment"));
    }
}
