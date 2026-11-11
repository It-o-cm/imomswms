package com.intermarche.e2e.engine.catering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.FabricationStation;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.policy.OperationalPolicyVersion;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.engine.catering.CateringParameters;
import com.intermarche.backoffice.engine.catering.CateringReferentialValidation;
import com.intermarche.backoffice.engine.policy.OperationalPolicySchemas;
import com.intermarche.backoffice.rules.RuleSchemaRegistry;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the catering parameters (§ 6): bounds and enums of the policy schema (E, M),
 * the station/article/variant validators (A, P, V), and the resolution precedence
 * (T0.078 to T0.081).
 */
@QuarkusTest
@TestProfile(CateringParametersTest.Isolated.class)
@TestTransaction
class CateringParametersTest {

    /** Isolated in-memory profile without demo data. */
    public static class Isolated implements QuarkusTestProfile {
        /**
         * Returns the isolated configuration overrides.
         *
         * @return the config overrides.
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url", "jdbc:h2:mem:isolated_CateringParametersTest;DB_CLOSE_DELAY=-1",
                    "quarkus.scheduler.enabled", "false",
                    "imomswms.demo-data.enabled", "false",
                    "imomswms.embeddings.enabled", "false");
        }
    }

    @Inject
    RuleSchemaRegistry registry;
    @Inject
    CateringParameters params;
    @Inject
    ObjectMapper mapper;

    /**
     * Builds a fully-defaulted CateringConstraints value.
     *
     * @return the default policy value.
     */
    private ObjectNode defaults() {
        return OperationalPolicySchemas.defaults("CateringConstraints");
    }

    /**
     * Asserts that a policy value passes schema validation.
     *
     * @param value the policy value.
     */
    private void accepts(ObjectNode value) {
        assertDoesNotThrow(() -> registry.validate(OperationalPolicySchemas.CATEGORY, value, List.of()));
    }

    /**
     * Asserts that a policy value is rejected and the message mentions the parameter.
     *
     * @param value the policy value.
     * @param param the parameter expected in the message.
     */
    private void rejects(ObjectNode value, String param) {
        var e = assertThrows(IllegalArgumentException.class,
                () -> registry.validate(OperationalPolicySchemas.CATEGORY, value, List.of()));
        assertTrue(e.getMessage().contains(param), "message should mention " + param + " but was: " + e.getMessage());
    }

    /**
     * T0.078: each numeric policy parameter is accepted at both bounds and refused just outside.
     */
    @Test
    void numericPolicyParametersAreBounded() {
        record Bound(String key, int min, int max) {
        }
        List<Bound> bounds = List.of(
                new Bound("shelfLifeHours", 1, 720),
                new Bound("maxAdvanceHours", 0, 720),
                new Bound("consumptionMarginHours", 0, 72),
                new Bound("stagingLeadMinutes", 0, 1440),
                new Bound("defaultLaborSeconds", 0, 86400),
                new Bound("proposalHorizonDays", 1, 60),
                new Bound("planningLeadHours", 1, 240),
                new Bound("supplierLeadDays", 0, 30),
                new Bound("labelCopies", 1, 5));
        for (Bound b : bounds) {
            accepts(defaults().put(b.key(), b.min()));
            accepts(defaults().put(b.key(), b.max()));
            rejects(defaults().put(b.key(), b.min() - 1), b.key());
            rejects(defaults().put(b.key(), b.max() + 1), b.key());
        }
    }

    /**
     * T0.079: each enumerated policy parameter refuses an unknown value, and cancelDispositions refuses the empty list.
     */
    @Test
    void enumeratedPolicyParametersRefuseUnknownValues() {
        for (String key : List.of("defaultSourcingMode", "defaultTemperatureZone", "componentShortagePolicy",
                "amendmentAfterStart", "cancelAfterStart")) {
            rejects(defaults().put(key, "BOGUS"), key);
        }
        ObjectNode emptyDisp = defaults();
        emptyDisp.set("cancelDispositions", mapper.createArrayNode());
        rejects(emptyDisp, "cancelDispositions");
        ObjectNode emptyRoles = defaults();
        emptyRoles.set("notificationRoles", mapper.createArrayNode());
        rejects(emptyRoles, "notificationRoles");
    }

    /**
     * T0.080: maxAdvanceHours above shelfLifeHours is refused; opensAt not before closesAt is refused.
     */
    @Test
    void crossFieldBoundsAreEnforced() {
        // Article P-level and the equivalent policy-level check share this helper.
        var tooAdvanced = assertThrows(IllegalArgumentException.class,
                () -> CateringReferentialValidation.maxAdvanceHours(100, 48));
        assertTrue(tooAdvanced.getMessage().contains("maxAdvanceHours"));
        assertDoesNotThrow(() -> CateringReferentialValidation.maxAdvanceHours(24, 48));

        var badHours = assertThrows(IllegalArgumentException.class,
                () -> CateringReferentialValidation.openingHours(LocalTime.of(14, 0), LocalTime.of(10, 0)));
        assertTrue(badHours.getMessage().contains("ouverture"));
        var equalHours = assertThrows(IllegalArgumentException.class,
                () -> CateringReferentialValidation.openingHours(LocalTime.of(10, 0), LocalTime.of(10, 0)));
        assertTrue(equalHours.getMessage().contains("ouverture"));
        assertDoesNotThrow(() -> CateringReferentialValidation.openingHours(LocalTime.of(6, 0), LocalTime.of(14, 0)));
    }

    /**
     * T0.078/T0.079 (A, P, V): station/article/variant validators honour their bounds and enums.
     */
    @Test
    void referentialValidatorsHonourBoundsAndEnums() {
        // Numeric A/P/V bounds.
        assertDoesNotThrow(() -> CateringReferentialValidation.laborSeconds(0));
        assertDoesNotThrow(() -> CateringReferentialValidation.laborSeconds(86400));
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.laborSeconds(86401));
        assertDoesNotThrow(() -> CateringReferentialValidation.maxOrderMinutes(15));
        assertDoesNotThrow(() -> CateringReferentialValidation.maxOrderMinutes(600));
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.maxOrderMinutes(14));
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.maxOrderMinutes(601));
        assertDoesNotThrow(() -> CateringReferentialValidation.dailyMinutes(0));
        assertDoesNotThrow(() -> CateringReferentialValidation.dailyMinutes(10000));
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.dailyMinutes(10001));
        // weekdays.
        assertDoesNotThrow(() -> CateringReferentialValidation.weekdays("1,7"));
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.weekdays("0"));
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.weekdays("8"));
        // cancelDispositions enum + empty.
        assertDoesNotThrow(() -> CateringReferentialValidation.cancelDispositions("REALLOCATE,ISOLATE"));
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.cancelDispositions("BOGUS"));
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.cancelDispositions(""));
        // sourcingMode required.
        assertThrows(IllegalArgumentException.class, () -> CateringReferentialValidation.sourcingMode(null));
        assertDoesNotThrow(() -> CateringReferentialValidation.sourcingMode(SourcingMode.FABRICATED));
    }

    /**
     * T0.081: resolution — schema default without a version, store version, article override,
     * station override, and fallback to the policy when the override is null.
     */
    @Test
    void resolutionAppliesMostSpecificValue() {
        Store store = new Store();
        store.code = "PARAM-01";
        store.name = "Magasin paramètres";
        store.persist();

        Product product = new Product();
        product.ean = "PARAM-EAN-1";
        product.name = "Article test";
        product.productType = ProductType.UNIT;
        product.temperatureZone = TemperatureZone.CHILLED;
        product.active = true;
        product.persist();

        FabricationStation station = new FabricationStation();
        station.store = store;
        station.code = "LAB";
        station.name = "Laboratoire";
        station.active = true;
        station.persist();

        CateringItem item = new CateringItem();
        item.product = product;
        item.sourcingMode = SourcingMode.FABRICATED;
        item.persist();

        // 1. No store version: enseigne (schema) defaults.
        assertTrue(params.enabled(store));
        assertEquals(48, params.shelfLifeHours(store, item));
        assertEquals(60, params.stagingLeadMinutes(store, station));
        assertEquals(SourcingMode.FABRICATED, params.defaultSourcingMode(store));
        assertEquals(List.of("MANAGER"), params.notificationRoles(store));

        // 2. Store version overrides the enseigne default.
        ObjectNode spec = defaults();
        spec.put("shelfLifeHours", 100);
        spec.put("stagingLeadMinutes", 120);
        persistVersion(store, spec);
        assertEquals(100, params.shelfLifeHours(store, item));
        assertEquals(120, params.stagingLeadMinutes(store, station));

        // 3. Article override wins over the store value.
        item.shelfLifeHours = 72;
        item.persistAndFlush();
        assertEquals(72, params.shelfLifeHours(store, item));

        // 4. Station override wins over the store value.
        station.stagingLeadMinutes = 90;
        station.persistAndFlush();
        assertEquals(90, params.stagingLeadMinutes(store, station));

        // 5. Null override falls back to the store value.
        item.shelfLifeHours = null;
        item.persistAndFlush();
        assertEquals(100, params.shelfLifeHours(store, item));
    }

    /**
     * Persists an active CateringConstraints version for a store.
     *
     * @param store the store.
     * @param spec  the policy value.
     */
    private void persistVersion(Store store, ObjectNode spec) {
        try {
            OperationalPolicyVersion version = new OperationalPolicyVersion();
            version.store = store;
            version.kind = "CateringConstraints";
            version.scopeKey = "CateringConstraints";
            version.revisionNumber = 1;
            version.active = true;
            version.activatedBy = "test";
            version.specification = mapper.writeValueAsString(spec);
            version.persistAndFlush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
