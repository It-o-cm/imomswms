package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProductCollectingOrderManagerFactory}.
 * Covers factory naming, JSON schema validation, min/max capacity validation,
 * and the {@code GroupKey} / {@code OrderMetrics} helper classes.
 */
class ProductCollectingOrderManagerFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductCollectingOrderManagerFactory factory = new ProductCollectingOrderManagerFactory();

    private JsonNode spec(String json) throws Exception {
        return mapper.readTree(json);
    }

    // --------------------------------------------------
    // getName
    // --------------------------------------------------

    @Test
    void getName_returnsProductCollecting() {
        assertEquals("ProductCollecting", factory.getName());
    }

    // --------------------------------------------------
    // build - valid configurations
    // --------------------------------------------------

    @Test
    void build_minimalSpec_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"buildingCode":"B1","eans":["EAN-1","EAN-2"]}
                """));
        assertNotNull(builder);
        assertInstanceOf(ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.class, builder);
    }

    @Test
    void build_fullSpec_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"name":"pc-1","buildingCode":"B1","eans":["EAN-1"],
                 "quantityMin":1,"quantityMax":100,"weightMin":0.5,"weightMax":50.0,
                 "volumeMin":1.0,"volumeMax":200.0,"validityDelayMinutes":30,"groupingWindowMinutes":60}
                """));
        assertInstanceOf(ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.class, builder);
    }

    @Test
    void build_minWithoutMax_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"buildingCode":"B1","eans":["EAN-1"],"quantityMin":5}
                """));
        assertInstanceOf(ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.class, builder);
    }

    @Test
    void build_maxWithoutMin_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"buildingCode":"B1","eans":["EAN-1"],"quantityMax":5}
                """));
        assertInstanceOf(ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.class, builder);
    }

    // --------------------------------------------------
    // build - schema validation
    // --------------------------------------------------

    @Test
    void build_missingBuildingCode_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"eans\":[\"EAN-1\"]}")));
    }

    @Test
    void build_missingEans_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\"}")));
    }

    @Test
    void build_emptySpec_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{}")));
    }

    @Test
    void build_eansNotArray_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"eans\":\"EAN-1\"}")));
    }

    @Test
    void build_eansWithNonStringItem_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"eans\":[123]}")));
    }

    @Test
    void build_zeroQuantityMin_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"eans\":[\"E\"],\"quantityMin\":0}")));
    }

    // --------------------------------------------------
    // build - min/max validation
    // --------------------------------------------------

    @Test
    void build_quantityMinGreaterThanMax_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"eans\":[\"E\"],\"quantityMin\":10,\"quantityMax\":5}")));
    }

    @Test
    void build_weightMinGreaterThanMax_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"eans\":[\"E\"],\"weightMin\":10.5,\"weightMax\":5.5}")));
    }

    @Test
    void build_volumeMinGreaterThanMax_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"eans\":[\"E\"],\"volumeMin\":10.0,\"volumeMax\":5.0}")));
    }

    @Test
    void build_quantityMinEqualsMax_returnsManager() throws Exception {
        var builder = factory.build(spec("{\"buildingCode\":\"B1\",\"eans\":[\"E\"],\"quantityMin\":5,\"quantityMax\":5}"));
        assertInstanceOf(ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.class, builder);
    }

    // --------------------------------------------------
    // parseConfiguration (via reflection: the JSON schema requires
    // "eans" as an array, so the defensive branches below are only
    // reachable by calling the parser directly)
    // --------------------------------------------------

    private Object invokeParseConfiguration(JsonNode spec) throws Exception {
        java.lang.reflect.Method method =
                ProductCollectingOrderManagerFactory.class.getDeclaredMethod("parseConfiguration", JsonNode.class);
        method.setAccessible(true);
        return method.invoke(factory, spec);
    }

    @Test
    void parseConfiguration_missingEans_returnsConfigWithEmptyEans() throws Exception {
        assertNotNull(invokeParseConfiguration(spec("{\"buildingCode\":\"B1\"}")));
    }

    @Test
    void parseConfiguration_eansNotArray_returnsConfigWithEmptyEans() throws Exception {
        assertNotNull(invokeParseConfiguration(spec("{\"buildingCode\":\"B1\",\"eans\":\"EAN-1\"}")));
    }

    // --------------------------------------------------
    // GroupKey
    // --------------------------------------------------

    @Test
    void groupKey_equalsAndHashCode() {
        Building building = new StoreBuilding();
        building.buildingCode = "B1";
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);

        var k1 = new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(42L, 7L, building, slot);
        var k2 = new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(42L, 7L, building, slot);

        assertEquals(k1, k1);
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void groupKey_notEquals() {
        Building building = new StoreBuilding();
        building.buildingCode = "B1";
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);
        var key = new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(42L, 7L, building, slot);

        assertNotEquals(key, new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(43L, 7L, building, slot));
        assertNotEquals(key, new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(42L, 8L, building, slot));
        assertNotEquals(key, new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(42L, 7L, null, slot));
        assertNotEquals(key, new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(42L, 7L, building, slot.plusHours(1)));
        assertNotEquals(key, null);
        assertNotEquals(key, "not-a-key");
    }

    @Test
    void groupKey_toString_containsIdentifiers() {
        Building building = new StoreBuilding();
        building.buildingCode = "B1";
        var key = new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(42L, 7L, building, LocalDateTime.of(2026, 10, 14, 10, 0));

        String s = key.toString();
        assertTrue(s.contains("42"));
        assertTrue(s.contains("7"));
        assertTrue(s.contains("B1"));
    }

    @Test
    void groupKey_toString_nullBuilding() {
        var key = new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.GroupKey(42L, 7L, null, null);
        assertTrue(key.toString().contains("null"));
    }

    // --------------------------------------------------
    // OrderMetrics
    // --------------------------------------------------

    @Test
    void orderMetrics_add_accumulates() {
        var m1 = new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.OrderMetrics();
        m1.quantity = 5;
        m1.weight = 1.5;
        m1.volume = 2.5;

        var m2 = new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.OrderMetrics();
        m2.quantity = 3;
        m2.weight = 0.5;
        m2.volume = 1.0;

        m1.add(m2);

        assertEquals(8, m1.quantity);
        assertEquals(2.0, m1.weight, 0.0001);
        assertEquals(3.5, m1.volume, 0.0001);
    }

    @Test
    void orderMetrics_defaults_areZero() {
        var metrics = new ProductCollectingOrderManagerFactory.ProductCollectingOrderManager.OrderMetrics();
        assertEquals(0, metrics.quantity);
        assertEquals(0.0, metrics.weight, 0.0001);
        assertEquals(0.0, metrics.volume, 0.0001);
    }
}
