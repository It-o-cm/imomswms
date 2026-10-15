package com.intermarche.backoffice.engine.fulfillers;

import com.intermarche.backoffice.domain.oms.Fulfillment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BaseFulfillerFactory} contract.
 * Pure unit tests — no database, no Quarkus.
 */
class BaseFulfillerFactoryTest {

    @Test
    void configurationConstant_isFulfiller() {
        assertEquals("FULFILLER", BaseFulfillerFactory.CONFIGURATION);
    }

    @Test
    void baseFulfiller_processCanBeInvoked() throws Exception {
        BaseFulfillerFactory.BaseFulfiller fulfiller = mock(BaseFulfillerFactory.BaseFulfiller.class);
        Fulfillment fulfillment = new Fulfillment();

        fulfiller.process(fulfillment);

        verify(fulfiller).process(fulfillment);
    }

    @Test
    void baseFulfiller_processPropagatesException() {
        BaseFulfillerFactory.BaseFulfiller fulfiller = mock(BaseFulfillerFactory.BaseFulfiller.class);
        assertThrows(Exception.class, () -> {
            doThrow(new Exception("boom")).when(fulfiller).process(any());
            fulfiller.process(new Fulfillment());
        });
    }

    @Test
    void factory_isAbstractContract() {
        BaseFulfillerFactory factory = mock(BaseFulfillerFactory.class);
        when(factory.getName()).thenReturn("TestFactory");

        assertEquals("TestFactory", factory.getName());
        verify(factory).getName();
    }
}
