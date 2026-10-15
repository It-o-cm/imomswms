package com.intermarche.e2e.graphql.store;

import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.graphql.store.BufferResource;
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
 * Integration tests for {@link BufferResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class BufferResourceTest {

    @Inject
    BufferResource bufferResource;

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
     * Creates and persists a buffer directly via the entity.
     */
    private Buffer persistBuffer(StoreBuilding building) {
        Buffer buffer = new Buffer();
        buffer.building = building;
        buffer.purpose = Buffer.BufferPurpose.STAGING;
        buffer.locationDescription = "Dock 1";
        buffer.ambientCapacity = 10;
        buffer.chilledCapacity = 5;
        buffer.frozenCapacity = 2;
        buffer.maxWeightKg = 500.0;
        buffer.persist();
        return buffer;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testAllBuffers() {
        persistBuffer(persistBuilding("BLD_B1"));

        List<Buffer> buffers = bufferResource.allBuffers();
        assertNotNull(buffers);
        assertFalse(buffers.isEmpty(), "Should return at least one buffer");
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testBufferById_Success() {
        Buffer existing = persistBuffer(persistBuilding("BLD_B2"));

        Buffer found = bufferResource.buffer(existing.id);
        assertNotNull(found);
        assertEquals(existing.id, found.id);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testBufferById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            bufferResource.buffer(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testBufferByBuildingId_Success() {
        StoreBuilding building = persistBuilding("BLD_B3");
        Buffer existing = persistBuffer(building);

        Buffer found = bufferResource.bufferByBuildingId(building.id);
        assertNotNull(found);
        assertEquals(existing.id, found.id);
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testBufferByBuildingId_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            bufferResource.bufferByBuildingId(9999L);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Create (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateBuffer_AllFields_Success() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_B4");

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = building.id;
        input.purpose = "processing"; // lowercase on purpose: must be uppercased
        input.locationDescription = "Zone A";
        input.active = false;
        input.ambientCapacity = 20;
        input.chilledCapacity = 8;
        input.frozenCapacity = 4;
        input.maxWeightKg = 750.5;

        Buffer created = bufferResource.createBuffer(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals(building.id, created.building.id);
        assertEquals(Buffer.BufferPurpose.PROCESSING, created.purpose);
        assertEquals("Zone A", created.locationDescription);
        assertFalse(created.active);
        assertEquals(20, created.ambientCapacity);
        assertEquals(8, created.chilledCapacity);
        assertEquals(4, created.frozenCapacity);
        assertEquals(750.5, created.maxWeightKg);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateBuffer_MinimalFields_UsesDefaults() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_B5");

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = building.id;
        // all other fields null

        Buffer created = bufferResource.createBuffer(input);

        assertNotNull(created);
        assertEquals(Buffer.BufferPurpose.STAGING, created.purpose, "Purpose should keep entity default");
        assertTrue(created.active, "Active should keep entity default");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateBuffer_UnknownPurpose_IsSwallowed() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_B6");

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = building.id;
        input.purpose = "NOT_A_PURPOSE"; // invalid: logged and swallowed, purpose unchanged

        Buffer created = bufferResource.createBuffer(input);

        assertNotNull(created);
        assertEquals(Buffer.BufferPurpose.STAGING, created.purpose, "Unknown purpose should be ignored");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateBuffer_BuildingNotFound() {
        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            bufferResource.createBuffer(input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateBuffer_DuplicateBuilding() {
        StoreBuilding building = persistBuilding("BLD_B7");
        persistBuffer(building);

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = building.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            bufferResource.createBuffer(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    // --------------------------------------------------
    // Mutation Tests: Update (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateBuffer_AllFields_Success() throws GraphQLException {
        Buffer existing = persistBuffer(persistBuilding("BLD_B8"));

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.purpose = "RECEIPT";
        input.locationDescription = "Zone B";
        input.active = false;
        input.ambientCapacity = 30;
        input.chilledCapacity = 12;
        input.frozenCapacity = 6;
        input.maxWeightKg = 1000.0;

        Buffer updated = bufferResource.updateBuffer(existing.id, input);

        assertEquals(Buffer.BufferPurpose.RECEIPT, updated.purpose);
        assertEquals("Zone B", updated.locationDescription);
        assertFalse(updated.active);
        assertEquals(30, updated.ambientCapacity);
        assertEquals(12, updated.chilledCapacity);
        assertEquals(6, updated.frozenCapacity);
        assertEquals(1000.0, updated.maxWeightKg);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateBuffer_ChangeBuilding_Success() throws GraphQLException {
        Buffer existing = persistBuffer(persistBuilding("BLD_B9"));
        StoreBuilding newBuilding = persistBuilding("BLD_B10");

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = newBuilding.id;

        Buffer updated = bufferResource.updateBuffer(existing.id, input);
        assertEquals(newBuilding.id, updated.building.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateBuffer_SameBuilding_NoConflict() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_B11");
        Buffer existing = persistBuffer(building);

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = building.id; // same building: uniqueness check must be skipped
        input.locationDescription = "Moved";

        Buffer updated = bufferResource.updateBuffer(existing.id, input);
        assertEquals(building.id, updated.building.id);
        assertEquals("Moved", updated.locationDescription);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateBuffer_ChangeBuilding_NotFound() {
        Buffer existing = persistBuffer(persistBuilding("BLD_B12"));

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            bufferResource.updateBuffer(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateBuffer_ChangeBuilding_ConflictingBuffer() {
        Buffer existing = persistBuffer(persistBuilding("BLD_B13"));
        StoreBuilding occupiedBuilding = persistBuilding("BLD_B14");
        persistBuffer(occupiedBuilding);

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.buildingId = occupiedBuilding.id;

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            bufferResource.updateBuffer(existing.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateBuffer_NotFound() {
        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.locationDescription = "Ghost";

        assertThrows(NoSuchElementException.class, () -> {
            bufferResource.updateBuffer(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateBuffer_UnknownPurpose_IsSwallowed() throws GraphQLException {
        Buffer existing = persistBuffer(persistBuilding("BLD_B15"));

        BufferResource.BufferRecord input = new BufferResource.BufferRecord();
        input.purpose = "GARBAGE";

        Buffer updated = bufferResource.updateBuffer(existing.id, input);
        assertEquals(Buffer.BufferPurpose.STAGING, updated.purpose, "Unknown purpose should be ignored");
    }

    // --------------------------------------------------
    // Mutation Tests: Delete (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteBuffer_Success() throws GraphQLException {
        Buffer existing = persistBuffer(persistBuilding("BLD_B16"));

        boolean result = bufferResource.deleteBuffer(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(Buffer.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteBuffer_NotFound() throws GraphQLException {
        boolean result = bufferResource.deleteBuffer(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testBufferRecord_ToString() {
        BufferResource.BufferRecord record = new BufferResource.BufferRecord();
        record.buildingId = 3L;
        record.purpose = "STAGING";
        record.ambientCapacity = 10;
        record.chilledCapacity = 5;
        record.frozenCapacity = 2;

        assertEquals("BufferRecord [buildingId=3, purpose=STAGING, ambientCapacity=10, chilledCapacity=5, frozenCapacity=2]",
                record.toString());
    }
}
