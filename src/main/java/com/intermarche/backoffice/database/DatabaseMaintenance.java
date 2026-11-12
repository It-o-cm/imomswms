package com.intermarche.backoffice.database;

import org.flywaydb.core.Flyway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Offline maintenance for H2 file databases; no application startup or seed. Passwords come from the environment. */
public final class DatabaseMaintenance {
 private DatabaseMaintenance(){}
 public static Flyway migration(String url,String user,String password,Path backups){
  return Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration")
   .baselineOnMigrate(true).baselineVersion("1").baselineDescription("Verified legacy schema V1")
   .callbacks(new LegacySchemaCallback().withBackupDirectory(backups)).cleanDisabled(true).outOfOrder(false)
   .validateMigrationNaming(true).validateOnMigrate(true).ignoreMigrationPatterns(new String[0]).load();
 }
 public static void main(String[] args) throws Exception {
  if(args.length<1 || !Set.of("status","migrate","backup","restore").contains(args[0]))throw new IllegalArgumentException("Usage: database.sh status|migrate|backup [directory]|restore <backup-directory>; set DATABASE_URL to an H2 file database");
  String url=System.getenv("DATABASE_URL");
  if(url==null || !url.startsWith("jdbc:h2:file:"))throw new IllegalArgumentException("Offline commands require DATABASE_URL=jdbc:h2:file:... (no connection to an application's in-memory database)");
  String user=System.getenv().getOrDefault("DATABASE_USER","username"),password=System.getenv().getOrDefault("DATABASE_PASSWORD","password");
  Path backups=Path.of(System.getenv().getOrDefault("DATABASE_BACKUP_DIRECTORY","data/backups"));
  if(args[0].equals("migrate")){
   var f=migration(url,user,password,backups);f.migrate();f.validate();System.out.println("Migration verified; schema version "+f.info().current().getVersion());return;
  }
  // Reading/backup must not silently create a missing database.
  String connectionUrl=args[0].equals("restore")?url:url+";IFEXISTS=TRUE";
  try(var c=DriverManager.getConnection(connectionUrl,user,password)){
   switch(args[0]){
    case "backup" -> System.out.println("Backup verified and stored: "+DatabaseBackup.create(c,args.length>1?Path.of(args[1]):backups));
    case "restore" -> {if(args.length!=2)throw new IllegalArgumentException("restore requires one backup directory");DatabaseBackup.restore(c,Path.of(args[1]));System.out.println("Restore verified: all tables, row counts and content fingerprints match");}
    case "status" -> {
     var f=migration(url,user,password,backups);f.validate();
     System.out.println(new ObjectMapper().writeValueAsString(Map.of("version",f.info().current()==null?"unversioned":f.info().current().getVersion().toString(),"tables",SchemaShape.tables(c).size())));
    }
   }
  }
 }
}
