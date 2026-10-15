package com.intermarche.backoffice.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BaseLocation} static logic that does not require a database.
 * Focuses on {@link BaseLocation#findCommonSpecification(List)} edge cases
 * and the {@link BaseLocation.LocationSpecification} record.
 */
public class BaseLocationTest {

    private static final String BUILDINGS_JPQL =
            "SELECT DISTINCT b FROM Building b JOIN b.locations loc WHERE loc.id IN ?1";

    @Test
    void findCommonSpecification_shouldReturnNullFields_whenLocationsEmpty() {
        BaseLocation.LocationSpecification spec = BaseLocation.findCommonSpecification(List.of());

        assertNotNull(spec);
        assertNull(spec.store);
        assertNull(spec.building);
        assertNull(spec.area);
        assertNull(spec.aisle);
    }

    @Test
    void findCommonSpecification_shouldThrowNPE_whenLocationsNull() {
        // locations.isEmpty() est appelé sans garde
        assertThrows(NullPointerException.class, () -> BaseLocation.findCommonSpecification(null));
    }

    @Test
    void locationSpecification_shouldExposeConstructorValues() {
        Store store = new Store();
        StoreBuilding building = new StoreBuilding();

        BaseLocation.LocationSpecification spec = new BaseLocation.LocationSpecification(store, building, "AREA", "A01");

        assertSame(store, spec.store);
        assertSame(building, spec.building);
        assertEquals("AREA", spec.area);
        assertEquals("A01", spec.aisle);
    }

    // --------------------------------------------------
    // findBuildingsByLocations
    // --------------------------------------------------

    @Test
    void findBuildingsByLocations_shouldReturnEmpty_whenLocationsNull() {
        assertTrue(BaseLocation.findBuildingsByLocations(null).isEmpty());
    }

    @Test
    void findBuildingsByLocations_shouldReturnEmpty_whenLocationsEmpty() {
        assertTrue(BaseLocation.findBuildingsByLocations(List.of()).isEmpty());
    }

    @Test
    void findBuildingsByLocations_shouldReturnEmpty_whenAllLocationIdsAreNull() {
        StoreShelfLocation location = new StoreShelfLocation();
        location.id = null;

        assertTrue(BaseLocation.findBuildingsByLocations(List.of(location)).isEmpty());
    }

    @Test
    void findBuildingsByLocations_shouldQueryBuildings_whenIdsPresent() {
        StoreShelfLocation location = new StoreShelfLocation();
        location.id = 1L;
        StoreBuilding building = new StoreBuilding();
        building.id = 2L;

        PanacheQuery<Building> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(building));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(BUILDINGS_JPQL), any(Object[].class)))
                    .thenReturn(query);

            List<Building> buildings = BaseLocation.findBuildingsByLocations(List.of(location));

            assertEquals(1, buildings.size());
            assertSame(building, buildings.get(0));
        }
    }

    // --------------------------------------------------
    // findCommonSpecification
    // --------------------------------------------------

    @Test
    void findCommonSpecification_shouldReturnNullFields_whenMultipleBuildings() {
        StoreShelfLocation loc1 = new StoreShelfLocation();
        loc1.id = 1L;
        StoreShelfLocation loc2 = new StoreShelfLocation();
        loc2.id = 2L;

        PanacheQuery<Building> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(new StoreBuilding(), new StoreBuilding()));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(BUILDINGS_JPQL), any(Object[].class)))
                    .thenReturn(query);

            BaseLocation.LocationSpecification spec = BaseLocation.findCommonSpecification(List.of(loc1, loc2));

            assertNotNull(spec);
            assertNull(spec.store);
            assertNull(spec.building);
            assertNull(spec.area);
            assertNull(spec.aisle);
        }
    }

    @Test
    void findCommonSpecification_shouldReturnNullStore_whenBuildingIsNull() {
        StoreShelfLocation location = new StoreShelfLocation();
        location.id = 1L;
        location.area = "FRESH";
        location.aisle = "A01";

        PanacheQuery<Building> query = mock(PanacheQuery.class);
        // The query result contains a single null building
        when(query.list()).thenReturn(Collections.singletonList(null));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(BUILDINGS_JPQL), any(Object[].class)))
                    .thenReturn(query);

            BaseLocation.LocationSpecification spec = BaseLocation.findCommonSpecification(List.of(location));

            assertNotNull(spec);
            assertNull(spec.store);
            assertNull(spec.building);
            assertEquals("FRESH", spec.area);
            assertEquals("A01", spec.aisle);
        }
    }

    @Test
    void findCommonSpecification_shouldTreatNonShelfLocationAsNullAreaAndAisle() {
        BaseLocation nonShelfLocation = mock(BaseLocation.class);
        nonShelfLocation.id = 2L;
        StoreBuilding building = new StoreBuilding();
        building.id = 3L;

        PanacheQuery<Building> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(building));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Building> buildingMock = mockStatic(Building.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(BUILDINGS_JPQL), any(Object[].class)))
                    .thenReturn(query);
            buildingMock.when(() -> Building.findStoreByBuildingId(any())).thenReturn(null);

            BaseLocation.LocationSpecification spec = BaseLocation.findCommonSpecification(List.of(nonShelfLocation));

            assertNotNull(spec);
            assertNull(spec.store);
            assertSame(building, spec.building);
            assertNull(spec.area);
            assertNull(spec.aisle);
        }
    }

    @Test
    void findCommonSpecification_shouldReturnCommonAreaAndAisle_whenAllShelfLocationsMatch() {
        StoreShelfLocation loc1 = new StoreShelfLocation();
        loc1.id = 1L;
        loc1.area = "FRESH";
        loc1.aisle = "A01";
        StoreShelfLocation loc2 = new StoreShelfLocation();
        loc2.id = 2L;
        loc2.area = "FRESH";
        loc2.aisle = "A01";
        StoreBuilding building = new StoreBuilding();
        building.id = 3L;
        Store store = new Store();

        PanacheQuery<Building> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(building));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Building> buildingMock = mockStatic(Building.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(BUILDINGS_JPQL), any(Object[].class)))
                    .thenReturn(query);
            buildingMock.when(() -> Building.findStoreByBuildingId(any())).thenReturn(store);

            BaseLocation.LocationSpecification spec = BaseLocation.findCommonSpecification(List.of(loc1, loc2));

            assertNotNull(spec);
            assertSame(store, spec.store);
            assertSame(building, spec.building);
            assertEquals("FRESH", spec.area);
            assertEquals("A01", spec.aisle);
        }
    }

    @Test
    void findCommonSpecification_shouldReturnNullArea_whenAreasDiffer() {
        StoreShelfLocation loc1 = new StoreShelfLocation();
        loc1.id = 1L;
        loc1.area = "FRESH";
        loc1.aisle = "A01";
        StoreShelfLocation loc2 = new StoreShelfLocation();
        loc2.id = 2L;
        loc2.area = "DRY";
        loc2.aisle = "A01";
        StoreBuilding building = new StoreBuilding();
        building.id = 3L;

        PanacheQuery<Building> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(building));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Building> buildingMock = mockStatic(Building.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(BUILDINGS_JPQL), any(Object[].class)))
                    .thenReturn(query);
            buildingMock.when(() -> Building.findStoreByBuildingId(any())).thenReturn(null);

            BaseLocation.LocationSpecification spec = BaseLocation.findCommonSpecification(List.of(loc1, loc2));

            assertNotNull(spec);
            assertNull(spec.area);
            assertEquals("A01", spec.aisle);
        }
    }

    @Test
    void findCommonSpecification_shouldReturnNullAisle_whenAislesDiffer() {
        StoreShelfLocation loc1 = new StoreShelfLocation();
        loc1.id = 1L;
        loc1.area = "FRESH";
        loc1.aisle = "A01";
        StoreShelfLocation loc2 = new StoreShelfLocation();
        loc2.id = 2L;
        loc2.area = "FRESH";
        loc2.aisle = "B02";
        StoreBuilding building = new StoreBuilding();
        building.id = 3L;

        PanacheQuery<Building> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(building));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class);
             MockedStatic<Building> buildingMock = mockStatic(Building.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(eq(BUILDINGS_JPQL), any(Object[].class)))
                    .thenReturn(query);
            buildingMock.when(() -> Building.findStoreByBuildingId(any())).thenReturn(null);

            BaseLocation.LocationSpecification spec = BaseLocation.findCommonSpecification(List.of(loc1, loc2));

            assertNotNull(spec);
            assertEquals("FRESH", spec.area);
            assertNull(spec.aisle);
        }
    }
}
