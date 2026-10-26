package com.intermarche.e2e.graphql.store;

import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.graphql.store.AisleResource;
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
 * Integration tests for {@link AisleResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class AisleResourceTest {

    @Inject
    AisleResource aisleResource;

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
     * Creates and persists an aisle directly via the entity.
     */
    private Aisle persistAisle(String code, StoreBuilding building) {
        Aisle aisle = new Aisle();
        aisle.code = code;
        aisle.lengthMeters = 12.5;
        aisle.aisleType = "LINEAR";
        aisle.connectedToHead = "HEAD_" + code;
        aisle.connectedToTail = "TAIL_" + code;
        aisle.building = building;
        aisle.persist();
        return aisle;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllAisles() {
        persistAisle("AISLE_01", persistBuilding("BLD_A1"));

        List<Aisle> aisles = aisleResource.allAisles();
        assertNotNull(aisles);
        assertFalse(aisles.isEmpty(), "Should return at least one aisle");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAisleById_Success() {
        Aisle existing = persistAisle("AISLE_02", persistBuilding("BLD_A2"));

        Aisle found = aisleResource.aisle(existing.id);
        assertNotNull(found);
        assertEquals("AISLE_02", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAisleById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            aisleResource.aisle(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAisleByCode_Success() {
        persistAisle("AISLE_03", persistBuilding("BLD_A3"));

        Aisle found = aisleResource.aisleByCode("AISLE_03");
        assertNotNull(found);
        assertEquals("AISLE_03", found.code);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAisleByCode_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            aisleResource.aisleByCode("AISLE_99");
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateAisle_AllFields_Success() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_A4");

        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "AISLE_10";
        input.lengthMeters = 25.0;
        input.aisleType = "CIRCULAR";
        input.connectedToHead = "PREV";
        input.connectedToTail = "NEXT";
        input.buildingId = building.id;

        Aisle created = aisleResource.createAisle(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("AISLE_10", created.code);
        assertEquals(25.0, created.lengthMeters);
        assertEquals("CIRCULAR", created.aisleType);
        assertEquals("PREV", created.connectedToHead);
        assertEquals("NEXT", created.connectedToTail);
        assertEquals(building.id, created.building.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateAisle_MinimalFields_Success() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_A5");

        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "AISLE_11";
        input.buildingId = building.id;
        // other fields null

        Aisle created = aisleResource.createAisle(input);

        assertNotNull(created);
        assertEquals("AISLE_11", created.code);
        assertNull(created.lengthMeters);
        assertNull(created.aisleType);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateAisle_DuplicateCode() {
        StoreBuilding building = persistBuilding("BLD_A6");
        persistAisle("AISLE_12", building);

        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "AISLE_12";
        input.buildingId = building.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            aisleResource.createAisle(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateAisle_NullBuildingId() {
        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "AISLE_13";
        input.buildingId = null; // mandatory

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            aisleResource.createAisle(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Building ID is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateAisle_BuildingNotFound() {
        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "AISLE_14";
        input.buildingId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            aisleResource.createAisle(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateAisle_AllFields_Success() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_A7");
        StoreBuilding otherBuilding = persistBuilding("BLD_A8");
        Aisle existing = persistAisle("AISLE_20", building);

        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "AISLE_21";
        input.lengthMeters = 30.0;
        input.aisleType = "CIRCULAR";
        input.connectedToHead = "NEW_HEAD";
        input.connectedToTail = "NEW_TAIL";
        input.buildingId = otherBuilding.id;

        Aisle updated = aisleResource.updateAisle(existing.id, input);

        assertEquals("AISLE_21", updated.code);
        assertEquals(30.0, updated.lengthMeters);
        assertEquals("CIRCULAR", updated.aisleType);
        assertEquals("NEW_HEAD", updated.connectedToHead);
        assertEquals("NEW_TAIL", updated.connectedToTail);
        assertEquals(otherBuilding.id, updated.building.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateAisle_SameCode_NoConflict() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_A9");
        Aisle existing = persistAisle("AISLE_22", building);

        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "AISLE_22"; // same code: uniqueness check must be skipped
        input.lengthMeters = 99.0;

        Aisle updated = aisleResource.updateAisle(existing.id, input);
        assertEquals("AISLE_22", updated.code);
        assertEquals(99.0, updated.lengthMeters);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateAisle_ConflictingCode() {
        StoreBuilding building = persistBuilding("BLD_A10");
        Aisle existing = persistAisle("AISLE_23", building);
        persistAisle("AISLE_24", building);

        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "AISLE_24";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            aisleResource.updateAisle(existing.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateAisle_NotFound() {
        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.code = "GHOST";

        assertThrows(NoSuchElementException.class, () -> {
            aisleResource.updateAisle(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateAisle_BuildingNotFound() {
        Aisle existing = persistAisle("AISLE_25", persistBuilding("BLD_A11"));

        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.buildingId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            aisleResource.updateAisle(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateAisle_PartialUpdate_KeepsOtherFields() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_A12");
        Aisle existing = persistAisle("AISLE_26", building);

        AisleResource.AisleRecord input = new AisleResource.AisleRecord();
        input.aisleType = "CIRCULAR"; // only type changes

        Aisle updated = aisleResource.updateAisle(existing.id, input);
        assertEquals("AISLE_26", updated.code, "Code should remain unchanged");
        assertEquals(12.5, updated.lengthMeters, "Length should remain unchanged");
        assertEquals(building.id, updated.building.id, "Building should remain unchanged");
        assertEquals("CIRCULAR", updated.aisleType);
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteAisle_Success() throws GraphQLException {
        Aisle existing = persistAisle("AISLE_27", persistBuilding("BLD_A13"));

        boolean result = aisleResource.deleteAisle(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(Aisle.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteAisle_NotFound() throws GraphQLException {
        boolean result = aisleResource.deleteAisle(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testAisleRecord_ToString() {
        AisleResource.AisleRecord record = new AisleResource.AisleRecord();
        record.code = "AISLE_REC";
        record.lengthMeters = 10.0;
        record.aisleType = "LINEAR";
        record.connectedToHead = "H";
        record.connectedToTail = "T";
        record.buildingId = 5L;

        assertEquals("AisleRecord [code=AISLE_REC, lengthMeters=10.0, aisleType=LINEAR, connectedToHead=H, connectedToTail=T, flowType=null, shapeType=null, headNodeCode=null, tailNodeCode=null, waypoints=null, buildingId=5]",
                record.toString());
    }
}
