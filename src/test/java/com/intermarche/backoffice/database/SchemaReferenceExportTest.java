package com.intermarche.backoffice.database;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit, isolated reference export. Never used by the application to update its schema. */
@QuarkusTest
@TestProfile(SchemaReferenceExportTest.Export.class)
public class SchemaReferenceExportTest {
 public static class Export implements QuarkusTestProfile {
  public Map<String,String> getConfigOverrides(){return Map.of(
   "quarkus.datasource.jdbc.url","jdbc:h2:mem:schema_reference;DB_CLOSE_DELAY=-1",
   "quarkus.hibernate-orm.schema-management.strategy","drop-and-create",
   "quarkus.hibernate-orm.scripts.generation","create",
   "quarkus.hibernate-orm.scripts.generation.create-target","target/schema-reference.sql",
   "quarkus.flyway.migrate-at-start","false",
   "quarkus.flyway.validate-at-start","false",
   "quarkus.scheduler.enabled","false",
   "imomswms.demo-data.enabled","false");}
 }
 @Test void exportsReference() throws Exception {assertTrue(Files.size(Path.of("target/schema-reference.sql"))>1000);}
}
