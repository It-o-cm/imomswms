package com.intermarche.backoffice.engine.semantic;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;
public class LocalEmbeddingProfile implements QuarkusTestProfile {
 public Map<String,String> getConfigOverrides(){return Map.of("imomswms.embeddings.enabled","true","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false");}
}
