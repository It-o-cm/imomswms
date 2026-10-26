package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CDI Bean Factory for creating Zone Grouping Fulfillers.
 * <p>
 * Groups products from PENDING chunks into a new READY chunk based on a precise hierarchical location.
 */
@ApplicationScoped
public class ZoneGroupingFulfillerFactory extends BaseFulfillerFactory {

    /**
     * Logger instance.
     */
    private static final Logger LOGGER = Logger.getLogger(ZoneGroupingFulfillerFactory.class);

    // --------------------------------------------------
    // JSON Schema for Configuration Validation
    // --------------------------------------------------

    /**
     * JSON Schema definition string.
     */
    public static final String ZONE_CONFIG_SCHEMA = """
    {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "title": "ZoneGroupingConfiguration",
      "type": "object",
      "description": "Defines the precise physical zone to group products by.",
      "properties": {
        "name": {
          "type": "string",
          "description": "Optional: Name to identify or document the fulfiller."
        },
        "buildingCode": {
          "type": "string",
          "description": "Mandatory: The code of the building (e.g., 'LYON-WH', 'STORE-01')."
        },
        "area": {
          "type": "string",
          "description": "Optional: The area within the building (e.g., 'FRESH')."
        },
        "aisle": {
          "type": "string",
          "description": "Optional: The aisle within the area (e.g., 'A05')."
        }
      },
      "required": ["buildingCode"]
    }
    """;

    /**
     * Compiled JSON Schema.
     */
    private final JsonSchema schema;

    /**
     * Default constructor. Initializes the JSON Schema validator.
     */
    public ZoneGroupingFulfillerFactory() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(ZONE_CONFIG_SCHEMA);
    }

    /**
     * Returns the name of this factory.
     *
     * @return The name "ZoneGrouping".
     */
    @Override
    public String getName() {
        return "ZoneGrouping";
    }

    /**
     * Builds a new instance of the ZoneGroupingFulfiller.
     *
     * @param spec The JSON specification for this fulfiller.
     * @return A new instance of ZoneGroupingFulfiller.
     * @throws IllegalArgumentException if the configuration is invalid.
     */
    @Override
    public BaseFulfiller build(JsonNode spec) {
        validateConfiguration(spec);
        ZoneConfig config = parseConfiguration(spec);
        return new ZoneGroupingFulfiller(config);
    }

    /**
     * Validates the provided JSON specification against the defined schema.
     *
     * @param spec The JSON specification node.
     * @throws IllegalArgumentException if validation fails.
     */
    void validateConfiguration(JsonNode spec) {
        Set<ValidationMessage> errors = schema.validate(spec);
        if (!errors.isEmpty()) {
            String errorMsg = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining(", "));
            LOGGER.errorf("Invalid ZoneGrouping configuration: %s", errorMsg);
            throw new IllegalArgumentException("Invalid ZoneGrouping configuration: " + errorMsg);
        }
    }

    /**
     * Parses the validated JSON specification into a strongly typed configuration object.
     *
     * @param spec The validated JSON specification node.
     * @return A populated {@link ZoneConfig} instance.
     */
    ZoneConfig parseConfiguration(JsonNode spec) {
        String buildingCode = spec.path("buildingCode").asText();
        String area = spec.has("area") ? spec.path("area").asText() : null;
        String aisle = spec.has("aisle") ? spec.path("aisle").asText() : null;
        LOGGER.infof("Parsed ZoneGrouping config -> Building: %s, Area: %s, Aisle: %s",
                buildingCode, area, aisle);
        return new ZoneConfig(buildingCode, area, aisle);
    }

    /**
     * Simple Data Transfer Object to hold the parsed configuration values.
     */
    record ZoneConfig(String buildingCode, String area, String aisle) {}

    /**
     * Concrete implementation of a Fulfiller that regroups products by location.
     */
    public static class ZoneGroupingFulfiller extends BaseFulfillerFactory.BaseFulfiller {

        /**
         * Logger instance.
         */
        static final Logger LOGGER = Logger.getLogger(ZoneGroupingFulfiller.class);

        /**
         * Target building code.
         */
        final String targetBuildingCode;

        /**
         * Target area filter.
         */
        final String targetArea;

        /**
         * Target aisle filter.
         */
        final String targetAisle;

        /**
         * Constructor for the ZoneGroupingFulfiller.
         *
         * @param config The parsed configuration containing the zone criteria.
         */
        public ZoneGroupingFulfiller(ZoneConfig config) {
            this.targetBuildingCode = config.buildingCode();
            this.targetArea = config.area();
            this.targetAisle = config.aisle();
        }

        /**
         * Processes the given fulfillment by regrouping lines from PENDING chunks into a new READY chunk.
         *
         * @param fulfillment The fulfillment entity to process.
         */
        @Override
        public void process(Fulfillment fulfillment) {
            fulfillment = Fulfillment.findById(fulfillment.id);
            logProcessingStart(fulfillment);
            Store store = getStoreFromFulfillment(fulfillment);
            Building targetBuilding = resolveTargetBuilding(store);
            if (targetBuilding == null) {
                handleMissingBuilding(store);
                return;
            }
            FulfillmentChunk newChunk = createTargetChunk(targetBuilding);
            List<FulfillmentChunk> sourceChunks = new ArrayList<>(fulfillment.chunks);
            int totalLinesMoved = 0;
            for (FulfillmentChunk sourceChunk : sourceChunks) {
                if (isPending(sourceChunk)) {
                    updateDeliveryDateFromSource(sourceChunk, newChunk);
                    int movedCount = moveMatchingLines(sourceChunk, newChunk, targetBuilding);
                    totalLinesMoved += movedCount;
                    removeChunkIfEmpty(fulfillment, sourceChunk);
                }
            }
            finalizeProcessing(fulfillment, newChunk, totalLinesMoved, targetBuilding);
        }

        /**
         * Logs the start of the fulfillment processing.
         *
         * @param fulfillment The fulfillment being processed.
         */
        void logProcessingStart(Fulfillment fulfillment) {
            LOGGER.infof("Starting Zone Grouping for Fulfillment ID %s -> Target: [%s / %s / %s]",
                    fulfillment.id, targetBuildingCode, targetArea, targetAisle);
        }

        /**
         * Retrieves the Store entity associated with the fulfillment.
         *
         * @param fulfillment The fulfillment entity.
         * @return The associated Store.
         * @throws IllegalStateException if the store cannot be resolved.
         */
        Store getStoreFromFulfillment(Fulfillment fulfillment) {
            if (fulfillment.order == null || fulfillment.order.store == null) {
                throw new IllegalStateException("Fulfillment " + fulfillment.id + " is missing Order or Store reference.");
            }
            return fulfillment.order.store;
        }

        /**
         * Resolves the target Building entity based on the configured code.
         *
         * @param store The store containing the buildings.
         * @return The target Building, or null if not found.
         */
        Building resolveTargetBuilding(Store store) {
            return store.findBuildingByCode(targetBuildingCode);
        }

        /**
         * Handles the scenario where the target building cannot be found.
         *
         * @param store The store that was searched.
         */
        void handleMissingBuilding(Store store) {
            LOGGER.errorf("Target building %s not found for Store %s. Aborting grouping.",
                    targetBuildingCode, store.code);
        }

        /**
         * Creates a new FulfillmentChunk configured as READY and linked to the target building.
         *
         * @param targetBuilding The building where the chunk is located.
         * @return A new, un-persisted FulfillmentChunk.
         */
        FulfillmentChunk createTargetChunk(Building targetBuilding) {
            FulfillmentChunk newChunk = new FulfillmentChunk();
            newChunk.type = FulfillmentChunk.READY;
            newChunk.building = targetBuilding;
            newChunk.label = "ZoneGrouping";
            return newChunk;
        }

        /**
         * Checks if a chunk is in the PENDING state.
         *
         * @param chunk The chunk to check.
         * @return true if the chunk type is PENDING.
         */
        boolean isPending(FulfillmentChunk chunk) {
            return FulfillmentChunk.PENDING.equals(chunk.type);
        }

        /**
         * Transfers the delivery date from the source chunk to the target chunk.
         *
         * @param sourceChunk The chunk providing the delivery date.
         * @param targetChunk The chunk receiving the delivery date.
         */
        void updateDeliveryDateFromSource(FulfillmentChunk sourceChunk, FulfillmentChunk targetChunk) {
            if (targetChunk.deliveryDate == null) {
                targetChunk.deliveryDate = sourceChunk.deliveryDate;
            } else if (sourceChunk.deliveryDate != null && !sourceChunk.deliveryDate.equals(targetChunk.deliveryDate)) {
                LOGGER.warnf("Delivery Date Conflict detected in Fulfillment grouping. " +
                                "Target chunk has %s, but source chunk has %s. Keeping Target date.",
                        targetChunk.deliveryDate, sourceChunk.deliveryDate);
            }
        }

        /**
         * Iterates through the lines of a source chunk and moves matching lines to the target chunk.
         *
         * @param sourceChunk    The chunk to extract lines from.
         * @param targetChunk    The chunk to add lines to.
         * @param targetBuilding The building used to resolve product locations.
         * @return The number of lines moved.
         */
        int moveMatchingLines(FulfillmentChunk sourceChunk, FulfillmentChunk targetChunk, Building targetBuilding) {
            int movedCount = 0;
            Iterator<FulfillmentLine> lineIterator = sourceChunk.lines.iterator();
            while (lineIterator.hasNext()) {
                FulfillmentLine line = lineIterator.next();
                if (isLineInTargetZone(line, targetBuilding)) {
                    lineIterator.remove();
                    Panache.flush();
                    FulfillmentLine newLine = new FulfillmentLine();
                    newLine.lineNumber = line.lineNumber;
                    newLine.product = line.product;
                    newLine.requestedQuantity = line.requestedQuantity;
                    newLine.location = line.location;
                    newLine.persist();
                    targetChunk.addLine(newLine);
                    movedCount++;
                    if (LOGGER.isDebugEnabled()) {
                        String ean = (line.product != null) ? line.product.ean : "Unknown";
                        LOGGER.debugf("Moved Product %s to new Zone Chunk.", ean);
                    }
                }
            }
            return movedCount;
        }

        /**
         * Removes a chunk from the fulfillment if it contains no more lines.
         *
         * @param fulfillment The fulfillment containing the chunk.
         * @param chunk       The chunk to potentially remove.
         */
        void removeChunkIfEmpty(Fulfillment fulfillment, FulfillmentChunk chunk) {
            if (chunk.lines.isEmpty()) {
                LOGGER.infof("Removing empty chunk %s from Fulfillment %s", chunk.id, fulfillment.id);
                fulfillment.chunks.remove(chunk);
            }
        }

        /**
         * Finalizes the processing by adding the new chunk if lines were moved.
         *
         * @param fulfillment   The fulfillment being processed.
         * @param newChunk      The newly created chunk.
         * @param totalMoved    The total number of lines moved.
         * @param targetBuilding The target building.
         */
        void finalizeProcessing(Fulfillment fulfillment, FulfillmentChunk newChunk, int totalMoved, Building targetBuilding) {
            if (totalMoved > 0) {
                newChunk.persist();
                fulfillment.addChunk(newChunk);
                LOGGER.infof("Zone Grouping completed. Moved %d lines to new chunk ID %s in building %s",
                        totalMoved, newChunk.id, targetBuilding.buildingCode);
            } else {
                LOGGER.infof("No lines matched the criteria for Fulfillment ID %s", fulfillment.id);
            }
        }

        /**
         * Checks if a line belongs to the specific building, area, and aisle defined in the configuration.
         *
         * @param line           The fulfillment line to check.
         * @param targetBuilding The building to search within.
         * @return true if the line matches the zone criteria, false otherwise.
         */
        boolean isLineInTargetZone(FulfillmentLine line, Building targetBuilding) {
            ShelfLocation location = targetBuilding.findLocationByProductId(line.product.id);
            if (location == null) {
                return false;
            }
            if (targetArea != null && !targetArea.equals(location.area)) {
                return false;
            }
            if (targetAisle != null && !targetAisle.equals(location.aisle)) {
                return false;
            }
            line.location = location;
            return true;
        }
    }
}