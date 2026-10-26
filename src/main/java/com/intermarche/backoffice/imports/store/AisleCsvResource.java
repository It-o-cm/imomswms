package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.AisleWaypoint;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNode;
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
 * Expected CSV format (6 mandatory columns, 5 optional):
 * Code, BuildingCode, LengthMeters, AisleType, ConnectedToHead, ConnectedToTail,
 * FlowType, ShapeType, HeadNode, TailNode, Waypoints
 * <p>
 * The optional geometry columns extend the aisle with its store plan layer:
 * FlowType (LINEAR, DIAGONAL, CIRCULAR), ShapeType (STRAIGHT, CURVED), the codes
 * of the head/tail {@link PlanNode} (import the plan nodes first), and the
 * waypoints in the format {@code x1,y1;x2,y2;...}.
 * <p>
 * The CSV is authoritative for every column it carries: an absent or blank
 * optional column clears the corresponding field on the entity, so that the
 * checksum computed from the CSV always mirrors {@link Aisle#getChecksum()}.
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
     * Fetches existing Aisles, referenced Buildings and referenced Plan Nodes.
     */
    @Override
    public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
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

        // 3. Fetch referenced Plan Nodes (geometry columns: HeadNode, TailNode)
        Set<String> nodeCodes = new HashSet<>();
        for (LineData d : parsedLines) {
            String headNode = safeGetOrNull(d.parts, 8);
            if (headNode != null) nodeCodes.add(headNode);
            String tailNode = safeGetOrNull(d.parts, 9);
            if (tailNode != null) nodeCodes.add(tailNode);
        }

        if (!nodeCodes.isEmpty()) {
            List<PlanNode> nodes = PlanNode.list("code IN ?1", nodeCodes);
            for (PlanNode n : nodes) {
                context.put("NODE_" + n.code, n);
            }
        }

        return context;
    }

    /**
     * Processes a single line to create or update an Aisle.
     */
    @Override
    public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
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
        if (aisle != null) {
            // Re-fetch the managed entity: the cached instance was loaded outside the
            // transaction (detached), so mutating it would be lost at commit. A null
            // result means the cached entity was a phantom from a rolled-back chunk.
            aisle = Aisle.findById(aisle.id);
        }

        if (aisle == null) {
            // Creation
            aisle = new Aisle();
            aisle.code = code;
            aisle.building = building; // Attach building immediately
            populateAisle(parts, aisle, entityMap);
            aisle.persist();
            entityMap.put(code, aisle);
            counters[0]++; // Created
        } else {
            // Update Check
            int incomingChecksum = computeChecksum(parts, code);
            if (aisle.checksum == incomingChecksum) {
                return; // No change needed
            }
            counters[1]++; // Updated
            aisle.building = building; // Update building in case it changed
            populateAisle(parts, aisle, entityMap);
        }
    }

    /**
     * Finds a single Aisle entity for fallback processing.
     */
    @Override
    public Object findEntityForLine(LineData data) {
        // Assuming Code is unique globally as per entity definition
        return Aisle.find("code", data.code).firstResult();
    }

    /**
     * Prepares the full context for a single line in 1-by-1 fallback mode.
     * <p>
     * The generic implementation only puts the found entity under the line code,
     * but the aisle logic also needs the referenced Building and the referenced
     * Plan Nodes (geometry columns). This override rebuilds that context with a
     * fresh lookup so that fallback processing behaves exactly like chunk processing.
     *
     * @param data The parsed CSV line data.
     * @return The context map for this single line.
     */
    @Override
    public Map<String, Object> prepareContextForLine(LineData data) {
        Map<String, Object> context = new HashMap<>();
        Object aisle = findEntityForLine(data);
        if (aisle != null) {
            context.put(data.code, aisle);
        }
        String buildingCode = safeGet(data.parts, 1);
        if (buildingCode != null) {
            Building building = Building.find("buildingCode", buildingCode).firstResult();
            if (building != null) {
                context.put("BUILDING_" + buildingCode, building);
            }
        }
        // Geometry columns: HeadNode (8), TailNode (9)
        for (int index : new int[]{8, 9}) {
            String nodeCode = safeGetOrNull(data.parts, index);
            if (nodeCode != null) {
                PlanNode node = PlanNode.find("code", nodeCode).firstResult();
                if (node != null) {
                    context.put("NODE_" + nodeCode, node);
                }
            }
        }
        return context;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Populates the Aisle entity from CSV parts, including the optional geometry
     * columns (flow type, shape type, head/tail nodes, waypoints).
     * <p>
     * The CSV is authoritative: an absent or blank optional column clears the
     * corresponding field, so the entity always mirrors the CSV line exactly.
     *
     * @param parts   The CSV columns.
     * @param aisle   The aisle to populate.
     * @param context The chunk context holding the referenced Plan Nodes under
     *                the {@code NODE_<code>} keys.
     */
    void populateAisle(String[] parts, Aisle aisle, Map<String, Object> context) {
        // Col 2: LengthMeters
        aisle.lengthMeters = safeParseDouble(parts, 2);

        // Col 3: AisleType
        aisle.aisleType = safeGetOrNull(parts, 3);

        // Col 4: ConnectedToHead
        aisle.connectedToHead = safeGetOrNull(parts, 4);

        // Col 5: ConnectedToTail
        aisle.connectedToTail = safeGetOrNull(parts, 5);

        // Col 6: FlowType (optional)
        aisle.flowType = safeGetOrNull(parts, 6);

        // Col 7: ShapeType (optional)
        aisle.shapeType = safeGetOrNull(parts, 7);

        // Col 8: HeadNode (optional)
        aisle.headNode = resolveNode(parts, 8, aisle.code, context);

        // Col 9: TailNode (optional)
        aisle.tailNode = resolveNode(parts, 9, aisle.code, context);

        // Col 10: Waypoints (optional, "x1,y1;x2,y2;...")
        applyWaypoints(parts, aisle);
    }

    /**
     * Resolves a Plan Node referenced by a CSV column against the chunk context.
     *
     * @param parts     The CSV columns.
     * @param index     The column index of the node code.
     * @param aisleCode The aisle code (for error reporting).
     * @param context   The chunk context holding the {@code NODE_<code>} entries.
     * @return The resolved Plan Node, or null if the column is blank.
     * @throws IllegalArgumentException if the node code is set but unknown.
     */
    private PlanNode resolveNode(String[] parts, int index, String aisleCode, Map<String, Object> context) {
        String nodeCode = safeGetOrNull(parts, index);
        if (nodeCode == null) return null;
        PlanNode node = (PlanNode) context.get("NODE_" + nodeCode);
        if (node == null) {
            LOGGER.error("Plan node with code " + nodeCode + " not found for aisle " + aisleCode);
            throw new IllegalArgumentException("Plan node with code '" + nodeCode + "' not found.");
        }
        return node;
    }

    /**
     * Replaces the waypoints of the aisle from the CSV column
     * ({@code x1,y1;x2,y2;...}). A blank column clears the waypoints.
     *
     * @param parts The CSV columns.
     * @param aisle The aisle to update.
     */
    private void applyWaypoints(String[] parts, Aisle aisle) {
        List<double[]> points = PlanZoneCsvResource.parsePoints(safeGetOrNull(parts, 10));
        aisle.waypoints.clear();
        for (int i = 0; i < points.size(); i++) {
            AisleWaypoint waypoint = new AisleWaypoint();
            waypoint.aisle = aisle;
            waypoint.position = i;
            waypoint.x = points.get(i)[0];
            waypoint.y = points.get(i)[1];
            aisle.waypoints.add(waypoint);
        }
    }

    /**
     * Computes checksum for validation.
     * <p>
     * Mirrors {@link Aisle#getChecksum()}: the building is represented by its
     * natural key (buildingCode, Column 1), never by the surrogate id — the
     * resolved Building may be a lazy Hibernate proxy whose id field reads null.
     * The head/tail nodes are represented by their codes (Columns 8 and 9), and
     * the waypoints hash iterates the points in order, exactly like the entity
     * iterates its ordered waypoints.
     */
    int computeChecksum(String[] parts, String code) {
        int waypointsHash = 0;
        List<double[]> points = PlanZoneCsvResource.parsePoints(safeGetOrNull(parts, 10));
        for (int i = 0; i < points.size(); i++) {
            waypointsHash = 31 * waypointsHash + Objects.hash(i, points.get(i)[0], points.get(i)[1]);
        }
        return Objects.hash(
                code,                       // code
                safeParseDouble(parts, 2),  // lengthMeters
                safeGetOrNull(parts, 3),    // aisleType
                safeGetOrNull(parts, 4),    // connectedToHead
                safeGetOrNull(parts, 5),    // connectedToTail
                safeGetOrNull(parts, 6),    // flowType
                safeGetOrNull(parts, 7),    // shapeType
                safeGetOrNull(parts, 8),    // headNode code
                safeGetOrNull(parts, 9),    // tailNode code
                waypointsHash,              // waypoints (ordered)
                safeGetOrNull(parts, 1)     // buildingCode
        );
    }

}