package com.intermarche.backoffice.domain.util;

import com.intermarche.backoffice.domain.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;

import java.math.BigDecimal;
import java.util.HashSet;

public class DomainUtils {

    public static Product createAndPersistProduct(String ean, String name, ProductType type) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.productType = type;
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
