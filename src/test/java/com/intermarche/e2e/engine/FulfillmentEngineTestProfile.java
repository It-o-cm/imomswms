package com.intermarche.e2e.engine;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile isolating {@link FulfillmentEngineTest} on its own in-memory database.
 * <p>
 * Some tests of {@link FulfillmentEngineTest} must commit data: the CRITICAL event
 * emitted on configuration failure is published in an independent transaction which
 * refetches the Store, so the Store has to be committed. The shared test database
 * must stay clean for the other test classes (their {@code cleanDatabase} performs
 * {@code Store.deleteAll()}), and the background polling consumers must not create
 * WMS handles or events for those committed stores. A dedicated database removes
 * any interference.
 */
public class FulfillmentEngineTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.datasource.jdbc.url", "jdbc:h2:mem:fulfillmentengine;DB_CLOSE_DELAY=-1");
    }
}
