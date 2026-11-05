package com.intermarche.e2e.domain.util;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import java.math.BigDecimal;
import java.util.HashSet;
import com.intermarche.backoffice.domain.Adresse;

public class DomainUtils {

    public static Product createAndPersistProduct(String ean, String name, ProductType type) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.productType = type;
        product.temperatureZone = TemperatureZone.AMBIENT;
        product.persist();
        return product;
    }

    public static void setProductCharacteristics(String ean, BigDecimal referenceWeight, BigDecimal referenceVolume) {
        Product product = Product.findByEan(ean);
        product.referenceWeight = referenceWeight;
        product.referenceVolume = referenceVolume;
        product.persist();
    }

    /**
     * Helper to create and persist a Store with coordinates.
     */
    public static Store createAndPersistStore(String code, Double lat, Double lon) {
        Store s = new Store();
        s.code = code;
        s.name = "Store " + code;
        s.address = new Adresse();
        s.persist();
        return s;
    }

    /**
     * Returns the store of a manager, creating the manager and a store of its own when the badge is unknown: managers
     * only read the topology of their store.
     *
     * @param badgeId the badge of the manager
     * @return the store of the manager
     */
    public static Store managerStore(String badgeId) {
        com.intermarche.backoffice.domain.store.Employee existing = com.intermarche.backoffice.domain.store.Employee.findByBadgeId(badgeId);
        if (existing != null && existing.store != null) return existing.store;
        Store store = createAndPersistStore("MGR_" + badgeId, 48.0, 2.0);
        var manager = new com.intermarche.backoffice.domain.store.Employee();
        manager.firstName = "Manager";
        manager.lastName = badgeId;
        manager.badgeId = badgeId;
        manager.email = badgeId + "@test.invalid";
        manager.password = org.mindrot.jbcrypt.BCrypt.hashpw("secret", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        manager.role = com.intermarche.backoffice.domain.store.Employee.EmployeeRole.MANAGER;
        manager.store = store;
        manager.persist();
        return store;
    }

    /**
     * Grants an integration account access to a store: an integration account reads and writes the single store of
     * its active grants.
     *
     * @param store     the store
     * @param principal the name of the integration account
     * @return the persisted grant
     */
    public static com.intermarche.backoffice.domain.commerce.CommerceStoreGrant grantStore(Store store, String principal) {
        var grant = new com.intermarche.backoffice.domain.commerce.CommerceStoreGrant();
        grant.store = store;
        grant.principal = principal;
        grant.sourceSystem = "TEST";
        grant.grantedBy = "test";
        grant.persist();
        return grant;
    }

    /**
     * Creates and persists a StoreGroup entity with the given code.
     * <p>
     * The 'name' is set to the code by default.
     * Initializes the 'stores' and 'storeGroups' sets to avoid NullPointerException.
     *
     * @param code The unique code (and name) of the group.
     * @return The created and persisted StoreGroup entity.
     */
    public static StoreGroup createAndPersistStoreGroup(String code) {
        StoreGroup group = new StoreGroup();
        group.code = code;
        group.name = code; // Default name to code
        // Initialize Sets to prevent NullPointerException during relationship operations
        if (group.stores == null) group.stores = new HashSet<>();
        if (group.storeGroups == null) group.storeGroups = new HashSet<>();

        group.persist();
        return group;
    }

}
