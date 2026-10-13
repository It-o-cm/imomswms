package com.intermarche.backoffice.domain.wms.handling;

import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Employee;
import jakarta.persistence.*;

import java.util.*;

/**
 * Specific implementation of a Handling Order for Collecting by Product List.
 * <p>
 * Designates a specific list of {@link Product}s to be collected, instead of a geographic zone.
 * <p>
 * Similar to {@link ZoneCollectingOrder} but targets specific SKUs rather than a physical area.
 * Extends {@link CollectingOrder} to inherit common fields like building and lot management.
 */
@Entity
@DiscriminatorValue("PRODUCT_COLLECTING")
public class ProductCollectingOrder extends CollectingOrder {

    /**
     * The list of Products designated for collecting in this order.
     * <p>
     * This defines the scope of the operation by specific items rather than by location.
     */
    public List<String> eans = new ArrayList<>();

    /**
     * Returns the prefix associated with the ProductCollectingOrder.
     *
     * @return The string "PRO", representing the prefix for ProductCollectingOrder codes.
     */
    @Override
    public String getPrefix() {
        return "PRO";
    }

    /**
     * Helper method to add a Product to this collecting order.
     *
     * @param ean The ean of the product to add.
     */
    public void addEan(String ean) {
        this.eans.add(ean);
    }

    /**
     * Calculates the checksum including specific fields (Products).
     *
     * @return A hash representing the state of the order.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                super.getChecksum(), // Includes fields from BaseHandlingOrder, CollectingOrder (status, dates, building, area, etc.)
                eans
        );
    }

}