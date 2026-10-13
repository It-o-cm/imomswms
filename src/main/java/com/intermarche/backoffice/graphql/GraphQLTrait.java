package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.persistence.PersistenceException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.jboss.logging.Logger;

import javax.security.auth.kerberos.KerberosKey;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Trait interface providing centralized error handling and logging logic.
 * <p>
 * Any class implementing this interface automatically inherits the {@code execute} method,
 * allowing for consistent wrapping of business logic with standardized exception mapping
 * and context-aware logging.
 */
public interface GraphQLTrait {

    /**
     * Default method to execute an action with standard error handling and logging.
     * <p>
     * This method wraps the execution of a lambda expression (typically a database operation)
     * and handles specific exceptions by mapping them to appropriate GraphQL errors.
     * <p>
     * <b>Exception Handling Strategy:</b>
     * <ul>
     *   <li>{@link AlreadyExistsException}: Logged as WARN and wrapped in {@link GraphQLException} (Business conflict).</li>
     *   <li>{@link PersistenceException}: Logged as ERROR and wrapped in {@link GraphQLException} (Database issue).</li>
     *   <li>{@link NoSuchElementException}: Re-thrown directly (handled by SmallRye GraphQL as 404/Not Found).</li>
     *   <li>{@link Exception}: Logged as ERROR and wrapped in {@link GraphQLException} (Catch-all for unexpected errors).</li>
     * </ul>
     *
     * @param <T>           The return type of the operation.
     * @param supplier      The business logic to execute (usually a lambda or method reference).
     * @param contextClass  The class calling the trait (used for creating a specific {@link Logger} instance).
     * @param operationName The name of the operation (used for logging context and error messages).
     * @return The result of the supplier execution.
     * @throws GraphQLException     if a business conflict, database error, or unexpected error occurs.
     * @throws NoSuchElementException if the business logic explicitly throws this (propagated as Not Found).
     */
    default <T> T execute(Supplier<T> supplier, Class<?> contextClass, String operationName) throws GraphQLException {
        // Create a logger specific to the class calling this utility
        Logger logger = Logger.getLogger(contextClass);
        try {
            // Execute the provided business logic (Lambda)
            return supplier.get();
        } catch (AlreadyExistsException aee) {
            // Handle specific "Already Exists" business errors (e.g., duplicate code or name)
            // We log as WARN because it's a data consistency issue, not a system failure
            logger.warn("Conflict in " + operationName + ": " + aee.getMessage());
            // Convert to GraphQLException so that API returns a clean JSON error message
            throw new GraphQLException(aee.getMessage(), aee);
        } catch (PersistenceException pe) {
            // Handle generic JPA/Hibernate/Database errors (Constraint violations, connection issues, etc.)
            logger.error("Persistence error in " + operationName, pe);
            throw new GraphQLException("Database error while performing " + operationName + ". Please check your data.", pe);
        } catch (NoSuchElementException nsee) {
            throw new GraphQLException(nsee.getMessage(), nsee);
        } catch (Exception e) {
            // Handle any other unexpected errors (including H2 raw exceptions or runtime issues)
            logger.error("Unexpected error in " + operationName, e);
            throw new GraphQLException("An error occurred during " + operationName + ".", e);
        }
    }


    /**
     * Determines the data scope (Store context) for the current authenticated user.
     *
     * @return The {@link Store} to filter by, or {@code null} for global access.
     */
    default Store getStoreContext() {
        String badgeId = getIdentity().getPrincipal().getName();
        Employee employee = Employee.findByBadgeId(badgeId);
        if (employee != null && employee.store != null) return employee.store;
        if (getIdentity().hasRole("ADMIN") || getIdentity().hasRole("SERVICE_CALLER")) return null;
        throw new SecurityException("User not linked to a store and lacks global privileges.");
    }

    /**
     * Retrieves the security identity of the current authenticated user.
     *
     * @return The {@link SecurityIdentity} object representing the authenticated user,
     *         providing access to roles, attributes, and credentials.
     */
    SecurityIdentity getIdentity();
}