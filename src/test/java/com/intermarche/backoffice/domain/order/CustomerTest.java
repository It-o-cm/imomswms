package com.intermarche.backoffice.domain.order;

import com.intermarche.backoffice.domain.Adresse;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Customer} business logic.
 * Focuses on {@link Customer#getChecksum()} sensitivity to business fields.
 */
public class CustomerTest {

    @Test
    void getChecksum_shouldChangeWhenCustomerIdChanges() {
        Customer customer = createCustomer();
        customer.customerId = "CUS-001";
        int checksum1 = customer.getChecksum();

        customer.customerId = "CUS-002";
        assertNotEquals(checksum1, customer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenNameChanges() {
        Customer customer = createCustomer();
        customer.firstName = "John";
        int checksum1 = customer.getChecksum();

        customer.firstName = "Jane";
        assertNotEquals(checksum1, customer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenEmailChanges() {
        Customer customer = createCustomer();
        customer.email = "a@test.com";
        int checksum1 = customer.getChecksum();

        customer.email = "b@test.com";
        assertNotEquals(checksum1, customer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenPhoneChanges() {
        Customer customer = createCustomer();
        customer.phone = "0600000001";
        int checksum1 = customer.getChecksum();

        customer.phone = "0600000002";
        assertNotEquals(checksum1, customer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenLoyaltyCardIdChanges() {
        Customer customer = createCustomer();
        customer.loyaltyCardId = "LOY-001";
        int checksum1 = customer.getChecksum();

        customer.loyaltyCardId = "LOY-002";
        assertNotEquals(checksum1, customer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenAddressChanges() {
        Customer customer = createCustomer();
        customer.address = new Adresse();
        customer.address.city = "Lyon";
        int checksum1 = customer.getChecksum();

        customer.address.city = "Paris";
        assertNotEquals(checksum1, customer.getChecksum());
    }

    @Test
    void getChecksum_shouldChangeWhenSpecificationChanges() {
        Customer customer = createCustomer();
        customer.specification = "{\"vip\":true}";
        int checksum1 = customer.getChecksum();

        customer.specification = "{\"vip\":false}";
        assertNotEquals(checksum1, customer.getChecksum());
    }

    @Test
    void getChecksum_shouldNotFailWhenOptionalFieldsAreNull() {
        Customer customer = new Customer();
        customer.customerId = "CUS-001";
        customer.firstName = "John";
        customer.lastName = "Doe";
        customer.email = "a@test.com";
        customer.phone = null;
        customer.loyaltyCardId = null;
        customer.address = null;
        customer.specification = null;

        assertDoesNotThrow(customer::getChecksum);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    @Test
    void findByCustomerId_shouldReturnCustomer_whenFound() {
        Customer customer = createCustomer();
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Customer> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(customer);
            panacheMock.when(() -> PanacheEntityBase.find(eq("customerId"), any(Object[].class)))
                    .thenReturn(query);

            assertSame(customer, Customer.findByCustomerId("CUS-001"));
        }
    }

    @Test
    void findByCustomerId_shouldReturnNull_whenNotFound() {
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Customer> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panacheMock.when(() -> PanacheEntityBase.find(eq("customerId"), any(Object[].class)))
                    .thenReturn(query);

            assertNull(Customer.findByCustomerId("UNKNOWN"));
        }
    }

    @Test
    void findByLoyaltyCardId_shouldReturnCustomer_whenFound() {
        Customer customer = createCustomer();
        try (MockedStatic<PanacheEntityBase> panacheMock = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Customer> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(customer);
            panacheMock.when(() -> PanacheEntityBase.find(eq("loyaltyCardId"), any(Object[].class)))
                    .thenReturn(query);

            assertSame(customer, Customer.findByLoyaltyCardId("LOY-001"));
        }
    }

    private Customer createCustomer() {
        Customer customer = new Customer();
        customer.customerId = "CUS-001";
        customer.firstName = "John";
        customer.lastName = "Doe";
        customer.email = "a@test.com";
        return customer;
    }
}
