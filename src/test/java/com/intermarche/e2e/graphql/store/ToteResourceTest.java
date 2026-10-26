package com.intermarche.e2e.graphql.store;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.graphql.store.ToteResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ToteResource}.
 * <p>
 * Uses {@link QuarkusTest} and {@link TestTransaction} to interact with the real database
 * and rollback changes after each test.
 * Uses {@link TestSecurity} to bypass authentication checks.
 */
@QuarkusTest
@TestTransaction
public class ToteResourceTest {

    @Inject
    ToteResource toteResource;

    /**
     * Creates and persists a tote directly via the entity.
     */
    private Tote persistTote(String code, Store store) {
        Tote tote = new Tote();
        tote.code = code;
        tote.volume = new BigDecimal("45.5");
        tote.toteType = Tote.ToteType.STANDARD;
        tote.store = store;
        tote.persist();
        return tote;
    }

    // --------------------------------------------------
    // Query Tests (Requires MANAGER role)
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testAllTotes() {
        Store store = DomainUtils.createAndPersistStore("STORE_T1", 48.0, 2.0);
        persistTote("TOTE_01", store);

        List<Tote> totes = toteResource.allTotes();
        assertNotNull(totes);
        assertFalse(totes.isEmpty(), "Should return at least one tote");
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testToteById_Success() {
        Store store = DomainUtils.createAndPersistStore("STORE_T2", 48.0, 2.0);
        Tote existing = persistTote("TOTE_02", store);

        Tote found = toteResource.tote(existing.id);
        assertNotNull(found);
        assertEquals("TOTE_02", found.code);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testToteById_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            toteResource.tote(9999L);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testToteByCode_Success() {
        Store store = DomainUtils.createAndPersistStore("STORE_T3", 48.0, 2.0);
        persistTote("TOTE_03", store);

        Tote found = toteResource.toteByCode("TOTE_03");
        assertNotNull(found);
        assertEquals("TOTE_03", found.code);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testToteByCode_NotFound() {
        assertThrows(NoSuchElementException.class, () -> {
            toteResource.toteByCode("TOTE_99");
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testTotesByStoreCode_Success() {
        Store store = DomainUtils.createAndPersistStore("STORE_T4", 48.0, 2.0);
        persistTote("TOTE_04", store);
        persistTote("TOTE_05", store);

        List<Tote> totes = toteResource.totesByStoreCode("STORE_T4");
        assertNotNull(totes);
        assertEquals(2, totes.size());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN", "MANAGER"})
    void testTotesByStoreCode_UnknownStore_ReturnsEmpty() {
        List<Tote> totes = toteResource.totesByStoreCode("STORE_UNKNOWN");
        assertNotNull(totes);
        assertTrue(totes.isEmpty());
    }

    // --------------------------------------------------
    // Mutation Tests: Create
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateTote_AllFields_Success() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_T5", 48.0, 2.0);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_10";
        input.volume = "45.5";
        input.toteType = "insulated"; // lowercase on purpose: must be uppercased
        input.storeId = store.id;

        Tote created = toteResource.createTote(input);

        assertNotNull(created);
        assertNotNull(created.id);
        assertEquals("TOTE_10", created.code);
        assertEquals(new BigDecimal("45.5"), created.volume);
        assertEquals(Tote.ToteType.INSULATED, created.toteType);
        assertNotNull(created.store);
        assertEquals(store.id, created.store.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateTote_MinimalFields_Success() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_T15", 48.0, 2.0);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_11";
        input.storeId = store.id; // mandatory
        // volume, toteType are null

        Tote created = toteResource.createTote(input);

        assertNotNull(created);
        assertEquals("TOTE_11", created.code);
        assertNull(created.volume);
        assertNull(created.toteType);
        assertEquals(store.id, created.store.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateTote_BlankVolume_Ignored() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_T16", 48.0, 2.0);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_12";
        input.storeId = store.id;
        input.volume = "   "; // blank: must be ignored, not parsed

        Tote created = toteResource.createTote(input);

        assertNotNull(created);
        assertNull(created.volume);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateTote_NullStoreId() {
        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_17";
        input.storeId = null; // mandatory

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            toteResource.createTote(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Store ID is mandatory"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateTote_DuplicateCode() {
        Store store = DomainUtils.createAndPersistStore("STORE_T6", 48.0, 2.0);
        persistTote("TOTE_13", store);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_13";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            toteResource.createTote(input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateTote_InvalidVolume() {
        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_14";
        input.volume = "not-a-number";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            toteResource.createTote(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Invalid volume format"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateTote_InvalidToteType() {
        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_15";
        input.toteType = "UNKNOWN_TYPE";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            toteResource.createTote(input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testCreateTote_StoreNotFound() {
        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_16";
        input.storeId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            toteResource.createTote(input);
        });
    }

    // --------------------------------------------------
    // Mutation Tests: Update
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateTote_AllFields_Success() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_T7", 48.0, 2.0);
        Store otherStore = DomainUtils.createAndPersistStore("STORE_T8", 48.0, 2.0);
        Tote existing = persistTote("TOTE_20", store);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_21";
        input.volume = "60.0";
        input.toteType = "INSULATED";
        input.storeId = otherStore.id;

        Tote updated = toteResource.updateTote(existing.id, input);

        assertEquals("TOTE_21", updated.code);
        assertEquals(new BigDecimal("60.0"), updated.volume);
        assertEquals(Tote.ToteType.INSULATED, updated.toteType);
        assertEquals(otherStore.id, updated.store.id);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateTote_SameCode_NoConflict() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_T9", 48.0, 2.0);
        Tote existing = persistTote("TOTE_22", store);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_22"; // same code: uniqueness check must be skipped
        input.volume = "10.0";

        Tote updated = toteResource.updateTote(existing.id, input);
        assertEquals("TOTE_22", updated.code);
        assertEquals(new BigDecimal("10.0"), updated.volume);
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateTote_ConflictingCode() {
        Store store = DomainUtils.createAndPersistStore("STORE_T10", 48.0, 2.0);
        Tote existing = persistTote("TOTE_23", store);
        persistTote("TOTE_24", store);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "TOTE_24";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            toteResource.updateTote(existing.id, input);
        });
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateTote_NotFound() {
        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.code = "GHOST";

        assertThrows(NoSuchElementException.class, () -> {
            toteResource.updateTote(9999L, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateTote_InvalidVolume() {
        Store store = DomainUtils.createAndPersistStore("STORE_T11", 48.0, 2.0);
        Tote existing = persistTote("TOTE_25", store);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.volume = "abc";

        GraphQLException ex = assertThrows(GraphQLException.class, () -> {
            toteResource.updateTote(existing.id, input);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateTote_StoreNotFound() {
        Store store = DomainUtils.createAndPersistStore("STORE_T12", 48.0, 2.0);
        Tote existing = persistTote("TOTE_26", store);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.storeId = 9999L;

        assertThrows(NoSuchElementException.class, () -> {
            toteResource.updateTote(existing.id, input);
        });
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testUpdateTote_PartialUpdate_KeepsOtherFields() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_T13", 48.0, 2.0);
        Tote existing = persistTote("TOTE_27", store);

        ToteResource.ToteRecord input = new ToteResource.ToteRecord();
        input.volume = "99.9"; // only volume changes

        Tote updated = toteResource.updateTote(existing.id, input);
        assertEquals("TOTE_27", updated.code, "Code should remain unchanged");
        assertEquals(Tote.ToteType.STANDARD, updated.toteType, "Type should remain unchanged");
        assertEquals(store.id, updated.store.id, "Store should remain unchanged");
        assertEquals(new BigDecimal("99.9"), updated.volume);
    }

    // --------------------------------------------------
    // Mutation Tests: Delete
    // --------------------------------------------------

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteTote_Success() throws GraphQLException {
        Store store = DomainUtils.createAndPersistStore("STORE_T14", 48.0, 2.0);
        Tote existing = persistTote("TOTE_28", store);

        boolean result = toteResource.deleteTote(existing.id);
        assertTrue(result, "Delete should return true");
        assertNull(Tote.findById(existing.id));
    }

    @Test
    @TestSecurity(user = "testAdmin", roles = {"ADMIN"})
    void testDeleteTote_NotFound() throws GraphQLException {
        boolean result = toteResource.deleteTote(9999L);
        assertFalse(result, "Delete should return false for non-existent ID");
    }

    // --------------------------------------------------
    // Record Tests
    // --------------------------------------------------

    @Test
    void testToteRecord_ToString() {
        ToteResource.ToteRecord record = new ToteResource.ToteRecord();
        record.code = "REC_01";
        record.volume = "12.5";
        record.toteType = "STANDARD";
        record.storeId = 42L;

        assertEquals("ToteRecord [code=REC_01, volume=12.5, toteType=STANDARD, storeId=42]",
                record.toString());
    }

    // --------------------------------------------------
    // Store scope of a manager
    // --------------------------------------------------

    /**
     * Creates a manager linked to a store.
     *
     * @param badgeId badge (user name) of the manager
     * @param store store of the manager
     */
    private void createManager(String badgeId, Store store) {
        Employee employee = new Employee();
        employee.firstName = "Emp";
        employee.lastName = badgeId;
        employee.badgeId = badgeId;
        employee.email = badgeId.toLowerCase() + "@test.com";
        employee.password = BCrypt.hashpw("secret", BCrypt.gensalt());
        employee.role = Employee.EmployeeRole.MANAGER;
        employee.store = store;
        employee.persist();
    }

    /**
     * A manager only reads the totes of their store.
     */
    @Test
    @TestSecurity(user = "scopedManager", roles = {"MANAGER"})
    void managerOnlyReadsTheTotesOfTheirStore() {
        Store own = DomainUtils.createAndPersistStore("STORE_TS_OWN", 48.0, 2.0);
        Store other = DomainUtils.createAndPersistStore("STORE_TS_OTHER", 48.0, 2.0);
        createManager("scopedManager", own);
        Tote mine = persistTote("TOTE_TS_OWN", own);
        Tote foreign = persistTote("TOTE_TS_OTH", other);

        List<Long> ids = toteResource.allTotes().stream().map(t -> t.id).toList();
        assertTrue(ids.contains(mine.id));
        assertFalse(ids.contains(foreign.id));
        assertEquals(mine.id, toteResource.tote(mine.id).id);
        assertEquals(mine.id, toteResource.toteByCode("TOTE_TS_OWN").id);
        assertThrows(NoSuchElementException.class, () -> toteResource.tote(foreign.id));
        assertThrows(NoSuchElementException.class, () -> toteResource.toteByCode("TOTE_TS_OTH"));
        assertEquals(List.of(mine.id), toteResource.totesByStoreCode("STORE_TS_OWN").stream().map(t -> t.id).toList());
        assertTrue(toteResource.totesByStoreCode("STORE_TS_OTHER").isEmpty());
    }
}
