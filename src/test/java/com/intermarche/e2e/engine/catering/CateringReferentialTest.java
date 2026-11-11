package com.intermarche.e2e.engine.catering;

import com.intermarche.backoffice.domain.catering.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Tests the catering referential resource rights and validations (§ 8.1, T1.060, T1.061):
 * the datasheet and its recipes are written by an administrator only, a manager is limited to
 * their own store, and each referential write refuses an invalid value with a 400.
 */
@QuarkusTest
public class CateringReferentialTest {

    @Inject
    TransactionManager tm;

    /**
     * Cleans the catering referential and its dependencies before each test.
     */
    @BeforeEach
    void cleanDatabase() {
        withTransaction(() -> {
            CateringRecipeLine.deleteAll();
            CateringRecipe.deleteAll();
            CateringItemStore.deleteAll();
            CateringItem.deleteAll();
            FabricationStationEmployee.deleteAll();
            FabricationStation.deleteAll();
            Employee.deleteAll();
            Product.deleteAll();
            Store.deleteAll();
            return null;
        });
    }

    /**
     * Runs a unit of work in a transaction.
     *
     * @param runnable the work.
     * @param <R>      the result type.
     * @return the result.
     */
    private <R> R withTransaction(Supplier<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (Exception e) {
            try {
                if (tm.getStatus() == jakarta.transaction.Status.STATUS_ACTIVE) {
                    tm.rollback();
                }
            } catch (Exception ignored) {
                // falls through
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a store.
     *
     * @param code the store code.
     * @return the store id.
     */
    private Long store(String code) {
        return withTransaction(() -> DomainUtils.createAndPersistStore(code, 48.0, 2.0).id);
    }

    /**
     * Creates a manager employee in a store.
     *
     * @param badge   the badge id (used as the security principal).
     * @param storeId the store id.
     */
    private void manager(String badge, Long storeId) {
        withTransaction(() -> {
            Employee employee = new Employee();
            employee.badgeId = badge;
            employee.firstName = badge;
            employee.lastName = "Test";
            employee.email = badge + "@test.com";
            employee.password = "x".repeat(60);
            employee.role = Employee.EmployeeRole.MANAGER;
            employee.active = true;
            employee.store = Store.findById(storeId);
            employee.persist();
            return null;
        });
    }

    /**
     * Creates a product and returns its id.
     *
     * @param ean the EAN.
     * @return the product id.
     */
    private Long product(String ean) {
        return withTransaction(() -> DomainUtils.createAndPersistProduct(ean, "P-" + ean, ProductType.UNIT).id);
    }

    /**
     * Creates an employee in a store (for qualification tests) and returns its id.
     *
     * @param badge   the badge.
     * @param storeId the store id.
     * @return the employee id.
     */
    private Long employee(String badge, Long storeId) {
        return withTransaction(() -> {
            Employee employee = new Employee();
            employee.badgeId = badge;
            employee.firstName = badge;
            employee.lastName = "Test";
            employee.email = badge + "@test.com";
            employee.password = "x".repeat(60);
            employee.role = Employee.EmployeeRole.PICKER;
            employee.active = true;
            employee.store = Store.findById(storeId);
            employee.persist();
            return employee.id;
        });
    }

    /**
     * Creates a station and returns its id.
     *
     * @param code    the code.
     * @param storeId the store id.
     * @return the station id.
     */
    private Long station(String code, Long storeId) {
        return withTransaction(() -> {
            FabricationStation station = new FabricationStation();
            station.store = Store.findById(storeId);
            station.code = code;
            station.name = code;
            station.active = true;
            station.persist();
            return station.id;
        });
    }

    // --------------------------------------------------
    // T1.061 — validation refusals (ADMIN bypasses store scoping)
    // --------------------------------------------------

    /**
     * T1.049/T1.061: a FABRICATED datasheet requires a station.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"ADMIN"})
    void fabricatedItemRequiresStation() {
        Long productId = product("REF-1");
        given().contentType(ContentType.JSON)
                .body(Map.of("productId", productId, "sourcingMode", "FABRICATED"))
                .when().put("/catering/items").then().statusCode(400);
    }

    /**
     * T1.061: maxAdvanceHours above shelfLifeHours is refused with the parameter label.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"ADMIN"})
    void itemMaxAdvanceAboveShelfLifeRejected() {
        Long storeId = store("REF_A");
        Long productId = product("REF-2");
        Long stationId = station("LAB", storeId);
        given().contentType(ContentType.JSON)
                .body(Map.of("productId", productId, "sourcingMode", "FABRICATED", "stationId", stationId,
                        "shelfLifeHours", 48, "maxAdvanceHours", 100))
                .when().put("/catering/items").then().statusCode(400);
    }

    /**
     * T1.052/T1.061: a recipe with an unknown component is refused.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"ADMIN"})
    void recipeUnknownComponentRejected() {
        Long storeId = store("REF_B");
        Long productId = product("REF-3");
        Long stationId = station("LAB", storeId);
        long itemId = given().contentType(ContentType.JSON)
                .body(Map.of("productId", productId, "sourcingMode", "FABRICATED", "stationId", stationId))
                .when().put("/catering/items").then().statusCode(200).extract().jsonPath().getLong("id");
        given().contentType(ContentType.JSON)
                .body(Map.of("variantId", "32", "laborSeconds", 600,
                        "lines", List.of(Map.of("componentEan", "UNKNOWN-EAN", "quantity", 1, "kind", "INGREDIENT"))))
                .when().put("/catering/items/" + itemId + "/recipes").then().statusCode(400);
    }

    /**
     * T1.061: a recipe line with a non-positive quantity is refused.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"ADMIN"})
    void recipeNonPositiveQuantityRejected() {
        Long storeId = store("REF_C");
        Long productId = product("REF-4");
        Long componentId = product("REF-4-C");
        Long stationId = station("LAB", storeId);
        long itemId = given().contentType(ContentType.JSON)
                .body(Map.of("productId", productId, "sourcingMode", "FABRICATED", "stationId", stationId))
                .when().put("/catering/items").then().statusCode(200).extract().jsonPath().getLong("id");
        given().contentType(ContentType.JSON)
                .body(Map.of("variantId", "32",
                        "lines", List.of(Map.of("componentEan", "REF-4-C", "quantity", 0, "kind", "INGREDIENT"))))
                .when().put("/catering/items/" + itemId + "/recipes").then().statusCode(400);
    }

    /**
     * T1.054/T1.061: a station whose opening is not before its closing is refused.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"ADMIN"})
    void stationOpeningHoursRejected() {
        Long storeId = store("REF_D");
        given().contentType(ContentType.JSON)
                .body(Map.of("storeId", storeId, "code", "LAB", "name", "Labo", "opensAt", "14:00", "closesAt", "10:00"))
                .when().put("/catering/stations").then().statusCode(400);
    }

    /**
     * T1.054: a duplicate station code within a store is refused.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"ADMIN"})
    void stationDuplicateCodeRejected() {
        Long storeId = store("REF_E");
        station("LAB", storeId);
        given().contentType(ContentType.JSON)
                .body(Map.of("storeId", storeId, "code", "LAB", "name", "Labo 2"))
                .when().put("/catering/stations").then().statusCode(400);
    }

    /**
     * T1.055/T1.061: an employee of another store cannot be qualified at a station.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"ADMIN"})
    void stationEmployeeOfAnotherStoreRejected() {
        Long storeA = store("REF_F");
        Long storeB = store("REF_G");
        Long stationId = station("LAB", storeA);
        Long foreignEmployee = employee("foreign", storeB);
        given().contentType(ContentType.JSON)
                .body(Map.of("storeId", storeA, "employeeIds", List.of(foreignEmployee)))
                .when().put("/catering/stations/" + stationId + "/employees").then().statusCode(400);
    }

    // --------------------------------------------------
    // T1.060 — rights
    // --------------------------------------------------

    /**
     * T1.056/T1.060: a manager cannot write an enseigne-wide datasheet.
     */
    @Test
    @TestSecurity(user = "mgrA", roles = {"MANAGER"})
    void managerCannotWriteEnseigneDatasheet() {
        Long storeId = store("REF_H");
        manager("mgrA", storeId);
        Long productId = product("REF-5");
        given().contentType(ContentType.JSON)
                .body(Map.of("productId", productId, "sourcingMode", "STOCKED"))
                .when().put("/catering/items").then().statusCode(403);
    }

    /**
     * T1.060: a picker cannot write a datasheet.
     */
    @Test
    @TestSecurity(user = "picker", roles = {"PICKER"})
    void pickerCannotWriteDatasheet() {
        Long productId = product("REF-6");
        given().contentType(ContentType.JSON)
                .body(Map.of("productId", productId, "sourcingMode", "STOCKED"))
                .when().put("/catering/items").then().statusCode(403);
    }

    /**
     * T1.050/T1.056/T1.060: a manager is refused when addressing another store.
     */
    @Test
    @TestSecurity(user = "mgrA", roles = {"MANAGER"})
    void managerLimitedToOwnStore() {
        Long storeA = store("REF_I");
        Long storeB = store("REF_J");
        manager("mgrA", storeA);
        given().contentType(ContentType.JSON)
                .body(Map.of("storeId", storeB, "code", "LAB", "name", "Labo"))
                .when().put("/catering/stations").then().statusCode(403);
    }
}
