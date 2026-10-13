package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Aisle;
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
 * REST Endpoint for bulk importing or updating Aisles.
 * <p>
 * Expected CSV format (6 columns):
 * Code, BuildingCode, LengthMeters, AisleType, ConnectedToHead, ConnectedToTail
 */
@Path("/aisles/import")
@ApplicationScoped
@RunOnVirtualThread
public class AisleCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(AisleCsvResource.class);

    /**
     * Imports or updates aisles from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importAisles(InputStream inputStream) {
        // 6 columns expected
        return this.importCsvStream(inputStream, 6);
    }

    /**
     * Prepares the context for a chunk.
     * Fetches existing Aisles and referenced Buildings.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> context = new HashMap<>();

        // 1. Fetch existing Aisles by Code
        List<Aisle> aisles = Aisle.list("code IN ?1", targetCodes);
        for (Aisle a : aisles) {
            context.put(a.code, a);
        }

        // 2. Fetch referenced Buildings
        Set<String> buildingCodes = new HashSet<>();
        for (LineData d : parsedLines) {
            String buildingCode = safeGet(d.parts, 1);
            if (buildingCode != null) buildingCodes.add(buildingCode);
        }

        if (!buildingCodes.isEmpty()) {
            List<Building> buildings = Building.list("buildingCode IN ?1", buildingCodes);
            for (Building b : buildings) {
                context.put("BUILDING_" + b.buildingCode, b);
            }
        }

        return context;
    }

    /**
     * Processes a single line to create or update an Aisle.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String[] parts = data.parts;
        String code = data.code; // Col 0
        String buildingCode = safeGet(parts, 1); // Col 1

        // 1. Resolve Building
        Building building = (Building) entityMap.get("BUILDING_" + buildingCode);
        if (building == null) {
            LOGGER.error("Building with code " + buildingCode + " not found for aisle " + code);
            throw new IllegalArgumentException("Building with code '" + buildingCode + "' not found.");
        }

        // 2. Resolve Aisle
        Aisle aisle = (Aisle) entityMap.get(code);
        boolean isUpdate = (aisle != null);

        if (!isUpdate) {
            // Creation
            aisle = new Aisle();
            aisle.code = code;
            aisle.building = building; // Attach building immediately
            populateAisle(parts, aisle);
            aisle.persist();
            entityMap.put(code, aisle);
            counters[0]++; // Created
        } else {
            // Update Check
            int incomingChecksum = computeChecksum(parts, code, building.id);
            if (aisle.checksum == incomingChecksum) {
                return; // No change needed
            }
            counters[1]++; // Updated
            aisle.building = building; // Update building in case it changed
            populateAisle(parts, aisle);
        }
    }

    /**
     * Finds a single Aisle entity for fallback processing.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        // Assuming Code is unique globally as per entity definition
        return Aisle.find("code", data.code).firstResult();
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Populates the Aisle entity from CSV parts.
     */
     void populateAisle(String[] parts, Aisle aisle) {
        // Col 2: LengthMeters
        aisle.lengthMeters = safeParseDouble(parts, 2);

        // Col 3: AisleType
        aisle.aisleType = safeGet(parts, 3);

        // Col 4: ConnectedToHead
        aisle.connectedToHead = safeGet(parts, 4);

        // Col 5: ConnectedToTail
        aisle.connectedToTail = safeGet(parts, 5);
    }

    /**
     * Computes checksum for validation.
     */
     int computeChecksum(String[] parts, String code, Long buildingId) {
        return Objects.hash(
                code,                       // code
                safeParseDouble(parts, 2),  // lengthMeters
                safeGet(parts, 3),          // aisleType
                safeGet(parts, 4),          // connectedToHead
                safeGet(parts, 5),          // connectedToTail
                buildingId                  // building.id
        );
    }

}