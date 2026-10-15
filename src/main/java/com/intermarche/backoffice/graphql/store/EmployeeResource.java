package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.AlreadyExistsException;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Employees.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * <p>
 * Manages creation, update, deletion, and retrieval of employees.
 * Passwords are automatically hashed before storage.
 * Access is restricted to MANAGERs for reading and ADMINs for writing.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class EmployeeResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(EmployeeResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Security identity of the current authenticated user.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * Retrieves the current security identity of the user or context.
     *
     * @return The {@link SecurityIdentity} associated with the current context.
     */
    @Override
    public SecurityIdentity getIdentity() {
        return identity;
    }

    /**
     * Retrieves the list of all employees in the database.
     *
     * @return A list of all Employee entities.
     */
    @Query
    @Description("Get the list of all employees")
    @RolesAllowed("MANAGER")
    public List<Employee> allEmployees() {
        LOGGER.info("Entering method allEmployees");
        List<Employee> result = Employee.listAll();
        LOGGER.info("Exiting method allEmployees");
        return result;
    }

    /**
     * Retrieves a specific employee by its unique identifier.
     *
     * @param id The unique ID of the employee.
     * @return The Employee entity.
     * @throws NoSuchElementException if the employee with the given ID does not exist.
     */
    @Query
    @Description("Get an employee by its ID")
    @RolesAllowed("MANAGER")
    public Employee employee(@Name("id") Long id) {
        LOGGER.info("Entering method employee with id: " + id);
        Employee employee = Employee.findById(id);
        if (employee == null) {
            LOGGER.error("Employee with id " + id + " not found");
            throw new NoSuchElementException("Employee with id " + id + " not found");
        }
        LOGGER.info("Exiting method employee");
        return employee;
    }

    /**
     * Retrieves a specific employee by their unique Badge ID.
     * <p>
     * This is the primary lookup method used for authentication on devices.
     *
     * @param badgeId The unique Badge ID of the employee.
     * @return The Employee entity.
     * @throws NoSuchElementException if the employee with the given Badge ID does not exist.
     */
    @Query
    @Description("Get an employee by their Badge ID")
    @RolesAllowed("MANAGER")
    public Employee employeeByBadgeId(@Name("badgeId") String badgeId) {
        LOGGER.info("Entering method employeeByBadgeId with badgeId: " + badgeId);
        Employee employee = Employee.findByBadgeId(badgeId);
        if (employee == null) {
            LOGGER.error("Employee with badgeId " + badgeId + " not found");
            throw new NoSuchElementException("Employee with badgeId '" + badgeId + "' not found");
        }
        LOGGER.info("Exiting method employeeByBadgeId");
        return employee;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // --------------------------------------------------

    /**
     * Creates a new employee in the database.
     * <p>
     * Validates that the Badge ID is unique before creation.
     * The password is automatically hashed using BCrypt before storage.
     *
     * @param input The input record containing employee details.
     * @return The newly created Employee entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new employee")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Employee createEmployee(@Name("input") EmployeeRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createEmployee for badgeId: " + input.badgeId);

            // --- Validation: Check Badge ID uniqueness ---
            Employee existingEmployeeByBadge = Employee.findByBadgeId(input.badgeId);
            if (existingEmployeeByBadge != null) {
                LOGGER.warn("Attempt to create employee with existing badgeId: " + input.badgeId);
                throw new AlreadyExistsException("Employee with badgeId '" + input.badgeId + "' already exists.");
            }

            // --- Creation Logic ---
            Employee employee = new Employee();

            // Basic fields
            employee.firstName = input.firstName;
            employee.lastName = input.lastName;
            employee.badgeId = input.badgeId;
            employee.email = input.email;
            employee.active = input.active != null ? input.active : true;

            // Password Hashing (Security)
            if (input.password != null && !input.password.isBlank()) {
                employee.password = org.mindrot.jbcrypt.BCrypt.hashpw(input.password, org.mindrot.jbcrypt.BCrypt.gensalt());
            } else {
                throw new IllegalArgumentException("Password cannot be empty");
            }

            // Enum conversion
            if (input.role != null) {
                employee.role = Employee.EmployeeRole.valueOf(input.role.toUpperCase());
            }

            // Relationship mapping (mandatory: the entity requires a store)
            if (input.storeId == null) {
                throw new IllegalArgumentException("Store ID is mandatory to create an employee.");
            }
            Store store = Store.findById(input.storeId);
            if (store == null) {
                throw new NoSuchElementException("Store with id " + input.storeId + " not found");
            }
            employee.store = store;

            employee.persist();

            LOGGER.info("Exiting method createEmployee. Created ID: " + employee.id);
            return employee;
        }, EmployeeResource.class, "createEmployee");
    }

    /**
     * Updates an existing employee identified by its ID.
     * <p>
     * Validates new Badge ID uniqueness if it is changed.
     * Only non-null fields in the input are updated (partial update support).
     * If a new password is provided, it is hashed.
     *
     * @param id    The ID of the employee to update.
     * @param input The input record containing fields to update.
     * @return The updated Employee entity.
     * @throws GraphQLException if validation fails or the employee is not found.
     */
    @Mutation
    @Description("Update an existing employee")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Employee updateEmployee(@Name("id") Long id, @Name("input") EmployeeRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updateEmployee for id: " + id);

            Employee employee = Employee.findById(id);
            if (employee == null) {
                LOGGER.error("Employee with id " + id + " not found");
                throw new NoSuchElementException("Employee with id " + id + " not found");
            }

            // --- Pre-validation Checks (Conditional) ---

            // 1. Badge ID Uniqueness:
            // Only check if the input Badge ID is provided AND different from the current Badge ID.
            if (input.badgeId != null && !input.badgeId.equals(employee.badgeId)) {
                Employee otherEmployeeWithBadge = Employee.findByBadgeId(input.badgeId);
                if (otherEmployeeWithBadge != null) {
                    LOGGER.warn("Attempt to update employee to conflicting badgeId: " + input.badgeId);
                    throw new AlreadyExistsException("Another employee with badgeId '" + input.badgeId + "' already exists.");
                }
            }

            // --- Apply Updates (Partial Update Logic) ---

            if (input.firstName != null) employee.firstName = input.firstName;
            if (input.lastName != null) employee.lastName = input.lastName;
            if (input.email != null) employee.email = input.email;
            if (input.badgeId != null) employee.badgeId = input.badgeId;
            if (input.active != null) employee.active = input.active;

            // Password Hashing (Security) - Only if provided
            if (input.password != null && !input.password.isBlank()) {
                employee.password = org.mindrot.jbcrypt.BCrypt.hashpw(input.password, org.mindrot.jbcrypt.BCrypt.gensalt());
            }

            // Enum conversion
            if (input.role != null) {
                employee.role = Employee.EmployeeRole.valueOf(input.role.toUpperCase());
            }

            // Relationship mapping
            if (input.storeId != null) {
                Store store = Store.findById(input.storeId);
                if (store == null) {
                    throw new NoSuchElementException("Store with id " + input.storeId + " not found");
                }
                employee.store = store;
            }

            LOGGER.info("Exiting method updateEmployee");
            return employee;
        }, EmployeeResource.class, "updateEmployee");
    }

    /**
     * Deletes an employee by its unique identifier.
     *
     * @param id The ID of the employee to delete.
     * @return true if the employee was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete an employee by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deleteEmployee(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deleteEmployee for id: " + id);
            boolean result = Employee.deleteById(id);
            LOGGER.info("Exiting method deleteEmployee. Result: " + result);
            return result;
        }, EmployeeResource.class, "deleteEmployee");
    }

    // --------------------------------------------------
    // Input Types
    // --------------------------------------------------

    /**
     * Input record type for GraphQL Employee mutations.
     * <p>
     * Contains fields corresponding to the {@link Employee} entity.
     * The relationship to {@link Store} is represented by the storeId.
     * All fields are mutable and nullable to support partial updates.
     */
    @Input
    static public class EmployeeRecord {

        /**
         * The employee's first name.
         */
        public String firstName;

        /**
         * The employee's last name.
         */
        public String lastName;

        /**
         * The plain text password.
         * <p>
         * This will be hashed by the backend before storage.
         */
        public String password;

        /**
         * The role of the employee (ADMIN, MANAGER, PICKER, CASHIER).
         */
        public String role;

        /**
         * The ID of the Store to which the employee is assigned.
         */
        public Long storeId;

        /**
         * The unique Badge ID (RFID).
         */
        public String badgeId;

        /**
         * The email address of the employee.
         * <p>
         * Must be unique.
         */
        public String email;

        /**
         * Flag indicating if the employee is active.
         */
        public Boolean active;

        /**
         * Default constructor.
         */
        public EmployeeRecord() {
        }

        /**
         * Returns a string representation of the record.
         *
         * @return A string representation.
         */
        @Override
        public String toString() {
            return "EmployeeRecord [firstName=" + firstName + ", lastName=" + lastName +
                    ", badgeId=" + badgeId + ", email=" + email +
                    ", role=" + role +
                    ", storeId=" + storeId + ", active=" + active + "]";
        }
    }
}