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
}
