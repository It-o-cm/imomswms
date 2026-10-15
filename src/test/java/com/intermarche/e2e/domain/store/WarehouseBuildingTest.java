package com.intermarche.e2e.domain.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link WarehouseBuilding}.
 * Covers SINGLE_TABLE inheritance persistence: the WAREHOUSE discriminator value,
 * polymorphic reloading through {@link Building}, and the Panache finder methods.
 */
@QuarkusTest
@TestTransaction
public class WarehouseBuildingTest {

    @Inject
    EntityManager em;

    private WarehouseBuilding persistBuilding(String code) {
        WarehouseBuilding building = new WarehouseBuilding();
        building.buildingCode = code;
        building.persist();
        return building;
    }

    @Test
    void persist_shouldStoreDiscriminatorValueWarehouse() {
        WarehouseBuilding building = persistBuilding("WH-TEST-01");
        Panache.flush();

        Object type = em.createNativeQuery("SELECT type FROM buildings WHERE id = ?1")
                .setParameter(1, building.id)
                .getSingleResult();

        assertEquals("WAREHOUSE", type);
    }

    @Test
    void findById_shouldReturnWarehouseBuildingInstance() {
        WarehouseBuilding building = persistBuilding("WH-TEST-02");

        Building reloaded = Building.findById(building.id);

        assertInstanceOf(WarehouseBuilding.class, reloaded);
        assertEquals("WH-TEST-02", reloaded.buildingCode);
    }

    @Test
    void findByCode_shouldFindWarehouseBuilding() {
        WarehouseBuilding building = persistBuilding("WH-TEST-03");

        Building found = Building.findByCode("WH-TEST-03");

        assertNotNull(found);
        assertInstanceOf(WarehouseBuilding.class, found);
        assertEquals(building.id, found.id);
    }

    @Test
    void findStoreByBuildingId_shouldReturnOwningStore() {
        Store store = DomainUtils.createAndPersistStore("WHB_S1", 48.0, 2.0);
        WarehouseBuilding building = new WarehouseBuilding();
        building.buildingCode = "WH-TEST-04";
        store.buildings.add(building);
        building.persist();
        em.flush(); // writes the store_id FK managed by the Store side

        Store found = Building.findStoreByBuildingId(building.id);

        assertNotNull(found);
        assertEquals(store.id, found.id);
    }

    @Test
    void getChecksum_shouldDelegateToBuildingChecksum() {
        WarehouseBuilding building = new WarehouseBuilding();
        building.buildingCode = "WH-TEST-05";
        building.address = new Adresse();
        building.address.city = "Lyon";

        int expected = Objects.hash("WH-TEST-05", building.address.getChecksum(), 0);

        assertEquals(expected, building.getChecksum());
    }
}
