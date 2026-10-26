package com.intermarche.e2e.graphql.store;

import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.PlanZoneVertex;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.graphql.store.PlanZoneResource;
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
 * Integration tests for {@link PlanZoneResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class PlanZoneResourceTest {

    @Inject
    PlanZoneResource planZoneResource;

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
     * Creates a vertex input.
     */
    private PlanZoneResource.VertexInput vertex(double x, double y) {
        PlanZoneResource.VertexInput v = new PlanZoneResource.VertexInput();
        v.x = x;
        v.y = y;
        return v;
    }

    /**
     * Creates a triangle vertex list.
     */
    private List<PlanZoneResource.VertexInput> triangle() {
        return List.of(vertex(0, 0), vertex(10, 0), vertex(10, 10));
    }

    /**
     * Creates and persists a plan zone directly via the entity.
     */
    private PlanZone persistZone(String code, StoreBuilding building) {
        PlanZone zone = new PlanZone();
        zone.code = code;
        zone.label = "Label " + code;
        zone.color = "#0891b2";
        zone.temperatureZone = TemperatureZone.CHILLED;
        zone.labelOffsetX = 0.0;
        zone.labelOffsetY = 0.0;
        zone.building = building;
        for (int i = 0; i < 3; i++) {
            PlanZoneVertex v = new PlanZoneVertex();
            v.zone = zone;
            v.position = i;
            v.x = i * 10.0;
            v.y = 0.0;
            zone.vertices.add(v);
        }
        zone.persist();
        return zone;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllPlanZones() {
        persistZone("PZ_01", persistBuilding("BLD_PZ1"));

        List<PlanZone> zones = planZoneResource.allPlanZones();
        assertNotNull(zones);
        assertFalse(zones.isEmpty(), "Should return at least one plan zone");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanZoneById_Success() {
        PlanZone existing = persistZone("PZ_02", persistBuilding("BLD_PZ2"));

        PlanZone found = planZoneResource.planZone(existing.id);
        assertNotNull(found);
        assertEquals("PZ_02", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanZoneById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            planZoneResource.planZone(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanZoneByCode_Success() {
        persistZone("PZ_03", persistBuilding("BLD_PZ3"));

        PlanZone found = planZoneResource.planZoneByCode("PZ_03");
        assertNotNull(found);
        assertEquals("PZ_03", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanZoneByCode_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            planZoneResource.planZoneByCode("PZ_99");
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanZonesByBuilding_Success() {
        StoreBuilding building = persistBuilding("BLD_PZ4");
        persistZone("PZ_04", building);
        persistZone("PZ_05", building);

        List<PlanZone> zones = planZoneResource.planZonesByBuilding("BLD_PZ4");
        assertEquals(2, zones.size());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testPlanZonesByBuilding_BuildingNotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            planZoneResource.planZonesByBuilding("BLD_UNKNOWN");
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanZone_AllFields_Success() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PZ5");

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "PZ_10";
        input.label = "Rayon Frais";
        input.color = "#0891b2";
        input.temperatureZone = TemperatureZone.CHILLED;
        input.labelOffsetX = 1.5;
        input.labelOffsetY = -2.5;
        input.vertices = triangle();
        input.buildingId = building.id;

        PlanZone created = planZoneResource.createPlanZone(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("PZ_10", created.code);
        assertEquals("Rayon Frais", created.label);
        assertEquals("#0891b2", created.color);
        assertEquals(TemperatureZone.CHILLED, created.temperatureZone);
        assertEquals(1.5, created.labelOffsetX);
        assertEquals(-2.5, created.labelOffsetY);
        assertEquals(building.id, created.building.id);
        assertEquals(3, created.vertices.size());
        assertEquals(0, created.vertices.get(0).position);
        assertEquals(10.0, created.vertices.get(1).x);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanZone_TooFewVertices() {
        StoreBuilding building = persistBuilding("BLD_PZ6");

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "PZ_11";
        input.vertices = List.of(vertex(0, 0), vertex(10, 0)); // only 2
        input.buildingId = building.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            planZoneResource.createPlanZone(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("at least 3 vertices"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanZone_DuplicateCode() {
        StoreBuilding building = persistBuilding("BLD_PZ7");
        persistZone("PZ_12", building);

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "PZ_12";
        input.vertices = triangle();
        input.buildingId = building.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            planZoneResource.createPlanZone(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanZone_NullBuildingId() {
        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "PZ_13";
        input.vertices = triangle();
        input.buildingId = null; // mandatory

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            planZoneResource.createPlanZone(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Building ID is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreatePlanZone_BuildingNotFound() {
        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "PZ_14";
        input.vertices = triangle();
        input.buildingId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            planZoneResource.createPlanZone(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanZone_AllFields_Success() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PZ8");
        StoreBuilding otherBuilding = persistBuilding("BLD_PZ9");
        PlanZone existing = persistZone("PZ_20", building);

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "PZ_21";
        input.label = "Surgelés";
        input.color = "#0284c7";
        input.temperatureZone = TemperatureZone.FROZEN;
        input.labelOffsetX = 3.0;
        input.labelOffsetY = 4.0;
        input.vertices = List.of(vertex(1, 1), vertex(2, 2), vertex(3, 3), vertex(4, 4));
        input.buildingId = otherBuilding.id;

        PlanZone updated = planZoneResource.updatePlanZone(existing.id, input);

        assertEquals("PZ_21", updated.code);
        assertEquals("Surgelés", updated.label);
        assertEquals("#0284c7", updated.color);
        assertEquals(TemperatureZone.FROZEN, updated.temperatureZone);
        assertEquals(3.0, updated.labelOffsetX);
        assertEquals(4.0, updated.labelOffsetY);
        assertEquals(otherBuilding.id, updated.building.id);
        assertEquals(4, updated.vertices.size());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanZone_SameCode_NoConflict() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PZ10");
        PlanZone existing = persistZone("PZ_22", building);

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "PZ_22"; // same code: uniqueness check must be skipped
        input.color = "#ff0000";

        PlanZone updated = planZoneResource.updatePlanZone(existing.id, input);
        assertEquals("PZ_22", updated.code);
        assertEquals("#ff0000", updated.color);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanZone_ConflictingCode() {
        StoreBuilding building = persistBuilding("BLD_PZ11");
        PlanZone existing = persistZone("PZ_23", building);
        persistZone("PZ_24", building);

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "PZ_24";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            planZoneResource.updatePlanZone(existing.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanZone_NotFound() {
        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.code = "GHOST";

        assertThrows(NoSuchElementException.class, () -> {
            planZoneResource.updatePlanZone(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanZone_BuildingNotFound() {
        PlanZone existing = persistZone("PZ_25", persistBuilding("BLD_PZ12"));

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.buildingId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            planZoneResource.updatePlanZone(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanZone_PartialUpdate_KeepsOtherFields() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PZ13");
        PlanZone existing = persistZone("PZ_26", building);

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.label = "New label"; // only the label changes

        PlanZone updated = planZoneResource.updatePlanZone(existing.id, input);
        assertEquals("PZ_26", updated.code, "Code should remain unchanged");
        assertEquals("#0891b2", updated.color, "Color should remain unchanged");
        assertEquals(TemperatureZone.CHILLED, updated.temperatureZone, "Temperature zone should remain unchanged");
        assertEquals(building.id, updated.building.id, "Building should remain unchanged");
        assertEquals(3, updated.vertices.size(), "Polygon should remain unchanged");
        assertEquals("New label", updated.label);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanZone_ReplacesVertices() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_PZ14");
        PlanZone existing = persistZone("PZ_27", building);
        assertEquals(3, existing.vertices.size());

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.vertices = List.of(vertex(5, 5), vertex(6, 6), vertex(7, 7), vertex(8, 8));

        PlanZone updated = planZoneResource.updatePlanZone(existing.id, input);
        assertEquals(4, updated.vertices.size());
        assertEquals(5.0, updated.vertices.get(0).x);
        assertEquals(8.0, updated.vertices.get(3).x);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdatePlanZone_TooFewVertices() {
        PlanZone existing = persistZone("PZ_28", persistBuilding("BLD_PZ15"));

        PlanZoneResource.PlanZoneRecord input = new PlanZoneResource.PlanZoneRecord();
        input.vertices = List.of(vertex(0, 0));

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            planZoneResource.updatePlanZone(existing.id, input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("at least 3 vertices"));
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeletePlanZone_Success() throws GraphQLException {
        PlanZone existing = persistZone("PZ_29", persistBuilding("BLD_PZ16"));

        boolean result = planZoneResource.deletePlanZone(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(PlanZone.findById(existing.id));
        // Vertices are cascade-deleted with the zone
        assertEquals(0, PlanZoneVertex.count("zone.id", existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeletePlanZone_NotFound() throws GraphQLException {
        boolean result = planZoneResource.deletePlanZone(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testPlanZoneRecord_ToString() {
        PlanZoneResource.PlanZoneRecord record = new PlanZoneResource.PlanZoneRecord();
        record.code = "PZ_REC";
        record.label = "Rayon Frais";
        record.color = "#0891b2";
        record.temperatureZone = TemperatureZone.CHILLED;
        record.vertices = triangle();
        record.buildingId = 5L;

        assertEquals("PlanZoneRecord [code=PZ_REC, label=Rayon Frais, color=#0891b2, temperatureZone=CHILLED, vertices=3, buildingId=5]",
                record.toString());
    }

    @Test
    void testPlanZoneRecord_ToString_NullVertices() {
        PlanZoneResource.PlanZoneRecord record = new PlanZoneResource.PlanZoneRecord();
        record.code = "PZ_REC";

        assertEquals("PlanZoneRecord [code=PZ_REC, label=null, color=null, temperatureZone=null, vertices=null, buildingId=null]",
                record.toString());
    }
}
