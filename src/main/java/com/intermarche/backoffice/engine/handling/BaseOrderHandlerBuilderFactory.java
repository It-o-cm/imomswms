package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import io.quarkus.hibernate.orm.panache.Panache;

import java.util.List;

/**
 * Abstract base class for hanldler order creator Factories.
 * <p>
 * Defines the contract for creating a {@link BaseOrderHandlerBuilder} worker
 * based on a specification.
 */
public abstract class BaseOrderHandlerBuilderFactory {

    public static final String CONFIGURATION = "ORDER_HANDLER";

    /**
     * Returns the name of this factory, used for identification in the HandlerOrder Engine.
     *
     * @return The unique name of this factory.
     */
    public abstract String getName();

    /**
     * Factory method to build a BaseOrderHandlerBuilder worker based on a specification.
     *
     * @param spec The JSON specification for the worker.
     * @return An instance of a BaseOrderHandlerBuilder.
     */
    public abstract BaseOrderHandlerBuilder build(JsonNode spec);

    /**
     * Abstract base class for BaseOrderHandlerBuilder.
     * <p>
     * Included within {@link BaseOrderHandlerBuilderFactory}. Defines the contract for processing
     *  {@link Lot}.
     */
    public static abstract class BaseOrderHandlerBuilder {

        /**
         * Processes the given store. The implementation should contain the business logic required to build order
         * handlers for the store (e.g., picking/packing, collect dispatch, mass collect).
         *
         * @param store The store for which the order handler is being built.
         * @throws Exception if the processing fails.
         */
        public abstract void process(Store store) throws Exception;

        /**
         * Updates the status of all lots in the provided list to the specified status.
         * After updating, the changes are flushed to the database.
         *
         * @param lots   The list of lots to update.
         * @param status The status to be set for all lots in the list.
         */
        protected void setStatusToLot(List<Lot> lots, Lot.LotStatus status) {
            for (Lot lot : lots) {
                lot.status = status;
            }
            Panache.flush();
        }
    }

}