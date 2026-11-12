package com.intermarche.backoffice.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.tools.RunScript;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** H2 logical backup; restores only into an empty database and verifies every row afterwards. */
public final class DatabaseBackup {
 private static final ObjectMapper JSON=new ObjectMapper();
 public record TableState(long rows,String sha256){}
 public record Manifest(int format,String createdAt,String databaseVersion,String sqlSha256,SortedMap<String,TableState> contents,SortedMap<String,String> sequences){}
 private DatabaseBackup(){}
 public static String sha(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
 private static List<String> allTables(Connection c) throws SQLException {
  var tables=new ArrayList<>(SchemaShape.tables(c));
  try(var r=c.getMetaData().getTables(null,"PUBLIC",null,new String[]{"TABLE","BASE TABLE"})){while(r.next())if(r.getString("TABLE_NAME").equalsIgnoreCase("flyway_schema_history"))tables.add(r.getString("TABLE_NAME"));}
  Collections.sort(tables);return tables;
 }
 public static SortedMap<String,TableState> contents(Connection c) throws SQLException {
  var out=new TreeMap<String,TableState>();
  for(String table:allTables(c)){
   var rows=new ArrayList<String>();
   try(var q=c.createStatement();var r=q.executeQuery("select * from PUBLIC."+SchemaShape.quote(table))){
    var md=r.getMetaData();
    while(r.next()){
     try(var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes)){
      for(int i=1;i<=md.getColumnCount();i++){
       data.writeInt(md.getColumnType(i));Object v=r.getObject(i);
       if(v==null){data.writeInt(-1);continue;}
       byte[] value=switch(md.getColumnType(i)){
        case Types.BLOB,Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY -> r.getBytes(i);
        default -> r.getString(i).getBytes(StandardCharsets.UTF_8);
       };data.writeInt(value.length);data.write(value);
      }rows.add(sha(bytes.toByteArray()));
     }catch(IOException e){throw new SQLException("Cannot fingerprint backup",e);}
    }
   }
   Collections.sort(rows);out.put(table,new TableState(rows.size(),sha(String.join("\n",rows).getBytes(StandardCharsets.UTF_8))));
  }return out;
 }
 public static SortedMap<String,String> sequences(Connection c) throws SQLException {
  var out=new TreeMap<String,String>();
  try(var s=c.createStatement();var r=s.executeQuery("select sequence_name,base_value from information_schema.sequences where sequence_schema='PUBLIC' order by sequence_name")){while(r.next())out.put(r.getString(1),r.getString(2));}
  return out;
 }
 private static void privateDirectory(Path p) throws IOException {Files.createDirectories(p);try{Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}}
 private static void privateFile(Path p) throws IOException {Files.createFile(p);try{Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
 public static Path create(Connection c,Path directory) throws Exception {
  if(!c.getMetaData().getDatabaseProductName().equals("H2"))throw new IllegalArgumentException("Backup tool currently supports H2 only");
  Path dest=directory.toAbsolutePath().resolve("backup-"+Instant.now().toEpochMilli()+"-"+UUID.randomUUID());privateDirectory(dest);
  var before=contents(c);var sequenceState=sequences(c);Path sql=dest.resolve("snapshot.sql");privateFile(sql);
  try(var q=c.createStatement()){q.execute("SCRIPT TO '"+sql.toString().replace("'","''")+"'");}
  if(!before.equals(contents(c)) || !sequenceState.equals(sequences(c)))throw new SQLException("Database changed during backup. Stop writers and retry; incomplete backup has no manifest.");
  var manifest=new Manifest(1,Instant.now().toString(),c.getMetaData().getDatabaseProductVersion(),sha(Files.readAllBytes(sql)),before,sequenceState);
  Path meta=dest.resolve("manifest.json");privateFile(meta);JSON.writerWithDefaultPrettyPrinter().writeValue(meta.toFile(),manifest);return dest;
 }
 public static Manifest read(Path directory) throws Exception {
  Path dir=directory.toAbsolutePath();var m=JSON.readValue(dir.resolve("manifest.json").toFile(),Manifest.class);
  if(m.format()!=1 || !MessageDigest.isEqual(m.sqlSha256().getBytes(StandardCharsets.US_ASCII),sha(Files.readAllBytes(dir.resolve("snapshot.sql"))).getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException("Backup damaged or modified; restore refused");
  return m;
 }
 public static void restore(Connection target,Path directory) throws Exception {
  Manifest m=read(directory);
  try(var r=target.getMetaData().getTables(null,null,null,new String[]{"TABLE","BASE TABLE","VIEW"})){while(r.next())if(!"INFORMATION_SCHEMA".equals(r.getString("TABLE_SCHEM")))throw new IllegalArgumentException("Restore target must be empty; existing data is never overwritten");}
  try(var input=Files.newBufferedReader(directory.resolve("snapshot.sql"),StandardCharsets.UTF_8)){RunScript.execute(target,input);}
  if(!m.contents().equals(contents(target)) || !m.sequences().equals(sequences(target)))throw new SQLException("Restored data differs from backup. Keep the original database; do not switch to this target.");
 }
}
