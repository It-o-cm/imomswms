package com.intermarche.backoffice.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlanZone} business logic.
 * Focuses on {@link PlanZone#getChecksum()} sensitivity to the CSV-imported fields.
 */
public class PlanZoneTest {

    @Test
    void getChecksum_shouldChangeWhenCodeChanges() {
        PlanZone zone = createZone();
        zone.code = "FRESH_AREA";
        int checksum1 = zone.getChecksum();

        zone.code = "FROZEN_AREA";
        int checksum2 = zone.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenLabelChanges() {
        PlanZone zone = createZone();
        zone.label = "Rayon Frais";
        int checksum1 = zone.getChecksum();

        zone.label = "Surgelés";
        int checksum2 = zone.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenColorChanges() {
        PlanZone zone = createZone();
        zone.color = "#0891b2";
        int checksum1 = zone.getChecksum();

        zone.color = "#0284c7";
        int checksum2 = zone.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenTemperatureZoneChanges() {
        PlanZone zone = createZone();
        zone.temperatureZone = TemperatureZone.CHILLED;
        int checksum1 = zone.getChecksum();

        zone.temperatureZone = TemperatureZone.FROZEN;
        int checksum2 = zone.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenVerticesChange() {
        PlanZone zone = createZone();
        zone.vertices.add(vertex(0, 0.0, 0.0));
        zone.vertices.add(vertex(1, 10.0, 0.0));
        zone.vertices.add(vertex(2, 10.0, 10.0));
        int checksum1 = zone.getChecksum();

        zone.vertices.get(2).x = 20.0;
        int checksum2 = zone.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenVertexOrderChanges() {
        PlanZone zone = createZone();
        zone.vertices.add(vertex(0, 0.0, 0.0));
        zone.vertices.add(vertex(1, 10.0, 0.0));
        zone.vertices.add(vertex(2, 10.0, 10.0));
        int checksum1 = zone.getChecksum();

        // Same points, different order: the ordered hash must change
        zone.vertices.clear();
        zone.vertices.add(vertex(0, 10.0, 10.0));
        zone.vertices.add(vertex(1, 10.0, 0.0));
        zone.vertices.add(vertex(2, 0.0, 0.0));
        int checksum2 = zone.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenLabelOffsetXChanges() {
        PlanZone zone = createZone();
        zone.labelOffsetX = 0.0;
        int checksum1 = zone.getChecksum();

        zone.labelOffsetX = 5.0;
        int checksum2 = zone.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenLabelOffsetYChanges() {
        PlanZone zone = createZone();
        zone.labelOffsetY = 0.0;
        int checksum1 = zone.getChecksum();

        zone.labelOffsetY = 5.0;
        int checksum2 = zone.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldUseBuildingCode_notBuildingId() {
        PlanZone zone = createZone();
        zone.building = createBuilding("BLD-01");
        int checksum1 = zone.getChecksum();

        // Same building code, different surrogate id: checksum must stay stable
        zone.building = createBuilding("BLD-01");
        zone.building.id = 999L;
        int checksum2 = zone.getChecksum();

        assertEquals(checksum1, checksum2);

        // Different building code: checksum must change
        zone.building = createBuilding("BLD-02");
        int checksum3 = zone.getChecksum();

        assertNotEquals(checksum1, checksum3);
    }

    @Test
    void getChecksum_shouldNotFailWhenBuildingIsNull() {
        PlanZone zone = createZone();
        zone.building = null;
        assertDoesNotThrow(zone::getChecksum);
    }

    @Test
    void getChecksum_shouldNotFailWhenVerticesAreNull() {
        PlanZone zone = createZone();
        zone.vertices = null;
        assertDoesNotThrow(zone::getChecksum);
    }

    private PlanZone createZone() {
        PlanZone zone = new PlanZone();
        zone.code = "ZONE_01";
        zone.label = "Zone 1";
        zone.color = "#0891b2";
        zone.temperatureZone = TemperatureZone.CHILLED;
        zone.labelOffsetX = 0.0;
        zone.labelOffsetY = 0.0;
        return zone;
    }

    private PlanZoneVertex vertex(int position, double x, double y) {
        PlanZoneVertex vertex = new PlanZoneVertex();
        vertex.position = position;
        vertex.x = x;
        vertex.y = y;
        return vertex;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    @Test
    void findByCode_shouldReturnZone_whenFound() {
        Building building = createBuilding("B1");
        PlanZone zone = createZone();
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PlanZone> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(zone);
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 and code = ?2"), any(Object[].class)))
                    .thenReturn(query);

            assertSame(zone, PlanZone.findByCode(building, "ZONE_01"));
        }
    }

    @Test
    void findByCode_shouldReturnNull_whenNotFound() {
        Building building = createBuilding("B1");
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PlanZone> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 and code = ?2"), any(Object[].class)))
                    .thenReturn(query);

            assertNull(PlanZone.findByCode(building, "ZZ"));
        }
    }

    @Test
    void listByBuilding_shouldReturnZones() {
        Building building = createBuilding("B1");
        PlanZone zone = createZone();
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PlanZone> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(java.util.List.of(zone));
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 order by code"), any(Object[].class)))
                    .thenReturn(query);

            assertEquals(1, PlanZone.listByBuilding(building).size());
        }
    }

    @Test
    void deleteByBuilding_shouldReturnDeletedCount() {
        Building building = createBuilding("B1");
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.delete(eq("building = ?1"), any(Object[].class)))
                    .thenReturn(2L);

            assertEquals(2L, PlanZone.deleteByBuilding(building));
        }
    }

    private Building createBuilding(String code) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        return building;
    }
}
