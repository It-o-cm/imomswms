package com.intermarche.e2e.engine.supervision;

import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.stock.StockAccount;
import com.intermarche.backoffice.domain.stock.StockCommitment;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.engine.alerts.StockAvailabilityAlerts;
import com.intermarche.backoffice.engine.supervision.ActivityModel;
import com.intermarche.backoffice.engine.supervision.ActivityModel.Situation;
import com.intermarche.backoffice.engine.supervision.ActivitySupervisionResource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The activity demo scenario is idempotent, keeps the existing stock scenarios and shows every transversal situation:
 * late arrival, blocked shelf replenishment, stock tension, capacity shortfall, saturated buffer and threatened shipment.
 */
@io.quarkus.test.junit.TestProfile(DemoActivityScenarioTest.Isolated.class)
@QuarkusTest
@TestTransaction
@TestSecurity(user = "act-demo-admin", roles = {"ADMIN"})
class DemoActivityScenarioTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_DemoActivityScenarioTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}


    // This fixture proves the original activity scenario. T58's additional
    // operational staffing and documents have their own complete seed test.
    @io.quarkus.test.InjectMock com.intermarche.backoffice.auth.DemoOperationalScenarios operationalScenarios;
    @Inject DemoDataInitializer seed;
    @Inject ActivitySupervisionResource activity;
    @Inject StockAvailabilityAlerts alerts;

    private LocalDateTime previousClock;

    /**
     * Freezes the clock in the morning so that the scenario stays within the store presences.
     */
    @BeforeEach
    void freeze() {
        previousClock = DateTimeProvider.getFixedDateTime();
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2031, 5, 6, 9, 0));
    }

    /**
     * Restores the clock.
     */
    @AfterEach
    void restore() {
        if (previousClock == null) DateTimeProvider.clear();
        else DateTimeProvider.setFixedDateTime(previousClock);
    }

    /**
     * Returns the codes of the commitments of a situation.
     *
     * @param s situation
     * @return order codes
     */
    private Set<String> codes(Situation s) {
        var result = new HashSet<String>();
        for (var c : s.commitments()) result.add(c.code());
        return result;
    }

    /**
     * Finds the situation of a kind.
     *
     * @param r report
     * @param prefix identity prefix
     * @return the situation
     */
    private Situation situation(ActivityModel.Report r, String prefix) {
        return r.situations().stream().filter(s -> s.id().startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError(prefix + " not in " + r.situations().stream().map(Situation::id).toList()));
    }

    /**
     * Seeding twice creates the scenario once without new stock movements, writes no movement on the shelves of the
     * other scenarios, and every transversal situation is visible with its commitments.
     */
    @Test
    void scenarioIsIdempotentAndShowsEveryTransversalSituation() {
        seed.seedDemoData();
        long orders = Order.count("orderId like 'ACT-CMD-%'");
        long handling = BaseHandlingOrder.count("code like 'ACT-%'");
        long movements = StockMovement.count();
        seed.seedDemoData();
        assertEquals(19, orders);
        assertEquals(10, handling);
        assertEquals(orders, Order.count("orderId like 'ACT-CMD-%'"));
        assertEquals(movements, StockMovement.count());
        StoreBuilding shop = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();
        for (var shelf : StoreShelfLocation.<StoreShelfLocation>list("product is not null"))
            assertTrue(StockMovement.count("location = ?1 and eventKey like 'demo-activity-%'", shelf) == 0, "no activity movement on a shop shelf");

        Store store = Store.findByCode("LYO-042");
        var tension = StockCommitment.<StockCommitment>find("reference", "ACT-RESA-TENSION").firstResult();
        alerts.evaluate(tension.account.id);
        var r = activity.report(store.id, null);

        var blocked = r.situations().stream().filter(s -> s.title().equals("Ordre sans employé") && s.operations().stream().anyMatch(o -> o.code().equals("ACT-RAY-BLOQUE")))
                .findFirst().orElseThrow();
        assertEquals(Set.of("ACT-CMD-0101", "ACT-CMD-0102", "ACT-CMD-0103", "ACT-CMD-0104"), codes(blocked));
        assertTrue(blocked.summary().startsWith("4 commandes risquent de manquer leur échéance : le réachalandage ACT-RAY-BLOQUE n’a aucun employé affecté"), blocked.summary());

        var truck = r.situations().stream().filter(s -> s.id().startsWith("LATE:") && s.operations().stream().anyMatch(o -> o.code().equals("ACT-CAM-RETARD")))
                .findFirst().orElseThrow();
        assertEquals(Set.of("ACT-CMD-0201", "ACT-CMD-0202"), codes(truck));
        assertEquals(ActivityModel.Certainty.BLOCKING, truck.certainty());

        var shortage = situation(r, "STOCK_SHORTAGE:" + shop.id);
        assertTrue(codes(shortage).contains("ACT-CMD-0402"));
        assertEquals("DEFINE_REPLENISHMENT", shortage.actions().get(0).code());
        var definition = situation(r, "DEFINITION:");
        assertEquals("ESTIMATED", definition.commitments().stream().filter(c -> c.code().equals("ACT-CMD-0402")).findFirst().orElseThrow().link());
        assertTrue(r.charts().stock().products().stream().anyMatch(p -> p.accountId().equals(tension.account.id) && "TENSION".equals(p.status())),
                "the reservation exhausts the product within the alert horizon");

        var capacity = situation(r, "CAPACITY:");
        assertEquals(Set.of("ACT-CMD-0301", "ACT-CMD-0302"), codes(capacity));
        assertTrue(capacity.operations().stream().anyMatch(o -> o.code().equals("ACT-COL-SOIR")));

        Buffer dock = Buffer.findByCode("ACT-BUF-QUAI");
        assertEquals(Set.of("ACT-CMD-0501"), codes(situation(r, "BUFFER_LOAD:" + dock.id)));
        assertTrue(codes(situation(r, "SHIPPING_LATE")).contains("ACT-CMD-0602"));
        assertEquals(Set.of("ACT-CMD-0603"), codes(situation(r, "SHIPPING_DEPOSIT")));
        assertEquals(Set.of("ACT-CMD-0801"), codes(situation(r, "ROUTING")));
        assertTrue(codes(situation(r, "PLANNING")).contains("ACT-CMD-0901"));
        for (var s : r.situations()) {
            assertFalse(codes(s).contains("ACT-CMD-0601"), "a prepared order waiting for its departure is normal: " + s.id());
            assertFalse(codes(s).contains("ACT-CMD-0902"), "an order to prepare far ahead is normal: " + s.id());
        }
        assertTrue(StockAccount.count() > 0);
    }
}
