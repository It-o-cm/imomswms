package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link StorePlanResource}.
 */
class StorePlanResourceTest {

    /**
     * Checks get identity: returns injected identity.
     */
    @Test
    void getIdentity_returnsInjectedIdentity() {
        StorePlanResource resource = new StorePlanResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    /**
     * Checks point data: default constructor, leaves coordinates null.
     */
    @Test
    void pointData_defaultConstructor_leavesCoordinatesNull() {
        StorePlanResource.PointData point = new StorePlanResource.PointData();
        assertEquals(null, point.x);
        assertEquals(null, point.y);
    }

    /**
     * Checks point data: coordinates constructor, sets coordinates.
     */
    @Test
    void pointData_coordinatesConstructor_setsCoordinates() {
        StorePlanResource.PointData point = new StorePlanResource.PointData(1.5, -2.5);
        assertEquals(1.5, point.x);
        assertEquals(-2.5, point.y);
    }

    // --------------------------------------------------
    // expectedCode
    // --------------------------------------------------

    /**
     * Checks expected code: builds code from full address.
     */
    @Test
    void expectedCode_buildsCodeFromFullAddress() {
        StorePlanResource.StoreLocation input = input(null, "EPICERIE", "A05", "B", "2", "05", null, null, null);

        assertEquals("EPICERIE-A05-B-2-05", StorePlanResource.expectedCode(input));
    }

    /**
     * Checks expected code: uses double question marks for missing parts.
     */
    @Test
    void expectedCode_usesDoubleQuestionMarksForMissingParts() {
        StorePlanResource.StoreLocation input = input(null, null, "A05", null, null, "05", null, null, null);

        assertEquals("??-A05-??-??-05", StorePlanResource.expectedCode(input));
    }

    /**
     * Checks expected code: uses double question marks for blank area.
     */
    @Test
    void expectedCode_usesDoubleQuestionMarksForBlankArea() {
        StorePlanResource.StoreLocation input = input(null, "  ", "A05", "B", "1", "1", null, null, null);

        assertEquals("??-A05-B-1-1", StorePlanResource.expectedCode(input));
    }

    // --------------------------------------------------
    // toData
    // --------------------------------------------------

    /**
     * Checks to data: maps all fields.
     */
    @Test
    void toData_mapsAllFields() {
        Product product = new Product();
        product.ean = "3270190123458";
        product.name = "Pâtes";
        StoreShelfLocation loc = location(7L, product, "EPICERIE", "A05", "B", "2", "05", 3, "Epicerie");
        loc.aislePosition = 0.42;

        StorePlanResource.StoreLocationData data = StorePlanResource.toData(loc);

        assertEquals(7L, data.id);
        assertEquals("EPICERIE-A05-B-2-05", data.locationCode);
        assertEquals("EPICERIE", data.area);
        assertEquals("A05", data.aisle);
        assertEquals("B", data.unit);
        assertEquals("2", data.elevation);
        assertEquals("05", data.position);
        assertEquals(3, data.facings);
        assertEquals("Epicerie", data.department);
        assertEquals(0.42, data.aislePosition);
        assertEquals("3270190123458", data.productEan);
        assertEquals("Pâtes", data.productName);
    }

    /**
     * Checks to data: null aisle position, maps to null.
     */
    @Test
    void toData_nullAislePosition_mapsToNull() {
        StoreShelfLocation loc = location(8L, null, "FRAIS", "C01", "C1", "1", "1", 1, "Frais");

        assertNull(StorePlanResource.toData(loc).aislePosition);
    }

    /**
     * Checks to data: empty bay, has null product fields.
     */
    @Test
    void toData_emptyBay_hasNullProductFields() {
        StoreShelfLocation loc = location(8L, null, "FRAIS", "C01", "C1", "1", "1", 1, "Frais");

        StorePlanResource.StoreLocationData data = StorePlanResource.toData(loc);

        assertNull(data.productEan);
        assertNull(data.productName);
    }

    // --------------------------------------------------
    // reconcileLocations
    // --------------------------------------------------

    /**
     * Checks reconcile: creates new location, when no match.
     */
    @Test
    void reconcile_createsNewLocation_whenNoMatch() {
        StorePlanResource.StoreLocation input = input(null, "EPICERIE", "A05", "B", "1", "1", 2, "Epicerie", null);

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(), List.of(input), ean -> null, loc -> false);

        assertEquals(1, rec.saved().size());
        StoreShelfLocation created = rec.saved().get(0);
        assertNull(created.id);
        assertEquals("EPICERIE", created.area);
        assertEquals("A05", created.aisle);
        assertEquals("B", created.unit);
        assertEquals("1", created.elevation);
        assertEquals("1", created.position);
        assertEquals(2, created.facings);
        assertEquals("Epicerie", created.department);
        assertNull(created.product);
        assertEquals("EPICERIE-A05-B-1-1", created.locationCode);
        assertTrue(rec.toDelete().isEmpty());
    }

    /**
     * Checks reconcile: updates by id, and regenerates code.
     */
    @Test
    void reconcile_updatesById_andRegeneratesCode() {
        StoreShelfLocation existing = location(7L, null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie");
        StorePlanResource.StoreLocation input = input(7L, "EPICERIE", "A05", "B2", "3", "1", 4, "Epicerie", null);

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(existing), List.of(input), ean -> null, loc -> false);

        assertEquals(1, rec.saved().size());
        assertSame(existing, rec.saved().get(0));
        assertEquals("B2", existing.unit);
        assertEquals("3", existing.elevation);
        assertEquals(4, existing.facings);
        assertEquals("EPICERIE-A05-B2-3-1", existing.locationCode);
        assertTrue(rec.toDelete().isEmpty());
    }

    /**
     * Checks reconcile: updates by code, when no id.
     */
    @Test
    void reconcile_updatesByCode_whenNoId() {
        StoreShelfLocation existing = location(7L, null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie");
        StorePlanResource.StoreLocation input = input(null, "EPICERIE", "A05", "B1", "1", "1", 5, "Epicerie", null);

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(existing), List.of(input), ean -> null, loc -> false);

        assertSame(existing, rec.saved().get(0));
        assertEquals(5, existing.facings);
        assertEquals("EPICERIE-A05-B1-1-1", existing.locationCode);
        assertTrue(rec.toDelete().isEmpty());
    }

    /**
     * Checks reconcile: empty bay, has null product.
     */
    @Test
    void reconcile_emptyBay_hasNullProduct() {
        StorePlanResource.StoreLocation input = input(null, "FRAIS", "C01", "C1", "1", "1", 1, "Frais", null);

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(), List.of(input), ean -> {
                    throw new AssertionError("Resolver must not be called for a null EAN");
                }, loc -> false);

        assertNull(rec.saved().get(0).product);
    }

    /**
     * Checks reconcile: resolves product by ean.
     */
    @Test
    void reconcile_resolvesProductByEan() {
        Product product = new Product();
        product.ean = "3270190123458";
        StorePlanResource.StoreLocation input = input(null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", "3270190123458");

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(), List.of(input), ean -> "3270190123458".equals(ean) ? product : null, loc -> false);

        assertSame(product, rec.saved().get(0).product);
    }

    /**
     * Checks reconcile: deletes unclaimed and unreferenced.
     */
    @Test
    void reconcile_deletesUnclaimedAndUnreferenced() {
        StoreShelfLocation kept = location(1L, null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie");
        StoreShelfLocation removed = location(2L, null, "EPICERIE", "A05", "B2", "1", "1", 1, "Epicerie");
        StorePlanResource.StoreLocation input = input(1L, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(kept, removed), List.of(input), ean -> null, loc -> false);

        assertEquals(List.of(removed), rec.toDelete());
    }

    /**
     * Checks reconcile: keeps unclaimed but referenced.
     */
    @Test
    void reconcile_keepsUnclaimedButReferenced() {
        StoreShelfLocation referenced = location(2L, null, "EPICERIE", "A05", "B2", "1", "1", 1, "Epicerie");

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(referenced), List.of(), ean -> null, loc -> true);

        assertTrue(rec.saved().isEmpty());
        assertTrue(rec.toDelete().isEmpty());
    }

    /**
     * Checks reconcile: null inputs, deletes all unreferenced.
     */
    @Test
    void reconcile_nullInputs_deletesAllUnreferenced() {
        StoreShelfLocation loc = location(2L, null, "EPICERIE", "A05", "B2", "1", "1", 1, "Epicerie");

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(loc), null, ean -> null, loc2 -> false);

        assertTrue(rec.saved().isEmpty());
        assertEquals(List.of(loc), rec.toDelete());
    }

    /**
     * Checks reconcile: duplicate code in input, yields single location.
     */
    @Test
    void reconcile_duplicateCodeInInput_yieldsSingleLocation() {
        StorePlanResource.StoreLocation first = input(null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);
        StorePlanResource.StoreLocation second = input(null, "EPICERIE", "A05", "B1", "1", "1", 2, "Epicerie", null);

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(), List.of(first, second), ean -> null, loc -> false);

        assertEquals(2, rec.saved().size());
        assertSame(rec.saved().get(0), rec.saved().get(1));
        assertEquals(2, rec.saved().get(0).facings);
    }

    /**
     * Checks reconcile: maps aisle position, on create.
     */
    @Test
    void reconcile_mapsAislePosition_onCreate() {
        StorePlanResource.StoreLocation input = input(null, "EPICERIE", "A05", "B", "1", "1", 2, "Epicerie", null);
        input.aislePosition = 0.42;

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(), List.of(input), ean -> null, loc -> false);

        assertEquals(0.42, rec.saved().get(0).aislePosition);
    }

    /**
     * Checks reconcile: maps aisle position, on update.
     */
    @Test
    void reconcile_mapsAislePosition_onUpdate() {
        StoreShelfLocation existing = location(7L, null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie");
        StorePlanResource.StoreLocation input = input(7L, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);
        input.aislePosition = 0.75;

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(existing), List.of(input), ean -> null, loc -> false);

        assertSame(existing, rec.saved().get(0));
        assertEquals(0.75, existing.aislePosition);
    }

    /**
     * Checks reconcile: null aisle position, clears explicit position.
     */
    @Test
    void reconcile_nullAislePosition_clearsExplicitPosition() {
        StoreShelfLocation existing = location(7L, null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie");
        existing.aislePosition = 0.5;
        StorePlanResource.StoreLocation input = input(7L, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);

        StorePlanResource.reconcileLocations(List.of(existing), List.of(input), ean -> null, loc -> false);

        assertNull(existing.aislePosition);
    }

    /**
     * Checks reconcile: throws on aisle position out of range.
     */
    @Test
    void reconcile_throwsOnAislePositionOutOfRange() {
        StorePlanResource.StoreLocation below = input(null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);
        below.aislePosition = -0.1;
        StorePlanResource.StoreLocation above = input(null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);
        above.aislePosition = 1.1;

        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.reconcileLocations(List.of(), List.of(below), ean -> null, loc -> false));
        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.reconcileLocations(List.of(), List.of(above), ean -> null, loc -> false));
    }

    /**
     * Checks reconcile: accepts aisle position bounds.
     */
    @Test
    void reconcile_acceptsAislePositionBounds() {
        StorePlanResource.StoreLocation zero = input(null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);
        zero.aislePosition = 0.0;
        StorePlanResource.StoreLocation one = input(null, "EPICERIE", "A05", "B2", "1", "1", 1, "Epicerie", null);
        one.aislePosition = 1.0;

        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(), List.of(zero, one), ean -> null, loc -> false);

        assertEquals(0.0, rec.saved().get(0).aislePosition);
        assertEquals(1.0, rec.saved().get(1).aislePosition);
    }

    /**
     * Checks reconcile: throws on blank aisle.
     */
    @Test
    void reconcile_throwsOnBlankAisle() {
        StorePlanResource.StoreLocation input = input(null, "EPICERIE", " ", "B1", "1", "1", 1, "Epicerie", null);

        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.reconcileLocations(List.of(), List.of(input), ean -> null, loc -> false));
    }

    /**
     * Checks reconcile: throws on null unit.
     */
    @Test
    void reconcile_throwsOnNullUnit() {
        StorePlanResource.StoreLocation input = input(null, "EPICERIE", "A05", null, "1", "1", 1, "Epicerie", null);

        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.reconcileLocations(List.of(), List.of(input), ean -> null, loc -> false));
    }

    /**
     * Checks reconcile: throws on facings below one.
     */
    @Test
    void reconcile_throwsOnFacingsBelowOne() {
        StorePlanResource.StoreLocation input = input(null, "EPICERIE", "A05", "B1", "1", "1", 0, "Epicerie", null);

        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.reconcileLocations(List.of(), List.of(input), ean -> null, loc -> false));
    }

    /**
     * Checks reconcile: throws on foreign id.
     */
    @Test
    void reconcile_throwsOnForeignId() {
        StoreShelfLocation existing = location(7L, null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie");
        StorePlanResource.StoreLocation input = input(99L, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null);

        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.reconcileLocations(List.of(existing), List.of(input), ean -> null, loc -> false));
    }

    /**
     * Checks reconcile: skips null inputs.
     */
    @Test
    void reconcile_skipsNullInputs() {
        StorePlanResource.Reconciliation rec = StorePlanResource.reconcileLocations(
                List.of(), java.util.Arrays.asList(null, input(null, "EPICERIE", "A05", "B1", "1", "1", 1, "Epicerie", null)),
                ean -> null, loc -> false);

        assertEquals(1, rec.saved().size());
    }

    // --------------------------------------------------
    // Fixture helpers
    // --------------------------------------------------

    /**
     * Builds a desired shelf location.
     *
     * @param id         the id of the location to update, or null
     * @param area       the area
     * @param aisle      the aisle
     * @param unit       the unit
     * @param elevation  the elevation
     * @param position   the position
     * @param facings    the facings
     * @param department the department
     * @param productEan the EAN of the product, or null for an empty bay
     * @return the input
     */
    private static StorePlanResource.StoreLocation input(Long id, String area, String aisle, String unit,
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

    /**
     * Builds an existing shelf location with its generated code.
     *
     * @param id         the id
     * @param product    the product, or null
     * @param area       the area
     * @param aisle      the aisle
     * @param unit       the unit
     * @param elevation  the elevation
     * @param position   the position
     * @param facings    the facings
     * @param department the department
     * @return the location
     */
    private static StoreShelfLocation location(Long id, Product product, String area, String aisle, String unit,
                                               String elevation, String position, int facings, String department) {
        StoreShelfLocation loc = new StoreShelfLocation();
        loc.id = id;
        loc.product = product;
        loc.area = area;
        loc.aisle = aisle;
        loc.unit = unit;
        loc.elevation = elevation;
        loc.position = position;
        loc.facings = facings;
        loc.department = department;
        loc.onCreate();
        return loc;
    }

    /**
     * Checks check plan image: accepts raster signatures.
     */
    @Test
    void checkPlanImage_acceptsRasterSignatures() {
        for (byte[] header : List.of(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0},
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0},
                new byte[]{'G', 'I', 'F', '8', '9', 'a'},
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})) {
            assertDoesNotThrow(() -> StorePlanResource.checkPlanImage(java.util.Base64.getEncoder().encodeToString(header)));
        }
    }

    /**
     * Checks check plan image: rejects other contents.
     */
    @Test
    void checkPlanImage_rejectsOtherContents() {
        java.util.Base64.Encoder encoder = java.util.Base64.getEncoder();
        assertThrows(IllegalArgumentException.class, () -> StorePlanResource.checkPlanImage("not base64 !"));
        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.checkPlanImage(encoder.encodeToString("<svg onload=alert(1)>".getBytes())));
        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.checkPlanImage(encoder.encodeToString(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'})));
        assertThrows(IllegalArgumentException.class,
                () -> StorePlanResource.checkPlanImage(encoder.encodeToString(new byte[]{(byte) 0x89, 'P'})));
    }

    /**
     * Checks check plan image: rejects images decoded above the limit.
     */
    @Test
    void checkPlanImage_rejectsImagesDecodedAboveTheLimit() {
        byte[] large = new byte[StorePlanResource.PLAN_IMAGE_MAX_BYTES + 1];
        large[0] = (byte) 0xFF;
        large[1] = (byte) 0xD8;
        large[2] = (byte) 0xFF;
        String encoded = java.util.Base64.getEncoder().encodeToString(large);
        assertThrows(IllegalArgumentException.class, () -> StorePlanResource.checkPlanImage(encoded));
        byte[] limit = java.util.Arrays.copyOf(large, StorePlanResource.PLAN_IMAGE_MAX_BYTES);
        assertDoesNotThrow(() -> StorePlanResource.checkPlanImage(java.util.Base64.getEncoder().encodeToString(limit)));
    }

    /**
     * Checks check plan image: rejects text longer than the limit before decoding.
     */
    @Test
    void checkPlanImage_rejectsTextLongerThanTheLimitBeforeDecoding() {
        // Two bytes over the limit need one more base64 block than the text bound allows
        byte[] bytes = new byte[StorePlanResource.PLAN_IMAGE_MAX_BYTES + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        String encoded = java.util.Base64.getEncoder().encodeToString(bytes);
        assertThrows(IllegalArgumentException.class, () -> StorePlanResource.checkPlanImage(encoded));
    }
}
