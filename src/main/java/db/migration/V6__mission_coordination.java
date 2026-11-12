package db.migration;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** H2 generated check names differ on adopted databases: locate only the two discriminator checks. */
public class V6__mission_coordination extends BaseJavaMigration {
 private String source(){try(var in=getClass().getResourceAsStream("/db/upgrades/mission_coordination.sql")){return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
 @Override public Integer getChecksum(){return source().hashCode();}
 @Override public void migrate(Context context)throws Exception{
  var c=context.getConnection();
  try(var q=c.prepareStatement("select t.table_name,t.constraint_name,c.check_clause from information_schema.table_constraints t join information_schema.check_constraints c on t.constraint_schema=c.constraint_schema and t.constraint_name=c.constraint_name where t.constraint_schema='PUBLIC' and t.table_name in ('HANDLING_ORDERS','HANDLING_EXECUTIONS') and t.constraint_type='CHECK'");var rows=q.executeQuery()){
   var drops=new ArrayList<String>();while(rows.next()){String clause=rows.getString(3).toUpperCase(Locale.ROOT);if(clause.contains(" IN(")||clause.contains(" IN (")){if(clause.contains("\"TYPE\"")||clause.contains("\"EXECUTION_TYPE\""))drops.add("alter table \""+rows.getString(1)+"\" drop constraint \""+rows.getString(2)+"\"");}}
   try(var statement=c.createStatement()){for(var sql:drops)statement.execute(sql);}
  }
  try(var statement=c.createStatement()){
   for(var sql:source().split(";"))if(!sql.isBlank())statement.execute(sql);
   statement.execute("alter table handling_orders add constraint ck_handling_order_type_v6 check (type in ('ZONE_COLLECTING','PRODUCT_COLLECTING','PACKING','GROUPING','STOCK_PROTECTION','REPLENISHMENT','CANCELLATION_DISPOSITION'))");
   statement.execute("alter table handling_executions add constraint ck_handling_execution_type_v6 check (execution_type in ('REPLENISHMENT','GROUPING','PICKING','CANCELLATION_DISPOSITION'))");
  }
 }
}
