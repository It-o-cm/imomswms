package com.intermarche.e2e.auth;

import com.intermarche.backoffice.auth.DefaultAccountsInitializer;
import com.intermarche.backoffice.domain.auth.Administrative;
import com.intermarche.backoffice.domain.store.Employee;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DefaultAccountsInitializer}.
 * <p>
 * The initializer normally runs at application startup (the default accounts already
 * exist in the test database). Each test wipes the administrative accounts first,
 * then drives the initializer manually. {@link TestTransaction} rolls everything back.
 */
@QuarkusTest
@TestTransaction
public class DefaultAccountsInitializerTest {

    @Inject
    DefaultAccountsInitializer initializer;

    @Test
    void onStartup_shouldCreateDefaultAdminAccount() {
        Administrative.deleteAll();

        initializer.onStartup(null);

        Administrative admin = Administrative.find("username", "admin").firstResult();
        assertNotNull(admin, "The default admin account must be created");
        assertEquals("System Administrator", admin.fullName);
        assertEquals("hdarmet@gmail.com", admin.email);
        assertTrue(admin.enabled);
        assertTrue(BCrypt.checkpw("admin", admin.password), "The password must be the BCrypt hash of 'admin'");

        Set<String> expectedRoles = new HashSet<>();
        for (Employee.EmployeeRole role : Employee.EmployeeRole.values()) {
            expectedRoles.add(role.name());
        }
        expectedRoles.add("SERVICE_CALLER");
        assertEquals(expectedRoles, admin.roles, "The admin account must hold every role");
    }

    @Test
    void onStartup_shouldCreateDefaultServiceAccount() {
        Administrative.deleteAll();

        initializer.onStartup(null);

        Administrative service = Administrative.find("username", "service").firstResult();
        assertNotNull(service, "The default service account must be created");
        assertEquals("System Service Account", service.fullName);
        assertEquals("service@intermarche.local", service.email);
        assertTrue(service.enabled);
        assertTrue(BCrypt.checkpw("service", service.password), "The password must be the BCrypt hash of 'service'");
        assertEquals(Set.of("SERVICE_CALLER"), service.roles);
    }

    @Test
    void onStartup_shouldBeIdempotent() {
        Administrative.deleteAll();

        initializer.onStartup(null);
        long countAfterFirstRun = Administrative.count();

        initializer.onStartup(null);

        assertEquals(countAfterFirstRun, Administrative.count(), "A second run must not create duplicates");
        assertEquals(1, Administrative.count("username", "admin"));
        assertEquals(1, Administrative.count("username", "service"));
    }
}
