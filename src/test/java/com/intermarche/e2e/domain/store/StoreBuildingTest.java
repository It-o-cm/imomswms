package com.intermarche.e2e.domain.store;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
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
 * Integration tests for {@link StoreBuilding}.
 * Covers SINGLE_TABLE inheritance persistence: the STORE discriminator value,
 * polymorphic reloading through {@link Building}, and the Panache finder methods.
 */
@QuarkusTest
@TestTransaction
public class StoreBuildingTest {

    @Inject
    EntityManager em;

    private StoreBuilding persistBuilding(String code) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = code;
        building.persist();
        return building;
    }

    @Test
    void persist_shouldStoreDiscriminatorValueStore() {
        StoreBuilding building = persistBuilding("SB-TEST-01");
        Panache.flush();

        Object type = em.createNativeQuery("SELECT type FROM buildings WHERE id = ?1")
                .setParameter(1, building.id)
                .getSingleResult();

        assertEquals("STORE", type);
    }

    @Test
    void findById_shouldReturnStoreBuildingInstance() {
        StoreBuilding building = persistBuilding("SB-TEST-02");

        Building reloaded = Building.findById(building.id);

        assertInstanceOf(StoreBuilding.class, reloaded);
        assertEquals("SB-TEST-02", reloaded.buildingCode);
    }

    @Test
    void findByCode_shouldFindStoreBuilding() {
        StoreBuilding building = persistBuilding("SB-TEST-03");

        Building found = Building.findByCode("SB-TEST-03");

        assertNotNull(found);
        assertInstanceOf(StoreBuilding.class, found);
        assertEquals(building.id, found.id);
    }

    @Test
    void findStoreByBuildingId_shouldReturnOwningStore() {
        Store store = DomainUtils.createAndPersistStore("SBB_S1", 48.0, 2.0);
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "SB-TEST-04";
        store.buildings.add(building);
        building.persist();
        em.flush(); // writes the store_id FK managed by the Store side

        Store found = Building.findStoreByBuildingId(building.id);

        assertNotNull(found);
        assertEquals(store.id, found.id);
    }

    @Test
    void findStoreByBuildingId_shouldReturnNull_whenBuildingIdIsNull() {
        assertNull(Building.findStoreByBuildingId(null));
    }

    @Test
    void getChecksum_shouldDelegateToBuildingChecksum() {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "SB-TEST-05";
        building.address = new Adresse();
        building.address.city = "Lyon";

        int expected = Objects.hash("SB-TEST-05", building.address.getChecksum(), 0);

        assertEquals(expected, building.getChecksum());
    }
}
