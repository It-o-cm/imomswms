package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.oms.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductFamily;
import com.intermarche.backoffice.domain.store.*;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CDI Bean Factory for creating Product Grouping Fulfillers.
 * <p>
 * Groups products from PENDING chunks into new READY chunks based on product identity or family.
 * <p>
 * This fulfiller dynamically creates sub-chunks per Building.
 * It resolves the physical location of products to assign them to the correct building chunk.
 * If a product exists in multiple buildings, it prioritizes the main StoreBuilding to avoid order duplication.
 */
@ApplicationScoped
public class ProductGroupingFulfillerFactory extends BaseFulfillerFactory {

    /**
     * Logger instance for this class.
     */
    private static final Logger LOGGER = Logger.getLogger(ProductGroupingFulfillerFactory.class);

    /**
     * JSON Schema definition for validation.
     */
    private static final String PRODUCT_GROUPING_SCHEMA = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "title": "ProductGroupingConfiguration",
          "type": "object",
          "description": "Defines criteria to group products by EAN or Family.",
          "properties": {
            "name": {
              "type": "string",
              "description": "Optional: Name to identify or document the fulfiller."
            },
            "eans": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Optional: List of EAN codes to include."
            },
            "families": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Optional: List of Product Family codes to include."
            }
          }
        }
        """;

    /**
     * The compiled JSON Schema validator.
     */
    private final JsonSchema schema;

    /**
     * Default constructor. Initializes the JSON Schema validator.
     */
    public ProductGroupingFulfillerFactory() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(PRODUCT_GROUPING_SCHEMA);
    }

    /**
     * Returns the name of this factory.
     *
     * @return The name "ProductGrouping".
     */
    @Override
    public String getName() {
        return "ProductGrouping";
    }

    /**
     * Builds a new instance of the ProductGroupingFulfiller.
     *
     * @param spec The JSON specification for this fulfiller.
     * @return A new instance of ProductGroupingFulfiller.
     * @throws IllegalArgumentException if the configuration is invalid.
     */
    @Override
    public BaseFulfiller build(JsonNode spec) {
        validateConfiguration(spec);
        ProductGroupingConfig config = parseConfiguration(spec);
        return new ProductGroupingFulfiller(config);
    }

    /**
     * Validates the provided JSON specification against the defined schema.
     *
     * @param spec The JSON specification node.
     * @throws IllegalArgumentException if validation fails.
     */
    private void validateConfiguration(JsonNode spec) {
        Set<ValidationMessage> errors = schema.validate(spec);
        if (!errors.isEmpty()) {
            String errorMsg = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining(", "));
            LOGGER.errorf("Invalid ProductGrouping configuration: %s", errorMsg);
            throw new IllegalArgumentException("Invalid ProductGrouping configuration: " + errorMsg);
        }
    }

    /**
     * Parses the validated JSON specification into a strongly typed configuration object.
     *
     * @param spec The validated JSON specification node.
     * @return A populated {@link ProductGroupingConfig} instance.
     */
    private ProductGroupingConfig parseConfiguration(JsonNode spec) {
        Set<String> eans = new HashSet<>();
        if (spec.has("eans") && spec.get("eans").isArray()) {
            spec.get("eans").forEach(node -> eans.add(node.asText()));
        }

        Set<String> families = new HashSet<>();
        if (spec.has("families") && spec.get("families").isArray()) {
            spec.get("families").forEach(node -> families.add(node.asText()));
        }

        if (eans.isEmpty() && families.isEmpty()) {
            LOGGER.warn("ProductGrouping configuration has no EANs and no Families defined. It will match nothing.");
        }

        return new ProductGroupingConfig(eans, families);
    }

    /**
     * Simple Data Transfer Object to hold the parsed configuration values.
     *
     * @param eans     The set of target EAN codes.
     * @param families The set of target Product Family codes.
     */
    private record ProductGroupingConfig(Set<String> eans, Set<String> families) {}

    /**
     * Concrete implementation of a Fulfiller that regroups products by identity or family.
     */
    public static class ProductGroupingFulfiller extends BaseFulfillerFactory.BaseFulfiller {

        /**
         * Logger instance.
         */
        static final Logger LOGGER = Logger.getLogger(ProductGroupingFulfiller.class);

        /**
         * Set of target EAN codes.
         */
        final Set<String> targetEans;

        /**
         * Set of target family codes.
         */
        final Set<String> targetFamilies;

        /**
         * Constructor for the ProductGroupingFulfiller.
         *
         * @param config The parsed configuration containing the grouping criteria.
         */
        public ProductGroupingFulfiller(ProductGroupingConfig config) {
            this.targetEans = config.eans();
            this.targetFamilies = config.families();
        }

        /**
         * Processes the given fulfillment by regrouping lines from PENDING chunks into new READY chunks.
         *
         * @param fulfillment The fulfillment entity to process.
         */
        @Override
        public void process(Fulfillment fulfillment) {
            fulfillment = Fulfillment.findById(fulfillment.id);
            if (shouldSkipProcessing()) {
                return;
            }
            Store store = getStore(fulfillment);
            Map<Building, FulfillmentChunk> chunksByBuilding = initializeBuildingChunksMap();
            List<FulfillmentChunk> pendingChunks = getPendingChunks(fulfillment);
            int totalLinesMoved = processSourceChunks(pendingChunks, store, chunksByBuilding);
            removeEmptyChunks(fulfillment, pendingChunks);
            finalizeProcessing(fulfillment, chunksByBuilding, totalLinesMoved);
        }

        /**
         * Checks if the fulfiller should skip processing based on configuration criteria.
         *
         * @return true if no EANs or Families are defined.
         */
        boolean shouldSkipProcessing() {
            if (targetEans.isEmpty() && targetFamilies.isEmpty()) {
                LOGGER.infof("Skipping ProductGrouping for Fulfillment: No criteria defined.");
                return true;
            }
            return false;
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
         * Initializes a map to hold chunks, ensuring one chunk per building.
         *
         * @return An empty HashMap keyed by Building.
         */
        Map<Building, FulfillmentChunk> initializeBuildingChunksMap() {
            return new HashMap<>();
        }

        /**
         * Filters and returns a list of PENDING chunks from the fulfillment.
         *
         * @param fulfillment The fulfillment containing the chunks.
         * @return A list of PENDING chunks.
         */
        List<FulfillmentChunk> getPendingChunks(Fulfillment fulfillment) {
            return fulfillment.chunks.stream()
                    .filter(this::isPending)
                    .collect(Collectors.toList());
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
         * Iterates through source chunks and moves matching lines to the target building chunks.
         *
         * @param sourceChunks     The list of chunks to process.
         * @param store             The store containing the buildings.
         * @param chunksByBuilding  The map to store/create target chunks.
         * @return The total number of lines moved.
         */
        int processSourceChunks(List<FulfillmentChunk> sourceChunks, Store store, Map<Building, FulfillmentChunk> chunksByBuilding) {
            int totalLinesMoved = 0;
            for (FulfillmentChunk sourceChunk : sourceChunks) {
                int movedCount = processSingleChunk(sourceChunk, store, chunksByBuilding);
                totalLinesMoved += movedCount;
            }
            return totalLinesMoved;
        }

        /**
         * Processes a single source chunk to move matching lines.
         *
         * @param sourceChunk    The chunk to process.
         * @param store          The store containing the buildings.
         * @param chunksByBuilding The map to store/create target chunks.
         * @return The number of lines moved from this chunk.
         */
        int processSingleChunk(FulfillmentChunk sourceChunk, Store store, Map<Building, FulfillmentChunk> chunksByBuilding) {
            int movedCount = 0;
            Iterator<FulfillmentLine> lineIterator = sourceChunk.lines.iterator();

            while (lineIterator.hasNext()) {
                FulfillmentLine line = lineIterator.next();

                if (isLineMatch(line)) {
                    ResolvedLocation resolved = resolveLocationInStore(store, line.product);

                    if (resolved != null) {
                        lineIterator.remove();
                        flushDatabaseChanges();
                        FulfillmentChunk targetChunk = getOrCreateTargetChunk(resolved.building, chunksByBuilding);
                        transferDeliveryDate(targetChunk, sourceChunk);
                        moveLineToNewChunk(line, resolved, targetChunk);
                        movedCount++;
                    } else {
                        LOGGER.warnf("Product %s matched criteria but has no location defined in any building.", line.product.ean);
                    }
                }
            }
            return movedCount;
        }

        /**
         * Transfers the delivery date from the source chunk to the target chunk.
         *
         * @param targetChunk The chunk receiving the delivery date.
         * @param sourceChunk The chunk providing the delivery date.
         */
        void transferDeliveryDate(FulfillmentChunk targetChunk, FulfillmentChunk sourceChunk) {
            if (targetChunk.deliveryDate == null) {
                targetChunk.deliveryDate = sourceChunk.deliveryDate;
            }
        }

        /**
         * Flushes pending changes to the database.
         */
        void flushDatabaseChanges() {
            Panache.flush();
        }

        /**
         * Handles the creation and linking logic for moving a line to a new chunk.
         *
         * @param sourceLine       The line to move.
         * @param resolvedLocation The resolved building and location data.
         * @param targetChunk      The target chunk to add the line to.
         */
        void moveLineToNewChunk(FulfillmentLine sourceLine, ResolvedLocation resolvedLocation, FulfillmentChunk targetChunk) {
            FulfillmentLine newLine = createNewLine(sourceLine, resolvedLocation.location);
            addLineToTargetChunk(newLine, targetChunk);
            logMove(sourceLine, resolvedLocation);
        }

        /**
         * Retrieves an existing chunk for the building or creates a new one.
         *
         * @param building        The building to look up.
         * @param chunksByBuilding The map of existing chunks.
         * @return The target FulfillmentChunk.
         */
        FulfillmentChunk getOrCreateTargetChunk(Building building, Map<Building, FulfillmentChunk> chunksByBuilding) {
            return chunksByBuilding.computeIfAbsent(building, this::createTargetChunk);
        }

        /**
         * Creates a new FulfillmentChunk configured as READY and linked to the building.
         *
         * @param building The building where the chunk is located.
         * @return A new, un-persisted FulfillmentChunk.
         */
        FulfillmentChunk createTargetChunk(Building building) {
            FulfillmentChunk newChunk = new FulfillmentChunk();
            newChunk.type = FulfillmentChunk.READY;
            newChunk.building = building;
            newChunk.label = "ProductGrouping";
            return newChunk;
        }

        /**
         * Creates a new FulfillmentLine copying data from the source line and assigning the resolved location.
         *
         * @param sourceLine The original line.
         * @param location   The resolved physical location.
         * @return A new FulfillmentLine.
         */
        FulfillmentLine createNewLine(FulfillmentLine sourceLine, BaseLocation location) {
            FulfillmentLine newLine = new FulfillmentLine();
            newLine.lineNumber = sourceLine.lineNumber;
            newLine.product = sourceLine.product;
            newLine.requestedQuantity = sourceLine.requestedQuantity;
            newLine.location = location;
            newLine.persist();
            return newLine;
        }

        /**
         * Adds the new line to the target chunk.
         *
         * @param line        The new line.
         * @param targetChunk The chunk to add it to.
         */
        void addLineToTargetChunk(FulfillmentLine line, FulfillmentChunk targetChunk) {
            targetChunk.addLine(line);
        }

        /**
         * Logs the successful move of a product.
         *
         * @param line     The moved line.
         * @param resolved The resolved location data.
         */
        void logMove(FulfillmentLine line, ResolvedLocation resolved) {
            if (LOGGER.isDebugEnabled()) {
                String ean = (line.product != null) ? line.product.ean : "Unknown";
                LOGGER.debugf("Moved Product %s to Building %s Chunk.", ean, resolved.building.buildingCode);
            }
        }

        /**
         * Removes chunks from the fulfillment if they contain no more lines.
         *
         * @param fulfillment      The fulfillment containing the chunks.
         * @param processedChunks The list of chunks to check.
         */
        void removeEmptyChunks(Fulfillment fulfillment, List<FulfillmentChunk> processedChunks) {
            for (FulfillmentChunk chunk : processedChunks) {
                if (chunk.lines.isEmpty()) {
                    LOGGER.infof("Removing empty chunk %s from Fulfillment %s", chunk.id, fulfillment.id);
                    fulfillment.chunks.remove(chunk);
                }
            }
        }

        /**
         * Finalizes the processing by adding the new chunks if lines were moved.
         *
         * @param fulfillment      The fulfillment being processed.
         * @param chunksByBuilding The map of created chunks keyed by building.
         * @param totalMoved       The total number of lines moved.
         */
        void finalizeProcessing(Fulfillment fulfillment, Map<Building, FulfillmentChunk> chunksByBuilding, int totalMoved) {
            if (!chunksByBuilding.isEmpty()) {
                for (FulfillmentChunk chunk : chunksByBuilding.values()) {
                    chunk.persist();
                    fulfillment.addChunk(chunk);
                    LOGGER.infof("Added ProductGrouping Chunk ID %s for Building %s",
                            chunk.id, chunk.building.buildingCode);
                }
                LOGGER.infof("ProductGrouping completed. Moved %d lines across %d building chunks.",
                        totalMoved, chunksByBuilding.size());
            } else {
                LOGGER.infof("No lines matched the criteria for Fulfillment ID %s", fulfillment.id);
            }
        }

        /**
         * Checks if a line matches the EAN list or belongs to one of the target families.
         *
         * @param line The fulfillment line to check.
         * @return true if the line matches the grouping criteria.
         */
        boolean isLineMatch(FulfillmentLine line) {
            if (isEanMatch(line.product.ean)) {
                return true;
            }
            if (isFamilyMatch(line.product)) {
                return true;
            }
            return false;
        }

        /**
         * Checks if the product EAN is in the target EAN list.
         *
         * @param ean The product EAN.
         * @return true if the EAN is in the list.
         */
        boolean isEanMatch(String ean) {
            return !targetEans.isEmpty() && targetEans.contains(ean);
        }

        /**
         * Checks if the product belongs to any of the target families.
         *
         * @param product The product to check.
         * @return true if a matching family is found.
         */
        boolean isFamilyMatch(Product product) {
            if (targetFamilies.isEmpty()) {
                return false;
            }
            Set<ProductFamily> productFamilies = ProductFamily.findAllFamiliesForProduct(product);
            for (ProductFamily pf : productFamilies) {
                if (targetFamilies.contains(pf.code)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Resolves the physical location of a product within the store's buildings.
         *
         * @param store   The store containing the buildings.
         * @param product The product to locate.
         * @return A {@link ResolvedLocation} containing the Building and BaseLocation, or null.
         */
        ResolvedLocation resolveLocationInStore(Store store, Product product) {
            if (product == null) {
                return null;
            }

            ResolvedLocation storeLocation = findLocationInStoreBuilding(store, product);
            if (storeLocation != null) {
                return storeLocation;
            }

            return findLocationInWarehouses(store, product);
        }

        /**
         * Searches for the product in the main StoreBuilding.
         *
         * @param store   The store.
         * @param product The product.
         * @return A ResolvedLocation if found, null otherwise.
         */
        ResolvedLocation findLocationInStoreBuilding(Store store, Product product) {
            StoreBuilding storeBuilding = store.getStoreBuilding();
            if (storeBuilding != null) {
                BaseLocation loc = storeBuilding.findLocationByProductId(product.id);
                if (loc != null) {
                    return new ResolvedLocation(storeBuilding, loc);
                }
            }
            return null;
        }

        /**
         * Searches for the product in the store's Warehouse buildings.
         *
         * @param store   The store.
         * @param product The product.
         * @return A ResolvedLocation if found, null otherwise.
         */
        ResolvedLocation findLocationInWarehouses(Store store, Product product) {
            for (Building building : store.buildings) {
                if (building instanceof WarehouseBuilding) {
                    BaseLocation loc = building.findLocationByProductId(product.id);
                    if (loc != null) {
                        return new ResolvedLocation(building, loc);
                    }
                }
            }
            return null;
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