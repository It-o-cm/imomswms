package com.intermarche.backoffice.database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class H2AsyncStorageTest {
 @TempDir Path dir;
 @Test void readsTheSameDatabaseFileAndSurvivesAnInterruptedReader()throws Exception{
  String file=dir.resolve("interrupt-check").toString(),async="jdbc:h2:async:"+file+";CACHE_SIZE=16";
  try(var c=DriverManager.getConnection("jdbc:h2:file:"+file,"sa","");var s=c.createStatement()){s.execute("create table specimen(id int primary key,payload varchar)");s.execute("insert into specimen select x,repeat('a',4000) from system_range(1,2000)");}
  var failure=new AtomicReference<Throwable>();Thread reader=new Thread(()->{try(var c=DriverManager.getConnection(async,"sa","");var s=c.createStatement()){Thread.currentThread().interrupt();try(var rs=s.executeQuery("select sum(length(payload)) from specimen")){assertTrue(rs.next());assertEquals(8000000,rs.getLong(1));}catch(SQLException cancelled){/* A caller may be cancelled; the shared database must remain usable. */}finally{Thread.interrupted();}}catch(Throwable e){failure.set(e);}});
  reader.start();reader.join(15000);assertFalse(reader.isAlive());assertNull(failure.get());
  try(var c=DriverManager.getConnection(async,"sa","");var s=c.createStatement();var rows=s.executeQuery("select count(*),sum(length(payload)) from specimen")){assertTrue(rows.next());assertEquals(2000,rows.getInt(1));assertEquals(8000000,rows.getLong(2));}
  try(var c=DriverManager.getConnection("jdbc:h2:file:"+file,"sa","");var s=c.createStatement();var rows=s.executeQuery("select count(*) from specimen")){assertTrue(rows.next());assertEquals(2000,rows.getInt(1));}
 }
}
