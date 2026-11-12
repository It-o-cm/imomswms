package com.intermarche.backoffice.engine.fieldops;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks what a tote or a parcel is said to carry on the handover screen.
 */
class DepartureContentsTest {

    /**
     * Builds a product of the given type and unit.
     *
     * @param id the product id
     * @param name the product name
     * @param type the product type
     * @param unitName the unit name, or null
     * @return the product
     */
    private static Product product(long id, String name, ProductType type, String unitName) {
        Product product = new Product();
        product.id = id;
        product.name = name;
        product.ean = "300000000000" + id;
        product.productType = type;
        product.unitName = unitName;
        return product;
    }

    /**
     * Checks that the quantities of a product spread over several picks are summed, in the order met.
     */
    @Test
    void quantitiesOfTheSameProductAreSummed() {
        Product flour = product(1, "Farine T55 1kg", ProductType.UNIT, null);
        Product salmon = product(2, "Saumon", ProductType.WEIGHT, null);

        List<Map<String, Object>> rows = DepartureService.contents(List.of(
                Map.entry(flour, new BigDecimal("1.000000")),
                Map.entry(salmon, new BigDecimal("1.250000")),
                Map.entry(flour, BigDecimal.ONE)));

        assertEquals(2, rows.size());
        assertEquals("Farine T55 1kg", rows.get(0).get("name"));
        assertEquals("2", rows.get(0).get("quantity"));
        assertEquals("pcs", rows.get(0).get("unit"));
        assertEquals("3000000000001", rows.get(0).get("ean"));
        assertEquals("1.25", rows.get(1).get("quantity"));
        assertEquals("kg", rows.get(1).get("unit"));
    }

    /**
     * Checks the unit given for a volume product and for a unit product with its own unit name.
     */
    @Test
    void unitsFollowTheProductType() {
        assertEquals("L", DepartureService.contents(List.of(Map.entry(product(3, "Huile", ProductType.VOLUME, null), BigDecimal.ONE))).get(0).get("unit"));
        assertEquals("sachets", DepartureService.contents(List.of(Map.entry(product(4, "Levure", ProductType.UNIT, "sachets"), BigDecimal.ONE))).get(0).get("unit"));
    }
}
