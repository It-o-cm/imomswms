package com.intermarche.e2e.engine;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile disabling the Quarkus Scheduler.
 * <p>
 * The polling consumers ({@code @Scheduled(every = "1s")}) must not run in the background
 * during tests: the tests drive them manually and assert on their effects, and background
 * polling could race with test fixtures (e.g. reserve OrderRequests or Fulfillments
 * created by the test).
 */
public class SchedulerDisabledTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.scheduler.enabled", "false");
    }
}
