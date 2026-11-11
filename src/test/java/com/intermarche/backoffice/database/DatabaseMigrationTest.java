package com.intermarche.backoffice.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.intermarche.backoffice.database.MigrationTestSupport.*;

class DatabaseMigrationTest {
 @TempDir Path dir;
 String url(){return "jdbc:h2:mem:migration_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";}
 Flyway flyway(String url){return DatabaseMaintenance.migration(url,"sa","",dir.resolve("backups"));}
 Connection open(String url) throws SQLException{return DriverManager.getConnection(url,"sa","");}
 void legacy(Connection c) throws Exception {try(var s=getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql")){RunScript.execute(c,new InputStreamReader(s,StandardCharsets.UTF_8));}}
 void fixture(Connection c) throws Exception {
  try(var s=c.createStatement()){
   s.execute("insert into stores(id,code,name,created_at,updated_at,version) values(101,'MIG-01','Magasin conservé',current_timestamp,current_timestamp,7)");
   s.execute("insert into products(id,ean,name,productType,temperature_zone,is_active,characteristics,reference_weight,created_at,updated_at,version) values(201,'1234567890123','Café témoin','WEIGHT','AMBIENT',true,'Origine française, 0,375 kg',0.375,current_timestamp,current_timestamp,11)");
   s.execute("insert into buildings(id,type,store_id,building_code,plan_image_opacity,plan_image_rotation,plan_image_scale,plan_image_x,plan_image_y,created_at,updated_at) values(301,'STORE',101,'MIG-SHOP',1,0,1,0,0,current_timestamp,current_timestamp)");
   s.execute("insert into item_locations(id,type,building_id,product_id,location_code,created_at,updated_at) values(401,'SHELF',301,201,'A1-B1',current_timestamp,current_timestamp)");
   s.execute("insert into stock_accounts(id,store_id,product_id,safetyMargin,created_at,updated_at) values(501,101,201,1.25,current_timestamp,current_timestamp)");
   boolean segmented;try(var cols=c.getMetaData().getColumns(null,"PUBLIC","STOCK_MOVEMENTS","SEGMENT_ID")){segmented=cols.next();}
   if(segmented)s.execute("insert into stock_segments(id,version,created_at,updated_at,account_id,location_id,quality,segmentKey) values(602,0,current_timestamp,current_timestamp,501,401,'UNASSESSED','401')");
   s.execute("insert into stock_movements(id,account_id,location_id,quantity,eventKey,reason,effectiveAt,created_at,updated_at"+(segmented?",segment_id":"")+") values(601,501,401,27.375001,'migration-fixture','INVENTORY',current_timestamp,current_timestamp,current_timestamp"+(segmented?",602":"")+")");
   s.execute("insert into stock_commitments(id,account_id,building_id,quantity,remainingQuantity,reference,status,preparationAt,created_at,updated_at) values(701,501,301,3.125001,2.125001,'ORDER-01','ACTIVE',current_timestamp,current_timestamp,current_timestamp)");
   s.execute("insert into handling_orders(id,type,code,store_id,status,created_at,updated_at,version) values(801,'REPLENISHMENT','MIG-ORDER',101,'PROCESSING',current_timestamp,current_timestamp,9)");
   s.execute("insert into totes(id,code,volume,tote_type,store_id,created_at,updated_at) values(901,'MIG-TOTE',60,'STANDARD',101,current_timestamp,current_timestamp)");
   s.execute("insert into loaded_totes(id,tote_id,status,storage_type,created_at,updated_at) values(902,901,'IN_USE','AMBIENT',current_timestamp,current_timestamp)");
   s.execute("insert into operator_command_receipts(id,store_id,orderId,performedBy,requestKey,action,fingerprint,result,payload,created_at,updated_at) values(1001,101,801,'demo','stable-request','PICK','abc','{\"status\":\"APPLIED\"}','{\"quantity\":0.375001}',current_timestamp,current_timestamp)");
   s.execute("alter sequence products_SEQ restart with 10051");
  }
 }
 java.util.Map<String,java.util.List<String>> originalColumns=new java.util.HashMap<>();
 SortedMap<String,DatabaseBackup.TableState> business(Connection c)throws Exception{
  var result=new java.util.TreeMap<String,DatabaseBackup.TableState>();
  for(String table:SchemaShape.tables(c)){
   var columns=originalColumns.get(table);if(columns==null){columns=new java.util.ArrayList<>();try(var rs=c.getMetaData().getColumns(null,"PUBLIC",table,null)){while(rs.next())columns.add(rs.getString("COLUMN_NAME"));}originalColumns.put(table,columns);}
   var rows=new java.util.ArrayList<String>();String selected=columns.stream().map(SchemaShape::quote).collect(java.util.stream.Collectors.joining(","));
   try(var statement=c.createStatement();var rs=statement.executeQuery("select "+selected+" from "+SchemaShape.quote(table))){while(rs.next()){var row=new StringBuilder();for(int i=1;i<=columns.size();i++){String value=rs.getString(i);row.append(value==null?-1:value.length()).append(':').append(value).append('|');}rows.add(row.toString());}}
   java.util.Collections.sort(rows);String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n",rows).getBytes(java.nio.charset.StandardCharsets.UTF_8)));result.put(table,new DatabaseBackup.TableState(rows.size(),hash));
  }return result;
 }
 void assertUnchanged(SortedMap<String,DatabaseBackup.TableState> before,Connection c)throws Exception {var after=business(c);for(var entry:before.entrySet())assertEquals(entry.getValue(),after.get(entry.getKey()),entry.getKey());for(var entry:after.entrySet())if(!before.containsKey(entry.getKey())&&!java.util.Set.of("SUPPORT_TYPES","PHYSICAL_SUPPORTS","STOCK_SEGMENTS").contains(entry.getKey().toUpperCase(java.util.Locale.ROOT)))assertEquals(0,entry.getValue().rows(),entry.getKey());
  if(after.keySet().stream().anyMatch(k->k.equalsIgnoreCase("PHYSICAL_SUPPORTS")))try(var rs=c.createStatement().executeQuery("select count(*) from physical_supports s join totes t on s.tote_id=t.id where t.id=901 and s.code='MIG-TOTE' and t.volume=60 and s.store_id=t.store_id")){assertTrue(rs.next());if(before.keySet().stream().noneMatch(k->k.equalsIgnoreCase("PHYSICAL_SUPPORTS")))assertEquals(1,rs.getInt(1));}
 }
 @Test void freshInstallAndRepeatAreNonDestructive() throws Exception {
  String u=url();var f=flyway(u);migratePending(f);
  try(var c=open(u)){assertEquals(150,SchemaShape.tables(c).size());assertCurrentColumns(c);fixture(c);var before=business(c);assertEquals(0,f.migrate().migrationsExecuted);assertUnchanged(before,c);}
  assertThrows(FlywayException.class,f::clean);
 }
 @Test void upgradesPopulatedV1WithoutChangingRowsOrSequences() throws Exception {
  String u=url();Flyway.configure().dataSource(u,"sa","").locations("db/migration").target("1").load().migrate();
  try(var c=open(u)){fixture(c);var before=business(c);migratePending(flyway(u));assertUnchanged(before,c);
   try(var s=c.createStatement();var r=s.executeQuery("select next value for products_SEQ")){assertTrue(r.next());assertEquals(10051,r.getLong(1));}}
 }
 @Test void segmentBackfillAllocatesDistinctIdsAcrossManyAccountsAndAddresses()throws Exception{
  String u=url();Flyway.configure().dataSource(u,"sa","").locations("db/migration").target("6").load().migrate();
  try(var c=open(u)){fixture(c);try(var statement=c.createStatement()){
   statement.execute("insert into item_locations(id,type,building_id,product_id,location_code,created_at,updated_at) values(402,'SHELF',301,201,'A1-B2',current_timestamp,current_timestamp)");
   statement.execute("insert into products(id,ean,name,productType,temperature_zone,is_active,created_at,updated_at) values(202,'1234567890124','Second produit','UNIT','AMBIENT',true,current_timestamp,current_timestamp)");
   statement.execute("insert into stock_accounts(id,store_id,product_id,safetyMargin,created_at,updated_at) values(502,101,202,0,current_timestamp,current_timestamp)");
   statement.execute("insert into item_locations(id,type,building_id,product_id,location_code,created_at,updated_at) values(403,'SHELF',301,202,'A2-B1',current_timestamp,current_timestamp)");
   statement.execute("insert into stock_movements(id,account_id,location_id,quantity,eventKey,reason,effectiveAt,created_at,updated_at) values(602,501,402,4,'second-address','INVENTORY',current_timestamp,current_timestamp,current_timestamp),(603,502,403,8,'second-product','INVENTORY',current_timestamp,current_timestamp,current_timestamp),(604,501,401,-2,'same-address','SALE',current_timestamp,current_timestamp,current_timestamp)");
  }var before=business(c);migratePending(flyway(u));assertUnchanged(before,c);
   try(var rows=c.createStatement().executeQuery("select count(*),count(distinct id) from stock_segments")){assertTrue(rows.next());assertEquals(3,rows.getInt(1));assertEquals(3,rows.getInt(2));}
   try(var rows=c.createStatement().executeQuery("select count(*) from stock_movements m join stock_segments s on m.segment_id=s.id where m.account_id=s.account_id and m.location_id=s.location_id")){assertTrue(rows.next());assertEquals(4,rows.getInt(1));}
  }
 }
 @Test void adoptsOnlyVerifiedLegacyWithBackupAndPreservesAllRows() throws Exception {
  String u=url();try(var c=open(u)){legacy(c);fixture(c);var before=business(c);flyway(u).migrate();assertUnchanged(before,c);}
  try(var backups=Files.list(dir.resolve("backups"))){Path backup=backups.findFirst().orElseThrow();DatabaseBackup.read(backup);assertTrue(Files.size(backup.resolve("snapshot.sql"))>1000);}
  assertFullyMigrated(flyway(u));
 }
 @Test void v15BackfillsOnlyProvenPicksAndNeverInventsHistoricalReportQuantities() throws Exception {
  String u=url();Flyway.configure().dataSource(u,"sa","").locations("classpath:db/migration").target("14").load().migrate();
  try(var c=open(u)){fixture(c);try(var stmt=c.createStatement()){stmt.execute("update operator_command_receipts set activity_quantity=0.375001,activity_unit='KG',activity_line_id=701,activity_product_id=201 where id=1001");}}
  flyway(u).migrate();
  try(var c=open(u);var rows=c.createStatement().executeQuery("select quantity,unit,actor,metric,receipt_id from activity_quantities")){assertTrue(rows.next());assertEquals(0,new java.math.BigDecimal("0.375001").compareTo(rows.getBigDecimal(1)));assertEquals("KG",rows.getString(2));assertEquals("demo",rows.getString(3));assertEquals("PICKED",rows.getString(4));assertEquals(1001,rows.getLong(5));assertFalse(rows.next());}
  assertEquals(0,flyway(u).migrate().migrationsExecuted);
 }
 @Test void unknownLegacySchemaIsRefusedWithoutLosingData() throws Exception {
  String u=url();try(var c=open(u)){try(var s=c.createStatement()){s.execute("create table retained(id int primary key, label varchar(40))");s.execute("insert into retained values(1,'keep me')");}
   assertThrows(FlywayException.class,()->flyway(u).migrate());try(var r=c.createStatement().executeQuery("select label from retained where id=1")){assertTrue(r.next());assertEquals("keep me",r.getString(1));}}
 }
 @Test void backupRestoresRowsRelationsAndSequencesIntoAnotherDatabase() throws Exception {
  String u=url();flyway(u).migrate();Path backup;SortedMap<String,DatabaseBackup.TableState> before;
  try(var c=open(u)){fixture(c);before=DatabaseBackup.contents(c);backup=DatabaseBackup.create(c,dir.resolve("backups"));}
  String restored=url();try(var c=open(restored)){DatabaseBackup.restore(c,backup);assertEquals(before,DatabaseBackup.contents(c));
   try(var r=c.createStatement().executeQuery("select next value for products_SEQ")){assertTrue(r.next());assertEquals(10051,r.getLong(1));}}
  flyway(restored).validate();assertEquals(0,flyway(restored).migrate().migrationsExecuted);
 }
 @Test void corruptBackupAndNonEmptyRestoreAreRefused() throws Exception {
  String u=url();flyway(u).migrate();Path backup;
  try(var c=open(u)){fixture(c);backup=DatabaseBackup.create(c,dir);var before=DatabaseBackup.contents(c);assertThrows(IllegalArgumentException.class,()->DatabaseBackup.restore(c,backup));assertEquals(before,DatabaseBackup.contents(c));}
  Files.writeString(backup.resolve("snapshot.sql"),"\nmodified",StandardOpenOption.APPEND);
  try(var c=open(url())){assertThrows(IllegalArgumentException.class,()->DatabaseBackup.restore(c,backup));assertTrue(SchemaShape.tables(c).isEmpty());}
 }
 @Test void modifiedAppliedMigrationIsRejected() throws Exception {
  Path migrations=dir.resolve("migrations");Files.createDirectories(migrations);
  for(String name:List.of("V1__initial_schema.sql","V2__operator_receipt_lookup.sql"))try(var s=getClass().getResourceAsStream("/db/migration/"+name)){Files.copy(s,migrations.resolve(name));}
  String u=url();var f=Flyway.configure().dataSource(u,"sa","").locations("filesystem:"+migrations).cleanDisabled(true).load();f.migrate();
  Path v1=migrations.resolve("V1__initial_schema.sql");Files.writeString(v1,Files.readString(v1).replace("characteristics TEXT","characteristics varchar(50000)"));
  assertThrows(FlywayException.class,f::migrate);
 }
 @Test void fileDatabaseSurvivesClosingEveryConnectionAndRestores() throws Exception {
  String source="jdbc:h2:file:"+dir.resolve("persistent").toAbsolutePath();
  flyway(source).migrate();SortedMap<String,DatabaseBackup.TableState> before;Path backup;
  try(var c=open(source)){fixture(c);before=DatabaseBackup.contents(c);}
  try(var c=open(source)){assertEquals(before,DatabaseBackup.contents(c));backup=DatabaseBackup.create(c,dir.resolve("offline"));}
  String target="jdbc:h2:file:"+dir.resolve("restored").toAbsolutePath();
  try(var c=open(target)){DatabaseBackup.restore(c,backup);}
  try(var c=open(target)){assertEquals(before,DatabaseBackup.contents(c));}
  assertEquals(0,flyway(target).migrate().migrationsExecuted);
 }
 @Test void alteredKnownLegacyColumnIsRefusedBeforeBaseline() throws Exception {
  String u=url();try(var c=open(u)){legacy(c);fixture(c);
   c.createStatement().execute("alter table products add column unexpected varchar(20)");
   var before=business(c);assertThrows(FlywayException.class,()->flyway(u).migrate());assertUnchanged(before,c);
  }
 }

}
