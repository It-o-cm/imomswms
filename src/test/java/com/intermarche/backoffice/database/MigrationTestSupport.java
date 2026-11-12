package com.intermarche.backoffice.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import java.sql.Connection;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Follow the resolved migration catalogue without weakening schema/data checks. */
final class MigrationTestSupport {
 private MigrationTestSupport() {}
 static void migratePending(Flyway flyway) {
  int pending=flyway.info().pending().length;
  assertTrue(pending>0,"The fixture must actually exercise a schema upgrade");
  assertEquals(pending,flyway.migrate().migrationsExecuted);
  assertFullyMigrated(flyway);
 }
 static void assertFullyMigrated(Flyway flyway) {
  flyway.validate();
  assertEquals(0,flyway.info().pending().length,"Every resolved migration must be applied");
  MigrationVersion latest=Arrays.stream(flyway.info().all()).map(m->m.getVersion())
   .filter(java.util.Objects::nonNull).max(MigrationVersion::compareTo).orElseThrow();
  assertNotNull(flyway.info().current());
  assertEquals(latest,flyway.info().current().getVersion());
 }
 static void assertCurrentColumns(Connection connection)throws Exception {
  for(String column:java.util.List.of("HAZARDCLASS","SEPARATIONGROUP","INCOMPATIBLEHAZARDS"))
   try(var rows=connection.getMetaData().getColumns(null,"PUBLIC","TRACEABILITY_POLICIES",column)){assertTrue(rows.next(),"V17 column missing: "+column);}
  for(String column:java.util.List.of("ANNOUNCEDQUANTITY","OBSERVEDQUANTITY"))
   try(var rows=connection.getMetaData().getColumns(null,"PUBLIC","FIELD_INCIDENTS",column)){assertTrue(rows.next(),"V18 column missing: "+column);}
 }
}
