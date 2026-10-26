package com.intermarche.e2e.graphql.store;

import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.AisleWaypoint;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanNodeType;
import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.PlanZoneVertex;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.graphql.store.StorePlanResource;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link StorePlanResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class StorePlanResourceTest {

    @Inject
    StorePlanResource storePlanResource;

    private Store managerStore(String code, String badge, StoreBuilding building) {
        Store store = new Store(); store.code=code; store.name=code;
        store.address=new Adresse(); store.address.streetLine1="Test"; store.address.city="Lyon";
        store.address.postalCode="69001"; store.address.country="France";
        store.buildings.add(building); store.persistAndFlush();
        if (badge != null) {
            Employee employee=new Employee(); employee.badgeId=badge; employee.store=store;
            employee.firstName="Test"; employee.lastName="Manager"; employee.email=badge+"@test.invalid";
            employee.password="x".repeat(60); employee.role=Employee.EmployeeRole.MANAGER; employee.active=true;
            employee.persistAndFlush();
        }
        return store;
    }

    @Test
    @TestSecurity(user="topology-manager", roles={"MANAGER"})
    void managerCanSaveTopologyAndBaysInOwnStore() throws GraphQLException {
        StoreBuilding building=persistBuilding("MANAGER-OWN-BLD");
        managerStore("MANAGER-OWN", "topology-manager", building);
        var input=new StorePlanResource.StorePlan();
        input.nodes=List.of(); input.aisles=List.of(); input.zones=List.of();
        assertEquals(building.buildingCode,storePlanResource.saveStorePlan(building.buildingCode,input).buildingCode);
        assertTrue(storePlanResource.saveStoreLocations(building.buildingCode,List.of()).isEmpty());
    }

    @Test
    @TestSecurity(user="topology-manager", roles={"MANAGER"})
    void managerCannotSaveTopologyOfAnotherStore() {
        managerStore("MANAGER-OWN", "topology-manager", persistBuilding("MANAGER-OWN-BLD"));
        StoreBuilding other=persistBuilding("MANAGER-OTHER-BLD");
        managerStore("MANAGER-OTHER", null, other);
        GraphQLException error=assertThrows(GraphQLException.class,() -> storePlanResource.saveStorePlan(other.buildingCode,new StorePlanResource.StorePlan()));
        assertInstanceOf(SecurityException.class,error.getCause());
    }

    @Test
    @TestSecurity(user="topology-manager", roles={"MANAGER"})
    void managerCannotSaveBaysOfAnotherStore() {
        managerStore("MANAGER-OWN", "topology-manager", persistBuilding("MANAGER-OWN-BLD"));
        StoreBuilding other=persistBuilding("MANAGER-OTHER-BLD");
        managerStore("MANAGER-OTHER", null, other);
        GraphQLException error=assertThrows(GraphQLException.class,() -> storePlanResource.saveStoreLocations(other.buildingCode,List.of()));
        assertInstanceOf(SecurityException.class,error.getCause());
    }

    @Test
    @TestSecurity(user="unlinked-topology-manager", roles={"MANAGER"})
    void managerWithoutStoreCannotSaveTopology() {
        StoreBuilding building=persistBuilding("UNLINKED-MANAGER-BLD");
        GraphQLException error=assertThrows(GraphQLException.class,() -> storePlanResource.saveStorePlan(building.buildingCode,new StorePlanResource.StorePlan()));
        assertInstanceOf(SecurityException.class,error.getCause());
    }

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
     * Creates and persists a plan node directly via the entity.
     */
    private PlanNode persistNode(String code, double x, double y, PlanNodeType type, String label, StoreBuilding building) {
        PlanNode node = new PlanNode();
        node.code = code;
        node.label = label;
        node.nodeType = type;
        node.x = x;
        node.y = y;
        node.building = building;
        node.persist();
        return node;
    }

    /**
     * Creates and persists an aisle directly via the entity.
     */
    private Aisle persistAisle(String code, StoreBuilding building) {
        Aisle aisle = new Aisle();
        aisle.code = code;
        aisle.lengthMeters = 25.0;
        aisle.aisleType = "LINEAR";
        aisle.connectedToHead = "PREV";
        aisle.connectedToTail = "NEXT";
        aisle.building = building;
        aisle.persist();
        return aisle;
    }

    /**
     * Creates and persists a plan zone directly via the entity.
     */
    private PlanZone persistZone(String code, StoreBuilding building) {
        PlanZone zone = new PlanZone();
        zone.code = code;
        zone.label = "Label " + code;
        zone.color = "#0891b2";
        zone.temperatureZone = TemperatureZone.CHILLED;
        zone.building = building;
        for (int i = 0; i < 3; i++) {
            PlanZoneVertex v = new PlanZoneVertex();
            v.zone = zone;
            v.position = i;
            v.x = i * 10.0;
            v.y = 0.0;
            zone.vertices.add(v);
        }
        zone.persist();
        return zone;
    }

    private StorePlanResource.Point point(double x, double y) {
        StorePlanResource.Point p = new StorePlanResource.Point();
        p.x = x;
        p.y = y;
        return p;
    }

    private StorePlanResource.PlanNode nodeInput(String code, double x, double y) {
        StorePlanResource.PlanNode n = new StorePlanResource.PlanNode();
        n.code = code;
        n.label = "Label " + code;
        n.type = PlanNodeType.INTERSECTION;
        n.x = x;
        n.y = y;
        return n;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStorePlan_BuildingNotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            storePlanResource.storePlan("BLD_UNKNOWN");
        });
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStorePlan_EmptyBuilding() {
        persistBuilding("BLD_SP_EMPTY");

        StorePlanResource.StorePlanData data = storePlanResource.storePlan("BLD_SP_EMPTY");

        assertNotNull(data);
        assertEquals("BLD_SP_EMPTY", data.buildingCode);
        assertNotNull(data.nodes);
        assertTrue(data.nodes.isEmpty());
        assertNotNull(data.aisles);
        assertTrue(data.aisles.isEmpty());
        assertNotNull(data.zones);
        assertTrue(data.zones.isEmpty());
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStorePlan_ReturnsFullPlan() {
        StoreBuilding building = persistBuilding("BLD_SP_FULL");
        PlanNode entrance = persistNode("ENTRANCE", -83, 242, PlanNodeType.ENTRANCE, "Entrée", building);
        PlanNode hub = persistNode("HUB", -83, 82, PlanNodeType.INTERSECTION, "Hub", building);

        Aisle aisle = persistAisle("A01", building);
        aisle.flowType = "CIRCULAR";
        aisle.shapeType = "CURVED";
        aisle.headNode = entrance;
        aisle.tailNode = hub;
        for (int i = 0; i < 2; i++) {
            AisleWaypoint w = new AisleWaypoint();
            w.aisle = aisle;
            w.position = i;
            w.x = -403.0 - i;
            w.y = -268.0 + i;
            aisle.waypoints.add(w);
        }

        PlanZone zone = persistZone("FRESH_AREA", building);
        zone.labelOffsetX = 1.5;
        zone.labelOffsetY = -2.5;

        StorePlanResource.StorePlanData data = storePlanResource.storePlan("BLD_SP_FULL");

        assertEquals("BLD_SP_FULL", data.buildingCode);

        // Nodes
        assertEquals(2, data.nodes.size());
        StorePlanResource.PlanNodeData nd = data.nodes.stream().filter(n -> n.code.equals("ENTRANCE")).findFirst().orElseThrow();
        assertEquals("Entrée", nd.label);
        assertEquals(PlanNodeType.ENTRANCE, nd.type);
        assertEquals(-83.0, nd.x);
        assertEquals(242.0, nd.y);

        // Aisles (geometric layer)
        assertEquals(1, data.aisles.size());
        StorePlanResource.PlanAisleData ad = data.aisles.get(0);
        assertEquals("A01", ad.code);
        assertEquals("CIRCULAR", ad.flowType);
        assertEquals("CURVED", ad.shapeType);
        assertEquals(25.0, ad.length);
        assertEquals("ENTRANCE", ad.head);
        assertEquals("HUB", ad.tail);
        assertEquals(2, ad.points.size());
        assertEquals(-403.0, ad.points.get(0).x);
        assertEquals(-268.0, ad.points.get(0).y);
        assertEquals(-404.0, ad.points.get(1).x);
        assertEquals(-267.0, ad.points.get(1).y);

        // Zones
        assertEquals(1, data.zones.size());
        StorePlanResource.PlanZoneData zd = data.zones.get(0);
        assertEquals("FRESH_AREA", zd.code);
        assertEquals("Label FRESH_AREA", zd.label);
        assertEquals("#0891b2", zd.color);
        assertEquals(3, zd.polygon.size());
        assertEquals(10.0, zd.polygon.get(1).x);
        assertNotNull(zd.labelOffset);
        assertEquals(1.5, zd.labelOffset.x);
        assertEquals(-2.5, zd.labelOffset.y);
    }

    // --------------------------------------------------
    // Mutation Tests: saveStorePlan (Requires ADMIN role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_BuildingNotFound() {
        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();

        assertThrows(NoSuchElementException.class, () -> {
            storePlanResource.saveStorePlan("BLD_UNKNOWN", input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_NullInput() {
        persistBuilding("BLD_SP_NUL");

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            storePlanResource.saveStorePlan("BLD_SP_NUL", null);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Input is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_ReplacesNodesAndZones() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_SP_REP");
        // Pre-existing plan data to be replaced
        persistNode("OLD_NODE", 0, 0, PlanNodeType.DEADEND, "Old", building);
        persistZone("OLD_ZONE", building);
        Aisle oldAisle = persistAisle("OLD_AISLE", building);
        oldAisle.headNode = PlanNode.findByCode(building, "OLD_NODE");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        input.nodes = List.of(nodeInput("NEW_NODE", 5, 6));
        input.zones = List.of();

        StorePlanResource.StorePlanData data = storePlanResource.saveStorePlan("BLD_SP_REP", input);

        // Old node/zone are gone, new node is present
        assertNull(PlanNode.findByCode(building, "OLD_NODE"));
        assertNull(PlanZone.findByCode(building, "OLD_ZONE"));
        assertNotNull(PlanNode.findByCode(building, "NEW_NODE"));
        assertEquals(1, data.nodes.size());
        assertEquals("NEW_NODE", data.nodes.get(0).code);
        assertTrue(data.zones.isEmpty());

        // The untouched aisle keeps its business fields but loses its node references
        Aisle reloaded = Aisle.findByCode(building, "OLD_AISLE");
        assertNotNull(reloaded);
        assertEquals(25.0, reloaded.lengthMeters);
        assertEquals("LINEAR", reloaded.aisleType);
        assertEquals("PREV", reloaded.connectedToHead);
        assertEquals("NEXT", reloaded.connectedToTail);
        assertNull(reloaded.headNode);
        assertNull(reloaded.tailNode);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_PersistsPlanImage() throws GraphQLException {
        persistBuilding("BLD_SP_IMG");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        input.nodes = List.of();
        input.planImage = "aW1hZ2UtcG5n";
        input.planImageX = -283.0;
        input.planImageY = -238.0;
        input.planImageScale = 1.5;
        input.planImageRotation = 90.0;
        input.planImageOpacity = 0.8;

        storePlanResource.saveStorePlan("BLD_SP_IMG", input);

        Building reloaded = Building.findByCode("BLD_SP_IMG");
        assertEquals("aW1hZ2UtcG5n", reloaded.planImage);
        assertEquals(-283.0, reloaded.planImageX);
        assertEquals(-238.0, reloaded.planImageY);
        assertEquals(1.5, reloaded.planImageScale);
        assertEquals(90.0, reloaded.planImageRotation);
        assertEquals(0.8, reloaded.planImageOpacity);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_PlanImageDefaultsWhenPartial() throws GraphQLException {
        persistBuilding("BLD_SP_IMGD");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        input.nodes = List.of();
        input.planImage = "aW1hZ2U=";
        // X/Y/scale/rotation/opacity not provided: defaults apply

        storePlanResource.saveStorePlan("BLD_SP_IMGD", input);

        Building reloaded = Building.findByCode("BLD_SP_IMGD");
        assertEquals("aW1hZ2U=", reloaded.planImage);
        assertEquals(0.0, reloaded.planImageX);
        assertEquals(0.0, reloaded.planImageY);
        assertEquals(1.0, reloaded.planImageScale);
        assertEquals(0.0, reloaded.planImageRotation);
        assertEquals(1.0, reloaded.planImageOpacity);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_ClearsPlanImageWhenNull() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_SP_IMGC");
        building.planImage = "aW1hZ2UtcG5n";
        building.planImageX = -283.0;
        building.planImageY = -238.0;
        building.planImageScale = 2.0;
        building.planImageRotation = 45.0;
        building.planImageOpacity = 0.5;
        building.persist();

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        input.nodes = List.of();
        input.planImage = null;

        storePlanResource.saveStorePlan("BLD_SP_IMGC", input);

        Building reloaded = Building.findByCode("BLD_SP_IMGC");
        assertNull(reloaded.planImage);
        assertEquals(0.0, reloaded.planImageX);
        assertEquals(0.0, reloaded.planImageY);
        assertEquals(1.0, reloaded.planImageScale);
        assertEquals(0.0, reloaded.planImageRotation);
        assertEquals(1.0, reloaded.planImageOpacity);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_RejectsInvalidPlanImageTransform() {
        persistBuilding("BLD_SP_IMGV");

        StorePlanResource.StorePlan badScale = new StorePlanResource.StorePlan();
        badScale.nodes = List.of();
        badScale.planImage = "aW1hZ2U=";
        badScale.planImageScale = 0.0;

        StorePlanResource.StorePlan badOpacity = new StorePlanResource.StorePlan();
        badOpacity.nodes = List.of();
        badOpacity.planImage = "aW1hZ2U=";
        badOpacity.planImageOpacity = 1.5;

        GraphQLException scaleEx = assertThrows(GraphQLException.class,
                () -> storePlanResource.saveStorePlan("BLD_SP_IMGV", badScale));
        assertInstanceOf(IllegalArgumentException.class, scaleEx.getCause());
        assertTrue(scaleEx.getCause().getMessage().contains("scale must be positive"));

        GraphQLException opacityEx = assertThrows(GraphQLException.class,
                () -> storePlanResource.saveStorePlan("BLD_SP_IMGV", badOpacity));
        assertInstanceOf(IllegalArgumentException.class, opacityEx.getCause());
        assertTrue(opacityEx.getCause().getMessage().contains("opacity must be between 0 and 1"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_SchemaExposesPlanImageFields() {
        // The image fields are part of the StorePlanInput contract used by the frontend.
        given()
                .contentType(ContentType.JSON)
                .body("{\"query\":\"{ __type(name: \\\"StorePlanInput\\\") { inputFields { name } } }\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.__type.inputFields.name", hasItem("planImage"))
                .body("data.__type.inputFields.name", hasItem("planImageX"))
                .body("data.__type.inputFields.name", hasItem("planImageY"))
                .body("data.__type.inputFields.name", hasItem("planImageScale"))
                .body("data.__type.inputFields.name", hasItem("planImageRotation"))
                .body("data.__type.inputFields.name", hasItem("planImageOpacity"));

        // The mutation validates against the schema: image fields are accepted
        // (an unknown building yields a business error, NOT a schema error).
        given()
                .contentType(ContentType.JSON)
                .body("{\"query\":\"mutation($b: String!, $input: StorePlanInput!) { saveStorePlan(buildingCode: $b, input: $input) { buildingCode } }\","
                        + "\"variables\":{\"b\":\"BLD_SCHEMA_UNKNOWN\",\"input\":{\"nodes\":[],\"planImage\":\"aW1hZ2U=\",\"planImageX\":-283.0,\"planImageY\":-238.0,\"planImageScale\":1.5,\"planImageRotation\":90.0,\"planImageOpacity\":0.8}}}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body(not(containsString("Unknown type")))
                .body(not(containsString("Validation error")));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_UpdatesExistingAisleGeometry() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_SP_UPD");
        PlanNode head = persistNode("HEAD", 0, 0, PlanNodeType.INTERSECTION, "Head", building);
        PlanNode tail = persistNode("TAIL", 10, 0, PlanNodeType.DEADEND, "Tail", building);
        Aisle aisle = persistAisle("A01", building);

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        input.nodes = List.of(nodeInput("HEAD", 0, 0), nodeInput("TAIL", 10, 0));
        StorePlanResource.PlanAisleGeometry aisleInput = new StorePlanResource.PlanAisleGeometry();
        aisleInput.code = "A01";
        aisleInput.flowType = "DIAGONAL";
        aisleInput.shapeType = "STRAIGHT";
        aisleInput.head = "HEAD";
        aisleInput.tail = "TAIL";
        aisleInput.points = List.of(point(3, 4), point(5, 6));
        input.aisles = List.of(aisleInput);

        StorePlanResource.StorePlanData data = storePlanResource.saveStorePlan("BLD_SP_UPD", input);

        // The aisle is updated, not recreated: business fields are preserved
        Aisle reloaded = Aisle.findByCode(building, "A01");
        assertNotNull(reloaded);
        assertEquals(aisle.id, reloaded.id);
        assertEquals(25.0, reloaded.lengthMeters, "Length must be preserved on update");
        assertEquals("LINEAR", reloaded.aisleType, "Aisle type must be preserved on update");
        assertEquals("PREV", reloaded.connectedToHead, "Logical graph must be preserved on update");
        assertEquals("NEXT", reloaded.connectedToTail, "Logical graph must be preserved on update");
        // Geometric layer is replaced
        assertEquals("DIAGONAL", reloaded.flowType);
        assertEquals("STRAIGHT", reloaded.shapeType);
        assertEquals("HEAD", reloaded.headNode.code);
        assertEquals("TAIL", reloaded.tailNode.code);
        assertEquals(2, reloaded.waypoints.size());
        assertEquals(3.0, reloaded.waypoints.get(0).x);
        assertEquals(6.0, reloaded.waypoints.get(1).y);

        // The returned data reflects the save
        assertEquals(1, data.aisles.size());
        assertEquals("DIAGONAL", data.aisles.get(0).flowType);
        assertEquals("HEAD", data.aisles.get(0).head);
        assertEquals(2, data.aisles.get(0).points.size());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_CreatesUnknownAisle() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_SP_NEW");
        persistNode("N1", 0, 0, PlanNodeType.INTERSECTION, "N1", building);

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        input.nodes = List.of(nodeInput("N1", 0, 0));

        StorePlanResource.PlanAisleGeometry withLength = new StorePlanResource.PlanAisleGeometry();
        withLength.code = "NEW_1";
        withLength.length = 7.5;
        withLength.flowType = "LINEAR";
        withLength.shapeType = "STRAIGHT";
        withLength.head = "N1";
        withLength.tail = "N1";

        StorePlanResource.PlanAisleGeometry withoutLength = new StorePlanResource.PlanAisleGeometry();
        withoutLength.code = "NEW_2";
        withoutLength.flowType = "LINEAR";

        input.aisles = List.of(withLength, withoutLength);

        storePlanResource.saveStorePlan("BLD_SP_NEW", input);

        Aisle created1 = Aisle.findByCode(building, "NEW_1");
        assertNotNull(created1);
        assertEquals(7.5, created1.lengthMeters);
        assertEquals("N1", created1.headNode.code);
        assertEquals("N1", created1.tailNode.code);

        // No length provided: the default length is used
        Aisle created2 = Aisle.findByCode(building, "NEW_2");
        assertNotNull(created2);
        assertEquals(1.0, created2.lengthMeters);
        assertNull(created2.headNode);
        assertNull(created2.tailNode);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_UnknownNodeReference() {
        persistBuilding("BLD_SP_GHOST");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        input.nodes = List.of(nodeInput("N1", 0, 0));
        StorePlanResource.PlanAisleGeometry aisleInput = new StorePlanResource.PlanAisleGeometry();
        aisleInput.code = "A01";
        aisleInput.head = "GHOST_NODE";
        input.aisles = List.of(aisleInput);

        assertThrows(NoSuchElementException.class, () -> {
            storePlanResource.saveStorePlan("BLD_SP_GHOST", input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_ZoneTooFewVertices() {
        persistBuilding("BLD_SP_Z2");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        StorePlanResource.PlanZone zoneInput = new StorePlanResource.PlanZone();
        zoneInput.code = "Z1";
        zoneInput.polygon = List.of(point(0, 0), point(1, 1)); // only 2
        input.zones = List.of(zoneInput);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            storePlanResource.saveStorePlan("BLD_SP_Z2", input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("at least 3 vertices"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_NodeMissingCode() {
        persistBuilding("BLD_SP_NC");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        StorePlanResource.PlanNode nodeInput = new StorePlanResource.PlanNode();
        nodeInput.code = " "; // blank
        nodeInput.x = 0.0;
        nodeInput.y = 0.0;
        input.nodes = List.of(nodeInput);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            storePlanResource.saveStorePlan("BLD_SP_NC", input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Code is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_NodeMissingCoordinate() {
        persistBuilding("BLD_SP_NX");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        StorePlanResource.PlanNode nodeInput = new StorePlanResource.PlanNode();
        nodeInput.code = "N1";
        nodeInput.x = null; // mandatory
        nodeInput.y = 0.0;
        input.nodes = List.of(nodeInput);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            storePlanResource.saveStorePlan("BLD_SP_NX", input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Coordinate is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_NodeDefaultType() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_SP_DT");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        StorePlanResource.PlanNode nodeInput = new StorePlanResource.PlanNode();
        nodeInput.code = "N1";
        nodeInput.type = null; // defaults to DEADEND
        nodeInput.x = 0.0;
        nodeInput.y = 0.0;
        input.nodes = List.of(nodeInput);

        StorePlanResource.StorePlanData data = storePlanResource.saveStorePlan("BLD_SP_DT", input);

        assertEquals(1, data.nodes.size());
        assertEquals(PlanNodeType.DEADEND, data.nodes.get(0).type);
        assertEquals(PlanNodeType.DEADEND, PlanNode.findByCode(building, "N1").nodeType);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_NullLists_ClearsNodesAndZones() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_SP_CLR");
        persistNode("OLD_NODE", 0, 0, PlanNodeType.DEADEND, "Old", building);
        persistZone("OLD_ZONE", building);

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        // nodes, aisles and zones are all null

        StorePlanResource.StorePlanData data = storePlanResource.saveStorePlan("BLD_SP_CLR", input);

        assertTrue(data.nodes.isEmpty());
        assertTrue(data.zones.isEmpty());
        assertNull(PlanNode.findByCode(building, "OLD_NODE"));
        assertNull(PlanZone.findByCode(building, "OLD_ZONE"));
        assertEquals(0, PlanZoneVertex.count());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStorePlan_SavesZonesWithPolygon() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_SP_ZONE");

        StorePlanResource.StorePlan input = new StorePlanResource.StorePlan();
        StorePlanResource.PlanZone zoneInput = new StorePlanResource.PlanZone();
        zoneInput.code = "FRESH_AREA";
        zoneInput.label = "Rayon Frais";
        zoneInput.color = "#0891b2";
        zoneInput.polygon = List.of(point(-293, 47), point(-188, 47), point(-183, 120), point(-298, 120));
        zoneInput.labelOffsetX = 0.0;
        zoneInput.labelOffsetY = 0.0;
        input.zones = List.of(zoneInput);

        StorePlanResource.StorePlanData data = storePlanResource.saveStorePlan("BLD_SP_ZONE", input);

        assertEquals(1, data.zones.size());
        StorePlanResource.PlanZoneData zd = data.zones.get(0);
        assertEquals("FRESH_AREA", zd.code);
        assertEquals("Rayon Frais", zd.label);
        assertEquals("#0891b2", zd.color);
        assertEquals(4, zd.polygon.size());
        assertEquals(-293.0, zd.polygon.get(0).x);
        assertEquals(120.0, zd.polygon.get(2).y);
        assertEquals(0.0, zd.labelOffset.x);

        PlanZone zone = PlanZone.findByCode(building, "FRESH_AREA");
        assertNotNull(zone);
        assertEquals(4, zone.vertices.size());
    }

    // --------------------------------------------------
    // Shelf Locations (bays) — Query Tests (MANAGER)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreLocations_BuildingNotFound() {
        assertThrows(NoSuchElementException.class, () -> storePlanResource.storeLocations("BLD_UNKNOWN"));
    }

    @Test
    @TestSecurity(user = "testUser", roles = {"MANAGER"})
    void testStoreLocations_ReturnsLocationsSortedByCode() {
        StoreBuilding building = persistBuilding("BLD_LOC_Q");
        // Chaque emplacement a son propre produit (ou aucun pour une baie vide).
        Product pates = persistProduct("3270190123458", "Pâtes");
        Product riz = persistProduct("3270190987654", "Riz");
        persistLocation(building, pates, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie");
        persistLocation(building, riz, "EPICERIE", "A05", "B1", "2", "1", 3, "Epicerie");
        persistLocation(building, null, "FRAIS", "C01", "C1", "1", "1", 1, "Frais"); // empty bay

        List<StorePlanResource.StoreLocationData> data = storePlanResource.storeLocations("BLD_LOC_Q");

        assertEquals(3, data.size());
        // Sorted by location code: EPICERIE-A05-B1-2-1 < EPICERIE-A05-B2-1-1 < FRAIS-C01-C1-1-1
        assertEquals("EPICERIE-A05-B1-2-1", data.get(0).locationCode);
        assertEquals("EPICERIE-A05-B2-1-1", data.get(1).locationCode);
        assertEquals("FRAIS-C01-C1-1-1", data.get(2).locationCode);
        assertEquals("Riz", data.get(0).productName);
        assertEquals("3270190987654", data.get(0).productEan);
        assertEquals("Pâtes", data.get(1).productName);
        // Empty bay: no product
        assertNull(data.get(2).productEan);
        assertNull(data.get(2).productName);
        assertNotNull(data.get(0).id);
    }

    // --------------------------------------------------
    // Shelf Locations (bays) — Mutation Tests (ADMIN)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_BuildingNotFound() {
        assertThrows(NoSuchElementException.class,
                () -> storePlanResource.saveStoreLocations("BLD_UNKNOWN", List.of()));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_CreatesEmptyBay() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_NEW");
        long before = ShelfLocation.count();

        StorePlanResource.StoreLocation input = locationInput(null, "FRAIS", "C01", "C9", "1", "1", 2, "Frais", null);
        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_NEW", List.of(input));

        assertEquals(1, saved.size());
        StorePlanResource.StoreLocationData data = saved.get(0);
        assertNotNull(data.id);
        assertEquals("FRAIS-C01-C9-1-1", data.locationCode);
        assertEquals("C9", data.unit);
        assertEquals(2, data.facings);
        assertNull(data.productEan);
        assertNull(data.productName);
        assertEquals(before + 1, ShelfLocation.count());

        StoreShelfLocation persisted = StoreShelfLocation.findById(data.id);
        assertNotNull(persisted);
        assertNull(persisted.product);
        assertTrue(building.locations.contains(persisted));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_UpdatesByIdAndRegeneratesCode() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_UPD");
        Product product = persistProduct("3270190123458", "Pâtes");
        StoreShelfLocation existing = persistLocation(building, product, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");
        long before = ShelfLocation.count();

        StorePlanResource.StoreLocation input = locationInput(existing.id, "EPICERIE", "A05", "B2", "3", "1", 4, "Epicerie", "3270190123458");
        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_UPD", List.of(input));

        assertEquals(1, saved.size());
        assertEquals(existing.id, saved.get(0).id);
        assertEquals("EPICERIE-A05-B2-3-1", saved.get(0).locationCode);
        assertEquals(before, ShelfLocation.count());

        StoreShelfLocation reloaded = StoreShelfLocation.findById(existing.id);
        assertEquals("B2", reloaded.unit);
        assertEquals("3", reloaded.elevation);
        assertEquals(4, reloaded.facings);
        assertEquals("EPICERIE-A05-B2-3-1", reloaded.locationCode);
        assertEquals(product.id, reloaded.product.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testSaveStoreLocations_PersistsAislePosition() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_POS");
        Product product = persistProduct("3270190123458", "Pâtes");
        StoreShelfLocation existing = persistLocation(building, product, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");

        // Placement libre : la baie est enregistrée à 42 % le long de l'allée.
        StorePlanResource.StoreLocation input = locationInput(existing.id, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie", "3270190123458");
        input.aislePosition = 0.42;
        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_POS", List.of(input));

        assertEquals(1, saved.size());
        assertEquals(0.42, saved.get(0).aislePosition);
        StoreShelfLocation reloaded = StoreShelfLocation.findById(existing.id);
        assertEquals(0.42, reloaded.aislePosition);

        // La position est aussi renvoyée par la query storeLocations.
        List<StorePlanResource.StoreLocationData> fetched = storePlanResource.storeLocations("BLD_LOC_POS");
        assertEquals(0.42, fetched.get(0).aislePosition);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_AislePositionOutOfRange() {
        persistBuilding("BLD_LOC_RANGE");
        StorePlanResource.StoreLocation input = locationInput(null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);
        input.aislePosition = 1.5;

        assertThrows(GraphQLException.class,
                () -> storePlanResource.saveStoreLocations("BLD_LOC_RANGE", List.of(input)));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_DeletesUnclaimedUnreferenced() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_DEL");
        // Produits distincts par emplacement dans le même bâtiment.
        Product product = persistProduct("3270190123458", "Pâtes");
        Product product2 = persistProduct("3270190987654", "Riz");
        StoreShelfLocation kept = persistLocation(building, product, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");
        StoreShelfLocation removed = persistLocation(building, product2, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie");
        long before = ShelfLocation.count();

        StorePlanResource.StoreLocation input = locationInput(kept.id, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie", "3270190123458");
        storePlanResource.saveStoreLocations("BLD_LOC_DEL", List.of(input));

        assertEquals(before - 1, ShelfLocation.count());
        assertNull(StoreShelfLocation.findById(removed.id));
        assertNotNull(StoreShelfLocation.findById(kept.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_MovesProductAndDeletesOldLocation() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_MV1");
        // Produits distincts par emplacement dans le même bâtiment.
        Product product = persistProduct("3270190123458", "Pâtes");
        StoreShelfLocation source = persistLocation(building, product, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");
        StoreShelfLocation target = persistLocation(building, null, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie");
        long before = ShelfLocation.count();

        // Déplace le produit vers l'emplacement cible et supprime l'emplacement
        // source en un seul enregistrement (la ligne source est absente de l'entrée).
        StorePlanResource.StoreLocation input = locationInput(target.id, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie", "3270190123458");
        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_MV1", List.of(input));

        assertEquals(1, saved.size());
        assertEquals(target.id, saved.get(0).id);
        assertEquals("3270190123458", saved.get(0).productEan);
        assertEquals(before - 1, ShelfLocation.count());
        assertNull(StoreShelfLocation.findById(source.id));
        StoreShelfLocation reloaded = StoreShelfLocation.findById(target.id);
        assertEquals(product.id, reloaded.product.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_MovesProductToNewLocationAndEmptiesOld() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_MV2");
        Product product = persistProduct("3270190123458", "Pâtes");
        StoreShelfLocation source = persistLocation(building, product, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");
        long before = ShelfLocation.count();

        // Frontend move flow: the old location is kept but emptied, a new
        // location is created with the product.
        StorePlanResource.StoreLocation emptied = locationInput(source.id, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie", null);
        StorePlanResource.StoreLocation added = locationInput(null, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie", "3270190123458");
        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_MV2", List.of(emptied, added));

        assertEquals(2, saved.size());
        assertEquals(before + 1, ShelfLocation.count());
        StoreShelfLocation reloadedSource = StoreShelfLocation.findById(source.id);
        assertNull(reloadedSource.product);
        StoreShelfLocation addedLoc = StoreShelfLocation.findById(saved.get(1).id);
        assertEquals(product.id, addedLoc.product.id);
        assertEquals("EPICERIE-A05-B2-1-1", addedLoc.locationCode);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_MovesProductBetweenExistingLocations() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_MV3");
        Product product = persistProduct("3270190123458", "Pâtes");
        Product other = persistProduct("3270190987654", "Riz");
        StoreShelfLocation source = persistLocation(building, product, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");
        StoreShelfLocation target = persistLocation(building, other, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie");

        // Replace flow: the target slot receives the product, the source slot
        // is emptied. Both rows are kept; the update order must not matter.
        StorePlanResource.StoreLocation replaced = locationInput(target.id, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie", "3270190123458");
        StorePlanResource.StoreLocation emptied = locationInput(source.id, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie", null);
        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_MV3", List.of(replaced, emptied));

        assertEquals(2, saved.size());
        StoreShelfLocation reloadedSource = StoreShelfLocation.findById(source.id);
        assertNull(reloadedSource.product);
        StoreShelfLocation reloadedTarget = StoreShelfLocation.findById(target.id);
        assertEquals(product.id, reloadedTarget.product.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_SameProductInTwoLocations() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_MULTI");
        Product product = persistProduct("3270190123458", "Pâtes");
        StoreShelfLocation bayA = persistLocation(building, null, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");
        StoreShelfLocation bayB = persistLocation(building, null, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie");

        // Un même produit peut être placé dans plusieurs baies du bâtiment.
        StorePlanResource.StoreLocation inputA = locationInput(bayA.id, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie", "3270190123458");
        StorePlanResource.StoreLocation inputB = locationInput(bayB.id, "EPICERIE", "A05", "B2", "1", "1", 2, "Epicerie", "3270190123458");
        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_MULTI", List.of(inputA, inputB));

        assertEquals(2, saved.size());
        StoreShelfLocation reloadedA = StoreShelfLocation.findById(bayA.id);
        StoreShelfLocation reloadedB = StoreShelfLocation.findById(bayB.id);
        assertEquals(product.id, reloadedA.product.id);
        assertEquals(product.id, reloadedB.product.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_KeepsLocationReferencedByFulfillmentLine() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_REF");
        Product product = persistProduct("3270190123458", "Pâtes");
        StoreShelfLocation referenced = persistLocation(building, product, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");
        persistFulfillmentLine(product, referenced);
        long before = ShelfLocation.count();

        // Empty input: the location is absent, but referenced -> must be kept.
        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_REF", List.of());

        assertTrue(saved.isEmpty());
        assertEquals(before, ShelfLocation.count());
        assertNotNull(StoreShelfLocation.findById(referenced.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_UnknownProductEan() {
        persistBuilding("BLD_LOC_EAN");
        StorePlanResource.StoreLocation input = locationInput(null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", "0000000000000");

        assertThrows(NoSuchElementException.class,
                () -> storePlanResource.saveStoreLocations("BLD_LOC_EAN", List.of(input)));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testSaveStoreLocations_BlankAisle() {
        persistBuilding("BLD_LOC_AISLE");
        StorePlanResource.StoreLocation input = locationInput(null, "EPICERIE", " ", "B1", "1", "1", 1, "Epicerie", null);

        assertThrows(GraphQLException.class,
                () -> storePlanResource.saveStoreLocations("BLD_LOC_AISLE", List.of(input)));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testSaveStoreLocations_RoundTrip_PreservesExisting() throws GraphQLException {
        StoreBuilding building = persistBuilding("BLD_LOC_RT");
        Product product = persistProduct("3270190123458", "Pâtes");
        persistLocation(building, product, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie");
        persistLocation(building, null, "FRAIS", "C01", "C1", "1", "1", 1, "Frais"); // empty bay
        long before = ShelfLocation.count();

        // Fetch, then save back the exact same state (as the editor does).
        List<StorePlanResource.StoreLocationData> fetched = storePlanResource.storeLocations("BLD_LOC_RT");
        List<StorePlanResource.StoreLocation> inputs = fetched.stream().map(d -> {
            StorePlanResource.StoreLocation in = new StorePlanResource.StoreLocation();
            in.id = d.id;
            in.area = d.area;
            in.aisle = d.aisle;
            in.unit = d.unit;
            in.elevation = d.elevation;
            in.position = d.position;
            in.facings = d.facings;
            in.department = d.department;
            in.aislePosition = d.aislePosition;
            in.productEan = d.productEan;
            return in;
        }).toList();

        List<StorePlanResource.StoreLocationData> saved = storePlanResource.saveStoreLocations("BLD_LOC_RT", inputs);

        assertEquals(fetched.size(), saved.size());
        assertEquals(before, ShelfLocation.count());
        for (int i = 0; i < fetched.size(); i++) {
            assertEquals(fetched.get(i).id, saved.get(i).id);
            assertEquals(fetched.get(i).locationCode, saved.get(i).locationCode);
            assertEquals(fetched.get(i).productEan, saved.get(i).productEan);
        }
    }

    /**
     * Schema contract: the GraphQL input type must be named {@code StoreLocationInput}.
     * SmallRye appends "Input" to the Java class simple name, so the class must be
     * named {@code StoreLocation} (a class named {@code StoreLocationInput} would be
     * exposed as {@code StoreLocationInputInput} and the frontend mutation declaring
     * {@code [StoreLocationInput!]!} would be rejected with "Unknown type").
     * This test goes through the real /graphql endpoint, unlike the tests above
     * which call the resource method directly.
     */
    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testSaveStoreLocations_SchemaExposesStoreLocationInput() {
        // The input type exists under the exact name used by the frontend.
        given()
                .contentType(ContentType.JSON)
                .body("{\"query\":\"{ __type(name: \\\"StoreLocationInput\\\") { kind name } }\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.__type.kind", equalTo("INPUT_OBJECT"))
                .body("data.__type.name", equalTo("StoreLocationInput"));

        // The free-placement field is part of the input contract.
        given()
                .contentType(ContentType.JSON)
                .body("{\"query\":\"{ __type(name: \\\"StoreLocationInput\\\") { inputFields { name type { name kind ofType { name kind } } } } }\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.__type.inputFields.name", hasItem("aislePosition"))
                .body("data.__type.inputFields.find { it.name == 'aislePosition' }.type.name", equalTo("Float"));

        // The mutation validates against the schema: an unknown building yields a
        // business error, NOT a schema validation error.
        given()
                .contentType(ContentType.JSON)
                .body("{\"query\":\"mutation($b: String!, $locations: [StoreLocationInput!]!) { saveStoreLocations(buildingCode: $b, locations: $locations) { id } }\",\"variables\":{\"b\":\"BLD_SCHEMA_UNKNOWN\",\"locations\":[]}}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body(not(containsString("Unknown type")))
                .body(not(containsString("Validation error")));
    }

    // --------------------------------------------------
    // Shelf location fixtures
    // --------------------------------------------------

    private Product persistProduct(String ean, String name) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.productType = ProductType.UNIT;
        product.temperatureZone = TemperatureZone.AMBIENT;
        product.persist();
        return product;
    }

    private StoreShelfLocation persistLocation(StoreBuilding building, Product product, String area, String aisle,
                                               String unit, String elevation, String position, int facings, String department) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product;
        location.area = area;
        location.aisle = aisle;
        location.unit = unit;
        location.elevation = elevation;
        location.position = position;
        location.facings = facings;
        location.department = department;
        // Persist before adding to the set: BaseEntity.hashCode() is based on the id.
        location.persist();
        building.locations.add(location);
        return location;
    }

    private FulfillmentLine persistFulfillmentLine(Product product, BaseLocation location) {
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = 1;
        line.product = product;
        line.requestedQuantity = BigDecimal.ONE;
        line.location = location;
        line.persist();
        return line;
    }

    private StorePlanResource.StoreLocation locationInput(Long id, String area, String aisle, String unit,
                                                               String elevation, String position, Integer facings,
                                                               String department, String productEan) {
        StorePlanResource.StoreLocation input = new StorePlanResource.StoreLocation();
        input.id = id;
        input.area = area;
        input.aisle = aisle;
        input.unit = unit;
        input.elevation = elevation;
        input.position = position;
        input.facings = facings;
        input.department = department;
        input.productEan = productEan;
        return input;
    }
}
