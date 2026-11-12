package com.intermarche.backoffice.database;

import java.sql.*;
import java.util.*;

/** Structural comparison, independent of generated constraint names and sequence positions. */
public final class SchemaShape {
 private SchemaShape(){}
 public static String quote(String s){return "\""+s.replace("\"","\"\"")+"\"";}
 public static List<String> tables(Connection c) throws SQLException {
  var out=new ArrayList<String>();
  try(var r=c.getMetaData().getTables(null,"PUBLIC",null,new String[]{"TABLE","BASE TABLE"})){
   while(r.next())if(!r.getString("TABLE_NAME").equalsIgnoreCase("flyway_schema_history"))out.add(r.getString("TABLE_NAME"));
  }Collections.sort(out);return out;
 }
 public static SortedMap<String,List<String>> describe(Connection c) throws SQLException {
  var result=new TreeMap<String,List<String>>();var md=c.getMetaData();
  for(String table:tables(c)){
   var shape=new ArrayList<String>();
   try(var r=md.getColumns(null,"PUBLIC",table,null)){while(r.next())shape.add("COLUMN:"+r.getString("COLUMN_NAME")+":"+r.getInt("DATA_TYPE")+":"+r.getString("TYPE_NAME")+":"+r.getInt("COLUMN_SIZE")+":"+r.getInt("DECIMAL_DIGITS")+":"+r.getInt("NULLABLE")+":"+r.getString("COLUMN_DEF"));}
   try(var r=md.getPrimaryKeys(null,"PUBLIC",table)){while(r.next())shape.add("PK:"+r.getShort("KEY_SEQ")+":"+r.getString("COLUMN_NAME"));}
   try(var r=md.getImportedKeys(null,"PUBLIC",table)){while(r.next())shape.add("FK:"+r.getString("FKCOLUMN_NAME")+":"+r.getString("PKTABLE_NAME")+":"+r.getString("PKCOLUMN_NAME")+":"+r.getShort("KEY_SEQ")+":"+r.getShort("UPDATE_RULE")+":"+r.getShort("DELETE_RULE"));}
   var unique=new HashMap<String,List<String>>();
   try(var r=md.getIndexInfo(null,"PUBLIC",table,true,false)){while(r.next())if(r.getString("COLUMN_NAME")!=null)unique.computeIfAbsent(r.getString("INDEX_NAME"),k->new ArrayList<>()).add(r.getShort("ORDINAL_POSITION")+":"+r.getString("COLUMN_NAME"));}
   for(var v:unique.values()){Collections.sort(v);shape.add("UNIQUE:"+String.join(",",v));}
   try(var q=c.prepareStatement("select cc.check_clause from information_schema.check_constraints cc join information_schema.table_constraints tc on cc.constraint_schema=tc.constraint_schema and cc.constraint_name=tc.constraint_name where tc.table_schema='PUBLIC' and tc.table_name=?")){
    q.setString(1,table);try(var r=q.executeQuery()){while(r.next())shape.add("CHECK:"+r.getString(1).replaceAll("\\s+"," ").trim());}
   }
   try(var q=c.prepareStatement("select c.column_name,e.value_name from information_schema.columns c join information_schema.enum_values e on e.object_catalog=c.table_catalog and e.object_schema=c.table_schema and e.object_name=c.table_name and e.object_type='TABLE' and e.enum_identifier=c.dtd_identifier where c.table_schema='PUBLIC' and c.table_name=?")){
    q.setString(1,table);try(var r=q.executeQuery()){while(r.next())shape.add("ENUM:"+r.getString(1)+":"+r.getString(2));}
   }
   Collections.sort(shape);result.put(table,shape);
  }
  var sequences=new ArrayList<String>();
  try(var q=c.createStatement();var r=q.executeQuery("select sequence_name,start_value,increment,minimum_value,maximum_value,cycle_option,cache from information_schema.sequences where sequence_schema='PUBLIC' order by sequence_name")){
   while(r.next())sequences.add(r.getString(1)+":"+r.getString(2)+":"+r.getString(3)+":"+r.getString(4)+":"+r.getString(5)+":"+r.getString(6)+":"+r.getString(7));
  }result.put("@SEQUENCES",sequences);return result;
 }
 public static void requireMatch(Connection c,Map<String,List<String>> expected) throws SQLException {
  var actual=describe(c);if(actual.equals(expected))return;
  var different=new TreeSet<String>();different.addAll(actual.keySet());different.addAll(expected.keySet());different.removeIf(k->Objects.equals(actual.get(k),expected.get(k)));
  throw new SQLException("Legacy schema differs from version 1 in: "+String.join(", ",different)+". No baseline applied; restore/upgrade a copy explicitly.");
 }
}
