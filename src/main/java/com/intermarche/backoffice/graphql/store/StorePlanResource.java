package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.AisleWaypoint;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNodeType;
import com.intermarche.backoffice.domain.store.PlanZoneVertex;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.WarehouseShelfLocation;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * GraphQL API exposing the store plan of a building as a single aggregate.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * <p>
 * The store plan is the geometric layer of the building topology:
 * <ul>
 *   <li>{@link com.intermarche.backoffice.domain.store.PlanNode}s: positioned topological nodes (entrance, intersection, dead-end).</li>
 *   <li>{@link Aisle}s: aisles with their geometric layer (flow type, shape type,
 *       head/tail nodes and waypoints).</li>
 *   <li>{@link com.intermarche.backoffice.domain.store.PlanZone}s: polygonal areas (e.g., fresh / frozen departments).</li>
 * </ul>
 * <p>
 * The {@code storePlan} query returns the whole plan in a single call (used by the
 * store plan editor). The {@code saveStorePlan} mutation replaces the plan of a
 * building in a single transaction: nodes and zones are fully replaced, aisles
 * are updated (or created) with their geometric layer.
 * <p>
 * Managers may edit topology and bays in their own store; administrators have global access.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class StorePlanResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(StorePlanResource.class);

    /**
     * Minimum number of vertices required to form a valid polygon.
     */
    private static final int MIN_VERTICES = 3;

    /**
     * Default length (meters) for an aisle created through the plan save
     * when no length is provided.
     */
    private static final double DEFAULT_AISLE_LENGTH = 1.0;

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Security identity of the current authenticated user.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * Retrieves the current security identity of the user or context.
     *
     * @return The {@link SecurityIdentity} associated with the current context.
     */
    @Override
    public SecurityIdentity getIdentity() {
        return identity;
    }

    /**
     * Retrieves the full store plan of a building: nodes, aisles (with their
     * geometric layer) and zones.
     *
     * @param buildingCode The unique code of the building.
     * @return The store plan data.
     * @throws NoSuchElementException if the building does not exist.
     */
    @Query
    @Description("Get the store plan (nodes, aisles geometry, zones) of a building")
    @RolesAllowed("MANAGER")
    public StorePlanData storePlan(@Name("buildingCode") String buildingCode) {
        LOGGER.info("Entering method storePlan with buildingCode: " + buildingCode);
        StorePlanData data = buildStorePlanData(findBuilding(buildingCode));
        LOGGER.info("Exiting method storePlan");
        return data;
    }

    /**
     * Retrieves the shelf locations (bays) of a building, with their full
     * retail attributes (facings, department) and the product they hold
     * (if any — an empty bay has no product).
     *
     * @param buildingCode The unique code of the building.
     * @return The shelf locations of the building, sorted by location code.
     * @throws NoSuchElementException if the building does not exist.
     */
    private static boolean isTopologyShelf(ShelfLocation location) {
        var l=org.hibernate.Hibernate.unproxy(location,ShelfLocation.class);
        return !(l instanceof com.intermarche.backoffice.domain.store.PendingSupplyLocation) && !(l instanceof com.intermarche.backoffice.domain.store.BufferToteStockLocation) && !(l instanceof com.intermarche.backoffice.domain.store.PickingToteLocation);
    }
    @Query
    @Description("Get the shelf locations (bays) of a building")
    @RolesAllowed("MANAGER")
    public List<StoreLocationData> storeLocations(@Name("buildingCode") String buildingCode) {
        LOGGER.info("Entering method storeLocations with buildingCode: " + buildingCode);
        Building building = findBuilding(buildingCode);
        List<StoreLocationData> data = building.locations.stream()
                .map(l -> org.hibernate.Hibernate.unproxy(l,ShelfLocation.class))
                .filter(l -> !(l instanceof com.intermarche.backoffice.domain.store.PickingToteLocation))
                .filter(StorePlanResource::isTopologyShelf).map(StorePlanResource::toData)
                .sorted(Comparator.comparing(d -> d.locationCode == null ? "" : d.locationCode))
                .toList();
        LOGGER.info("Exiting method storeLocations");
        return data;
    }

    /**
     * Builds the store plan data of a building: nodes, aisles (with their
     * geometric layer) and zones.
     * <p>
     * Private helper shared by {@link #storePlan(String)} and
     * {@link #saveStorePlan(String, StorePlan)}. It deliberately carries no
     * security annotation: Arc intercepts self-invocations through the bean
     * subclass, so calling the public {@code storePlan} from {@code saveStorePlan}
     * would enforce the MANAGER role on top of ADMIN.
     *
     * @param building The building.
     * @return The store plan data.
     */
    private StorePlanData buildStorePlanData(Building building) {
        StorePlanData data = new StorePlanData();
        data.buildingCode = building.buildingCode;

        // Nodes
        data.nodes = new ArrayList<>();
        for (com.intermarche.backoffice.domain.store.PlanNode node : com.intermarche.backoffice.domain.store.PlanNode.listByBuilding(building)) {
            PlanNodeData nd = new PlanNodeData();
            nd.code = node.code;
            nd.label = node.label;
            nd.type = node.nodeType;
            nd.x = node.x;
            nd.y = node.y;
            data.nodes.add(nd);
        }

        // Aisles (geometric layer)
        data.aisles = new ArrayList<>();
        for (Aisle aisle : Aisle.listByBuilding(building)) {
            PlanAisleData ad = new PlanAisleData();
            ad.code = aisle.code;
            ad.flowType = aisle.flowType;
            ad.shapeType = aisle.shapeType;
            ad.length = aisle.lengthMeters;
            ad.head = aisle.headNode != null ? aisle.headNode.code : null;
            ad.tail = aisle.tailNode != null ? aisle.tailNode.code : null;
            ad.points = new ArrayList<>();
            if (aisle.waypoints != null) {
                for (AisleWaypoint w : aisle.waypoints) {
                    ad.points.add(new PointData(w.x, w.y));
                }
            }
            data.aisles.add(ad);
        }

        // Zones
        data.zones = new ArrayList<>();
        for (com.intermarche.backoffice.domain.store.PlanZone zone : com.intermarche.backoffice.domain.store.PlanZone.listByBuilding(building)) {
            PlanZoneData zd = new PlanZoneData();
            zd.code = zone.code;
            zd.label = zone.label;
            zd.color = zone.color;
            zd.polygon = new ArrayList<>();
            if (zone.vertices != null) {
                for (PlanZoneVertex v : zone.vertices) {
                    zd.polygon.add(new PointData(v.x, v.y));
                }
            }
            zd.labelOffset = new PointData(zone.labelOffsetX, zone.labelOffsetY);
            data.zones.add(zd);
        }

        return data;
    }

    // --------------------------------------------------
    // Mutations: own-store managers and global administrators
    // --------------------------------------------------

    /**
     * Replaces the store plan of a building in a single transaction.
     * <p>
     * <b>Nodes:</b> all existing nodes of the building are deleted and recreated
     * from the input. Aisles referencing them have their head/tail node references
     * cleared first (foreign keys), then re-resolved from the input.
     * <p>
     * <b>Aisles:</b> aisles matching an input code are updated with the geometric
     * layer (flow type, shape type, head/tail nodes, waypoints); unknown codes are
     * created. Aisles not present in the input are left untouched (their business
     * fields and logical graph are preserved), except their head/tail node
     * references which are cleared.
     * <p>
     * <b>Zones:</b> all existing zones of the building (and their vertices) are
     * deleted and recreated from the input.
     *
     * @param buildingCode The unique code of the building.
     * @param input        The full plan to save.
     * @return The store plan data after save.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Replace the store plan (nodes, aisles geometry, zones) of a building")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public StorePlanData saveStorePlan(@Name("buildingCode") String buildingCode,
                                       @Name("input") StorePlan input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method saveStorePlan for buildingCode: " + buildingCode);
            Building building = findBuilding(buildingCode);
            requireTopologyWriteAccess(building);
            if (input == null) {
                throw new IllegalArgumentException("Input is mandatory to save a store plan.");
            }

            // 1. Clear head/tail node references of all aisles (frees the FKs on plan_nodes)
            for (Aisle aisle : Aisle.listByBuilding(building)) {
                aisle.headNode = null;
                aisle.tailNode = null;
            }
            // The bulk delete below executes immediately as SQL: the UPDATEs clearing
            // the aisle references must be flushed first, otherwise the foreign key
            // on plan_nodes is violated.
            Panache.getEntityManager().flush();

            // 2. Replace nodes
            com.intermarche.backoffice.domain.store.PlanNode.deleteByBuilding(building);
            if (input.nodes != null) {
                for (PlanNode n : input.nodes) {
                    com.intermarche.backoffice.domain.store.PlanNode node = new com.intermarche.backoffice.domain.store.PlanNode();
                    node.code = requireCode(n.code, "node");
                    node.label = n.label;
                    node.nodeType = n.type != null ? n.type : PlanNodeType.DEADEND;
                    node.x = requireCoordinate(n.x, "node " + n.code + " x");
                    node.y = requireCoordinate(n.y, "node " + n.code + " y");
                    node.building = building;
                    node.persist();
                }
            }

            // 3. Update / create aisles (geometric layer)
            if (input.aisles != null) {
                for (PlanAisleGeometry a : input.aisles) {
                    Aisle aisle = Aisle.findByCode(building, a.code);
                    if (aisle == null) {
                        aisle = new Aisle();
                        aisle.code = requireCode(a.code, "aisle");
                        aisle.building = building;
                        aisle.lengthMeters = a.length != null ? a.length : DEFAULT_AISLE_LENGTH;
                        aisle.persist();
                    }
                    aisle.flowType = a.flowType;
                    aisle.shapeType = a.shapeType;
                    aisle.headNode = resolveNode(building, a.head);
                    aisle.tailNode = resolveNode(building, a.tail);
                    applyWaypoints(aisle, a.points);
                }
            }

            // 4. Replace zones (vertices first: bulk delete does not cascade)
            PlanZoneVertex.delete("zone.building = ?1", building);
            com.intermarche.backoffice.domain.store.PlanZone.deleteByBuilding(building);
            if (input.zones != null) {
                for (PlanZone z : input.zones) {
                    com.intermarche.backoffice.domain.store.PlanZone zone = new com.intermarche.backoffice.domain.store.PlanZone();
                    zone.code = requireCode(z.code, "zone");
                    zone.label = z.label;
                    zone.color = z.color;
                    zone.building = building;
                    zone.labelOffsetX = z.labelOffsetX;
                    zone.labelOffsetY = z.labelOffsetY;
                    applyVertices(zone, z.polygon);
                    zone.persist();
                }
            }

            // 5. Background image: the input carries the complete image state
            // (null image = removal).
            applyPlanImage(building, input);

            LOGGER.info("Exiting method saveStorePlan");
            return buildStorePlanData(building);
        }, StorePlanResource.class, "saveStorePlan");
    }

    /**
     * Replaces the shelf locations (bays) of a building in a single transaction.
     * <p>
     * The input carries the full desired state of the building's shelf locations:
     * <ul>
     *   <li>An input with an {@code id} matching an existing location of the
     *       building updates it (the location code is regenerated from the
     *       address fields).</li>
     *   <li>An input without {@code id} matches an existing location by its
     *       location code (derived from area/aisle/unit/elevation/position);
     *       if none matches, a new location is created. A null or blank
     *       {@code productEan} creates an <b>empty bay</b> (no product).</li>
     *   <li>Existing locations absent from the input are deleted, unless they
     *       are referenced by a fulfillment line (foreign key protection).</li>
     * </ul>
     *
     * @param buildingCode The unique code of the building.
     * @param locations    The full list of shelf locations to save.
     * @return The saved shelf locations, in input order.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Replace the shelf locations (bays) of a building")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Transactional(rollbackOn = Exception.class)
    public List<StoreLocationData> saveStoreLocations(@Name("buildingCode") String buildingCode,
                                                      @Name("locations") List<StoreLocation> locations) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method saveStoreLocations for buildingCode: " + buildingCode);
            Building building = findBuilding(buildingCode);
            requireTopologyWriteAccess(building);
            List<ShelfLocation> existing = building.locations.stream().map(l -> org.hibernate.Hibernate.unproxy(l,ShelfLocation.class)).filter(StorePlanResource::isTopologyShelf).toList();
            ShelfReconciliation reconciliation = reconcileShelfLocations(existing, locations, StorePlanResource::resolveProduct,
                    StorePlanResource::isReferencedShelf,
                    org.hibernate.Hibernate.unproxy(building) instanceof WarehouseBuilding ? WarehouseShelfLocation::new : StoreShelfLocation::new);
            for (ShelfLocation loc : reconciliation.saved()) {
                // Persist before adding to the set: BaseEntity.hashCode() is based on the id,
                // so adding a transient entity (null id) would break the set once the id is assigned.
                loc.persist();
                building.locations.add(loc);
            }
            for (ShelfLocation loc : reconciliation.toDelete()) {
                building.locations.remove(loc);
                loc.delete();
            }
            LOGGER.info("Exiting method saveStoreLocations");
            return reconciliation.saved().stream().map(StorePlanResource::toData).toList();
        }, StorePlanResource.class, "saveStoreLocations");
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Finds a building by its code or throws a Not Found error.
     *
     * @param buildingCode The unique code of the building.
     * @return The Building entity.
     * @throws NoSuchElementException if the building does not exist.
     */
    private void requireTopologyWriteAccess(Building building) {
        if (identity.hasRole("ADMIN")) return;
        Store managerStore = getStoreContext();
        Store owner = Store.find("select s from Store s join s.buildings b where b.id = ?1", building.id).firstResult();
        if (managerStore == null || owner == null || !managerStore.id.equals(owner.id)) {
            throw new SecurityException("A manager may only edit the topology of their own store.");
        }
    }

    private Building findBuilding(String buildingCode) {
        Building building = Building.findByCode(buildingCode);
        if (building == null) {
            LOGGER.error("Building with code " + buildingCode + " not found");
            throw new NoSuchElementException("Building with code '" + buildingCode + "' not found");
        }
        return building;
    }

    /**
     * Resolves a plan node by its code within a building.
     *
     * @param building The building context.
     * @param code     The node code (may be null).
     * @return The PlanNode entity, or null if the code is null.
     * @throws NoSuchElementException if the code is provided but no node matches.
     */
    private com.intermarche.backoffice.domain.store.PlanNode resolveNode(Building building, String code) {
        if (code == null) return null;
        com.intermarche.backoffice.domain.store.PlanNode node = com.intermarche.backoffice.domain.store.PlanNode.findByCode(building, code);
        if (node == null) {
            throw new NoSuchElementException("PlanNode with code '" + code + "' not found in building '" + building.buildingCode + "'");
        }
        return node;
    }

    /**
     * Replaces the waypoints of an aisle with the given points.
     *
     * @param aisle  The managed aisle.
     * @param points The input points (may be null or empty).
     */
    private void applyWaypoints(Aisle aisle, List<Point> points) {
        aisle.waypoints.clear();
        if (points == null) return;
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            AisleWaypoint waypoint = new AisleWaypoint();
            waypoint.aisle = aisle;
            waypoint.position = i;
            waypoint.x = p.x;
            waypoint.y = p.y;
            aisle.waypoints.add(waypoint);
        }
    }

    /**
     * Replaces the polygon of a zone with the given points.
     *
     * @param zone   The zone (not yet persisted).
     * @param points The input points (must contain at least {@value #MIN_VERTICES} elements).
     */
    private void applyVertices(com.intermarche.backoffice.domain.store.PlanZone zone, List<Point> points) {
        if (points == null || points.size() < MIN_VERTICES) {
            throw new IllegalArgumentException("A zone polygon requires at least " + MIN_VERTICES + " vertices.");
        }
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            PlanZoneVertex vertex = new PlanZoneVertex();
            vertex.zone = zone;
            vertex.position = i;
            vertex.x = p.x;
            vertex.y = p.y;
            zone.vertices.add(vertex);
        }
    }

    /**
     * Validates that a code is present.
     *
     * @param code     The code to check.
     * @param entity   The entity label used in the error message.
     * @return The code.
     */
    private String requireCode(String code, String entity) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code is mandatory for a plan " + entity + ".");
        }
        return code;
    }

    /**
     * Validates that a coordinate is present.
     *
     * @param value The coordinate to check.
     * @param label The field label used in the error message.
     * @return The coordinate.
     */
    private Double requireCoordinate(Double value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("Coordinate is mandatory: " + label);
        }
        return value;
    }

    /**
     * Resolves a product by its EAN.
     *
     * @param ean The product EAN (may be null or blank for an empty bay).
     * @return The product, or null if the EAN is null or blank.
     * @throws NoSuchElementException if the EAN is provided but no product matches.
     */
    private static Product resolveProduct(String ean) {
        if (ean == null || ean.isBlank()) return null;
        Product product = Product.find("ean", ean).firstResult();
        if (product == null) {
            throw new NoSuchElementException("Product with ean '" + ean + "' not found");
        }
        return product;
    }

    /**
     * Computes the location code an input would generate, using the same rule
     * as {@link com.intermarche.backoffice.domain.store.ShelfLocation#onCreate()}:
     * AREA-AISLE-UNIT-ELEVATION-POSITION (with {@code ??} fallbacks).
     *
     * @param input The location input.
     * @return The expected location code.
     */
    static String expectedCode(StoreLocation input) {
        StoreShelfLocation probe = new StoreShelfLocation();
        probe.area = input.area;
        probe.aisle = input.aisle;
        probe.unit = input.unit;
        probe.elevation = input.elevation;
        probe.position = input.position;
        probe.onCreate();
        return probe.locationCode;
    }

    /**
     * Maps a shelf location entity to its output DTO.
     *
     * @param loc The shelf location entity.
     * @return The output DTO.
     */
    static StoreLocationData toData(ShelfLocation loc) {
        StoreLocationData data = new StoreLocationData();
        data.id = loc.id;
        data.locationCode = loc.locationCode;
        data.area = loc.area;
        data.aisle = loc.aisle;
        data.unit = loc.unit;
        data.elevation = loc.elevation;
        data.position = loc.position;
        if(loc instanceof StoreShelfLocation retail) {data.facings = retail.facings;data.department = retail.department;}
        data.aislePosition = loc.aislePosition;
        data.productEan = loc.product != null ? loc.product.ean : null;
        data.productName = loc.product != null ? loc.product.name : null;
        return data;
    }

    /**
     * Reconciles the desired state of the shelf locations of a building with
     * the existing ones. Pure logic: no database access, the product resolution
     * and the reference check are injected.
     * <p>
     * Each input is matched to an existing location by {@code id} first, then
     * by location code; unmatched inputs create a new location. Existing
     * locations that no input claimed are marked for deletion, unless the
     * reference checker reports them as still in use (e.g. referenced by a
     * fulfillment line).
     *
     * @param existing         The existing shelf locations of the building.
     * @param inputs           The desired state (may be null or empty).
     * @param productResolver  Resolves a product EAN to a product (null EAN = empty bay).
     * @param referenceChecker Tells whether a location is still referenced and must be kept.
     * @return The reconciliation result: saved locations (in input order) and
     *         locations to delete.
     */
    /**
     * Applies the background image state carried by a {@code saveStorePlan} input
     * to the building. A null/blank {@code planImage} removes the image and resets
     * its placement to the defaults.
     *
     * @param building The building to update (managed entity).
     * @param input    The save input carrying the image fields.
     * @throws IllegalArgumentException if the scale is not positive or the opacity
     *                                  is outside [0, 1].
     */
    public static void applyPlanImage(Building building, StorePlan input) {
        if (input.planImage != null && !input.planImage.isBlank()) {
            if (input.planImageScale != null && input.planImageScale <= 0) {
                throw new IllegalArgumentException("Plan image scale must be positive.");
            }
            if (input.planImageOpacity != null
                    && (input.planImageOpacity < 0 || input.planImageOpacity > 1)) {
                throw new IllegalArgumentException("Plan image opacity must be between 0 and 1.");
            }
            building.planImage = input.planImage;
            building.planImageX = input.planImageX != null ? input.planImageX : 0;
            building.planImageY = input.planImageY != null ? input.planImageY : 0;
            building.planImageScale = input.planImageScale != null ? input.planImageScale : 1;
            building.planImageRotation = input.planImageRotation != null ? input.planImageRotation : 0;
            building.planImageOpacity = input.planImageOpacity != null ? input.planImageOpacity : 1;
        } else {
            building.planImage = null;
            building.planImageX = 0;
            building.planImageY = 0;
            building.planImageScale = 1;
            building.planImageRotation = 0;
            building.planImageOpacity = 1;
        }
    }

    public static Reconciliation reconcileLocations(List<StoreShelfLocation> existing,List<StoreLocation> inputs,
            Function<String,Product> productResolver,Predicate<StoreShelfLocation> referenceChecker) {
        ShelfReconciliation result=reconcileShelfLocations(new ArrayList<>(existing),inputs,productResolver,
                l -> referenceChecker.test((StoreShelfLocation)l),StoreShelfLocation::new);
        return new Reconciliation(result.saved().stream().map(StoreShelfLocation.class::cast).toList(),result.toDelete().stream().map(StoreShelfLocation.class::cast).toList());
    }
    private static boolean isReferencedShelf(ShelfLocation loc) {
        return FulfillmentLine.count("location",loc)>0
            || com.intermarche.backoffice.domain.wms.LotLine.count("location",loc)>0
            || com.intermarche.backoffice.domain.stock.StockMovement.count("location",loc)>0
            || com.intermarche.backoffice.domain.stock.ExpectedSupply.count("source = ?1 or destination = ?1",loc)>0;
    }
    public record ShelfReconciliation(List<ShelfLocation> saved,List<ShelfLocation> toDelete) {}

    public static ShelfReconciliation reconcileShelfLocations(List<ShelfLocation> existing,
                                                    List<StoreLocation> inputs,
                                                    Function<String, Product> productResolver,
                                                    Predicate<ShelfLocation> referenceChecker, java.util.function.Supplier<ShelfLocation> locationFactory) {
        Map<Long, ShelfLocation> byId = new HashMap<>();
        Map<String, ShelfLocation> byCode = new HashMap<>();
        for (ShelfLocation loc : existing) {
            if (loc.id != null) byId.put(loc.id, loc);
            if (loc.locationCode != null) byCode.put(loc.locationCode, loc);
        }
        Set<ShelfLocation> claimed = new HashSet<>();
        List<ShelfLocation> saved = new ArrayList<>();
        if (inputs != null) {
            for (StoreLocation input : inputs) {
                if (input == null) continue;
                if (input.aisle == null || input.aisle.isBlank()) {
                    throw new IllegalArgumentException("Aisle is mandatory for a shelf location.");
                }
                if (input.unit == null || input.unit.isBlank()) {
                    throw new IllegalArgumentException("Unit is mandatory for a shelf location.");
                }
                int facings = input.facings != null ? input.facings : 1;
                if (facings < 1) {
                    throw new IllegalArgumentException("Facings must be at least 1.");
                }
                if (input.aislePosition != null && (input.aislePosition < 0 || input.aislePosition > 1)) {
                    throw new IllegalArgumentException("Aisle position must be between 0 and 1.");
                }
                ShelfLocation loc;
                if (input.id != null) {
                    loc = byId.get(input.id);
                    if (loc == null) {
                        throw new IllegalArgumentException("Location id " + input.id + " does not belong to this building.");
                    }
                } else {
                    loc = byCode.get(expectedCode(input));
                }
                if (loc == null) {
                    loc = locationFactory.get();
                } else {
                    claimed.add(loc);
                }
                loc.area = input.area;
                loc.aisle = input.aisle;
                loc.unit = input.unit;
                loc.elevation = input.elevation;
                loc.position = input.position;
                if(loc instanceof StoreShelfLocation retail) {retail.facings=facings;retail.department=input.department;}
                loc.aislePosition = input.aislePosition;
                // Empty bay: no product resolution for a null/blank EAN.
                loc.product = (input.productEan == null || input.productEan.isBlank())
                        ? null
                        : productResolver.apply(input.productEan);
                // Regenerates the location code from the address fields.
                loc.onCreate();
                byCode.put(loc.locationCode, loc);
                saved.add(loc);
            }
        }
        List<ShelfLocation> toDelete = new ArrayList<>();
        for (ShelfLocation loc : existing) {
            if (claimed.contains(loc)) continue;
            if (referenceChecker.test(loc)) continue;
            toDelete.add(loc);
        }
        return new ShelfReconciliation(saved, toDelete);
    }

    /**
     * Result of {@link #reconcileLocations(List, List, Function, Predicate)}.
     *
     * @param saved    The locations to persist, in input order (new ones have a null id).
     * @param toDelete The existing locations to delete (unclaimed and unreferenced).
     */
    public record Reconciliation(List<StoreShelfLocation> saved, List<StoreShelfLocation> toDelete) {
    }

    // --------------------------------------------------
    // Output Types
    // --------------------------------------------------

    /**
     * Output type: the full store plan of a building.
     */
    public static class StorePlanData {

        /**
         * The unique code of the building.
         */
        public String buildingCode;

        /**
         * The topological nodes of the plan.
         */
        public List<PlanNodeData> nodes;

        /**
         * The aisles of the plan with their geometric layer.
         */
        public List<PlanAisleData> aisles;

        /**
         * The polygonal zones of the plan.
         */
        public List<PlanZoneData> zones;
    }

    /**
     * Output type: a topological node of the plan.
     */
    public static class PlanNodeData {

        /**
         * The unique code of the node.
         */
        public String code;

        /**
         * The display label of the node.
         */
        public String label;

        /**
         * The type of the node (ENTRANCE, INTERSECTION, DEADEND).
         */
        public PlanNodeType type;

        /**
         * The X coordinate of the node, in meters.
         */
        public Double x;

        /**
         * The Y coordinate of the node, in meters.
         */
        public Double y;
    }

    /**
     * Output type: an aisle with its geometric layer.
     */
    public static class PlanAisleData {

        /**
         * The unique code of the aisle.
         */
        public String code;

        /**
         * The flow type of the aisle (LINEAR, DIAGONAL, CIRCULAR).
         */
        public String flowType;

        /**
         * The shape type of the aisle (STRAIGHT, CURVED).
         */
        public String shapeType;

        /**
         * The physical length of the aisle in meters.
         */
        public Double length;

        /**
         * The code of the head node, if any.
         */
        public String head;

        /**
         * The code of the tail node, if any.
         */
        public String tail;

        /**
         * The ordered waypoints of the aisle.
         */
        public List<PointData> points;
    }

    /**
     * Output type: a polygonal zone of the plan.
     */
    public static class PlanZoneData {

        /**
         * The unique code of the zone.
         */
        public String code;

        /**
         * The display label of the zone.
         */
        public String label;

        /**
         * The display color of the zone (hex format).
         */
        public String color;

        /**
         * The ordered vertices of the zone polygon.
         */
        public List<PointData> polygon;

        /**
         * The offset of the zone label from the polygon centroid.
         */
        public PointData labelOffset;
    }

    /**
     * Output type: a 2D point (plan coordinates, meters).
     */
    public static class PointData {

        /**
         * The X coordinate, in meters.
         */
        public Double x;

        /**
         * The Y coordinate, in meters.
         */
        public Double y;

        /**
         * Default constructor.
         */
        public PointData() {
        }

        /**
         * Creates a point with the given coordinates.
         *
         * @param x The X coordinate, in meters.
         * @param y The Y coordinate, in meters.
         */
        public PointData(Double x, Double y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Output type: a shelf location (bay) of a building.
     */
    public static class StoreLocationData {

        /**
         * The unique identifier of the location.
         */
        public Long id;

        /**
         * The generated human-readable code: AREA-AISLE-UNIT-ELEVATION-POSITION.
         */
        public String locationCode;

        /**
         * The broad area (department code, e.g. "EPICERIE").
         */
        public String area;

        /**
         * The aisle code the bay belongs to.
         */
        public String aisle;

        /**
         * The structural unit (bay) within the aisle.
         */
        public String unit;

        /**
         * The vertical level (e.g. "1" bottom, "2" eye-level).
         */
        public String elevation;

        /**
         * The precise horizontal position (slot) within the bay.
         */
        public String position;

        /**
         * The number of product facings on the bay.
         */
        public Integer facings;

        /**
         * The department display name.
         */
        public String department;

        /**
         * The position of the bay along its aisle, as a fraction of the aisle
         * path length (0..1), or null for automatic placement.
         */
        public Double aislePosition;

        /**
         * The EAN of the product held by this location, or null for an empty bay.
         */
        public String productEan;

        /**
         * The name of the product held by this location, or null for an empty bay.
         */
        public String productName;
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input type: the full store plan to save for a building.
     */
    @Input
    public static class StorePlan {

        /**
         * The topological nodes of the plan (replaces all existing nodes).
         */
        public List<PlanNode> nodes;

        /**
         * The aisles with their geometric layer (updated or created).
         */
        public List<PlanAisleGeometry> aisles;

        /**
         * The polygonal zones of the plan (replaces all existing zones).
         */
        public List<PlanZone> zones;

        /**
         * Background image of the plan (PNG encoded in base64), or null to remove it.
         */
        public String planImage;

        /**
         * World X coordinate of the top-left corner of the background image.
         */
        public Double planImageX;

        /**
         * World Y coordinate of the top-left corner of the background image.
         */
        public Double planImageY;

        /**
         * Scale factor of the background image (1 = natural size, must be positive).
         */
        public Double planImageScale;

        /**
         * Rotation of the background image, in degrees (around its center).
         */
        public Double planImageRotation;

        /**
         * Opacity of the background image (0..1).
         */
        public Double planImageOpacity;
    }

    /**
     * Input type: a topological node of the plan.
     */
    @Input
    public static class PlanNode {

        /**
         * The unique code of the node.
         */
        public String code;

        /**
         * The display label of the node.
         */
        public String label;

        /**
         * The type of the node (ENTRANCE, INTERSECTION, DEADEND).
         */
        public PlanNodeType type;

        /**
         * The X coordinate of the node, in meters.
         */
        public Double x;

        /**
         * The Y coordinate of the node, in meters.
         */
        public Double y;
    }

    /**
     * Input type: the geometric layer of an aisle.
     */
    @Input
    public static class PlanAisleGeometry {

        /**
         * The unique code of the aisle.
         */
        public String code;

        /**
         * The flow type of the aisle (LINEAR, DIAGONAL, CIRCULAR).
         */
        public String flowType;

        /**
         * The shape type of the aisle (STRAIGHT, CURVED).
         */
        public String shapeType;

        /**
         * The physical length of the aisle in meters (used only on creation).
         */
        public Double length;

        /**
         * The code of the head node, if any.
         */
        public String head;

        /**
         * The code of the tail node, if any.
         */
        public String tail;

        /**
         * The ordered waypoints of the aisle.
         */
        public List<Point> points;
    }

    /**
     * Input type: a polygonal zone of the plan.
     */
    @Input
    public static class PlanZone {

        /**
         * The unique code of the zone.
         */
        public String code;

        /**
         * The display label of the zone.
         */
        public String label;

        /**
         * The display color of the zone (hex format).
         */
        public String color;

        /**
         * The ordered vertices of the zone polygon (minimum 3).
         */
        public List<Point> polygon;

        /**
         * The horizontal offset of the zone label from the polygon centroid, in meters.
         */
        public Double labelOffsetX;

        /**
         * The vertical offset of the zone label from the polygon centroid, in meters.
         */
        public Double labelOffsetY;
    }

    /**
     * Input type: a 2D point (plan coordinates, meters).
     */
    @Input
    public static class Point {

        /**
         * The X coordinate, in meters.
         */
        public Double x;

        /**
         * The Y coordinate, in meters.
         */
        public Double y;
    }

    /**
     * Input type: a shelf location (bay) to save for a building.
     */
    @Input
    public static class StoreLocation {

        /**
         * The identifier of an existing location to update, or null to match
         * by location code / create a new location.
         */
        public Long id;

        /**
         * The broad area (department code, e.g. "EPICERIE").
         */
        public String area;

        /**
         * The aisle code the bay belongs to (mandatory).
         */
        public String aisle;

        /**
         * The structural unit (bay) within the aisle (mandatory).
         */
        public String unit;

        /**
         * The vertical level (e.g. "1" bottom, "2" eye-level).
         */
        public String elevation;

        /**
         * The precise horizontal position (slot) within the bay.
         */
        public String position;

        /**
         * The number of product facings on the bay (defaults to 1).
         */
        public Integer facings;

        /**
         * The department display name.
         */
        public String department;

        /**
         * The position of the bay along its aisle, as a fraction of the aisle
         * path length (0..1), or null for automatic placement.
         */
        public Double aislePosition;

        /**
         * The EAN of the product held by this location, or null for an empty bay.
         */
        public String productEan;
    }
}
