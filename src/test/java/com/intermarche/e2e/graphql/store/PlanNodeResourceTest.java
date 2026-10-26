package com.intermarche.e2e.graphql.store;

import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanNodeType;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.graphql.store.PlanNodeResource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PlanNodeResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class PlanNodeResourceTest {

    @Inject
    PlanNodeResource planNodeResource;

    /**
     * Creates and persists a StoreBuilding (concrete Building) with the given code.
     */
    private StoreBuilding persistBuilding(String buildingCode) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = buildingCode;
        building.persist();
        return building;
    }

    /**
     * Creates and persists a plan node directly via the entity.
     */
    private PlanNode persistNode(String code, StoreBuilding building) {
        PlanNode node = new PlanNode();
        node.code = code;
        node.label = "Label " + code;
        node.nodeType = PlanNodeType.INTERSECTION;
        node.x = 10.0;
        node.y = 20.0;
        node.building = building;
        node.persist();
        return node;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllPlanNodes() {
        persistNode("PN_01", persistBuilding("BLD_PN1"));

        List<PlanNode> nodes = planNodeResource.allPlanNodes();
        assertNotNull(nodes);
        assertFalse(nodes.isEmpty(), "Should return at least one plan node");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanNodeById_Success() {
        PlanNode existing = persistNode("PN_02", persistBuilding("BLD_PN2"));

        PlanNode found = planNodeResource.planNode(existing.id);
        assertNotNull(found);
        assertEquals("PN_02", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanNodeById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            planNodeResource.planNode(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanNodeByCode_Success() {
        persistNode("PN_03", persistBuilding("BLD_PN3"));

        PlanNode found = planNodeResource.planNodeByCode("PN_03");
        assertNotNull(found);
        assertEquals("PN_03", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanNodeByCode_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            planNodeResource.planNodeByCode("PN_99");
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanNodesByBuilding_Success() {
        StoreBuilding building = persistBuilding("BLD_PN4");
        persistNode("PN_04", building);
        persistNode("PN_05", building);

        List<PlanNode> nodes = planNodeResource.planNodesByBuilding("BLD_PN4");
        assertEquals(2, nodes.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanNodesByBuilding_BuildingNotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            planNodeResource.planNodesByBuilding("BLD_UNKNOWN");
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanNode_AllFields_Success() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PN5");

        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.code = "PN_10";
        input.label = "Entrée";
        input.nodeType = PlanNodeType.ENTRANCE;
        input.x = -83.0;
        input.y = 242.0;
        input.buildingId = building.id;

        PlanNode created = planNodeResource.createPlanNode(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("PN_10", created.code);
        assertEquals("Entrée", created.label);
        assertEquals(PlanNodeType.ENTRANCE, created.nodeType);
        assertEquals(-83.0, created.x);
        assertEquals(242.0, created.y);
        assertEquals(building.id, created.building.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanNode_DuplicateCode() {
        StoreBuilding building = persistBuilding("BLD_PN6");
        persistNode("PN_11", building);

        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.code = "PN_11";
        input.buildingId = building.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            planNodeResource.createPlanNode(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanNode_NullBuildingId() {
        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.code = "PN_12";
        input.buildingId = null; // mandatory

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            planNodeResource.createPlanNode(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Building ID is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanNode_BuildingNotFound() {
        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.code = "PN_13";
        input.buildingId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            planNodeResource.createPlanNode(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanNode_AllFields_Success() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PN7");
        StoreBuilding otherBuilding = persistBuilding("BLD_PN8");
        PlanNode existing = persistNode("PN_20", building);

        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.code = "PN_21";
        input.label = "Hub Central";
        input.nodeType = PlanNodeType.INTERSECTION;
        input.x = -83.0;
        input.y = 82.0;
        input.buildingId = otherBuilding.id;

        PlanNode updated = planNodeResource.updatePlanNode(existing.id, input);

        assertEquals("PN_21", updated.code);
        assertEquals("Hub Central", updated.label);
        assertEquals(PlanNodeType.INTERSECTION, updated.nodeType);
        assertEquals(-83.0, updated.x);
        assertEquals(82.0, updated.y);
        assertEquals(otherBuilding.id, updated.building.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanNode_SameCode_NoConflict() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PN9");
        PlanNode existing = persistNode("PN_22", building);

        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.code = "PN_22"; // same code: uniqueness check must be skipped
        input.x = 99.0;

        PlanNode updated = planNodeResource.updatePlanNode(existing.id, input);
        assertEquals("PN_22", updated.code);
        assertEquals(99.0, updated.x);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanNode_ConflictingCode() {
        StoreBuilding building = persistBuilding("BLD_PN10");
        PlanNode existing = persistNode("PN_23", building);
        persistNode("PN_24", building);

        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.code = "PN_24";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            planNodeResource.updatePlanNode(existing.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanNode_NotFound() {
        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.code = "GHOST";

        assertThrows(NoSuchElementException.class, () -> {
            planNodeResource.updatePlanNode(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanNode_BuildingNotFound() {
        PlanNode existing = persistNode("PN_25", persistBuilding("BLD_PN11"));

        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.buildingId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            planNodeResource.updatePlanNode(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanNode_PartialUpdate_KeepsOtherFields() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PN12");
        PlanNode existing = persistNode("PN_26", building);

        PlanNodeResource.PlanNodeRecord input = new PlanNodeResource.PlanNodeRecord();
        input.label = "New label"; // only the label changes

        PlanNode updated = planNodeResource.updatePlanNode(existing.id, input);
        assertEquals("PN_26", updated.code, "Code should remain unchanged");
        assertEquals(PlanNodeType.INTERSECTION, updated.nodeType, "Type should remain unchanged");
        assertEquals(10.0, updated.x, "X should remain unchanged");
        assertEquals(20.0, updated.y, "Y should remain unchanged");
        assertEquals(building.id, updated.building.id, "Building should remain unchanged");
        assertEquals("New label", updated.label);
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeletePlanNode_Success() throws GraphQLException {
        PlanNode existing = persistNode("PN_27", persistBuilding("BLD_PN13"));

        boolean result = planNodeResource.deletePlanNode(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(PlanNode.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeletePlanNode_NotFound() throws GraphQLException {
        boolean result = planNodeResource.deletePlanNode(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testPlanNodeRecord_ToString() {
        PlanNodeResource.PlanNodeRecord record = new PlanNodeResource.PlanNodeRecord();
        record.code = "PN_REC";
        record.label = "Entrée";
        record.nodeType = PlanNodeType.ENTRANCE;
        record.x = 1.0;
        record.y = 2.0;
        record.buildingId = 5L;

        assertEquals("PlanNodeRecord [code=PN_REC, label=Entrée, nodeType=ENTRANCE, x=1.0, y=2.0, buildingId=5]",
                record.toString());
    }
}
