package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.*;

/**
 * REST Endpoint for bulk importing or updating Employees.
 * <p>
 * This resource handles the upload of CSV files to populate or synchronize employee data.
 * It implements an "Upsert" (Update or Insert) strategy:
 * <ul>
 *   <li>If the Badge ID exists in the database, the record is updated (if data changed).</li>
 *   <li>If the Badge ID does not exist, a new record is created.</li>
 * </ul>
 * <p>
 * <b>Expected CSV Format (8 columns, headerless):</b>
 * <ol>
 *   <li>Badge ID (String, Unique)</li>
 *   <li>Store Code (String, Must exist)</li>
 *   <li>First Name (String)</li>
 *   <li>Last Name (String)</li>
 *   <li>Email (String)</li>
 *   <li>Role (String: ADMIN, MANAGER, PICKER, CASHIER)</li>
 *   <li>Password (String: Plain text will be hashed, Hash will be kept)</li>
 *   <li>Active (Boolean: true/false)</li>
 * </ol>
 */
@Path("/employees/import")
@ApplicationScoped
@RunOnVirtualThread
public class EmployeeCsvResource extends ImporterCsvResource {

    /**
     * Logger instance for logging import operations and errors.
     */
    private static final Logger LOGGER = Logger.getLogger(EmployeeCsvResource.class);

    /**
     * Imports or updates employees from a CSV stream.
     * <p>
     * This method delegates the heavy lifting to the parent class {@link ImporterCsvResource},
     * which handles streaming, parsing, and chunking.
     *
     * @param inputStream The input stream containing the raw CSV data.
     * @return A {@link Response} containing a JSON summary of the import process
     *         (number of created, updated, skipped items and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importEmployees(InputStream inputStream) {
        // Expected 8 columns per line
        return this.importCsvStream(inputStream, 8);
    }

    /**
     * Prepares the database context for a specific chunk of lines.
     * <p>
     * This method optimizes performance by fetching all necessary entities
     * (Employees and Stores) in bulk before processing individual lines.
     * It prevents the N+1 query problem.
     *
     * @param parsedLines  The list of parsed lines in the current chunk.
     * @param targetCodes  The set of unique Badge IDs found in this chunk.
     * @param counters     An array to track statistics (created, updated, etc.).
     * @param errors       A list to collect error messages.
     * @return A {@link Map} serving as the context, containing fetched
     *         {@link Employee} entities keyed by BadgeId and {@link Store} entities keyed by "STORE_" + StoreCode.
     */
    @Override
    public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> context = new HashMap<>();

        // 1. Fetch existing Employees by BadgeId to support Updates
        List<Employee> employees = Employee.list("badgeId IN ?1", targetCodes);
        for (Employee e : employees) {
            context.put(e.badgeId, e);
        }

        // 2. Fetch referenced Stores to establish relationships
        Set<String> storeCodes = new HashSet<>();
        for (LineData d : parsedLines) {
            String storeCode = safeGet(d.parts, 1);
            if (storeCode != null) storeCodes.add(storeCode);
        }

        if (!storeCodes.isEmpty()) {
            List<Store> stores = Store.list("code IN ?1", storeCodes);
            for (Store s : stores) {
                // Prefix with "STORE_" to avoid ID collision with Employees if IDs overlapped
                context.put("STORE_" + s.code, s);
            }
        }

        return context;
    }

    /**
     * Processes a single CSV line to create or update an Employee entity.
     * <p>
     * Logic flow:
     * <ol>
     *   <li>Resolve the associated {@link Store} from the context.</li>
     *   <li>Check if an {@link Employee} with this Badge ID exists in the context.</li>
     *   <li>If not found: Create a new {@link Employee}, persist it, and increment 'created' counter.</li>
     *   <li>If found: Compare checksums. If different, update fields and increment 'updated' counter.</li>
     *   <li>If checksum matches: Skip processing (no changes).</li>
     * </ol>
     *
     * @param data      The parsed line data containing the raw parts.
     * @param entityMap The context map containing pre-fetched entities.
     * @param counters  The statistics array to update.
     * @throws IllegalArgumentException if the referenced Store does not exist.
     */
    @Override
    public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String[] parts = data.parts;
        String badgeId = data.code;
        String storeCode = safeGet(parts, 1);

        // 1. Resolve Store
        Store store = (Store) entityMap.get("STORE_" + storeCode);
        if (store == null) {
            LOGGER.error("Store with code " + storeCode + " not found for employee " + badgeId);
            throw new IllegalArgumentException("Store with code '" + storeCode + "' not found.");
        }

        // 2. Resolve Employee
        Employee employee = (Employee) entityMap.get(badgeId);
        if (employee != null) {
            // Re-fetch the managed entity: the cached instance was loaded outside the
            // transaction (detached), so mutating it would be lost at commit. A null
            // result means the cached entity was a phantom from a rolled-back chunk.
            employee = Employee.findById(employee.id);
        }

        if (employee == null) {
            // Creation Logic
            employee = new Employee();
            employee.badgeId = badgeId;
            employee.store = store; // Attach store immediately
            populateEmployee(parts, employee);
            employee.persist();
            entityMap.put(badgeId, employee); // Cache the new entity in context
            counters[0]++; // Increment Created counter
        } else {
            // Update Logic
            // Calculate checksum of incoming data to detect actual changes
            int incomingChecksum = computeChecksum(parts, badgeId);

            // Only update if the data has actually changed
            if (employee.checksum == incomingChecksum) {
                return; // No change needed
            }

            counters[1]++; // Increment Updated counter
            employee.store = store; // Update store reference in case it changed
            populateEmployee(parts, employee);
        }
    }

    /**
     * Finds a single entity for fallback processing if it was not found in the initial chunk context.
     * <p>
     * This is a safety mechanism used by the parent class if an entity exists in the DB
     * but was missed during the bulk fetch (unlikely here due to ID fetch, but part of the contract).
     *
     * @param data The line data containing the code (Badge ID).
     * @return The {@link Employee} entity or null if not found.
     */
    @Override
    public Object findEntityForLine(LineData data) {
        return Employee.findByBadgeId(data.code);
    }

    /**
     * Prepares the full context for a single line in 1-by-1 fallback mode.
     * <p>
     * The generic implementation only puts the found entity under the line code,
     * but the employee logic also needs the referenced Store. This override rebuilds
     * that context with a fresh lookup so that fallback processing behaves
     * exactly like chunk processing.
     *
     * @param data The parsed CSV line data.
     * @return The context map for this single line.
     */
    @Override
    public Map<String, Object> prepareContextForLine(LineData data) {
        Map<String, Object> context = new HashMap<>();
        Object employee = findEntityForLine(data);
        if (employee != null) {
            context.put(data.code, employee);
        }
        String storeCode = safeGet(data.parts, 1);
        if (storeCode != null) {
            Store store = Store.findByCode(storeCode);
            if (store != null) {
                context.put("STORE_" + storeCode, store);
            }
        }
        return context;
    }

    // --------------------------------------------------
    // Helper Methods
    // --------------------------------------------------

    /**
     * Populates an {@link Employee} entity with data from the CSV parts array.
     * <p>
     * Maps CSV columns to entity fields.
     * <b>Password Handling:</b> If the password string does not start with "$2" (BCrypt prefix),
     * it is assumed to be plain text and is hashed using BCrypt before assignment.
     *
     * @param parts    The array of string values from the CSV line.
     * @param employee The employee entity to populate.
     */
    private void populateEmployee(String[] parts, Employee employee) {
        employee.firstName = safeGet(parts, 2);
        employee.lastName = safeGet(parts, 3);
        employee.email = safeGet(parts, 4); // Column 4: Email

        // Handle Role Enum (Column 5)
        String roleStr = safeGet(parts, 5);
        if (roleStr != null && !roleStr.isBlank()) {
            try {
                employee.role = Employee.EmployeeRole.valueOf(roleStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown Role '" + roleStr + "' for employee " + employee.badgeId);
            }
        }

        // Handle Password (Column 6)
        String passwordInput = safeGet(parts, 6);
        if (passwordInput != null && !passwordInput.isBlank()) {
            // Heuristic: Check if it's already a BCrypt hash
            if (!passwordInput.startsWith("$2")) {
                // Plain text -> Hash it
                employee.password = org.mindrot.jbcrypt.BCrypt.hashpw(passwordInput, org.mindrot.jbcrypt.BCrypt.gensalt());
            } else {
                // Already hashed -> Keep it
                employee.password = passwordInput;
            }
        }

        // Handle Active status (Column 7)
        employee.active = safeParseBoolean(parts, 7);
    }

    /**
     * Computes a checksum based on the incoming CSV data.
     * <p>
     * This checksum is compared against the entity's stored checksum to determine
     * if an update is necessary. It excludes the password from the hash to avoid
     * re-hashing unchanged passwords, but includes all other relevant fields.
     *
     * @param parts    The CSV data array.
     * @param badgeId  The employee's Badge ID.
     * @return An integer hash code representing the data state.
     */
    private int computeChecksum(String[] parts, String badgeId) {
        String roleStr = safeGet(parts, 5);
        Object roleObj = roleStr;
        try {
            if (roleStr != null) {
                roleObj = Employee.EmployeeRole.valueOf(roleStr.toUpperCase());
            }
        } catch (IllegalArgumentException e) {
            // Keep string if invalid enum, though this would likely fail validation elsewhere
        }

        return Objects.hash(
                safeGet(parts, 2), // firstName
                safeGet(parts, 3), // lastName
                safeGet(parts, 4), // email
                roleObj,           // role
                safeGetOrNull(parts, 1), // storeCode (natural key, mirrors Employee.getChecksum())
                badgeId,           // badgeId
                safeParseBoolean(parts, 7) // active
        );
    }
}