package com.intermarche.e2e;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;
public class DemoFixtureProfile implements QuarkusTestProfile {
 public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:demo_fixture_tests;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}
}
