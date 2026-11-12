package com.intermarche.e2e.graphql.store;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.graphql.store.AisleResource;
import com.intermarche.backoffice.graphql.store.PlanNodeResource;
import com.intermarche.backoffice.graphql.store.PlanZoneResource;
import com.intermarche.backoffice.graphql.store.StoreResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A manager reads the topology (store, aisles, plan nodes and plan zones) of their own store only; an administrator
 * reads every store.
 */
@QuarkusTest
@TestTransaction
public class TopologyScopeTest {

    @Inject StoreResource stores;
    @Inject AisleResource aisles;
    @Inject PlanNodeResource nodes;
    @Inject PlanZoneResource zones;

    /**
     * The topology of one store: a building holding an aisle, a plan node and a plan zone.
     *
     * @param store    the store
     * @param building the building
     * @param aisle    the aisle
     * @param node     the plan node
     * @param zone     the plan zone
     */
    record Topology(Store store, StoreBuilding building, Aisle aisle, PlanNode node, PlanZone zone) {}

    /**
     * Persists a building of a store with an aisle, a plan node and a plan zone.
     *
     * @param store  the store
     * @param prefix the prefix of the codes
     * @return the topology
     */
    private static Topology topology(Store store, String prefix) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = prefix + "_BLD";
        building.persist();
        store.buildings.add(building);
        Aisle aisle = new Aisle();
        aisle.code = prefix + "_AISLE";
        aisle.lengthMeters = 10.0;
        aisle.aisleType = "LINEAR";
        aisle.connectedToHead = "H";
        aisle.connectedToTail = "T";
        aisle.building = building;
        aisle.persist();
        PlanNode node = new PlanNode();
        node.code = prefix + "_NODE";
        node.label = "Node";
        node.nodeType = PlanNodeType.INTERSECTION;
        node.x = 1.0;
        node.y = 2.0;
        node.building = building;
        node.persist();
        PlanZone zone = new PlanZone();
        zone.code = prefix + "_ZONE";
        zone.label = "Zone";
        zone.color = "#0891b2";
        zone.temperatureZone = TemperatureZone.CHILLED;
        zone.labelOffsetX = 0.0;
        zone.labelOffsetY = 0.0;
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
        return new Topology(store, building, aisle, node, zone);
    }

    /**
     * A manager lists and reads its own topology, and is refused the topology of another store addressed by id, by
     * code or by building.
     */
    @Test
    @TestSecurity(user = "TOPO_MGR", roles = {"MANAGER"})
    void managerReadsOnlyTheTopologyOfTheirStore() {
        Topology own = topology(DomainUtils.managerStore("TOPO_MGR"), "TOPO_OWN");
        Topology other = topology(DomainUtils.createAndPersistStore("TOPO_OTHER", 48.0, 2.0), "TOPO_OTH");

        assertEquals(java.util.List.of(own.store().id), stores.allStores().stream().map(s -> s.id).toList());
        assertEquals(own.store().id, stores.store(own.store().id).id);
        assertThrows(SecurityException.class, () -> stores.store(other.store().id));
        assertThrows(SecurityException.class, () -> stores.storeByCode("TOPO_OTHER"));

        assertEquals(java.util.List.of(own.aisle().id), aisles.allAisles().stream().map(a -> a.id).toList());
        assertEquals(own.aisle().id, aisles.aisle(own.aisle().id).id);
        assertThrows(SecurityException.class, () -> aisles.aisle(other.aisle().id));
        assertThrows(SecurityException.class, () -> aisles.aisleByCode("TOPO_OTH_AISLE"));
        assertThrows(SecurityException.class, () -> aisles.aislesByBuilding("TOPO_OTH_BLD"));

        assertEquals(java.util.List.of(own.node().id), nodes.allPlanNodes().stream().map(n -> n.id).toList());
        assertThrows(SecurityException.class, () -> nodes.planNode(other.node().id));
        assertThrows(SecurityException.class, () -> nodes.planNodeByCode("TOPO_OTH_NODE"));
        assertThrows(SecurityException.class, () -> nodes.planNodesByBuilding("TOPO_OTH_BLD"));

        assertEquals(java.util.List.of(own.zone().id), zones.allPlanZones().stream().map(z -> z.id).toList());
        assertThrows(SecurityException.class, () -> zones.planZone(other.zone().id));
        assertThrows(SecurityException.class, () -> zones.planZoneByCode("TOPO_OTH_ZONE"));
        assertThrows(SecurityException.class, () -> zones.planZonesByBuilding("TOPO_OTH_BLD"));
    }

    /**
     * An administrator, holding every role, reads the topology of every store.
     */
    @Test
    @TestSecurity(user = "TOPO_ADMIN", roles = {"ADMIN", "MANAGER"})
    void administratorReadsEveryStore() {
        Topology first = topology(DomainUtils.createAndPersistStore("TOPO_A", 48.0, 2.0), "TOPO_A");
        Topology second = topology(DomainUtils.createAndPersistStore("TOPO_B", 48.0, 2.0), "TOPO_B");

        assertTrue(stores.allStores().stream().map(s -> s.id).toList().containsAll(java.util.List.of(first.store().id, second.store().id)));
        assertEquals(second.aisle().id, aisles.aisle(second.aisle().id).id);
        assertEquals(second.node().id, nodes.planNodeByCode("TOPO_B_NODE").id);
        assertEquals(1, zones.planZonesByBuilding("TOPO_B_BLD").size());
    }
}
