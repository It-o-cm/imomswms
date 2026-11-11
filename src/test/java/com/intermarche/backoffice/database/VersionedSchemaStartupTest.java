package com.intermarche.backoffice.database;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.intermarche.backoffice.database.MigrationTestSupport.*;

@QuarkusTest
class VersionedSchemaStartupTest {
 @Inject org.flywaydb.core.Flyway flyway;
 @Inject DataSource dataSource;
 @Test void applicationBootsOnMigratedSchemaAndHibernateValidation() throws Exception {
  assertFullyMigrated(flyway);
  try(var c=dataSource.getConnection()){assertEquals(150,SchemaShape.tables(c).size());assertCurrentColumns(c);}
 }
}
