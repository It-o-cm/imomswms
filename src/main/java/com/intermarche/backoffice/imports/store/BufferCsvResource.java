package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.*;

/**
 * REST Endpoint for bulk importing or updating Buffers.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle {@link Buffer} entities.
 * It defines the CSV structure (8 columns) and the business logic for creating or updating buffer configurations.
 * <p>
 * It leverages the parent's Staged Fallback algorithm (1000 -> 100 -> 10 -> 1) to process large files efficiently.
 * <p>
 * Since a Buffer has a 1-to-1 relationship with a {@link Building}, the CSV uses the Building Code as the unique key.
 * <p>
 * Expected CSV format (8 columns, pipe-separated):
 * <pre>
 * BufferCode|BuildingCode|Purpose|AmbientCapacity|ChilledCapacity|FrozenCapacity|MaxWeightKg|LocationDescription|Active
 * </pre>
 * <ul>
 *   <li><b>BufferCode</b>: Unique code for the Buffer (String).</li>
 *   <li><b>BuildingCode</b>: The unique code of the Building (String).</li>
 *   <li><b>Purpose</b>: Operational purpose (STAGING, PROCESSING, RECEIPT).</li>
 *   <li><b>AmbientCapacity</b>: Max totes for ambient items (Integer).</li>
 *   <li><b>ChilledCapacity</b>: Max totes for chilled items (Integer).</li>
 *   <li><b>FrozenCapacity</b>: Max totes for frozen items (Integer).</li>
 *   <li><b>MaxWeightKg</b>: Maximum weight capacity (Double).</li>
 *   <li><b>LocationDescription</b>: Physical location description (String).</li>
 *   <li><b>Active</b>: Status flag (Boolean).</li>
 * </ul>
 */
@Path("/buffers/import")
@ApplicationScoped
@RunOnVirtualThread
public class BufferCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(BufferCsvResource.class);

    /**
     * Imports or updates Buffers from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importBuffers(InputStream inputStream) {
        // 8 columns expected
        return this.importCsvStream(inputStream, 8);
    }

    /**
     * Prepares the context for a chunk of lines.
     * <p>
     * <b>Phase 1 (Specific):</b> Bulk fetches {@link Building}s referenced in the CSV (now from Column 1).
     * Then bulk fetches existing {@link Buffer}s associated with these buildings.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique Buffer codes in this chunk (extracted from Col 0).
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A map containing the cached entities (Buildings by code, Buffers by Building ID).
     */
    @Override
    public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> context = new HashMap<>();

        // 1. Fetch referenced Buildings. The Building codes are in Column 1:
        //    targetCodes holds the Buffer codes (Column 0), not the building codes.
        Set<String> buildingCodes = new HashSet<>();
        for (LineData d : parsedLines) {
            String buildingCode = safeGet(d.parts, 1);
            if (buildingCode != null) buildingCodes.add(buildingCode);
        }
        List<Building> buildings = buildingCodes.isEmpty()
                ? List.of()
                : Building.list("buildingCode IN ?1", buildingCodes);
        Set<Long> buildingIds = new HashSet<>();
        Map<String, Building> buildingMap = new HashMap<>();

        for (Building b : buildings) {
            buildingMap.put(b.buildingCode, b);
            buildingIds.add(b.id);
        }

        // 2. Fetch existing Buffers for these buildings
        Map<String, Buffer> bufferMap = new HashMap<>();
        if (!buildingIds.isEmpty()) {
            List<Buffer> buffers = Buffer.list("building.id IN ?1", buildingIds);
            for (Buffer buffer : buffers) {
                bufferMap.put(buffer.bufferCode, buffer);
            }
        }

        context.put("BUILDINGS", buildingMap);
        context.put("BUFFERS", bufferMap);

        return context;
    }

    /**
     * Implements the specific logic for creating or updating a {@link Buffer} entity.
     * <p>
     * <b>Logic Flow:</b>
     * <ul>
     *   <li>1. Retrieves the {@link Building} from context using Building Code (Column 1).</li>
     *   <li>2. Retrieves existing Buffer from context using Building ID.</li>
     *   <li>3. Populates data (using Buffer Code from Column 0).</li>
     *   <li>4. Persists or updates the entity.</li>
     * </ul>
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  A map of cached entities.
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String[] parts = data.parts;
        // SWAPPED: BuildingCode is now at index 1
        String buildingCode = parts[1];
        Building building = Building.findByCode(buildingCode);
        if (building == null) {
            LOGGER.error("Building with code " + buildingCode + " not found.");
            throw new IllegalArgumentException("Building with code '" + buildingCode + "' not found.");
        }
        // 2. Resolve Buffer
        @SuppressWarnings("unchecked")
        Map<String, Buffer> bufferMap = (Map<String, Buffer>) entityMap.get("BUFFERS");
        Buffer buffer = bufferMap.get(data.code);
        if (buffer != null) {
            // Re-fetch the managed entity: the cached instance was loaded outside the
            // transaction (detached), so mutating it would be lost at commit. A null
            // result means the cached entity was a phantom from a rolled-back chunk.
            buffer = Buffer.findById(buffer.id);
        }
        if (buffer == null) {
            // Creation
            buffer = new Buffer();
            buffer.building = building;
            populateBuffer(parts, buffer);
            buffer.persist();
            bufferMap.put(data.code, buffer);
            counters[0]++; // Created
        } else {
            // Update Check
            int incomingChecksum = computeChecksum(parts);
            if (buffer.checksum == incomingChecksum) {
                return; // No change
            }
            counters[1]++; // Updated
            populateBuffer(parts, buffer);
        }
    }

    /**
     * Implements the specific logic to find a fresh Buffer from the database.
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The Buffer entity or null if not found.
     */
    @Override
    public Object findEntityForLine(LineData data) {
        // SWAPPED: BuildingCode is at index 1
        String buildingCode = data.parts[1];
        Building building = Building.find("buildingCode", buildingCode).firstResult();
        if (building == null) return null;
        return Buffer.findByBuilding(building);
    }

    /**
     * Prepares the full context for a single line in 1-by-1 fallback mode.
     * <p>
     * The generic implementation only puts the found entity under the line code,
     * but the buffer logic expects a "BUFFERS" map keyed by buffer code. Without
     * this override, fallback processing would fail with a NullPointerException
     * on every line. This override rebuilds that context with a fresh lookup so
     * that fallback processing behaves exactly like chunk processing.
     *
     * @param data The parsed CSV line data.
     * @return The context map for this single line.
     */
    @Override
    public Map<String, Object> prepareContextForLine(LineData data) {
        Map<String, Object> context = new HashMap<>();
        String buildingCode = safeGet(data.parts, 1);
        Building building = Building.find("buildingCode", buildingCode).firstResult();
        if (building != null) {
            Map<String, Buffer> bufferMap = new HashMap<>();
            Buffer buffer = Buffer.findByBuilding(building);
            if (buffer != null) {
                bufferMap.put(buffer.bufferCode, buffer);
            }
            context.put("BUFFERS", bufferMap);
        }
        return context;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Populates a Buffer entity with data from the parsed CSV line.
     * Indices are adjusted for the swap (Col 0 = BufferCode, Col 1 = BuildingCode).
     */
    private void populateBuffer(String[] parts, Buffer buffer) {
        // SWAPPED: BufferCode is now at index 0
        buffer.bufferCode = parts[0] != null ? parts[0].trim() : null;

        // Purpose remains at index 2 (originally 2, shift didn't affect indices >= 2 relative to each other)
        String purposeStr = safeGet(parts, 2);
        if (purposeStr != null && !purposeStr.isBlank()) {
            try {
                buffer.purpose = Buffer.BufferPurpose.valueOf(purposeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown BufferPurpose '" + purposeStr + "' for buffer " + buffer.bufferCode);
            }
        }

        // Capacities remain at indices 3, 4, 5
        buffer.ambientCapacity = safeParseInt(parts, 3);
        buffer.chilledCapacity = safeParseInt(parts, 4);
        buffer.frozenCapacity = safeParseInt(parts, 5);

        // MaxWeight at 6
        buffer.maxWeightKg = safeParseDouble(parts, 6);

        // Location at 7
        buffer.locationDescription = safeGetOrNull(parts, 7);

        // Active at 8
        buffer.active = safeParseBoolean(parts, 8);
    }

    /**
     * Computes a checksum.
     * <p>
     * Mirrors {@link Buffer#getChecksum()}: the building is represented by its
     * natural key (buildingCode, Column 1), never by the surrogate id — the
     * resolved Building may be a lazy Hibernate proxy whose id field reads null.
     */
    private int computeChecksum(String[] parts) {
        String purposeStr = safeGet(parts, 2);
        Object purposeObj = purposeStr;
        try {
            if (purposeStr != null) {
                purposeObj = Buffer.BufferPurpose.valueOf(purposeStr.toUpperCase());
            }
        } catch (IllegalArgumentException e) { /* ignore */ }

        return Objects.hash(
                parts[0] != null ? parts[0].trim() : null, // BufferCode
                safeGetOrNull(parts, 1), // BuildingCode
                purposeObj,
                safeGetOrNull(parts, 7), // LocationDescription
                safeParseBoolean(parts, 8), // Active
                safeParseInt(parts, 3), // Ambient
                safeParseInt(parts, 4), // Chilled
                safeParseInt(parts, 5), // Frozen
                safeParseDouble(parts, 6) // MaxWeight
        );
    }
}