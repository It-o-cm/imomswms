package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.FabricationLine;
import com.intermarche.backoffice.domain.catering.FabricationLineUnit;
import com.intermarche.backoffice.domain.executing.FabricationExecution;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.FabricationConsumption;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.stock.StockAccount;
import com.intermarche.backoffice.domain.stock.StockCommitment;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.executing.OperatorCommandInput;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Applies the operator v2 fabrication actions to a {@link FabricationExecution} (rule F12).
 * <p>
 * This packet implements the {@code FABRICATE} quantity and state effect (T3.028, T3.029): it adds the
 * fabricated quantity for a unit, refuses a total above the planned quantity, moves the order to
 * {@code PROCESSING} on the first action, and moves the served unit to {@code IN_PROGRESS} then
 * {@code PRODUCED}. The stock movements (F13), the staging placement (F15) and the labels (F14) are
 * layered in later packets; {@code FABRICATION_LOSS}, {@code FABRICATION_SHORT} and the fabrication
 * {@code CONCLUDE} guards likewise.
 */
@ApplicationScoped
public class CateringFabricationExecution {

    @Inject
    CateringUnitStateService states;
    @Inject
    CateringParameters parameters;
    @Inject
    CateringRecipeResolver recipes;
    @Inject
    TemporalStockService stocks;
    @Inject
    ObjectMapper mapper;

    /**
     * Applies one operator command to a fabrication execution.
     *
     * @param execution the fabrication execution.
     * @param input     the operator command.
     * @param employee  the operator (reserved for later packets: incidents, authorship).
     * @param store     the store (reserved for later packets: stock, labels).
     */
    public void apply(FabricationExecution execution, OperatorCommandInput input, Employee employee, Store store) {
        FabricationOrder order = (FabricationOrder) Hibernate.unproxy(execution.handlingOrder,
                com.intermarche.backoffice.domain.wms.BaseHandlingOrder.class);
        switch (input.action) {
            case "FABRICATE" -> fabricate(order, input.itemId, input.quantity);
            case "FABRICATION_LOSS" -> declareLoss(order, input.itemId, input.quantity);
            case "FABRICATION_SHORT" -> declareShort(order, input.itemId, input.quantity);
            case "CONCLUDE" -> conclude(execution, order);
            default -> throw new OperatorFailure(400, "UNKNOWN_ACTION", "Action de fabrication inconnue : " + input.action);
        }
    }

    /**
     * Records a fabricated quantity for a unit (F12, T3.026 quantity, T3.028, T3.029).
     * <p>
     * The total produced for the unit may not exceed its planned quantity ({@code QUANTITY_EXCEEDED}).
     * The first action moves the order to {@code PROCESSING}; the unit becomes {@code IN_PROGRESS} on
     * its first quantity and {@code PRODUCED} once its whole quantity is fabricated.
     *
     * @param order      the fabrication order.
     * @param lineUnitId the fabrication-line unit (the share of a line destined for one unit).
     * @param quantity   the fabricated quantity.
     */
    public void fabricate(FabricationOrder order, Long lineUnitId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new OperatorFailure(400, "INVALID_REQUEST", "Quantité fabriquée positive requise");
        }
        FabricationLineUnit lineUnit = lineUnitId == null ? null : FabricationLineUnit.findById(lineUnitId);
        FabricationLine line = lineUnit == null ? null : Hibernate.unproxy(lineUnit.line, FabricationLine.class);
        if (lineUnit == null || line == null || !Hibernate.unproxy(line.order, FabricationOrder.class).id.equals(order.id)) {
            throw new OperatorFailure(409, "STALE_REVISION", "Part de ligne introuvable pour cet ordre");
        }
        if (lineUnit.producedQuantity.add(quantity).compareTo(lineUnit.quantity) > 0) {
            throw new OperatorFailure(409, "QUANTITY_EXCEEDED", "Quantité supérieure à la quantité prévue pour l'unité");
        }
        if (order.status != HandlingOrderStatus.PROCESSING) {
            order.status = HandlingOrderStatus.PROCESSING; // F12: the first action starts the order
        }
        if (line.producedQuantity.signum() == 0) {
            stampLine(order, line); // F13: producedAt, useBy and lotCode are fixed at the first quantity
        }
        lineUnit.producedQuantity = lineUnit.producedQuantity.add(quantity);
        line.producedQuantity = line.producedQuantity.add(quantity);
        CateringUnit unit = Hibernate.unproxy(lineUnit.unit, CateringUnit.class);
        consumeComponents(order, line, unit, quantity, line.producedQuantity); // F13: components out, commitments released
        unit.producedQuantity = unit.producedQuantity.add(quantity);
        if (unit.producedQuantity.compareTo(unit.quantity) >= 0) {
            states.markProduced(unit); // F3: whole quantity fabricated
        } else {
            states.markInProgress(unit); // F3: first quantity fabricated
        }
    }

    /**
     * Consumes the components of a fabricated quantity and releases their commitments (F13, T3.045,
     * T3.051), in the action's transaction. For a fabricated line the components are the resolved recipe
     * ingredients; for a mise-en-support line the component is the received article. Each component is
     * taken from a store location that holds it, the matching {@code FAB:}/{@code CATSUP:} commitment is
     * drawn down, and a {@link FabricationConsumption} is recorded. A component with no commitment (demo
     * without a committed component) is skipped.
     *
     * @param order    the fabrication order.
     * @param line     the fabrication line.
     * @param unit     the served unit.
     * @param quantity the fabricated quantity.
     * @param token    a value that makes the stock event key unique across successive actions.
     */
    private void consumeComponents(FabricationOrder order, FabricationLine line, CateringUnit unit,
                                   BigDecimal quantity, BigDecimal token) {
        Store store = Hibernate.unproxy(order.store, Store.class);
        CateringItem item = Hibernate.unproxy(line.item, CateringItem.class);
        LocalDateTime now = DateTimeProvider.now();
        if (unit.sourcingMode == com.intermarche.backoffice.domain.catering.SourcingMode.SUPPLIED) {
            consumeOne(store, line, Hibernate.unproxy(item.product, Product.class), quantity,
                    "CATSUP:" + unit.id, token, now);
            return;
        }
        Map<Product, BigDecimal> components = recipes.resolve(item, line.variantId, configuration(unit), quantity,
                parameters.defaultLaborSeconds(store)).components();
        for (Map.Entry<Product, BigDecimal> entry : components.entrySet()) {
            String reference = "FAB:" + order.id + ":" + line.id + ":" + entry.getKey().ean;
            consumeOne(store, line, entry.getKey(), entry.getValue(), reference, token, now);
        }
    }

    /**
     * Draws one component out of stock against its commitment and records the consumption (F13).
     *
     * @param store     the store.
     * @param line      the fabrication line.
     * @param component the component product.
     * @param quantity  the quantity to consume.
     * @param reference the commitment reference.
     * @param token     a uniqueness token for the stock event key.
     * @param now       the action instant.
     */
    private void consumeOne(Store store, FabricationLine line, Product component, BigDecimal quantity,
                            String reference, BigDecimal token, LocalDateTime now) {
        StockAccount account = stocks.account(store.id, component.id);
        StockCommitment commitment = StockCommitment.<StockCommitment>find("reference = ?1 and status = ?2",
                reference, StockCommitment.CommitmentStatus.ACTIVE).firstResult();
        if (commitment == null) {
            return; // nothing committed for this component in the demo
        }
        Long locationId = stockLocation(account, quantity);
        if (locationId == null) {
            throw new OperatorFailure(409, "COMPONENT_SHORTAGE", "Composant indisponible : " + component.ean);
        }
        stocks.prepare(commitment.id, locationId, "fab:" + reference + ":" + token.toPlainString(), quantity, now);
        FabricationConsumption consumption = new FabricationConsumption();
        consumption.line = line;
        consumption.component = component;
        consumption.quantity = quantity;
        consumption.loss = false;
        consumption.persist();
    }

    /**
     * Returns a store location holding at least the requested quantity of a component, or null.
     *
     * @param account  the component stock account.
     * @param quantity the required quantity.
     * @return the location id, or null.
     */
    private Long stockLocation(StockAccount account, BigDecimal quantity) {
        for (TemporalStockService.Position position : stocks.positions(account.id)) {
            if (position.quantity.compareTo(quantity) >= 0) {
                return position.locationId;
            }
        }
        return null;
    }

    /**
     * Parses the configuration of a unit, or returns null.
     *
     * @param unit the unit.
     * @return the configuration node, or null.
     */
    private JsonNode configuration(CateringUnit unit) {
        if (unit.configuration == null) {
            return null;
        }
        try {
            return mapper.readTree(unit.configuration);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fixes the traceability metadata of a line at its first fabricated quantity (F13, T3.043, T3.044):
     * {@code producedAt} is the server time of the first quantity, {@code useBy} is {@code producedAt}
     * plus the article shelf life, and {@code lotCode} is {@code <order code>-<line rank>}.
     *
     * @param order the fabrication order.
     * @param line  the fabrication line.
     */
    private void stampLine(FabricationOrder order, FabricationLine line) {
        Store store = Hibernate.unproxy(order.store, Store.class);
        LocalDateTime now = DateTimeProvider.now();
        line.producedAt = now;
        line.useBy = now.plusHours(parameters.shelfLifeHours(store, Hibernate.unproxy(line.item,
                com.intermarche.backoffice.domain.catering.CateringItem.class)));
        line.lotCode = order.code + "-" + lineRank(order, line);
    }

    /**
     * Returns the 1-based rank of a line among the order's lines, ordered by id.
     *
     * @param order the fabrication order.
     * @param line  the fabrication line.
     * @return the rank.
     */
    private int lineRank(FabricationOrder order, FabricationLine line) {
        List<FabricationLine> lines = FabricationLine.list("order = ?1 order by id", order);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id.equals(line.id)) {
                return i + 1;
            }
        }
        return 1;
    }

    /**
     * Declares a fabrication loss for a unit (F12, T3.030, state effect): the lost quantity is recorded
     * on the line; the quantity still to fabricate does not change. The component consumption of the
     * lost quantity (F13) and the incident ({@code FieldOperationsService}) are layered in later packets.
     *
     * @param order      the fabrication order.
     * @param lineUnitId the fabrication-line unit.
     * @param quantity   the lost quantity.
     */
    public void declareLoss(FabricationOrder order, Long lineUnitId, BigDecimal quantity) {
        FabricationLineUnit lineUnit = require(order, lineUnitId, quantity);
        FabricationLine line = Hibernate.unproxy(lineUnit.line, FabricationLine.class);
        startOrder(order);
        line.lostQuantity = line.lostQuantity.add(quantity); // the remaining to fabricate is unchanged
    }

    /**
     * Declares that a quantity cannot be fabricated for a unit (F12, T3.031, T3.032, state effect): the
     * quantity is added to the short quantity of the line unit, the line and the unit; a unit whose whole
     * quantity is unserved becomes {@code SHORT}. The picking-shortage record, the incident and the
     * {@code CATERING_SHORT} situation are layered in later packets.
     *
     * @param order      the fabrication order.
     * @param lineUnitId the fabrication-line unit.
     * @param quantity   the quantity that cannot be fabricated.
     */
    public void declareShort(FabricationOrder order, Long lineUnitId, BigDecimal quantity) {
        FabricationLineUnit lineUnit = require(order, lineUnitId, quantity);
        FabricationLine line = Hibernate.unproxy(lineUnit.line, FabricationLine.class);
        if (lineUnit.producedQuantity.add(lineUnit.shortQuantity).add(quantity).compareTo(lineUnit.quantity) > 0) {
            throw new OperatorFailure(409, "QUANTITY_EXCEEDED", "Quantité supérieure au reste à servir pour l'unité");
        }
        startOrder(order);
        lineUnit.shortQuantity = lineUnit.shortQuantity.add(quantity);
        line.shortQuantity = line.shortQuantity.add(quantity);
        CateringUnit unit = Hibernate.unproxy(lineUnit.unit, CateringUnit.class);
        states.recordShort(unit, unit.shortQuantity.add(quantity)); // SHORT only when wholly unserved (F3)
    }

    /**
     * Concludes a fabrication execution (F12, T3.034): it is refused while a line unit still has a
     * remainder that is neither fabricated nor declared short. The deposit guard (T3.035, F15) is layered
     * in a later packet. On success the execution and the order are {@code DONE}.
     *
     * @param execution the fabrication execution.
     * @param order     the fabrication order.
     */
    public void conclude(FabricationExecution execution, FabricationOrder order) {
        for (FabricationLine line : FabricationLine.<FabricationLine>list("order", order)) {
            for (FabricationLineUnit lineUnit : FabricationLineUnit.<FabricationLineUnit>list("line", line)) {
                if (lineUnit.producedQuantity.add(lineUnit.shortQuantity).compareTo(lineUnit.quantity) < 0) {
                    throw new OperatorFailure(409, "LINE_NOT_COMPLETE",
                            "Une part de ligne n'est ni fabriquée ni déclarée non servie");
                }
            }
        }
        execution.status = com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE;
        order.status = HandlingOrderStatus.DONE;
    }

    /**
     * Resolves and validates a line unit of an order for a positive quantity.
     *
     * @param order      the fabrication order.
     * @param lineUnitId the fabrication-line unit id.
     * @param quantity   the quantity.
     * @return the line unit.
     */
    private FabricationLineUnit require(FabricationOrder order, Long lineUnitId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new OperatorFailure(400, "INVALID_REQUEST", "Quantité positive requise");
        }
        FabricationLineUnit lineUnit = lineUnitId == null ? null : FabricationLineUnit.findById(lineUnitId);
        FabricationLine line = lineUnit == null ? null : Hibernate.unproxy(lineUnit.line, FabricationLine.class);
        if (lineUnit == null || line == null || !Hibernate.unproxy(line.order, FabricationOrder.class).id.equals(order.id)) {
            throw new OperatorFailure(409, "STALE_REVISION", "Part de ligne introuvable pour cet ordre");
        }
        return lineUnit;
    }

    /**
     * Moves the order to {@code PROCESSING} on the first action (F12, T3.029).
     *
     * @param order the fabrication order.
     */
    private void startOrder(FabricationOrder order) {
        if (order.status != HandlingOrderStatus.PROCESSING) {
            order.status = HandlingOrderStatus.PROCESSING;
        }
    }
}
