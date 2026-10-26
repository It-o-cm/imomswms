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
 * Unit tests for {@link Aisle} business logic.
 * Focuses on {@link Aisle#getChecksum()} sensitivity to the CSV-imported fields.
 */
public class AisleTest {

    @Test
    void getChecksum_shouldChangeWhenCodeChanges() {
        Aisle aisle = createAisle();
        aisle.code = "A01";
        int checksum1 = aisle.getChecksum();

        aisle.code = "A02";
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenLengthChanges() {
        Aisle aisle = createAisle();
        aisle.lengthMeters = 10.0;
        int checksum1 = aisle.getChecksum();

        aisle.lengthMeters = 20.0;
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenAisleTypeChanges() {
        Aisle aisle = createAisle();
        aisle.aisleType = "LINEAR";
        int checksum1 = aisle.getChecksum();

        aisle.aisleType = "CIRCULAR";
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenConnectedToHeadChanges() {
        Aisle aisle = createAisle();
        aisle.connectedToHead = "A00";
        int checksum1 = aisle.getChecksum();

        aisle.connectedToHead = "B00";
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenConnectedToTailChanges() {
        Aisle aisle = createAisle();
        aisle.connectedToTail = "A99";
        int checksum1 = aisle.getChecksum();

        aisle.connectedToTail = "B99";
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldUseBuildingCode_notBuildingId() {
        Aisle aisle = createAisle();
        aisle.building = createBuilding("BLD-01");
        int checksum1 = aisle.getChecksum();

        // Same building code, different surrogate id: checksum must stay stable
        aisle.building = createBuilding("BLD-01");
        aisle.building.id = 999L;
        int checksum2 = aisle.getChecksum();

        assertEquals(checksum1, checksum2);

        // Different building code: checksum must change
        aisle.building = createBuilding("BLD-02");
        int checksum3 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum3);
    }

    @Test
    void getChecksum_shouldNotFailWhenBuildingIsNull() {
        Aisle aisle = createAisle();
        aisle.building = null;
        assertDoesNotThrow(aisle::getChecksum);
    }

    @Test
    void getChecksum_shouldChangeWhenFlowTypeChanges() {
        Aisle aisle = createAisle();
        aisle.flowType = "LINEAR";
        int checksum1 = aisle.getChecksum();

        aisle.flowType = "CIRCULAR";
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenShapeTypeChanges() {
        Aisle aisle = createAisle();
        aisle.shapeType = "STRAIGHT";
        int checksum1 = aisle.getChecksum();

        aisle.shapeType = "CURVED";
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenHeadNodeChanges() {
        Aisle aisle = createAisle();
        aisle.headNode = createNode("N1");
        int checksum1 = aisle.getChecksum();

        aisle.headNode = createNode("N2");
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenTailNodeChanges() {
        Aisle aisle = createAisle();
        aisle.tailNode = createNode("N1");
        int checksum1 = aisle.getChecksum();

        aisle.tailNode = createNode("N2");
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenHeadNodeIsSetOrCleared() {
        Aisle aisle = createAisle();
        aisle.headNode = null;
        int checksum1 = aisle.getChecksum();

        aisle.headNode = createNode("N1");
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenWaypointsChange() {
        Aisle aisle = createAisle();
        aisle.waypoints.add(createWaypoint(0, 1.0, 2.0));
        int checksum1 = aisle.getChecksum();

        aisle.waypoints.get(0).x = 5.0;
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenWaypointOrderChanges() {
        Aisle aisle = createAisle();
        aisle.waypoints.add(createWaypoint(0, 1.0, 2.0));
        aisle.waypoints.add(createWaypoint(1, 3.0, 4.0));
        int checksum1 = aisle.getChecksum();

        // Same points, different order: the ordered hash must change
        aisle.waypoints.clear();
        aisle.waypoints.add(createWaypoint(0, 3.0, 4.0));
        aisle.waypoints.add(createWaypoint(1, 1.0, 2.0));
        int checksum2 = aisle.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldNotFailWhenWaypointsAreNull() {
        Aisle aisle = createAisle();
        aisle.waypoints = null;
        assertDoesNotThrow(aisle::getChecksum);
    }

    private PlanNode createNode(String code) {
        PlanNode node = new PlanNode();
        node.code = code;
        return node;
    }

    private AisleWaypoint createWaypoint(int position, double x, double y) {
        AisleWaypoint waypoint = new AisleWaypoint();
        waypoint.position = position;
        waypoint.x = x;
        waypoint.y = y;
        return waypoint;
    }

    private Aisle createAisle() {
        Aisle aisle = new Aisle();
        aisle.code = "A01";
        aisle.lengthMeters = 12.5;
        aisle.aisleType = "LINEAR";
        aisle.connectedToHead = null;
        aisle.connectedToTail = null;
        return aisle;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    @Test
    void findByCode_shouldReturnAisle_whenFound() {
        Building building = createBuilding("B1");
        Aisle aisle = createAisle();
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Aisle> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(aisle);
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 and code = ?2"), any(Object[].class)))
                    .thenReturn(query);

            assertSame(aisle, Aisle.findByCode(building, "A01"));
        }
    }

    @Test
    void findByCode_shouldReturnNull_whenNotFound() {
        Building building = createBuilding("B1");
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Aisle> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 and code = ?2"), any(Object[].class)))
                    .thenReturn(query);

            assertNull(Aisle.findByCode(building, "ZZ"));
        }
    }

    @Test
    void listByBuilding_shouldReturnAisles() {
        Building building = createBuilding("B1");
        Aisle aisle = createAisle();
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Aisle> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(java.util.List.of(aisle));
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 order by code"), any(Object[].class)))
                    .thenReturn(query);

            assertEquals(1, Aisle.listByBuilding(building).size());
        }
    }

    private Building createBuilding(String code) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        return building;
    }
}
