package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.oms.Fulfillment;

/**
 * Abstract base class for Fulfiller Factories.
 * <p>
 * Defines the contract for creating a {@link BaseFulfiller} worker
 * based on a specification.
 */
public abstract class BaseFulfillerFactory {

    public static final String CONFIGURATION = "FULFILLER";

    /**
     * Returns the name of this factory, used for identification in the Fulfillment Engine.
     *
     * @return The unique name of this factory.
     */
    public abstract String getName();

    /**
     * Factory method to build a Fulfiller worker based on a specification.
     *
     * @param spec The JSON specification for the worker.
     * @return An instance of a BaseFulfiller.
     */
    public abstract BaseFulfiller build(JsonNode spec);

    /**
     * Abstract base class for Fulfillers.
     * <p>
     * Included within {@link BaseFulfillerFactory}. Defines the contract for processing
     * a {@link Fulfillment}.
     */
    public static abstract class BaseFulfiller {

        /**
         * Processes the given fulfillment.
         * <p>
         * Implementations should contain the business logic required to handle
         * the fulfillment (e.g., picking, packing, shipping logic).
         *
         * @param fulfillment The fulfillment entity to process.
         * @throws Exception if the processing fails.
         */
        public abstract void process(Fulfillment fulfillment) throws Exception;

    }

}