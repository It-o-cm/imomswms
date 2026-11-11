package com.intermarche.backoffice.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code V30__catering.sql} applies on an empty base and on a base
 * populated at V29 without changing existing products, orders, chunks or shortages
 * (§ 11.1, {@code CateringMigrationTest}).
 */
class CateringMigrationTest {

    /** Temporary directory used for Flyway backups. */
    @TempDir
    Path dir;

    /**
     * Builds a unique in-memory H2 URL.
     *
     * @return a fresh JDBC URL.
     */
    String url() {
        return "jdbc:h2:mem:catering_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    /**
     * Builds the production Flyway configuration for a URL.
     *
     * @param url the JDBC URL.
     * @return a configured Flyway instance.
     */
    Flyway flyway(String url) {
        return DatabaseMaintenance.migration(url, "sa", "", dir.resolve("backups"));
    }

    /**
     * Opens a JDBC connection.
     *
     * @param url the JDBC URL.
     * @return an open connection.
     * @throws SQLException on failure.
     */
    Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    /**
     * Tests whether a table exists in the PUBLIC schema.
     *
     * @param c     the connection.
     * @param table the table name.
     * @return true if the table exists.
     * @throws SQLException on failure.
     */
    boolean hasTable(Connection c, String table) throws SQLException {
        try (var rs = c.getMetaData().getTables(null, "PUBLIC", table.toUpperCase(Locale.ROOT), null)) {
            return rs.next();
        }
    }

    /**
     * Tests whether a column exists on a table.
     *
     * @param c      the connection.
     * @param table  the table name.
     * @param column the column name.
     * @return true if the column exists.
     * @throws SQLException on failure.
     */
    boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (var rs = c.getMetaData().getColumns(null, "PUBLIC", table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT))) {
            return rs.next();
        }
    }

    /**
     * V30 applies on an empty base: the new tables and columns exist, the handling-order
     * type check accepts FABRICATION and rejects an unknown type, and the EAN is widened.
     *
     * @throws Exception on failure.
     */
    @Test
    void v30AppliesOnEmptyBase() throws Exception {
        String u = url();
        flyway(u).migrate();
        try (var c = open(u)) {
            for (String t : List.of("catering_items", "catering_item_stores", "catering_recipes",
                    "catering_recipe_lines", "fabrication_stations", "fabrication_station_employees",
                    "catering_units", "fabrication_lines", "fabrication_line_units",
                    "fabrication_consumptions", "catering_purchase_needs", "catering_amendments")) {
                assertTrue(hasTable(c, t), "table missing: " + t);
            }
            assertTrue(hasColumn(c, "handling_orders", "station_id"));
            assertTrue(hasColumn(c, "handling_orders", "productionDate"));
            assertTrue(hasColumn(c, "handling_orders", "proposalState"));
            assertTrue(hasColumn(c, "handling_orders", "proposedEmployee_id"));
            assertTrue(hasColumn(c, "handling_orders", "plannedMinutes"));
            assertTrue(hasColumn(c, "orders", "payment_method"));
            assertTrue(hasColumn(c, "orders", "payment_acknowledged_at"));
            assertTrue(hasColumn(c, "fulfillment_chunks", "catering"));
            assertTrue(hasColumn(c, "picked_items", "fabrication_execution_id"));
            assertTrue(hasColumn(c, "loaded_totes", "fabrication_execution_id"));
            try (var rs = c.getMetaData().getColumns(null, "PUBLIC", "PRODUCTS", "EAN")) {
                assertTrue(rs.next());
                assertEquals(40, rs.getInt("COLUMN_SIZE"));
            }
            try (var s = c.createStatement()) {
                s.execute("insert into stores(id,code,name,created_at,updated_at,version) values(101,'CAT-01','Magasin',current_timestamp,current_timestamp,0)");
                s.execute("insert into handling_orders(id,type,code,store_id,status,proposalState,created_at,updated_at,version) values(5001,'FABRICATION','CAT-FAB',101,'OPEN','PROPOSED',current_timestamp,current_timestamp,0)");
            }
            try (var rs = c.createStatement().executeQuery("select type from handling_orders where id=5001")) {
                assertTrue(rs.next());
                assertEquals("FABRICATION", rs.getString(1));
            }
            try (var s = c.createStatement()) {
                assertThrows(SQLException.class, () -> s.execute("insert into handling_orders(id,type,code,store_id,status,created_at,updated_at,version) values(5002,'BOGUS','CAT-X',101,'OPEN',current_timestamp,current_timestamp,0)"));
            }
        }
    }

    /**
     * V30 applies on a base populated at V29 and preserves existing products, orders,
     * chunks and shortages; the new chunk flag defaults to false and the shortage
     * handling order becomes optional.
     *
     * @throws Exception on failure.
     */
    @Test
    void v30PreservesDataPopulatedAtV29() throws Exception {
        String u = url();
        Flyway.configure().dataSource(u, "sa", "").locations("db/migration").target("29").load().migrate();
        try (var c = open(u)) {
            try (var s = c.createStatement()) {
                s.execute("insert into stores(id,code,name,created_at,updated_at,version) values(101,'CAT-01','Magasin',current_timestamp,current_timestamp,0)");
                s.execute("insert into products(id,ean,name,productType,temperature_zone,is_active,created_at,updated_at,version) values(201,'1234567890123','Produit témoin','UNIT','AMBIENT',true,current_timestamp,current_timestamp,3)");
                s.execute("insert into buildings(id,type,store_id,building_code,plan_image_opacity,plan_image_rotation,plan_image_scale,plan_image_x,plan_image_y,created_at,updated_at) values(301,'STORE',101,'CAT-SHOP',1,0,1,0,0,current_timestamp,current_timestamp)");
                s.execute("insert into item_locations(id,type,building_id,product_id,location_code,created_at,updated_at) values(401,'SHELF',301,201,'A1-B1',current_timestamp,current_timestamp)");
                s.execute("insert into employees(id,store_id,badge_id,email,first_name,last_name,password,role,is_active,created_at,updated_at,version) values(1101,101,'B-900','b900@demo','Jean','Test','x','PICKER',true,current_timestamp,current_timestamp,0)");
                s.execute("insert into orders(id,order_id,order_date,store_id,channel,status,created_at,updated_at,version) values(3001,'CAT-ORDER-1',current_timestamp,101,'ECOMMERCE','VALIDATED',current_timestamp,current_timestamp,2)");
                s.execute("insert into fulfillment_chunks(id,type,label,created_at,updated_at,version) values(1201,'READY','Default',current_timestamp,current_timestamp,0)");
                s.execute("insert into handling_orders(id,type,code,store_id,status,created_at,updated_at,version) values(801,'REPLENISHMENT','CAT-ORD',101,'PROCESSING',current_timestamp,current_timestamp,9)");
                s.execute("insert into lot_lines(id,location_id,product_id,created_at,updated_at,version) values(1301,401,201,current_timestamp,current_timestamp,0)");
                s.execute("insert into picking_shortages(id,quantity,employee_id,handlingOrder_id,lotLine_id,requestedProduct_id,reason,created_at,updated_at,version) values(1401,2.5,1101,801,1301,201,'Rupture',current_timestamp,current_timestamp,0)");
            }
            assertTrue(flyway(u).info().pending().length > 0, "V30 must be pending");
            flyway(u).migrate();

            try (var rs = c.createStatement().executeQuery("select ean from products where id=201")) {
                assertTrue(rs.next());
                assertEquals("1234567890123", rs.getString(1));
            }
            try (var rs = c.createStatement().executeQuery("select order_id from orders where id=3001")) {
                assertTrue(rs.next());
                assertEquals("CAT-ORDER-1", rs.getString(1));
            }
            try (var rs = c.createStatement().executeQuery("select type from handling_orders where id=801")) {
                assertTrue(rs.next());
                assertEquals("REPLENISHMENT", rs.getString(1));
            }
            try (var rs = c.createStatement().executeQuery("select reason from picking_shortages where id=1401")) {
                assertTrue(rs.next());
                assertEquals("Rupture", rs.getString(1));
            }
            try (var rs = c.createStatement().executeQuery("select catering from fulfillment_chunks where id=1201")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1));
            }
            try (var s = c.createStatement()) {
                s.execute("insert into picking_shortages(id,quantity,employee_id,handlingOrder_id,lotLine_id,requestedProduct_id,reason,created_at,updated_at,version) values(1402,1.0,1101,null,1301,201,'Manager hors ordre',current_timestamp,current_timestamp,0)");
            }
            try (var rs = c.createStatement().executeQuery("select handlingOrder_id from picking_shortages where id=1402")) {
                assertTrue(rs.next());
                rs.getObject(1);
                assertTrue(rs.wasNull(), "handlingOrder_id must be nullable after V30");
            }
            try (var s = c.createStatement()) {
                s.execute("insert into products(id,ean,name,productType,temperature_zone,is_active,created_at,updated_at,version) values(202,'DEMO-TRA-PAIN-SURPRISE-0123456789','Pain surprise','UNIT','CHILLED',true,current_timestamp,current_timestamp,0)");
            }
            try (var rs = c.createStatement().executeQuery("select length(ean) from products where id=202")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 13, "EAN column must accept more than 13 characters");
            }
        }
    }
}
