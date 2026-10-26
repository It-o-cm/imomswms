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
 * Unit tests for {@link PlanNode} business logic.
 * Focuses on {@link PlanNode#getChecksum()} sensitivity to the CSV-imported fields.
 */
public class PlanNodeTest {

    @Test
    void getChecksum_shouldChangeWhenCodeChanges() {
        PlanNode node = createNode();
        node.code = "ENTRANCE";
        int checksum1 = node.getChecksum();

        node.code = "HUB";
        int checksum2 = node.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenLabelChanges() {
        PlanNode node = createNode();
        node.label = "Entrée";
        int checksum1 = node.getChecksum();

        node.label = "Hub";
        int checksum2 = node.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenNodeTypeChanges() {
        PlanNode node = createNode();
        node.nodeType = PlanNodeType.ENTRANCE;
        int checksum1 = node.getChecksum();

        node.nodeType = PlanNodeType.DEADEND;
        int checksum2 = node.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenXChanges() {
        PlanNode node = createNode();
        node.x = 10.0;
        int checksum1 = node.getChecksum();

        node.x = 20.0;
        int checksum2 = node.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldChangeWhenYChanges() {
        PlanNode node = createNode();
        node.y = 10.0;
        int checksum1 = node.getChecksum();

        node.y = 20.0;
        int checksum2 = node.getChecksum();

        assertNotEquals(checksum1, checksum2);
    }

    @Test
    void getChecksum_shouldUseBuildingCode_notBuildingId() {
        PlanNode node = createNode();
        node.building = createBuilding("BLD-01");
        int checksum1 = node.getChecksum();

        // Same building code, different surrogate id: checksum must stay stable
        node.building = createBuilding("BLD-01");
        node.building.id = 999L;
        int checksum2 = node.getChecksum();

        assertEquals(checksum1, checksum2);

        // Different building code: checksum must change
        node.building = createBuilding("BLD-02");
        int checksum3 = node.getChecksum();

        assertNotEquals(checksum1, checksum3);
    }

    @Test
    void getChecksum_shouldNotFailWhenBuildingIsNull() {
        PlanNode node = createNode();
        node.building = null;
        assertDoesNotThrow(node::getChecksum);
    }

    private PlanNode createNode() {
        PlanNode node = new PlanNode();
        node.code = "NODE_01";
        node.label = "Node 1";
        node.nodeType = PlanNodeType.INTERSECTION;
        node.x = 1.0;
        node.y = 2.0;
        return node;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    @Test
    void findByCode_shouldReturnNode_whenFound() {
        Building building = createBuilding("B1");
        PlanNode node = createNode();
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PlanNode> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(node);
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 and code = ?2"), any(Object[].class)))
                    .thenReturn(query);

            assertSame(node, PlanNode.findByCode(building, "NODE_01"));
        }
    }

    @Test
    void findByCode_shouldReturnNull_whenNotFound() {
        Building building = createBuilding("B1");
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PlanNode> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 and code = ?2"), any(Object[].class)))
                    .thenReturn(query);

            assertNull(PlanNode.findByCode(building, "ZZ"));
        }
    }

    @Test
    void listByBuilding_shouldReturnNodes() {
        Building building = createBuilding("B1");
        PlanNode node = createNode();
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PlanNode> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(java.util.List.of(node));
            panacheMock.when(() -> PanacheEntityBase.find(eq("building = ?1 order by code"), any(Object[].class)))
                    .thenReturn(query);

            assertEquals(1, PlanNode.listByBuilding(building).size());
        }
    }

    @Test
    void deleteByBuilding_shouldReturnDeletedCount() {
        Building building = createBuilding("B1");
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.delete(eq("building = ?1"), any(Object[].class)))
                    .thenReturn(3L);

            assertEquals(3L, PlanNode.deleteByBuilding(building));
        }
    }

    private Building createBuilding(String code) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        return building;
    }
}
