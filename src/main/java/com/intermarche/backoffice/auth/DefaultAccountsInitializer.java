package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.auth.Administrative;
import com.intermarche.backoffice.domain.store.Employee;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Initialization class responsible for creating default system accounts.
 * <p>
 * This bean listens for the application startup event and ensures that
 * the necessary default administrative accounts exist.
 */
@ApplicationScoped
public class DefaultAccountsInitializer {

    /**
     * Logger instance for this class.
     */
    private static final Logger LOGGER = Logger.getLogger(DefaultAccountsInitializer.class);

    /**
     * Method called automatically when the application starts.
     * <p>
     * It is transactional to ensure the database operations are committed.
     *
     * @param ev the startup event.
     */
    @Transactional
    public void onStartup(@Observes StartupEvent ev) {
        createDefaultAdminAccount();
        createDefaultServiceAccount();
    }

    /**
     * Creates the default Administrator account with full permissions if it does not exist.
     * <p>
     * Default Credentials: admin / admin
     * Roles: All defined roles (ADMIN, MANAGER, PICKER, CASHIER, SERVICE_CALLER).
     */
    private void createDefaultAdminAccount() {
        String username = "admin";

        if (Administrative.count("username", username) > 0) {
            LOGGER.info("Default Admin account already exists. Skipping creation.");
            return;
        }

        LOGGER.info("Creating default Admin account...");

        Administrative admin = new Administrative();
        admin.username = username;
        admin.password = org.mindrot.jbcrypt.BCrypt.hashpw("admin", org.mindrot.jbcrypt.BCrypt.gensalt());
        admin.fullName = "System Administrator";
        admin.email = "hdarmet@gmail.com";

        // Assign all possible roles
        Set<String> allRoles = new HashSet<>();
        for (Employee.EmployeeRole role : Employee.EmployeeRole.values()) {
            allRoles.add(role.name());
        }
        // Add the technical service role for full system control
        allRoles.add("SERVICE_CALLER");

        admin.roles = allRoles;

        admin.persist();

        LOGGER.infof("Default Admin account created successfully with username: %s", username);
    }

    /**
     * Creates the default Service account for machine-to-machine communication if it does not exist.
     * <p>
     * Default Credentials: service / service
     * Roles: SERVICE_CALLER.
     */
    private void createDefaultServiceAccount() {
        String username = "service";

        if (Administrative.count("username", username) > 0) {
            LOGGER.info("Default Service account already exists. Skipping creation.");
            return;
        }

        LOGGER.info("Creating default Service account...");

        Administrative service = new Administrative();
        service.username = username;
        service.password = org.mindrot.jbcrypt.BCrypt.hashpw("service", org.mindrot.jbcrypt.BCrypt.gensalt());
        service.fullName = "System Service Account";
        service.email = "service@intermarche.local";

        // Assign specific service role
        Set<String> serviceRoles = new HashSet<>();
        serviceRoles.add("SERVICE_CALLER");

        service.roles = serviceRoles;

        service.persist();

        LOGGER.infof("Default Service account created successfully with username: %s", username);
    }
}