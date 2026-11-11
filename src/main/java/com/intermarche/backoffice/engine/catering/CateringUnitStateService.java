package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.commerce.PickupContract;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import java.math.BigDecimal;

/**
 * Authority on the {@link CateringUnitState} machine (rule F3).
 * <p>
 * Every transition goes through this service so each state change both increments the unit
 * {@code revision} and raises the published order revision ({@link PickupContract#bump}), as required
 * by F3. The nine states are reached by their entry event: {@code RECEIVED} at reception (I6),
 * {@code BLOCKED} when the article is unreferenced or catering is disabled, {@code PLANNED} at
 * validation (F8), {@code IN_PROGRESS}/{@code PRODUCED} during fabrication (F12), {@code STAGED} on
 * staging (F15), {@code HANDED_OVER} at handover (F17), {@code SHORT} when the whole quantity is
 * definitively not served (F12), and {@code CANCELLED} on cancellation (F19). A {@code BLOCKED} unit
 * never stops the other units of its order, and it returns to {@code RECEIVED} at the first
 * {@link #recompute} that follows the disappearance of its blocking cause.
 */
@ApplicationScoped
public class CateringUnitStateService {

    @Inject
    CateringParameters parameters;
    @Inject
    CateringIntake intake;
    @Inject
    PickupContract pickup;

    /**
     * Moves a unit to a target state, incrementing its revision and the published order revision.
     * A no-op when the unit is already in the target state.
     *
     * @param unit   the unit.
     * @param target the target state.
     */
    private void transition(CateringUnit unit, CateringUnitState target) {
        if (unit.state == target) {
            return;
        }
        unit.state = target;
        bump(unit);
    }

    /**
     * Increments the unit revision and raises the published order revision after an observable change.
     *
     * @param unit the unit.
     */
    private void bump(CateringUnit unit) {
        unit.revision++;
        Order order = Hibernate.unproxy(unit.order, Order.class);
        pickup.bump(order, DateTimeProvider.now());
    }

    /**
     * Enters {@code RECEIVED}: the article is referenced and catering is enabled (I6, or recovery).
     *
     * @param unit the unit.
     */
    public void markReceived(CateringUnit unit) {
        transition(unit, CateringUnitState.RECEIVED);
    }

    /**
     * Enters {@code BLOCKED}: the article is unreferenced or catering is disabled.
     *
     * @param unit the unit.
     */
    public void markBlocked(CateringUnit unit) {
        transition(unit, CateringUnitState.BLOCKED);
    }

    /**
     * Enters {@code PLANNED}: a fabrication (or validated collecting) order covers the whole quantity (F8).
     *
     * @param unit the unit.
     */
    public void markPlanned(CateringUnit unit) {
        transition(unit, CateringUnitState.PLANNED);
    }

    /**
     * Enters {@code IN_PROGRESS}: the first quantity has been fabricated, staged or picked (F12).
     *
     * @param unit the unit.
     */
    public void markInProgress(CateringUnit unit) {
        transition(unit, CateringUnitState.IN_PROGRESS);
    }

    /**
     * Enters {@code PRODUCED}: the whole quantity has been fabricated, staged or picked (F12).
     *
     * @param unit the unit.
     */
    public void markProduced(CateringUnit unit) {
        transition(unit, CateringUnitState.PRODUCED);
    }

    /**
     * Enters {@code STAGED}: the whole quantity has been deposited in a staging buffer (F15).
     *
     * @param unit the unit.
     */
    public void markStaged(CateringUnit unit) {
        transition(unit, CateringUnitState.STAGED);
    }

    /**
     * Enters {@code HANDED_OVER}: the order has been handed over or shipped (F17).
     *
     * @param unit the unit.
     */
    public void markHandedOver(CateringUnit unit) {
        transition(unit, CateringUnitState.HANDED_OVER);
    }

    /**
     * Enters {@code CANCELLED}: the unit has been cancelled (F19).
     *
     * @param unit the unit.
     */
    public void markCancelled(CateringUnit unit) {
        transition(unit, CateringUnitState.CANCELLED);
    }

    /**
     * Records a definitively unserved quantity (F12, T2.006): the unit becomes {@code SHORT} only when
     * the whole quantity is unserved; otherwise it keeps its current state and carries the short
     * quantity. Either way the change is published.
     *
     * @param unit      the unit.
     * @param unserved  the quantity definitively not served.
     */
    public void recordShort(CateringUnit unit, BigDecimal unserved) {
        BigDecimal shortQuantity = unserved == null ? BigDecimal.ZERO : unserved;
        unit.shortQuantity = shortQuantity;
        boolean whole = unit.producedQuantity.signum() == 0 && shortQuantity.compareTo(unit.quantity) >= 0;
        if (whole) {
            transition(unit, CateringUnitState.SHORT);
        } else {
            bump(unit);
        }
    }

    /**
     * Returns the blocked units of a store to {@code RECEIVED} when their blocking cause has
     * disappeared (T2.003, T2.004): catering was re-enabled, a datasheet was referenced, or a
     * datasheet was reactivated. Each recovered unit is re-attached to its active datasheet and its
     * mode is re-resolved. Blocked units never prevent the other units of their order from advancing.
     *
     * @param store the store.
     */
    @Transactional
    public void recompute(Store store) {
        if (!parameters.enabled(store)) {
            return; // catering still disabled: nothing recovers
        }
        for (CateringUnit unit : CateringUnit.<CateringUnit>list("order.store = ?1 and state = ?2",
                store, CateringUnitState.BLOCKED)) {
            Product product = Product.findByEan(unit.ean);
            CateringItem item = intake.activeItem(product);
            if (item != null) {
                unit.item = item;
                unit.sourcingMode = intake.resolveMode(item, store);
                markReceived(unit);
                if (unit.sourcingMode == SourcingMode.STOCKED) {
                    reinjectStockedLine(unit);
                }
            }
        }
    }

    /**
     * Returns the products to pick of a recovered {@code STOCKED} unit to a {@code PENDING} chunk of its
     * order (T2.005, I7). While it was {@code BLOCKED}, the catering fulfiller had routed the unit's line
     * into the {@code READY} catering chunk; once the unit recovers as {@code STOCKED} that line must
     * rejoin the ordinary picking flow so the subsequent fulfillers prepare it. A {@code STOCKED} unit
     * carries no catering line, so its {@code fulfillmentLine} is cleared. A no-op when the unit has no
     * routed line (an unreferenced unit whose product was unknown stayed in the pending chunk).
     *
     * @param unit the recovered stocked unit.
     */
    private void reinjectStockedLine(CateringUnit unit) {
        FulfillmentLine routed = unit.fulfillmentLine;
        if (routed == null) {
            return; // the line was never routed out of the pending flow
        }
        Order order = Hibernate.unproxy(unit.order, Order.class);
        Fulfillment fulfillment = Fulfillment.find("order", order).firstResult();
        if (fulfillment == null) {
            unit.fulfillmentLine = null;
            return;
        }
        FulfillmentChunk source = null;
        FulfillmentChunk pending = null;
        for (FulfillmentChunk chunk : fulfillment.chunks) {
            if (chunk.lines.contains(routed)) {
                source = chunk;
            }
            if (FulfillmentChunk.PENDING.equals(chunk.type) && pending == null) {
                pending = chunk;
            }
        }
        int lineNumber = routed.lineNumber;
        Product product = routed.product;
        BigDecimal quantity = routed.requestedQuantity;
        // Drop the unit's reference before the routed line is orphan-removed, so no managed unit
        // points at a deleted line when the delete is flushed.
        unit.fulfillmentLine = null; // a STOCKED unit carries no catering line (data model)
        if (source != null) {
            source.lines.remove(routed);
            // Flush so orphanRemoval deletes the routed line before its copy is created.
            Panache.getEntityManager().flush();
        }
        if (pending == null) {
            pending = new FulfillmentChunk();
            pending.type = FulfillmentChunk.PENDING;
            pending.deliveryDate = source == null ? null : source.deliveryDate;
            pending.persist();
            fulfillment.addChunk(pending);
        }
        FulfillmentLine line = new FulfillmentLine();
        line.lineNumber = lineNumber;
        line.product = product;
        line.requestedQuantity = quantity;
        line.persist();
        pending.addLine(line);
        // Drop the now-empty catering chunk left behind by the block-time routing.
        fulfillment.chunks.removeIf(c -> c.catering && c.lines.isEmpty());
        Panache.getEntityManager().flush();
    }
}
