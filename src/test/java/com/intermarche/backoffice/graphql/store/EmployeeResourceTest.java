package com.intermarche.backoffice.graphql.store;

import com.intermarche.backoffice.domain.store.Employee;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmployeeResource}.
 * Covers the identity accessor and the password blank-check branch of the update mutation.
 */
class EmployeeResourceTest {

    private MockedStatic<PanacheEntityBase> panacheMock;

    @BeforeEach
    void setUp() {
        panacheMock = mockStatic(PanacheEntityBase.class);
    }

    @AfterEach
    void tearDown() {
        panacheMock.close();
    }

    private EmployeeResource newResource() {
        EmployeeResource resource = new EmployeeResource();
        resource.identity = mock(SecurityIdentity.class);
        return resource;
    }

    @Test
    void getIdentity_returnsInjectedIdentity() {
        EmployeeResource resource = new EmployeeResource();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        resource.identity = identity;

        assertSame(identity, resource.getIdentity());
    }

    @Test
    void updateEmployee_blankPassword_keepsExistingPassword() throws Exception {
        Employee employee = new Employee();
        employee.id = 1L;
        employee.badgeId = "BADGE-1";
        employee.password = "$2a$10$existinghash";
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(employee);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.password = "   ";

        Employee result = newResource().updateEmployee(1L, input);

        assertSame(employee, result);
        assertEquals("$2a$10$existinghash", employee.password);
    }

    @Test
    void updateEmployee_validPassword_hashesNewPassword() throws Exception {
        Employee employee = new Employee();
        employee.id = 1L;
        employee.badgeId = "BADGE-1";
        employee.password = "$2a$10$existinghash";
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(employee);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.password = "secret";

        Employee result = newResource().updateEmployee(1L, input);

        assertSame(employee, result);
        assertNotEquals("$2a$10$existinghash", employee.password);
        assertNotEquals("secret", employee.password);
        assertTrue(employee.password.startsWith("$2"));
    }

    @Test
    void updateEmployee_nullPassword_keepsExistingPassword() throws Exception {
        Employee employee = new Employee();
        employee.id = 1L;
        employee.badgeId = "BADGE-1";
        employee.password = "$2a$10$existinghash";
        panacheMock.when(() -> PanacheEntityBase.findById(any())).thenReturn(employee);

        EmployeeResource.EmployeeRecord input = new EmployeeResource.EmployeeRecord();
        input.password = null;

        Employee result = newResource().updateEmployee(1L, input);

        assertSame(employee, result);
        assertEquals("$2a$10$existinghash", employee.password);
    }
}
