package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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
 * REST Endpoint for bulk importing or updating Locations from a CSV file stream.
 * <p>
 * Optimized to prevent N+1 queries by bulk fetching Buildings with their Locations
 * and Products. It manages relationships by manipulating the parent collection
 * ({@link Building#locations}).
 */
@Path("/locations/import")
@ApplicationScoped
@RunOnVirtualThread
public class LocationCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(LocationCsvResource.class);
    private static final String CACHE_KEY_LOCATIONS = "LOCATION_CACHE";

    /**
     * Imports or updates locations from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importLocations(InputStream inputStream) {
        return this.importCsvStream(inputStream, 8);
    }

    /**
     * Prepares the execution context for a chunk of lines.
     *
     * @param parsedLines The list of parsed CSV data for the current chunk.
     * @param targetCodes The set of Building codes found in the chunk.
     * @param counters    The counters array for tracking stats.
     * @param errors      The list to collect error messages.
     * @return A context map containing Buildings, Products, and a cache of existing Locations.
     */
    @Override
    public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> context = new HashMap<>();
        List<Building> buildings = fetchBuildingsWithLocations(targetCodes);
        cacheBuildings(buildings, context);
        buildLocationLookupCache(buildings, context);

        Set<String> eans = extractProductEans(parsedLines);
        fetchAndCacheProducts(eans, context);
        return context;
    }

    /**
     * Processes a single CSV line to create or update a Location.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The context map containing caches.
     * @param counters   The counters array for tracking creation/updates.
     */
    @Override
    public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        LocationContext ctx = resolveLocationContext(data, entityMap);
        if (ctx.missingParents()) {
            LOGGER.warn("Skipping location line: missing parents. Building=" + ctx.buildingCode + ", Product=" + ctx.productEan);
            return;
        }
        ShelfLocation location = resolveOrCreateLocation(ctx, entityMap, counters);
        if (location != null) {
            populateLocationFields(data, location);
        }
    }

    /**
     * Prepares the full context for a single line in 1-by-1 fallback mode.
     * <p>
     * The generic implementation only puts the found entity under the line code,
     * but the location logic needs the Building, the Product and the location
     * lookup cache. This override rebuilds that context with fresh lookups so
     * that fallback processing behaves exactly like chunk processing.
     *
     * @param data The parsed CSV line data.
     * @return The context map for this single line.
     */
    @Override
    public Map<String, Object> prepareContextForLine(LineData data) {
        Map<String, Object> context = new HashMap<>();
        String buildingCode = safeGet(data.parts, 0);
        String productEan = safeGet(data.parts, 1);

        Building building = Building.find("buildingCode", buildingCode).firstResult();
        if (building != null) {
            context.put("BLD_" + buildingCode, building);
            Map<String, ShelfLocation> cache = new HashMap<>();
            if (building.locations != null) {
                for (BaseLocation loc : building.locations) {
                    if (loc.product != null) {
                        cache.put(generateLocationKey(buildingCode, loc.product.ean, typeTokenOf(loc)), (ShelfLocation) loc);
                    }
                }
            }
            context.put(CACHE_KEY_LOCATIONS, cache);
        }

        Product product = Product.find("ean", productEan).firstResult();
        if (product != null) {
            context.put("PRD_" + productEan, product);
        }

        return context;
    }

    /**
     * Finds a single Location entity for fallback processing (1-by-1 mode).
     *
     * @param data The parsed CSV line data.
     * @return The Location entity or null if not found.
     */
    @Override
    public Object findEntityForLine(LineData data) {
        Building b = Building.find("buildingCode", safeGet(data.parts, 0)).firstResult();
        Product p = Product.find("ean", safeGet(data.parts, 1)).firstResult();
        if (b == null || p == null) return null;

        return b.locations.stream()
                .filter(loc -> loc.product.id.equals(p.id) && isTypeConsistent(loc, safeGet(data.parts, 2)))
                .findFirst()
                .orElse(null);
    }

    // --------------------------------------------------
    // Context & Resolution Logic
    // --------------------------------------------------

    /**
     * Resolves the Building and Product parents from the context map.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The context map.
     * @return A LocationContext object containing resolved entities and codes.
     */
    private LocationContext resolveLocationContext(LineData data, Map<String, Object> entityMap) {
        String[] parts = data.parts;
        String buildingCode = safeGet(parts, 0);
        String productEan = safeGet(parts, 1);
        String typeStr = safeGet(parts, 2);

        Building building = (Building) entityMap.get("BLD_" + buildingCode);
        Product product = (Product) entityMap.get("PRD_" + productEan);

        return new LocationContext(building, product, buildingCode, productEan, typeStr, data);
    }

    /**
     * Retrieves an existing location from cache or creates a new one.
     * <p>
     * Handles the creation/update logic and increments counters.
     *
     * @param ctx       The context containing parents and keys.
     * @param entityMap The context map containing the location cache.
     * @param counters  The counters array.
     * @return The existing or newly created BaseLocation.
     */
    private ShelfLocation resolveOrCreateLocation(LocationContext ctx, Map<String, Object> entityMap, int[] counters) {
        String cacheKey = generateLocationKey(ctx.buildingCode, ctx.productEan, ctx.typeStr);
        @SuppressWarnings("unchecked")
        Map<String, ShelfLocation> locationCache = (Map<String, ShelfLocation>) entityMap.get(CACHE_KEY_LOCATIONS);
        ShelfLocation location = locationCache.get(cacheKey);
        if (location != null) {
            // Re-fetch the managed entity: the cached instance was loaded outside the
            // transaction (detached), so mutating it would be lost at commit. A null
            // result means the cached entity was a phantom from a rolled-back chunk.
            location = ShelfLocation.findById(location.id);
        }
        if (location == null) {
            location = handleNewLocation(ctx, cacheKey, locationCache);
            counters[0]++;
        } else {
            boolean updated = handleExistingLocation(location, ctx);
            if (updated) counters[1]++;
            else return null; // Skip processing if no update needed
        }
        return location;
    }

    /**
     * Creates a new location instance, links it to the building, and adds it to the cache.
     *
     * @param ctx           The context containing parents and keys.
     * @param cacheKey      The unique key for the cache.
     * @param locationCache The location cache map.
     * @return The newly created Location.
     */
    private ShelfLocation handleNewLocation(LocationContext ctx, String cacheKey, Map<String, ShelfLocation> locationCache) {
        ShelfLocation location = createLocationInstance(ctx.typeStr);
        if (location == null) {
            // Throw to trigger the staged fallback: the line is reported as an error
            // instead of being silently counted as created.
            throw new IllegalArgumentException("Unknown location type: " + ctx.typeStr);
        }
        location.product = ctx.product;
        Building building = Building.findById(ctx.building.id);
        building.locations.add(location);
        locationCache.put(cacheKey, location);
        return location;
    }

    /**
     * Validates an existing location and updates it if necessary.
     *
     * @param location The existing location.
     * @param ctx      The context containing incoming data.
     * @return True if the location was updated, false otherwise.
     */
    private boolean handleExistingLocation(BaseLocation location, LocationContext ctx) {
        if (!isTypeConsistent(location, ctx.typeStr)) {
            LOGGER.warn("Type mismatch for existing location. Skipping line.");
            return false;
        }
        int incomingChecksum = computeIncomingChecksum(ctx.data);
        return location.checksum != incomingChecksum;
    }

    // --------------------------------------------------
    // Data Fetching & Caching
    // --------------------------------------------------

    /**
     * Fetches buildings and eagerly loads their locations collection.
     *
     * @param buildingCodes The set of unique building codes to fetch.
     * @return A list of Building entities with initialized locations collections.
     */
    private List<Building> fetchBuildingsWithLocations(Set<String> buildingCodes) {
        EntityManager em = BaseEntity.getEntityManager();
        TypedQuery<Building> query = em.createQuery(
                "SELECT DISTINCT b FROM Building b LEFT JOIN FETCH b.locations WHERE b.buildingCode IN :codes", Building.class);
        query.setParameter("codes", buildingCodes);
        return query.getResultList();
    }

    /**
     * Stores the fetched Building entities into the context map.
     *
     * @param buildings The list of buildings to cache.
     * @param context   The context map to populate.
     */
    private void cacheBuildings(List<Building> buildings, Map<String, Object> context) {
        for (Building b : buildings) {
            context.put("BLD_" + b.buildingCode, b);
        }
    }

    /**
     * Creates a lookup map for existing locations.
     * <p>
     * The cache key uses the same type token as the CSV ("SHELF"/"BIN") so that
     * lookups performed during line processing actually match existing locations.
     *
     * @param buildings The list of buildings containing locations.
     * @param context   The context map to populate.
     */
    private void buildLocationLookupCache(List<Building> buildings, Map<String, Object> context) {
        Map<String, BaseLocation> cache = new HashMap<>();
        for (Building b : buildings) {
            if (b.locations != null) {
                for (BaseLocation loc : b.locations) {
                    if (loc.product != null) {
                        String key = generateLocationKey(b.buildingCode, loc.product.ean, typeTokenOf(loc));
                        cache.put(key, loc);
                    }
                }
            }
        }
        context.put(CACHE_KEY_LOCATIONS, cache);
    }

    /**
     * Returns the CSV type token ("SHELF"/"BIN") matching the location's concrete class.
     *
     * @param loc The location entity.
     * @return The CSV type token.
     */
    private static String typeTokenOf(BaseLocation loc) {
        return loc instanceof WarehouseShelfLocation ? "BIN" : "SHELF";
    }

    /**
     * Extracts all unique product EANs from the list of parsed CSV lines.
     *
     * @param lines The list of parsed line data.
     * @return A set of unique EAN strings.
     */
    private Set<String> extractProductEans(List<LineData> lines) {
        Set<String> eans = new HashSet<>();
        for (LineData data : lines) {
            String ean = safeGet(data.parts, 1);
            if (ean != null) eans.add(ean);
        }
        return eans;
    }

    /**
     * Fetches products by their EANs and stores them in the context map.
     *
     * @param eans    The set of EANs to fetch.
     * @param context The context map to populate.
     */
    private void fetchAndCacheProducts(Set<String> eans, Map<String, Object> context) {
        if (eans.isEmpty()) return;
        List<Product> products = Product.list("ean IN ?1", eans);
        for (Product p : products) {
            context.put("PRD_" + p.ean, p);
        }
    }

    // --------------------------------------------------
    // Entity Logic Helpers
    // --------------------------------------------------

    /**
     * Factory method to create the correct Location subclass.
     *
     * @param typeStr The type discriminator.
     * @return A new instance or null if type is unknown.
     */
    private ShelfLocation createLocationInstance(String typeStr) {
        if ("BIN".equalsIgnoreCase(typeStr)) return new WarehouseShelfLocation();
        if ("SHELF".equalsIgnoreCase(typeStr)) return new StoreShelfLocation();
        LOGGER.warn("Unknown location type: " + typeStr);
        return null;
    }

    /**
     * Populates common and specific fields of a location.
     *
     * @param data     The parsed CSV line data.
     * @param location The location entity.
     */
    private void populateLocationFields(LineData data, ShelfLocation location) {
        String[] parts = data.parts;
        location.area = safeGet(parts, 3);
        location.aisle = safeGet(parts, 4);
        location.unit = safeGet(parts, 5);
        location.elevation = safeGet(parts, 6);
        location.position = safeGet(parts, 7);
        populateSpecificFields(location, parts);
        location.onCreate();
    }

    /**
     * Populates subclass-specific fields.
     *
     * @param location The location entity.
     * @param parts    The CSV parts.
     */
    private void populateSpecificFields(BaseLocation location, String[] parts) {
        if (location instanceof StoreShelfLocation shelf) {
            shelf.facings = safeParseInt(parts, 8, 1);
            shelf.department = safeGet(parts, 9);
        } else {
            // ShelfLocation has exactly two concrete subtypes: StoreShelfLocation and WarehouseShelfLocation
            WarehouseShelfLocation bin = (WarehouseShelfLocation) location;
            bin.maxWeightKg = safeParseDouble(parts, 8);
            bin.maxPallets = safeParseInt(parts, 9, 1);
        }
    }

    /**
     * Checks type consistency between existing entity and CSV data.
     *
     * @param location The existing location.
     * @param typeStr  The type string from CSV.
     * @return True if types match, false otherwise.
     */
    private boolean isTypeConsistent(BaseLocation location, String typeStr) {
        if ("BIN".equalsIgnoreCase(typeStr)) return location instanceof WarehouseShelfLocation;
        return location instanceof StoreShelfLocation;
    }

    /**
     * Generates a unique cache key for a location.
     *
     * @param bldCode The building code.
     * @param ean     The product EAN.
     * @param type    The location type.
     * @return A composite string key.
     */
    private String generateLocationKey(String bldCode, String ean, String type) {
        return bldCode + "_" + ean + "_" + type;
    }

    // --------------------------------------------------
    // Parsing & Checksum
    // --------------------------------------------------

    /**
     * Computes the checksum for the incoming CSV data to detect changes.
     * <p>
     * Replicates the entity checksum structure exactly:
     * {@code Objects.hash(super.getChecksum(), <type-specific fields>)} where
     * {@code super.getChecksum()} is the hash of (area, aisle, unit, elevation, position).
     *
     * @param data The parsed CSV line data.
     * @return The integer hash representing the data state.
     */
    private int computeIncomingChecksum(LineData data) {
        String[] parts = data.parts;
        int baseHash = Objects.hash(safeGet(parts, 3), safeGet(parts, 4), safeGet(parts, 5), safeGet(parts, 6), safeGet(parts, 7));
        String typeStr = safeGet(parts, 2);
        if ("SHELF".equalsIgnoreCase(typeStr)) {
            return Objects.hash(baseHash, safeParseInt(parts, 8, 1), safeGet(parts, 9));
        }
        if ("BIN".equalsIgnoreCase(typeStr)) {
            return Objects.hash(baseHash, safeParseDouble(parts, 8), safeParseInt(parts, 9, 1));
        }
        return baseHash;
    }

    /**
     * Inner record to hold context data cleanly.
     */
    private record LocationContext(Building building, Product product, String buildingCode, String productEan, String typeStr, LineData data) {
        /**
         * Checks if either parent entity is missing.
         *
         * @return True if building or product is null.
         */
        boolean missingParents() { return building == null || product == null; }
    }
}