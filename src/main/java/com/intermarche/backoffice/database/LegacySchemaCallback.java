package com.intermarche.backoffice.database;

import org.flywaydb.core.api.callback.*;
import org.flywaydb.core.api.FlywayException;
import org.eclipse.microprofile.config.ConfigProvider;
import org.h2.tools.RunScript;
import java.nio.file.Path;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Automatic adoption is permitted only for the exact frozen V1 schema, with a private backup first. */
public class LegacySchemaCallback implements Callback {
 private Path directory;
 public LegacySchemaCallback(){this.directory=null;}
 public LegacySchemaCallback withBackupDirectory(Path directory){this.directory=directory;return this;}
 @Override public boolean supports(Event e,Context c){return e==Event.BEFORE_BASELINE || e==Event.BEFORE_EACH_MIGRATE;}
 @Override public boolean canHandleInTransaction(Event e,Context c){return false;}
 @Override public String getCallbackName(){return "verify-and-backup-legacy-v1";}
 public static void verify(Connection c) throws Exception {
  if(!c.getMetaData().getDatabaseProductName().equals("H2"))throw new SQLException("Only H2 legacy adoption is defined; configure a reviewed migration for this database");
  try(Connection expected=DriverManager.getConnection("jdbc:h2:mem:reference_"+UUID.randomUUID(),"sa","");
      InputStream source=LegacySchemaCallback.class.getResourceAsStream("/db/migration/V1__initial_schema.sql")){
   if(source==null)throw new SQLException("Missing frozen V1 reference");
   RunScript.execute(expected,new InputStreamReader(source,StandardCharsets.UTF_8));
   SchemaShape.requireMatch(c,SchemaShape.describe(expected));
  }
 }
 @Override public void handle(Event e,Context context){
  try{
   if(e==Event.BEFORE_BASELINE)verify(context.getConnection());
   var content=DatabaseBackup.contents(context.getConnection());
   if(e==Event.BEFORE_EACH_MIGRATE && content.entrySet().stream().noneMatch(x->!x.getKey().equalsIgnoreCase("flyway_schema_history") && x.getValue().rows()>0))return;
   Path backup=DatabaseBackup.create(context.getConnection(),directory!=null?directory:Path.of(ConfigProvider.getConfig().getOptionalValue("imomswms.database.backup-directory",String.class).orElse("data/backups")));
   org.jboss.logging.Logger.getLogger(getClass()).infof("Database checkpoint before %s; private backup: %s",e,backup);
  }catch(Exception failure){throw new FlywayException("Database migration checkpoint refused: "+failure.getMessage(),failure);}
 }
}
