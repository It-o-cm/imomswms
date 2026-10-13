package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.*;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CDI Bean Factory for creating Default Fulfillers.
 * <p>
 * Splits PENDING chunks into multiple READY chunks based on building and capacity constraints.
 * <p>
 * Distributes lines across available buildings (Warehouse and Store) to optimize picking logistics.
 * If a configuration is not provided, it defaults to Warehouses then Stores.
 * Ensures no chunk exceeds the configured maximum number of lines (capacity).
 */
@ApplicationScoped
public class DefaultFulfillerFactory extends BaseFulfillerFactory {

    /**
     * Logger instance for this class.
     */
    static final Logger LOGGER = Logger.getLogger(DefaultFulfillerFactory.class);

    // --------------------------------------------------
    // JSON Schema for Configuration Validation
    // --------------------------------------------------

    /**
     * JSON Schema definition for validating the configuration of this fulfiller.
     */
    static final String DEFAULT_FULFILLER_SCHEMA = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "title": "DefaultFulfillerConfiguration",
          "type": "object",
          "description": "Defines the rules for splitting chunks into capacity-limited sub-chunks.",
          "properties": {
            "name": {
              "type": "string",
              "description": "Optional: Name to identify or document the fulfiller."
            },
            "buildingCodes": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Optional: Ordered list of building codes to prioritize (e.g., ['WH01', 'STORE01']). If missing, defaults to all warehouses then stores."
            }
          }
        }
        """;

    /**
     * The compiled JSON Schema validator instance.
     */
    final JsonSchema schema;

    /**
     * Default constructor. Initializes the JSON Schema validator.
     */
    public DefaultFulfillerFactory() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(DEFAULT_FULFILLER_SCHEMA);
    }

    /**
     * Returns the name of this factory, used for identification in the Fulfillment Engine.
     *
     * @return The name "Default".
     */
    @Override
    public String getName() {
        return "Default";
    }

    /**
     * Builds a new instance of the DefaultFulfiller.
     *
     * @param spec The JSON specification for this fulfiller.
     * @return A new instance of DefaultFulfiller.
     */
    @Override
    public BaseFulfiller build(JsonNode spec) {
        validateConfiguration(spec);
        DefaultFulfillerConfig config = parseConfiguration(spec);
        return new DefaultFulfiller(config);
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
            LOGGER.errorf("Invalid DefaultFulfiller configuration: %s", errorMsg);
            throw new IllegalArgumentException("Invalid DefaultFulfiller configuration: " + errorMsg);
        }
    }

    /**
     * Parses the validated JSON specification into a strongly typed configuration object.
     *
     * @param spec The validated JSON specification node.
     * @return A populated {@link DefaultFulfillerConfig} instance.
     */
    DefaultFulfillerConfig parseConfiguration(JsonNode spec) {
        List<String> buildingCodes = new ArrayList<>();
        if (spec.has("buildingCodes") && spec.get("buildingCodes").isArray()) {
            spec.get("buildingCodes").forEach(node -> buildingCodes.add(node.asText()));
        }
        return new DefaultFulfillerConfig(buildingCodes);
    }

    /**
     * Simple Data Transfer Object to hold the parsed configuration values.
     *
     * @param buildingCodes   The list of target building codes (can be empty).
     */
    record DefaultFulfillerConfig(List<String> buildingCodes) {}

    /**
     * Concrete implementation of a Fulfiller that splits chunks by capacity and building.
     */
    public static class DefaultFulfiller extends BaseFulfillerFactory.BaseFulfiller {

        /**
         * Logger instance for the fulfiller logic.
         */
        static final Logger LOGGER = Logger.getLogger(DefaultFulfiller.class);

        /**
         * The list of building codes configured for this fulfiller instance.
         */
        final List<String> configuredBuildingCodes;

        /**
         * Constructor for the DefaultFulfiller.
         *
         * @param config The parsed configuration containing the splitting rules.
         */
        public DefaultFulfiller(DefaultFulfillerConfig config) {
            this.configuredBuildingCodes = config.buildingCodes();
        }

        /**
         * Processes the given fulfillment by splitting PENDING chunks into READY chunks.
         * <p>
         * Distributes lines across target buildings, ensuring no chunk exceeds the max capacity.
         *
         * @param fulfillment The fulfillment entity to process.
         */
        @Override
        public void process(Fulfillment fulfillment) {
            fulfillment = Fulfillment.findById(fulfillment.id);
            Store store = getStore(fulfillment);
            List<Building> targetBuildings = resolveTargetBuildings(store);
            // Map containing one chunk per target building
            Map<Building, FulfillmentChunk> chunkByBuilding = new HashMap<>();
            // Copy the list to avoid ConcurrentModificationException while iterating/deleting
            List<FulfillmentChunk> sourceChunks = new ArrayList<>(fulfillment.chunks);
            int totalLinesMoved = 0;
            for (FulfillmentChunk sourceChunk : sourceChunks) {
                if (isPending(sourceChunk)) {
                    int movedCount = distributeLinesFromChunk(sourceChunk, targetBuildings, chunkByBuilding);
                    totalLinesMoved += movedCount;
                    removeChunkIfEmpty(fulfillment, sourceChunk);
                }
            }
            // Attach new chunks to the fulfillment and persist them
            for (Map.Entry<Building, FulfillmentChunk> entry : chunkByBuilding.entrySet()) {
                FulfillmentChunk chunk = entry.getValue();
                chunk.persist();
                fulfillment.addChunk(chunk);
                LOGGER.infof("Created Default Chunk for Building %s with %d lines.",
                        entry.getKey().buildingCode, chunk.lines.size());
            }
            LOGGER.infof("DefaultFulfiller processed Fulfillment ID %s. Moved %d lines across %d buildings.",
                    fulfillment.id, totalLinesMoved, chunkByBuilding.size());
        }

        /**
         * Retrieves the Store associated with the fulfillment.
         *
         * @param fulfillment The fulfillment entity.
         * @return The associated Store.
         * @throws IllegalStateException if the store cannot be resolved.
         */
        Store getStore(Fulfillment fulfillment) {
            if (fulfillment.order == null || fulfillment.order.store == null) {
                throw new IllegalStateException("Fulfillment " + fulfillment.id + " is missing Order or Store reference.");
            }
            return fulfillment.order.store;
        }

        /**
         * Determines the ordered list of target buildings.
         * <p>
         * If codes are configured, uses that order.
         * If not, defaults to Warehouses (sorted) followed by Stores (sorted).
         *
         * @param store The store containing the buildings.
         * @return The ordered list of buildings.
         */
        List<Building> resolveTargetBuildings(Store store) {
            if (configuredBuildingCodes != null && !configuredBuildingCodes.isEmpty()) {
                return resolveBuildingsByCodes(store, configuredBuildingCodes);
            } else {
                return resolveDefaultBuildings(store);
            }
        }

        /**
         * Resolves buildings based on an explicit list of codes.
         * <p>
         * Optimization: Loads all buildings from the store into memory (Map) once,
         * then performs lookups. This is more efficient than iterating the collection
         * for every code in the list.
         *
         * @param store        The store.
         * @param buildingCodes The list of codes to look up.
         * @return A list of Building entities matching the codes, in the order of the codes list.
         */
        List<Building> resolveBuildingsByCodes(Store store, List<String> buildingCodes) {
            // 1. Load all buildings for the store into a Map for O(1) access.
            // Accessing store.buildings triggers the lazy load (one SQL query).
            Map<String, Building> buildingInventory = new HashMap<>();
            for (Building b : store.buildings) {
                buildingInventory.put(b.buildingCode, b);
            }
            // 2. Build the result list following the priority order defined in the configuration
            List<Building> result = new ArrayList<>();
            for (String code : buildingCodes) {
                Building b = buildingInventory.get(code);
                if (b != null) {
                    result.add(b);
                } else {
                    LOGGER.warnf("Configured building code %s not found for Store %s", code, store.code);
                }
            }
            return result;
        }

        /**
         * Resolves the default list of buildings: Warehouses first, then Stores.
         *
         * @param store The store containing the buildings.
         * @return The sorted list of all buildings.
         */
        List<Building> resolveDefaultBuildings(Store store) {
            // Split by type
            List<Building> warehouses = new ArrayList<>();
            List<Building> stores = new ArrayList<>();
            for (Building b : store.buildings) {
                if (b instanceof WarehouseBuilding) {
                    warehouses.add(b);
                } else if (b instanceof StoreBuilding) {
                    stores.add(b);
                }
            }
            // Sort alphabetically by code
            warehouses.sort(Comparator.comparing(b -> b.buildingCode));
            stores.sort(Comparator.comparing(b -> b.buildingCode));
            // Concatenate: Warehouses then Stores
            warehouses.addAll(stores);
            return warehouses;
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
         * Distributes lines from a source chunk into the target buildings.
         * <p>
         * Uses an iterator to safely remove lines from the source chunk while iterating.
         * Flushes changes immediately after removal to handle the unidirectional orphanRemoval.
         * <p>
         * Distributes lines across buildings in the specified priority order.
         *
         * @param sourceChunk      The chunk containing lines to distribute.
         * @param targetBuildings  The list of target buildings to fill.
         * @param chunkByBuilding A map tracking READY chunks per building.
         * @return The number of lines moved.
         */
        int distributeLinesFromChunk(FulfillmentChunk sourceChunk, List<Building> targetBuildings, Map<Building, FulfillmentChunk> chunkByBuilding) {
            int movedCount = 0;
            Iterator<FulfillmentLine> lineIterator = sourceChunk.lines.iterator();
            while (lineIterator.hasNext()) {
                FulfillmentLine line = lineIterator.next();
                // Resolve location and building together
                ResolvedLocation resolved = findLocationInBuildings(line.product, targetBuildings);
                if (resolved != null) {
                    // 1. Transfer delivery date if needed
                    if (resolved.building() != null && sourceChunk.deliveryDate != null) {
                        // We ensure the target chunk gets the delivery date
                        FulfillmentChunk target = chunkByBuilding.get(resolved.building());
                        if (target != null && target.deliveryDate == null) {
                            target.deliveryDate = sourceChunk.deliveryDate;
                        }
                    }
                    // 2. Remove from source
                    lineIterator.remove();
                    // 3. Flush immediately to handle unidirectional orphanRemoval
                    Panache.flush();
                    // 4. Find or Create the target chunk in the resolved building
                    FulfillmentChunk targetChunk = findOrCreateTargetChunk(chunkByBuilding, resolved.building());
                    // 5. Transfer delivery date to the newly created chunk if not already set
                    if (targetChunk.deliveryDate == null) {
                        targetChunk.deliveryDate = sourceChunk.deliveryDate;
                    }
                    // 6. Create and add line
                    FulfillmentLine newLine = createNewLine(line, resolved.location());
                    targetChunk.addLine(newLine);
                    movedCount++;
                    logLineMoved(line, resolved.building().buildingCode);
                } else {
                    LOGGER.warnf("Product %s cannot be moved (no location found in target buildings).", line.product.ean);
                }
            }
            return movedCount;
        }

        /**
         * Finds the first building in the list where the product is located.
         * <p>
         * Since BaseLocation does not hold a reference to Building, this method iterates through the list
         * of target buildings and checks if they contain the product.
         *
         * @param product         The product to locate.
         * @param targetBuildings The ordered list of buildings to search.
         * @return A {@link ResolvedLocation} containing the Building and BaseLocation, or null if not found.
         */
        ResolvedLocation findLocationInBuildings(Product product, List<Building> targetBuildings) {
            if (product == null) return null;

            for (Building building : targetBuildings) {
                BaseLocation loc = building.findLocationByProductId(product.id);
                if (loc != null) {
                    return new ResolvedLocation(building, loc);
                }
            }
            return null;
        }

        /**
         * Retrieves an existing chunk for the building or creates a new one.
         * <p>
         * Assigns the Building to the FulfillmentChunk here.
         *
         * @param chunkByBuilding The map of existing chunks.
         * @param building        The building to look up.
         * @return The target FulfillmentChunk.
         */
        FulfillmentChunk findOrCreateTargetChunk(Map<Building, FulfillmentChunk> chunkByBuilding, Building building) {
            return chunkByBuilding.computeIfAbsent(building, b -> {
                FulfillmentChunk newChunk = new FulfillmentChunk();
                newChunk.type = FulfillmentChunk.READY;
                // ASSIGNMENT: Set the building on the chunk
                newChunk.building = b;
                // ASSIGNMENT: Set the label origin
                newChunk.label = "Default";
                return newChunk;
            });
        }

        /**
         * Removes a chunk from the fulfillment if it contains no more lines.
         *
         * @param fulfillment The fulfillment containing the chunk.
         * @param chunk       The chunk to potentially remove.
         */
        void removeChunkIfEmpty(Fulfillment fulfillment, FulfillmentChunk chunk) {
            if (chunk.lines.isEmpty()) {
                fulfillment.chunks.remove(chunk);
            }
        }

        /**
         * Logs the successful move of a product.
         *
         * @param line        The line moved.
         * @param buildingCode The code of the building where the line was placed.
         */
        void logLineMoved(FulfillmentLine line, String buildingCode) {
            if (LOGGER.isDebugEnabled()) {
                String ean = (line.product != null) ? line.product.ean : "Unknown";
                LOGGER.debugf("Moved Product %s to Building %s Chunk.", ean, buildingCode);
            }
        }

        /**
         * Creates a new FulfillmentLine copying data from the source line and assigning the resolved location.
         * <p>
         * ASSIGNMENT: Sets the location on the line.
         *
         * @param sourceLine The original line.
         * @param location   The resolved physical location.
         * @return A new, persisted FulfillmentLine.
         */
        FulfillmentLine createNewLine(FulfillmentLine sourceLine, BaseLocation location) {
            FulfillmentLine newLine = new FulfillmentLine();
            newLine.lineNumber = sourceLine.lineNumber;
            newLine.product = sourceLine.product;
            newLine.requestedQuantity = sourceLine.requestedQuantity;
            // ASSIGNMENT: Set the physical location on the line
            newLine.location = location;
            newLine.persist();
            return newLine;
        }

        /**
         * Helper record to hold both the building and the specific location within it.
         *
         * @param building The building entity.
         * @param location The specific location within the building.
         */
        record ResolvedLocation(Building building, BaseLocation location) {}
    }
}