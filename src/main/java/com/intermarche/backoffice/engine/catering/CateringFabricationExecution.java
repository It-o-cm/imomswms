package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.FabricationLine;
import com.intermarche.backoffice.domain.catering.FabricationLineUnit;
import com.intermarche.backoffice.domain.executing.FabricationExecution;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import com.intermarche.backoffice.engine.executing.OperatorCommandInput;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.Hibernate;
import java.math.BigDecimal;

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
        lineUnit.producedQuantity = lineUnit.producedQuantity.add(quantity);
        line.producedQuantity = line.producedQuantity.add(quantity);
        CateringUnit unit = Hibernate.unproxy(lineUnit.unit, CateringUnit.class);
        unit.producedQuantity = unit.producedQuantity.add(quantity);
        if (unit.producedQuantity.compareTo(unit.quantity) >= 0) {
            states.markProduced(unit); // F3: whole quantity fabricated
        } else {
            states.markInProgress(unit); // F3: first quantity fabricated
        }
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
