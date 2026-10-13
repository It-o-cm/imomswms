package com.intermarche.backoffice.graphql;

import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.PreparingOrder;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.Input;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service class centralizing complex Lot and LotLine business logic.
 * <p>
 * This service extracts the synchronization logic for both Lots and their Lines
 * so it can be shared across different GraphQL Resources (e.g., LotResource, GroupingOrderResource)
 * without code duplication.
 * <p>
 * <b>Architecture Note:</b> This is a standard Java class, NOT a GraphQL endpoint. It does not inject EntityManager
 * because Panache handles transaction contexts automatically, even when called from a @RunOnVirtualThread GraphQL Resource.
 */
@ApplicationScoped
public class LotResourceService {

    private static final Logger LOGGER = Logger.getLogger(LotResourceService.class);

    // --------------------------------------------------
    // LOT LOGIC
    // --------------------------------------------------

    /**
     * Synchronizes the state of multiple Lots belonging to a specific PreparingOrder.
     * <p>
     * This method validates that each provided Lot belongs to the given order, updates the Lot's
     * status if specified, and delegates the synchronization of the Lot's lines to
     * {@link #synchronizeLotLines(Lot, List)}.
     *
     * @param order      The parent PreparingOrder containing the lots to update.
     * @param inputLots  The incoming list of lot records from the client.
     * @throws NoSuchElementException    If a Lot ID is provided but not found within the order.
     * @throws IllegalArgumentException  If a Lot ID is null.
     */
    public void synchronizeLots(PreparingOrder order, List<LotUpdateRecord> inputLots) {
        if (inputLots == null || inputLots.isEmpty()) {
            return;
        }

        for (LotUpdateRecord lotInput : inputLots) {
            if (lotInput.id == null) {
                throw new IllegalArgumentException("Lot ID is mandatory for lot updates.");
            }

            // Find the lot and verify it belongs to this specific order
            Lot lot = order.lots.stream()
                    .filter(l -> l.id != null && l.id.equals(lotInput.id))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException(
                            "Lot with id " + lotInput.id + " not found in order " + order.id));

            // Update Lot-level attributes
            if (lotInput.status != null) {
                lot.status = lotInput.status;
            }

            // Delegate line synchronization if lines are provided
            if (lotInput.lines != null && !lotInput.lines.isEmpty()) {
                synchronizeLotLines(lot, lotInput.lines);
            }
        }
    }

    // --------------------------------------------------
    // LOT LINE LOGIC
    // --------------------------------------------------

    /**
     * Synchronizes the lines of a Lot based on an input list.
     * <p>
     * Compares the input line IDs against the current lot's lines to cleanly remove deleted lines.
     * Creates new lines and updates existing ones.
     * <p>
     * <b>Fallback Location Logic:</b> If a line is provided without a specific locationId, it calculates the common
     * Area/Aisle of the Lot's current lines and finds the closest ShelfLocation for the product using
     * {@link ShelfLocation#findClosestForProduct}. This logic is only applied if the lot is in a PROCESSING state.
     *
     * @param lot         The Lot entity to update.
     * @param inputLines  The incoming list of line records from the client.
     * @throws NoSuchElementException If a referenced entity (Product, FulfillmentLine, Location) is not found.
     */
    @Transactional(rollbackOn = Exception.class)
    public void synchronizeLotLines(Lot lot, List<LotLineRecord> inputLines) {
        LOGGER.info("Entering method synchronizeLotLines for Lot id: " + lot.id);

        Store effectiveStore = lot.store;
        Building effectiveBuilding = (lot.chunk != null) ? lot.chunk.building : null;

        // We need the common Area/Aisle from the current lines to use as fallback
        ShelfLocation.LocationSpecification locSpec = ShelfLocation.findCommonSpecification(
                lot.lines.stream().map(l -> l.location).toList()
        );
        String effectiveArea = (locSpec != null) ? locSpec.area : null;
        String effectiveAisle = (locSpec != null) ? locSpec.aisle : null;

        // Identify lines to keep
        Set<Long> inputLineIds = inputLines.stream()
                .map(l -> l.id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Remove lines missing from input (Orphan removal)
        lot.lines.removeIf(line -> !inputLineIds.contains(line.id));
        // Process incoming lines
        for (LotLineRecord lineInput : inputLines) {
            LotLine line;
            if (lineInput.id != null) {
                // Update existing line
                line = lot.lines.stream()
                        .filter(l -> l.id.equals(lineInput.id))
                        .findFirst()
                        .orElse(null);
                if (line == null) {
                    throw new NoSuchElementException("LotLine with id " + lineInput.id + " not found in this lot");
                }
            } else {
                line = new LotLine();
            }
            // Update basic fields
            if (lineInput.quantity != null) {
                line.quantity = lineInput.quantity;
            }
            if (lineInput.productId != null) {
                line.product = Product.findById(lineInput.productId);
                if (line.product == null) throw new NoSuchElementException("Product not found");
            }
            if (lineInput.fulfillmentLineId != null) {
                line.fulfillmentLine = FulfillmentLine.findById(lineInput.fulfillmentLineId);
                if (line.fulfillmentLine == null) throw new NoSuchElementException("FulfillmentLine not found");
            }
            // STRICT LOCATION LOGIC (Respecting the updateFulfillmentChunk pattern)
            if (lineInput.locationId != null) {
                line.location = BaseLocation.findById(lineInput.locationId);
            } else {
                // Fallback : use the dynamically calculated context
                List<ShelfLocation> closestLocations = ShelfLocation.findClosestForProduct(
                        effectiveStore,
                        line.product,
                        effectiveBuilding,
                        effectiveArea,
                        effectiveAisle
                );
                if (!closestLocations.isEmpty()) {
                    line.location = closestLocations.getFirst();
                } else {
                    throw new NoSuchElementException("No fallback location found for product " + line.product.id);
                }
            }
            if (lineInput.id == null) {
                lot.lines.add(line);
            }
        }
        LOGGER.info("Exiting method synchronizeLotLines");
    }

    // --------------------------------------------------
    // INPUT RECORDS
    // --------------------------------------------------

    /**
     * Input record type for updating a Lot and its nested lines.
     */
    @Input
    static public class LotUpdateRecord {
        /**
         * The ID of the Lot.
         * Mandatory to identify which Lot to update.
         */
        public Long id;

        /**
         * The new status for the Lot.
         * If null, the status remains unchanged.
         */
        public Lot.LotStatus status;

        /**
         * The list of LotLine records to synchronize.
         * If null or empty, the lines are left untouched.
         */
        public List<LotLineRecord> lines;
    }

    /**
     * Input record type for updating a LotLine.
     */
    @Input
    static public class LotLineRecord {
        /**
         * The ID of the line.
         * If null, a new line is created.
         * If present, the existing line is updated.
         */
        public Long id;

        /**
         * The picked/processed quantity for this line.
         */
        public BigDecimal quantity;

        /**
         * The ID of the Product associated with this line.
         */
        public Long productId;

        /**
         * The ID of the FulfillmentLine this LotLine references.
         */
        public Long fulfillmentLineId;

        /**
         * The ID of the BaseLocation where this line is physically located.
         * If null, defaults to the closest location calculated via fallback logic.
         */
        public Long locationId;
    }

}