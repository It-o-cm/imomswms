package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.*;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
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
 * REST Endpoint for bulk importing or updating Buildings.
 */
@Path("/buildings/import")
@ApplicationScoped
@RunOnVirtualThread
public class BuildingCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(BuildingCsvResource.class);

    /**
     * Imports or updates buildings from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importBuildings(InputStream inputStream) {
        return this.importCsvStream(inputStream, 3);
    }

    /**
     * Prepares the context for a chunk.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique Building codes.
     * @param counters    An array for stats.
     * @param errors      A list for error messages.
     * @return A map containing cached entities.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> context = new HashMap<>();
        List<Building> buildings = Building.list("buildingCode IN ?1", targetCodes);
        cacheBuildings(buildings, context);
        Set<String> storeCodes = extractStoreCodes(parsedLines);
        List<Store> stores = Store.list("code IN ?1", storeCodes);
        cacheStores(stores, context);
        return context;
    }

    /**
     * Processes a single line to create or update a Building.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The context map.
     * @param counters   The counters array.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        BuildingContext ctx = resolveBuildingContext(data, entityMap);
        Building building = resolveOrCreateBuilding(ctx, entityMap, counters);
        if (building != null) {
            populateBuildingData(data, building);
        }
    }

    /**
     * Finds a single Building entity for fallback processing.
     *
     * @param data The parsed CSV line data.
     * @return The Building entity or null.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Building.find("buildingCode", data.code).firstResult();
    }

    // --------------------------------------------------
    // Context & Resolution Logic
    // --------------------------------------------------

    /**
     * Resolves necessary context (Codes, Store) from the map.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The context map.
     * @return A BuildingContext instance.
     */
    private BuildingContext resolveBuildingContext(LineData data, Map<String, Object> entityMap) {
        String[] parts = data.parts;
        String buildingCode = data.code;
        String storeCode = safeGet(parts, 1);
        String typeStr = safeGet(parts, 2);
        Store store = (Store) entityMap.get("STORE_" + storeCode);
        if (store == null) {
            LOGGER.warn("Store with code " + storeCode + " not found for building " + buildingCode);
            throw new IllegalArgumentException("Store not found for building " + buildingCode);
        }
        LOGGER.debugf("Resolving building context for %s, store: %s, type: %s", buildingCode, storeCode, typeStr);
        return new BuildingContext(buildingCode, storeCode, typeStr, store, data);
    }

    /**
     * Retrieves an existing building or creates a new one.
     *
     * @param ctx       The context object.
     * @param entityMap The context map.
     * @param counters  The counters array.
     * @return The Building entity.
     */
    private Building resolveOrCreateBuilding(BuildingContext ctx, Map<String, Object> entityMap, int[] counters) {
        Building building = (Building) entityMap.get(ctx.buildingCode);
        if (building == null) {
            building = handleNewBuilding(ctx, entityMap);
            counters[0]++;
        } else {
            boolean updated = handleExistingBuilding(building, ctx);
            if (updated) counters[1]++;
            else return null; // No update needed
        }
        return building;
    }

    /**
     * Handles the creation of a new building.
     *
     * @param ctx       The context object.
     * @param entityMap The context map.
     * @return The newly created Building.
     */
    private Building handleNewBuilding(BuildingContext ctx, Map<String, Object> entityMap) {
        Building building = createBuildingInstance(ctx.typeStr);
        if (building == null) return null;

        building.buildingCode = ctx.buildingCode;
        Store store = Store.findById(ctx.store.id);
        store.buildings.add(building);
        entityMap.put(ctx.buildingCode, building);
        return building;
    }

    /**
     * Handles the update logic for an existing building.
     *
     * @param building The existing building.
     * @param ctx      The context object.
     * @return True if updated, false otherwise.
     */
    private boolean handleExistingBuilding(Building building, BuildingContext ctx) {
        if (!isTypeConsistent(building, ctx.typeStr)) {
            LOGGER.warn("Type mismatch for building " + ctx.buildingCode + ". Skipping.");
            return false;
        }
        int incomingChecksum = computeIncomingChecksum(ctx.data);
        return building.checksum != incomingChecksum;
    }

    // --------------------------------------------------
    // Data Fetching & Caching
    // --------------------------------------------------

    /**
     * Extracts store codes from the lines.
     *
     * @param lines The list of parsed line data.
     * @return A set of store codes.
     */
    private Set<String> extractStoreCodes(List<LineData> lines) {
        Set<String> codes = new HashSet<>();
        for (LineData d : lines) {
            String code = safeGet(d.parts, 1);
            if (code != null) codes.add(code);
        }
        return codes;
    }

    /**
     * Caches buildings in the context map.
     *
     * @param buildings The list of buildings.
     * @param context   The context map.
     */
    private void cacheBuildings(List<Building> buildings, Map<String, Object> context) {
        for (Building b : buildings) context.put(b.buildingCode, b);
    }

    /**
     * Caches stores in the context map.
     *
     * @param stores  The list of stores.
     * @param context The context map.
     */
    private void cacheStores(List<Store> stores, Map<String, Object> context) {
        for (Store s : stores) context.put("STORE_" + s.code, s);
    }

    // --------------------------------------------------
    // Entity Logic Helpers
    // --------------------------------------------------

    /**
     * Factory for Building types.
     *
     * @param typeStr The type string.
     * @return A new Building instance.
     */
    private Building createBuildingInstance(String typeStr) {
        if ("WAREHOUSE".equalsIgnoreCase(typeStr)) return new WarehouseBuilding();
        return new StoreBuilding();
    }

    /**
     * Populates address and GPS fields.
     *
     * @param data     The parsed CSV line data.
     * @param building The building entity.
     */
    private void populateBuildingData(LineData data, Building building) {
        String[] parts = data.parts;
        if (building.address == null) building.address = new Adresse();
        if (building.gpsPosition == null) building.gpsPosition = new GPSPosition();

        building.address.streetLine1 = safeGet(parts, 3);
        building.address.streetLine2 = safeGet(parts, 4);
        building.address.postalCode = safeGet(parts, 5);
        building.address.city = safeGet(parts, 6);
        building.address.country = safeGet(parts, 7);
        building.gpsPosition.latitude = safeParseDouble(parts, 8);
        building.gpsPosition.longitude = safeParseDouble(parts, 9);
    }

    /**
     * Checks type consistency.
     *
     * @param building The building entity.
     * @param typeStr  The type string.
     * @return True if consistent.
     */
    private boolean isTypeConsistent(Building building, String typeStr) {
        if ("WAREHOUSE".equalsIgnoreCase(typeStr)) return building instanceof WarehouseBuilding;
        return building instanceof StoreBuilding;
    }

    /**
     * Computes checksum for the incoming data.
     *
     * @param data The parsed CSV line data.
     * @return The checksum integer.
     */
    private int computeIncomingChecksum(LineData data) {
        String[] parts = data.parts;
        int addressHash = Objects.hash(safeGet(parts, 3), safeGet(parts, 4), safeGet(parts, 5), safeGet(parts, 6), safeGet(parts, 7));
        int gpsHash = Objects.hash(safeParseDouble(parts, 8), safeParseDouble(parts, 9));
        return Objects.hash(safeGet(parts, 1), data.code, addressHash, gpsHash);
    }

    /**
     * Inner record for context.
     */
    private record BuildingContext(String buildingCode, String storeCode, String typeStr, Store store, LineData data) {
        /**
         * Checks if the store is missing.
         *
         * @return True if store is null.
         */
        boolean isStoreMissing() { return store == null; }
    }
}