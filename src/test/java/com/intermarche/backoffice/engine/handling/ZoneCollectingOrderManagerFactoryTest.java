package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ZoneCollectingOrderManagerFactory}.
 * Covers factory naming, JSON schema validation, the Volume XOR Weight business rule,
 * min/max capacity validation, and the {@code GroupKey} / {@code OrderMetrics} helper classes.
 */
class ZoneCollectingOrderManagerFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ZoneCollectingOrderManagerFactory factory = new ZoneCollectingOrderManagerFactory();

    private JsonNode spec(String json) throws Exception {
        return mapper.readTree(json);
    }

    // --------------------------------------------------
    // getName
    // --------------------------------------------------

    @Test
    void getName_returnsZoneCollecting() {
        assertEquals("ZoneCollecting", factory.getName());
    }

    // --------------------------------------------------
    // build - valid configurations
    // --------------------------------------------------

    @Test
    void build_volumeConstraintsOnly_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"buildingCode":"B1","area":"A1","aisle":"A01","volumeMin":1.0,"volumeMax":100.0}
                """));
        assertNotNull(builder);
        assertInstanceOf(ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.class, builder);
    }

    @Test
    void build_weightConstraintsOnly_returnsManager() throws Exception {
        var builder = factory.build(spec("""
                {"buildingCode":"B1","weightMin":0.5,"weightMax":50.0,"validityDelayMinutes":30,"groupingWindowMinutes":60}
                """));
        assertInstanceOf(ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.class, builder);
    }

    @Test
    void build_volumeMinOnly_returnsManager() throws Exception {
        var builder = factory.build(spec("{\"buildingCode\":\"B1\",\"volumeMin\":5.0}"));
        assertInstanceOf(ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.class, builder);
    }

    // --------------------------------------------------
    // build - schema validation
    // --------------------------------------------------

    @Test
    void build_missingBuildingCode_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"volumeMin\":1.0,\"volumeMax\":10.0}")));
    }

    @Test
    void build_emptySpec_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{}")));
    }

    @Test
    void build_wrongType_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":123,\"volumeMax\":10.0}")));
    }

    // --------------------------------------------------
    // build - Volume XOR Weight business rule
    // --------------------------------------------------

    @Test
    void build_noConstraints_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\"}")));
    }

    @Test
    void build_bothVolumeAndWeight_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"volumeMin\":1.0,\"weightMin\":1.0}")));
    }

    @Test
    void build_bothVolumeMaxAndWeightMax_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"volumeMax\":10.0,\"weightMax\":5.0}")));
    }

    // --------------------------------------------------
    // build - min/max validation
    // --------------------------------------------------

    @Test
    void build_volumeMinGreaterThanMax_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"volumeMin\":10.0,\"volumeMax\":5.0}")));
    }

    @Test
    void build_weightMinGreaterThanMax_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec("{\"buildingCode\":\"B1\",\"weightMin\":10.0,\"weightMax\":5.0}")));
    }

    @Test
    void build_volumeMinEqualsMax_returnsManager() throws Exception {
        var builder = factory.build(spec("{\"buildingCode\":\"B1\",\"volumeMin\":5.0,\"volumeMax\":5.0}"));
        assertInstanceOf(ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.class, builder);
    }

    // --------------------------------------------------
    // GroupKey
    // --------------------------------------------------

    @Test
    void groupKey_equalsAndHashCode() {
        Building building = new StoreBuilding();
        building.buildingCode = "B1";
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);

        var k1 = new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, building, "A1", "A01", slot);
        var k2 = new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, building, "A1", "A01", slot);

        assertEquals(k1, k1);
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void groupKey_notEquals() {
        Building building = new StoreBuilding();
        building.buildingCode = "B1";
        LocalDateTime slot = LocalDateTime.of(2026, 10, 14, 10, 0);
        var key = new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, building, "A1", "A01", slot);

        assertNotEquals(key, new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(8L, building, "A1", "A01", slot));
        assertNotEquals(key, new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, null, "A1", "A01", slot));
        assertNotEquals(key, new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, building, "A2", "A01", slot));
        assertNotEquals(key, new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, building, "A1", "A02", slot));
        assertNotEquals(key, new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, building, "A1", "A01", slot.plusHours(1)));
        assertNotEquals(key, null);
        assertNotEquals(key, "not-a-key");
    }

    @Test
    void groupKey_toString_containsIdentifiers() {
        Building building = new StoreBuilding();
        building.buildingCode = "B1";
        var key = new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, building, "A1", "A01", null);

        String s = key.toString();
        assertTrue(s.contains("7"));
        assertTrue(s.contains("B1"));
        assertTrue(s.contains("A1"));
        assertTrue(s.contains("A01"));
    }

    @Test
    void groupKey_toString_nullBuilding() {
        var key = new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.GroupKey(7L, null, null, null, null);
        assertTrue(key.toString().contains("null"));
    }

    // --------------------------------------------------
    // OrderMetrics
    // --------------------------------------------------

    @Test
    void orderMetrics_add_accumulates() {
        var m1 = new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.OrderMetrics();
        m1.volume = 1.5;
        m1.weight = 2.5;

        var m2 = new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.OrderMetrics();
        m2.volume = 0.5;
        m2.weight = 1.0;

        m1.add(m2);

        assertEquals(2.0, m1.volume, 0.0001);
        assertEquals(3.5, m1.weight, 0.0001);
    }

    @Test
    void orderMetrics_defaults_areZero() {
        var metrics = new ZoneCollectingOrderManagerFactory.ZoneCollectingOrderManager.OrderMetrics();
        assertEquals(0.0, metrics.volume, 0.0001);
        assertEquals(0.0, metrics.weight, 0.0001);
    }
}
