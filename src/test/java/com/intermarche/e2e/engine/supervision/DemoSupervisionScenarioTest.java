package com.intermarche.e2e.engine.supervision;

import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.engine.supervision.HandlingSupervisionResource;
import com.intermarche.backoffice.engine.supervision.SupervisionModel.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The supervision demo scenario is idempotent, keeps the existing stock scenarios and shows every situation family.
 */
@io.quarkus.test.junit.TestProfile(DemoSupervisionScenarioTest.Isolated.class)
@QuarkusTest
@TestTransaction
@TestSecurity(user = "sup-demo-admin", roles = {"ADMIN"})
class DemoSupervisionScenarioTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_DemoSupervisionScenarioTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}


    @Inject DemoDataInitializer seed;
    @Inject HandlingSupervisionResource supervision;

    private LocalDateTime previousClock;

    /**
     * Freezes the clock in the morning so that the scenario stays within one day.
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
     * Finds the anomaly of a type for an order code.
     *
     * @param r report
     * @param type type
     * @param code order code
     * @return the anomaly
     */
    private Anomaly card(Report r, Type type, String code) {
        return r.anomalies().stream().filter(a -> a.type() == type && a.orders().stream().anyMatch(o -> o.code().equals(code)))
                .findFirst().orElseThrow(() -> new AssertionError(type + " " + code + " not in " + r.anomalies().stream().map(Anomaly::id).toList()));
    }

    /**
     * Seeding twice creates the scenario once, without new stock movements, and every situation family is visible.
     */
    @Test
    void scenarioIsIdempotentAndShowsEverySituation() {
        seed.seedDemoData();
        long orders = BaseHandlingOrder.count("code like 'SUP-%'");
        long movements = StockMovement.count();
        seed.seedDemoData();
        assertEquals(10, orders);
        assertEquals(orders, BaseHandlingOrder.count("code like 'SUP-%'"));
        assertEquals(movements, StockMovement.count());

        Store store = Store.findByCode("LYO-042");
        var day = DateTimeProvider.now().toLocalDate();
        var r = supervision.report(store.id, day.atStartOfDay().toString(), day.plusDays(1).atStartOfDay().toString());
        var truck = card(r, Type.LATE, "SUP-CAM-RETARD");
        assertEquals(Severity.CRITICAL, truck.severity());
        assertTrue(truck.consequences().stream().anyMatch(o -> o.code().equals("SUP-RAY-ATTENTE")));
        assertEquals(Certainty.BLOCKING, card(r, Type.LATE, "SUP-RAY-RETARD").certainty());
        assertFalse(card(r, Type.UNASSIGNED, "SUP-RAY-SANS").proposals().isEmpty());
        assertEquals(Certainty.RISK, card(r, Type.ASSIGNMENT, "SUP-RAY-ABSENT").certainty());
        assertEquals(Certainty.MISSING_DATA, card(r, Type.ASSIGNMENT, "SUP-RAY-NOHOR").certainty());
        assertEquals(Certainty.BLOCKING, card(r, Type.TRANSIT, "SUP-RAY-TRANSIT").certainty());
        assertEquals(Certainty.BLOCKING, card(r, Type.STOCK, "SUP-RAY-MANQUE").certainty());
        var forecast = card(r, Type.STOCK, "SUP-RAY-PREVU");
        assertEquals(Certainty.RISK, forecast.certainty());
        assertTrue(forecast.products().stream().anyMatch(p -> "UNKNOWN".equals(p.state())));
        assertTrue(truck.impact().contains("SUP-RAY-ATTENTE attend ces quantités"), truck.impact());
        assertTrue(r.anomalies().stream().noneMatch(a -> a.order().code().equals("SUP-RAY-ATTENTE")), "the waiting shelf replenishment is only a consequence of the late truck");
        var buffer = card(r, Type.BUFFER, "SUP-GRP-BUFFER");
        assertEquals("Buffer de destination désactivé", buffer.title());
        assertFalse(buffer.proposals().isEmpty());
    }
}
