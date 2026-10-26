package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanNodeType;
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
 * REST Endpoint for bulk importing or updating Plan Nodes (topological nodes
 * of the store plan).
 * <p>
 * Expected CSV format (6 columns):
 * Code, BuildingCode, Label, NodeType, X, Y
 * <p>
 * NodeType is one of ENTRANCE, INTERSECTION, DEADEND.
 * X/Y are plan coordinates in meters (origin = building center).
 */
@Path("/plan-nodes/import")
@ApplicationScoped
@RunOnVirtualThread
public class PlanNodeCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(PlanNodeCsvResource.class);

    /**
     * Imports or updates plan nodes from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importPlanNodes(InputStream inputStream) {
        // 6 columns expected
        return this.importCsvStream(inputStream, 6);
    }

    /**
     * Prepares the context for a chunk.
     * Fetches existing Plan Nodes and referenced Buildings.
     */
    @Override
    public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> context = new HashMap<>();

        // 1. Fetch existing Plan Nodes by Code
        List<PlanNode> nodes = PlanNode.list("code IN ?1", targetCodes);
        for (PlanNode n : nodes) {
            context.put(n.code, n);
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
     * Processes a single line to create or update a Plan Node.
     */
    @Override
    public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String[] parts = data.parts;
        String code = data.code; // Col 0
        String buildingCode = safeGet(parts, 1); // Col 1

        // 1. Resolve Building
        Building building = (Building) entityMap.get("BUILDING_" + buildingCode);
        if (building == null) {
            LOGGER.error("Building with code " + buildingCode + " not found for plan node " + code);
            throw new IllegalArgumentException("Building with code '" + buildingCode + "' not found.");
        }

        // 2. Resolve Plan Node
        PlanNode node = (PlanNode) entityMap.get(code);
        if (node != null) {
            // Re-fetch the managed entity: the cached instance was loaded outside the
            // transaction (detached), so mutating it would be lost at commit. A null
            // result means the cached entity was a phantom from a rolled-back chunk.
            node = PlanNode.findById(node.id);
        }

        if (node == null) {
            // Creation
            node = new PlanNode();
            node.code = code;
            node.building = building; // Attach building immediately
            populateNode(parts, node);
            node.persist();
            entityMap.put(code, node);
            counters[0]++; // Created
        } else {
            // Update Check
            int incomingChecksum = computeChecksum(parts, code);
            if (node.checksum == incomingChecksum) {
                return; // No change needed
            }
            counters[1]++; // Updated
            node.building = building; // Update building in case it changed
            populateNode(parts, node);
        }
    }

    /**
     * Finds a single Plan Node entity for fallback processing.
     */
    @Override
    public Object findEntityForLine(LineData data) {
        // Assuming Code is unique globally as per entity definition
        return PlanNode.find("code", data.code).firstResult();
    }

    /**
     * Prepares the full context for a single line in 1-by-1 fallback mode.
     * <p>
     * The generic implementation only puts the found entity under the line code,
     * but the node logic also needs the referenced Building. This override rebuilds
     * that context with a fresh lookup so that fallback processing behaves
     * exactly like chunk processing.
     *
     * @param data The parsed CSV line data.
     * @return The context map for this single line.
     */
    @Override
    public Map<String, Object> prepareContextForLine(LineData data) {
        Map<String, Object> context = new HashMap<>();
        Object node = findEntityForLine(data);
        if (node != null) {
            context.put(data.code, node);
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
     * Populates the Plan Node entity from CSV parts.
     */
    void populateNode(String[] parts, PlanNode node) {
        // Col 2: Label
        node.label = safeGetOrNull(parts, 2);

        // Col 3: NodeType (ENTRANCE, INTERSECTION, DEADEND)
        String type = safeGetOrNull(parts, 3);
        node.nodeType = type != null ? PlanNodeType.valueOf(type) : null;

        // Col 4: X
        node.x = safeParseDouble(parts, 4);

        // Col 5: Y
        node.y = safeParseDouble(parts, 5);
    }

    /**
     * Computes checksum for validation.
     * <p>
     * Mirrors {@link PlanNode#getChecksum()}: the building is represented by its
     * natural key (buildingCode, Column 1), never by the surrogate id — the
     * resolved Building may be a lazy Hibernate proxy whose id field reads null.
     */
    int computeChecksum(String[] parts, String code) {
        String type = safeGetOrNull(parts, 3);
        return Objects.hash(
                code,                       // code
                safeGetOrNull(parts, 2),    // label
                type != null ? PlanNodeType.valueOf(type) : null, // nodeType
                safeParseDouble(parts, 4),  // x
                safeParseDouble(parts, 5),  // y
                safeGetOrNull(parts, 1)     // buildingCode
        );
    }

}
