package com.intermarche.e2e.engine.handling;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.engine.handling.BaseOrderHandlerBuilderFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BaseOrderHandlerBuilderFactory}.
 * Covers the configuration constant and the concrete {@code setStatusToLot} helper
 * of the inner {@link BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder} class.
 */
@QuarkusTest
@TestTransaction
public class BaseOrderHandlerBuilderFactoryTest {

    @Test
    void configurationConstant_shouldBeOrderHandler() {
        assertEquals("ORDER_HANDLER", BaseOrderHandlerBuilderFactory.CONFIGURATION);
    }

    @Test
    void setStatusToLot_shouldUpdateStatusOfAllLots() {
        Lot lot1 = new Lot();
        lot1.status = Lot.LotStatus.PENDING;
        Lot lot2 = new Lot();
        lot2.status = Lot.LotStatus.PENDING;

        TestBuilder builder = new TestBuilder();

        builder.updateStatus(List.of(lot1, lot2), Lot.LotStatus.PLANNED_TO_BE_COLLECTED);

        assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot1.status);
        assertEquals(Lot.LotStatus.PLANNED_TO_BE_COLLECTED, lot2.status);
    }

    @Test
    void setStatusToLot_shouldDoNothingOnEmptyList() {
        TestBuilder builder = new TestBuilder();

        assertDoesNotThrow(() -> builder.updateStatus(List.of(), Lot.LotStatus.PLANNED_TO_BE_COLLECTED));
    }

    /**
     * Concrete builder exposing the protected {@code setStatusToLot} helper for testing.
     */
    private static class TestBuilder extends BaseOrderHandlerBuilderFactory.BaseOrderHandlerBuilder {
        @Override
        public void process(Store store) {
            // no-op
        }

        void updateStatus(List<Lot> lots, Lot.LotStatus status) {
            setStatusToLot(lots, status);
        }
    }
}
