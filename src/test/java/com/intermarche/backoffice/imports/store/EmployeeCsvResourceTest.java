package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import com.intermarche.backoffice.testutil.PanacheTestSupport;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmployeeCsvResource}.
 * Focuses on the edge branches of chunk context building, the 1-by-1 fallback
 * context and the role/password parsing of {@code populateEmployee}/{@code computeChecksum}.
 */
class EmployeeCsvResourceTest {

    private final EmployeeCsvResource resource = new EmployeeCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private static ImporterCsvResource.LineData line(String... parts) {
        return new ImporterCsvResource.LineData(2, parts.length > 0 ? parts[0] : null, parts);
    }

    private static Store store(String code, Long id) {
        Store store = new Store();
        store.id = id;
        store.code = code;
        return store;
    }

    private static Employee employee(String badgeId, Long id) {
        Employee employee = new Employee();
        employee.id = id;
        employee.badgeId = badgeId;
        employee.checksum = -999; // never matches an incoming checksum: forces the update path
        return employee;
    }

    @SuppressWarnings("unchecked")
    private static <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    // --------------------------------------------------
    // processChunkWithFallback
    // --------------------------------------------------

    @Test
    void processChunkWithFallback_nullStoreCode_skipsStoreFetch() {
        // Only one column: safeGet(parts, 1) is out of bounds -> null
        List<ImporterCsvResource.LineData> lines = List.of(line("E1"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("badgeId IN ?1"), any(Object[].class)))
                    .thenReturn(List.of());

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("E1"), new int[]{0, 0}, new ArrayList<>());

            // No store code in any line: the store fetch is skipped entirely
            panacheMock.verify(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)), never());
            assertTrue(ctx.isEmpty());
        }
    }

    @Test
    void processChunkWithFallback_withStoreCode_fetchesEmployeesAndStores() {
        Store store = store("S1", 3L);
        Employee employee = employee("E1", 100L);
        List<ImporterCsvResource.LineData> lines = List.of(
                line("E1", "S1", "John", "Doe", "john@x.fr", "PICKER", "secret", "true"));

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.list(eq("badgeId IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(employee));
            panacheMock.when(() -> PanacheEntityBase.list(eq("code IN ?1"), any(Object[].class)))
                    .thenReturn(List.of(store));

            Map<String, Object> ctx = resource.processChunkWithFallback(lines, Set.of("E1"), new int[]{0, 0}, new ArrayList<>());

            assertSame(employee, ctx.get("E1"));
            assertSame(store, ctx.get("STORE_S1"));
        }
    }

    // --------------------------------------------------
    // prepareContextForLine (1-by-1 fallback context)
    // --------------------------------------------------

    @Test
    void prepareContextForLine_employeeFound_cachesEmployeeAndStore() {
        Employee employee = employee("E1", 100L);
        Store store = store("S1", 3L);
        PanacheQuery<Employee> employeeQuery = queryReturning(employee);
        PanacheQuery<Store> storeQuery = queryReturning(store);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(startsWith("SELECT e FROM Employee"), any(Object[].class)))
                    .thenReturn(employeeQuery);
            panacheMock.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class)))
                    .thenReturn(storeQuery);

            Map<String, Object> ctx = resource.prepareContextForLine(
                    line("E1", "S1", "John", "Doe", "john@x.fr", "PICKER", "secret", "true"));

            assertSame(employee, ctx.get("E1"));
            assertSame(store, ctx.get("STORE_S1"));
        }
    }

    @Test
    void prepareContextForLine_nullStoreCode_skipsStoreLookup() {
        Employee employee = employee("E1", 100L);
        PanacheQuery<Employee> employeeQuery = queryReturning(employee);

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.find(startsWith("SELECT e FROM Employee"), any(Object[].class)))
                    .thenReturn(employeeQuery);

            // Only one column: no store code
            Map<String, Object> ctx = resource.prepareContextForLine(line("E1"));

            assertSame(employee, ctx.get("E1"));
            assertEquals(1, ctx.size());
        }
    }

    // --------------------------------------------------
    // processLineLogic - role/password parsing
    // --------------------------------------------------

    @Test
    void processLineLogic_nullRoleAndPassword_keepsDefaults() {
        Store store = store("S1", 3L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        int[] counters = {0, 0};

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);

            // Columns 5 (role) and 6 (password) are out of bounds -> null
            resource.processLineLogic(line("E1", "S1", "John", "Doe", "john@x.fr"), ctx, counters);

            assertEquals(1, counters[0]);
            Employee created = (Employee) ctx.get("E1");
            assertNotNull(created);
            assertNull(created.role);
            assertNull(created.password);
            assertSame(store, created.store);
        }
    }

    @Test
    void processLineLogic_blankRoleAndPassword_keepsDefaults() {
        Store store = store("S1", 3L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        int[] counters = {0, 0};

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);

            resource.processLineLogic(line("E1", "S1", "John", "Doe", "john@x.fr", "   ", "   ", "true"), ctx, counters);

            assertEquals(1, counters[0]);
            Employee created = (Employee) ctx.get("E1");
            assertNull(created.role);
            assertNull(created.password);
        }
    }

    @Test
    void processLineLogic_unknownRole_throwsIAE() {
        Store store = store("S1", 3L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        int[] counters = {0, 0};

        try (MockedStatic<Arc> arcMock = mockStatic(Arc.class);
             MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheTestSupport.mockArcSession(arcMock);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(line("E1", "S1", "John", "Doe", "john@x.fr", "FOO", "secret", "true"), ctx, counters));

            assertTrue(ex.getMessage().contains("Unknown Role 'FOO'"));
            assertEquals(0, counters[0]);
        }
    }

    // --------------------------------------------------
    // processLineLogic - update path edge branches
    // --------------------------------------------------

    @Test
    void processLineLogic_existingEmployeeNullRole_computesChecksumWithoutRole() {
        Store store = store("S1", 3L);
        Employee existing = employee("E1", 100L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        ctx.put("E1", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // No role column: computeChecksum must skip the enum parsing
            resource.processLineLogic(line("E1", "S1", "John", "Doe", "john@x.fr"), ctx, counters);

            assertEquals(1, counters[1]);
            assertNull(existing.role);
        }
    }

    @Test
    void processLineLogic_existingEmployeeInvalidRole_coversChecksumCatchThenThrows() {
        Store store = store("S1", 3L);
        Employee existing = employee("E1", 100L);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("STORE_S1", store);
        ctx.put("E1", existing);
        int[] counters = {0, 0};

        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            panacheMock.when(() -> PanacheEntityBase.findById(eq(100L))).thenReturn(existing);

            // computeChecksum swallows the invalid enum, then populateEmployee rejects it
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(line("E1", "S1", "John", "Doe", "john@x.fr", "FOO", "secret", "true"), ctx, counters));

            assertTrue(ex.getMessage().contains("Unknown Role 'FOO'"));
        }
    }
}
