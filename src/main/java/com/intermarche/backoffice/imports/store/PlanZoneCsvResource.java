package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.PlanZoneVertex;
import com.intermarche.backoffice.domain.store.TemperatureZone;
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
 * REST Endpoint for bulk importing or updating Plan Zones (geometric areas of
 * the store plan).
 * <p>
 * Expected CSV format (8 columns):
 * Code, BuildingCode, Label, Color, TemperatureZone, Vertices, OffsetX, OffsetY
 * <p>
 * Vertices format: {@code x1,y1;x2,y2;x3,y3} (semicolon between points, comma
 * between coordinates). At least 3 vertices are required.
 * TemperatureZone is optional (AMBIENT, CHILLED, FROZEN, HOUSEHOLD).
 * OffsetX/OffsetY are optional label offsets in meters.
 */
@Path("/plan-zones/import")
@ApplicationScoped
@RunOnVirtualThread
public class PlanZoneCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(PlanZoneCsvResource.class);

    /**
     * Minimum number of vertices required to form a valid polygon.
     */
    private static final int MIN_VERTICES = 3;

    /**
     * Imports or updates plan zones from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importPlanZones(InputStream inputStream) {
        // 8 columns expected
        return this.importCsvStream(inputStream, 8);
    }

    /**
     * Prepares the context for a chunk.
     * Fetches existing Plan Zones and referenced Buildings.
     */
    @Override
    public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> context = new HashMap<>();

        // 1. Fetch existing Plan Zones by Code
        List<PlanZone> zones = PlanZone.list("code IN ?1", targetCodes);
        for (PlanZone z : zones) {
            context.put(z.code, z);
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
     * Processes a single line to create or update a Plan Zone.
     */
    @Override
    public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String[] parts = data.parts;
        String code = data.code; // Col 0
        String buildingCode = safeGet(parts, 1); // Col 1

        // 1. Resolve Building
        Building building = (Building) entityMap.get("BUILDING_" + buildingCode);
        if (building == null) {
            LOGGER.error("Building with code " + buildingCode + " not found for plan zone " + code);
            throw new IllegalArgumentException("Building with code '" + buildingCode + "' not found.");
        }

        // 2. Resolve Plan Zone
        PlanZone zone = (PlanZone) entityMap.get(code);
        if (zone != null) {
            // Re-fetch the managed entity: the cached instance was loaded outside the
            // transaction (detached), so mutating it would be lost at commit. A null
            // result means the cached entity was a phantom from a rolled-back chunk.
            zone = PlanZone.findById(zone.id);
        }

        if (zone == null) {
            // Creation
            zone = new PlanZone();
            zone.code = code;
            zone.building = building; // Attach building immediately
            populateZone(parts, zone);
            zone.persist();
            entityMap.put(code, zone);
            counters[0]++; // Created
        } else {
            // Update Check
            int incomingChecksum = computeChecksum(parts, code);
            if (zone.checksum == incomingChecksum) {
                return; // No change needed
            }
            counters[1]++; // Updated
            zone.building = building; // Update building in case it changed
            populateZone(parts, zone);
        }
    }

    /**
     * Finds a single Plan Zone entity for fallback processing.
     */
    @Override
    public Object findEntityForLine(LineData data) {
        // Assuming Code is unique globally as per entity definition
        return PlanZone.find("code", data.code).firstResult();
    }

    /**
     * Prepares the full context for a single line in 1-by-1 fallback mode.
     * <p>
     * The generic implementation only puts the found entity under the line code,
     * but the zone logic also needs the referenced Building. This override rebuilds
     * that context with a fresh lookup so that fallback processing behaves
     * exactly like chunk processing.
     *
     * @param data The parsed CSV line data.
     * @return The context map for this single line.
     */
    @Override
    public Map<String, Object> prepareContextForLine(LineData data) {
        Map<String, Object> context = new HashMap<>();
        Object zone = findEntityForLine(data);
        if (zone != null) {
            context.put(data.code, zone);
        }
        String buildingCode = safeGet(data.parts, 1);
        if (buildingCode != null) {
            Building building = Building.find("buildingCode", buildingCode).firstResult();
            if (building != null) {
                context.put("BUILDING_" + buildingCode, building);
            }
        }
        return context;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Populates the Plan Zone entity from CSV parts, replacing its polygon.
     */
    void populateZone(String[] parts, PlanZone zone) {
        // Col 2: Label
        zone.label = safeGetOrNull(parts, 2);

        // Col 3: Color
        zone.color = safeGetOrNull(parts, 3);

        // Col 4: TemperatureZone (optional)
        String temp = safeGetOrNull(parts, 4);
        zone.temperatureZone = temp != null ? TemperatureZone.valueOf(temp) : null;

        // Col 5: Vertices ("x1,y1;x2,y2;...")
        List<double[]> points = parsePoints(safeGetOrNull(parts, 5));
        if (points.size() < MIN_VERTICES) {
            throw new IllegalArgumentException("A zone polygon requires at least " + MIN_VERTICES + " vertices.");
        }
        zone.vertices.clear();
        for (int i = 0; i < points.size(); i++) {
            PlanZoneVertex vertex = new PlanZoneVertex();
            vertex.zone = zone;
            vertex.position = i;
            vertex.x = points.get(i)[0];
            vertex.y = points.get(i)[1];
            zone.vertices.add(vertex);
        }

        // Col 6: LabelOffsetX
        zone.labelOffsetX = safeParseDouble(parts, 6);

        // Col 7: LabelOffsetY
        zone.labelOffsetY = safeParseDouble(parts, 7);
    }

    /**
     * Computes checksum for validation.
     * <p>
     * Mirrors {@link PlanZone#getChecksum()}: the building is represented by its
     * natural key (buildingCode, Column 1), never by the surrogate id — the
     * resolved Building may be a lazy Hibernate proxy whose id field reads null.
     * The vertices hash iterates the points in order, exactly like the entity
     * iterates its ordered vertices.
     */
    int computeChecksum(String[] parts, String code) {
        String temp = safeGetOrNull(parts, 4);
        int verticesHash = 0;
        List<double[]> points = parsePoints(safeGetOrNull(parts, 5));
        for (int i = 0; i < points.size(); i++) {
            verticesHash = 31 * verticesHash + Objects.hash(i, points.get(i)[0], points.get(i)[1]);
        }
        return Objects.hash(
                code,                       // code
                safeGetOrNull(parts, 2),    // label
                safeGetOrNull(parts, 3),    // color
                temp != null ? TemperatureZone.valueOf(temp) : null, // temperatureZone
                verticesHash,               // vertices (ordered)
                safeParseDouble(parts, 6),  // labelOffsetX
                safeParseDouble(parts, 7),  // labelOffsetY
                safeGetOrNull(parts, 1)     // buildingCode
        );
    }

    /**
     * Parses a point list in the format {@code x1,y1;x2,y2;...}.
     * <p>
     * Points with malformed coordinates are skipped.
     *
     * @param raw The raw CSV cell (may be null).
     * @return The list of parsed points (never null).
     */
    static List<double[]> parsePoints(String raw) {
        List<double[]> points = new ArrayList<>();
        if (raw == null || raw.isBlank()) return points;
        for (String token : raw.split(";")) {
            String[] xy = token.split(",");
            if (xy.length < 2) continue;
            try {
                points.add(new double[]{Double.parseDouble(xy[0].trim()), Double.parseDouble(xy[1].trim())});
            } catch (NumberFormatException e) {
                LOGGER.warn("Skipping malformed point: " + token);
            }
        }
        return points;
    }

}
