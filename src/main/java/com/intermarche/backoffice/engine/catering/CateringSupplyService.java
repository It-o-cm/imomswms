package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.CateringPurchaseNeed;
import com.intermarche.backoffice.domain.catering.CateringPurchaseNeed.PurchaseNeedState;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.CateringUnitState;
import com.intermarche.backoffice.domain.catering.FabricationStation;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.stock.StockAccount;
import com.intermarche.backoffice.domain.stock.StockCommitment;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.PendingSupplyLocation;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds and tracks the purchase needs of {@code SUPPLIED} catering units (rule F10).
 * <p>
 * A {@code SUPPLIED} unit is covered by a supplier order: this service proposes a purchase need at the
 * first computation that sees the unit (F10.1), dates it ({@code dueAt}, {@code orderBy}, F10.1-F10.2),
 * and exposes its lateness (F10.7). Validation into an {@code ExpectedSupply}, the fabrication (mise en
 * support) candidacy (F10.4) and the supplier receipt (F10.5) are handled in later packets; this class
 * owns the proposal and the lateness computation only.
 */
@ApplicationScoped
public class CateringSupplyService {

    @Inject
    CateringParameters parameters;
    @Inject
    TemporalStockService stocks;

    /** A validation request for one purchase need. */
    public static class NeedValidation {
        public Long id;
        public Long revision;
    }

    /** Lateness of a purchase need (F10.7). */
    public enum SupplyLateState {
        /** On time. */
        NONE,
        /** At risk: order-by or due date passed without the expected progress. */
        RISK,
        /** Confirmed: the latest supply end (F5) is passed and the supply is not received. */
        CONFIRMED
    }

    /**
     * Proposes the missing purchase needs of a store (F10.1, F10.2).
     * <p>
     * For each {@code SUPPLIED} unit that is still active (not cancelled, short or handed over) and has
     * no purchase need yet, a {@code PROPOSED} need is created: {@code dueAt} is the unit availability
     * minus the article {@code maxAdvanceHours}; {@code orderBy} is the day of {@code dueAt} minus
     * {@code supplierLeadDays}, taken at the {@code supplierOrderCutoff} time or, when none is set, at
     * the end of that day. The computation is idempotent: a unit that already carries a need is skipped.
     *
     * @param store the store.
     */
    @Transactional
    public void recomputeNeeds(Store store) {
        if (store == null || !parameters.enabled(store)) {
            return;
        }
        for (CateringUnit unit : CateringUnit.<CateringUnit>list(
                "order.store = ?1 and sourcingMode = ?2 and state not in ?3", store, SourcingMode.SUPPLIED,
                java.util.List.of(CateringUnitState.CANCELLED, CateringUnitState.SHORT, CateringUnitState.HANDED_OVER))) {
            CateringItem item = Hibernate.unproxy(unit.item, CateringItem.class);
            if (item == null || unit.availableAt == null) {
                continue; // an unreferenced supplied unit is blocked; no supplier order yet
            }
            if (CateringPurchaseNeed.count("unit = ?1", unit) > 0) {
                continue; // already proposed, validated or received
            }
            CateringPurchaseNeed need = new CateringPurchaseNeed();
            need.store = store;
            need.unit = unit;
            need.supplierName = item.supplierName;
            need.quantity = unit.quantity;
            need.dueAt = dueAt(store, unit, item);
            need.orderBy = orderBy(store, need.dueAt, item);
            need.state = PurchaseNeedState.PROPOSED;
            need.persist();
        }
    }

    /**
     * Validates the chosen purchase needs (F10.3, T3.004): each {@code PROPOSED} need becomes
     * {@code VALIDATED} and gets an {@link ExpectedSupply} ({@code EXPECTED}, reference {@code CAT:…})
     * towards the store's receiving location, created by the existing supply flow. A stale revision or a
     * need that is not proposable is refused with {@code STALE_REVISION}.
     *
     * @param store           the store.
     * @param inputs          the per-need validation requests.
     * @param enforceRevision whether to enforce the expected revision.
     * @return the ids of the validated needs.
     */
    @Transactional
    public List<Long> validate(Store store, List<NeedValidation> inputs, boolean enforceRevision) {
        List<Long> validated = new ArrayList<>();
        for (NeedValidation input : inputs) {
            CateringPurchaseNeed need = input.id == null ? null : CateringPurchaseNeed.findById(input.id);
            if (need == null || !Hibernate.unproxy(need.store, Store.class).id.equals(store.id)
                    || need.state != PurchaseNeedState.PROPOSED) {
                throw new OperatorFailure(409, "STALE_REVISION", "Besoin introuvable ou déjà traité");
            }
            if (enforceRevision && input.revision != null && !Objects.equals(need.version, input.revision)) {
                throw new OperatorFailure(409, "STALE_REVISION", "Le besoin a changé, actualisez");
            }
            CateringUnit unit = Hibernate.unproxy(need.unit, CateringUnit.class);
            CateringItem item = Hibernate.unproxy(unit.item, CateringItem.class);
            Building building = receptionBuilding(item);
            if (building == null) {
                throw new OperatorFailure(409, "NO_RECEPTION_LOCATION", "Aucun bâtiment de réception pour l'article");
            }
            BaseLocation destination = receptionLocation(building);
            StockAccount account = stocks.account(store.id, item.product.id);
            Order order = Hibernate.unproxy(unit.order, Order.class);
            String reference = "CAT:" + order.id + ":" + unit.lineId;
            ExpectedSupply supply = stocks.planSupply(account.id, reference, null, destination.id, need.quantity, need.dueAt);
            need.expectedSupply = supply;
            need.state = PurchaseNeedState.VALIDATED;
            validated.add(need.id);
        }
        return validated;
    }

    /**
     * Reconciles supplier receipts (F10.5, T3.009): a {@code VALIDATED} need whose {@link ExpectedSupply}
     * has been received through the existing reception flow becomes {@code RECEIVED}, and the received
     * quantity is committed for its unit so fabrication can consume it. Idempotent: an already-committed
     * supply is not committed twice.
     *
     * @param store the store.
     */
    @Transactional
    public void reconcileReceipts(Store store) {
        if (store == null) {
            return;
        }
        for (CateringPurchaseNeed need : CateringPurchaseNeed.<CateringPurchaseNeed>list("store = ?1 and state = ?2",
                store, PurchaseNeedState.VALIDATED)) {
            ExpectedSupply supply = Hibernate.unproxy(need.expectedSupply, ExpectedSupply.class);
            if (supply == null || supply.status != ExpectedSupply.SupplyStatus.RECEIVED) {
                continue;
            }
            need.state = PurchaseNeedState.RECEIVED;
            CateringUnit unit = Hibernate.unproxy(need.unit, CateringUnit.class);
            CateringItem item = Hibernate.unproxy(unit.item, CateringItem.class);
            Building building = receptionBuilding(item);
            if (building == null) {
                continue;
            }
            String reference = "CATSUP:" + unit.id;
            if (StockCommitment.count("reference = ?1", reference) == 0) {
                StockAccount account = stocks.account(store.id, item.product.id);
                stocks.reserve(account.id, reference, building.id, need.quantity, unit.availableAt, false);
            }
        }
    }

    /**
     * Returns the building of the article's station, or null when none is resolved.
     *
     * @param item the supplied article datasheet.
     * @return the reception building, or null.
     */
    private Building receptionBuilding(CateringItem item) {
        FabricationStation station = item == null ? null : Hibernate.unproxy(item.station, FabricationStation.class);
        return station == null || station.building == null ? null : Hibernate.unproxy(station.building, Building.class);
    }

    /**
     * Resolves the store's receiving location in a building, creating the forecast
     * {@link PendingSupplyLocation} when the building has none (the expected supply's forecast address).
     *
     * @param building the reception building.
     * @return the receiving location.
     */
    private BaseLocation receptionLocation(Building building) {
        for (BaseLocation location : building.locations) {
            if (Hibernate.unproxy(location, BaseLocation.class) instanceof PendingSupplyLocation pending) {
                return pending;
            }
        }
        PendingSupplyLocation pending = new PendingSupplyLocation();
        pending.area = "EN_ATTENTE";
        pending.aisle = "TRAITEUR";
        pending.unit = "RECEPTION";
        pending.position = "1";
        pending.elevation = "1";
        building.locations.add(pending);
        pending.persist();
        return pending;
    }

    /**
     * Computes the due instant of a supplied unit (F10.1): the availability minus the article's maximum
     * advance in hours.
     *
     * @param store the store.
     * @param unit  the supplied unit.
     * @param item  the unit's datasheet.
     * @return the due instant.
     */
    private LocalDateTime dueAt(Store store, CateringUnit unit, CateringItem item) {
        return unit.availableAt.minusHours(parameters.maxAdvanceHours(store, item));
    }

    /**
     * Computes the latest order instant of a need (F10.2): the day of {@code dueAt} minus the supplier
     * lead days, at the supplier order cutoff time, or at the end of that day when no cutoff is set.
     *
     * @param store the store.
     * @param dueAt the due instant.
     * @param item  the unit's datasheet.
     * @return the latest order instant.
     */
    private LocalDateTime orderBy(Store store, LocalDateTime dueAt, CateringItem item) {
        LocalDate orderDay = dueAt.toLocalDate().minusDays(parameters.supplierLeadDays(store, item));
        LocalTime cutoff = parseCutoff(parameters.supplierOrderCutoff(store));
        return cutoff != null ? orderDay.atTime(cutoff) : orderDay.atTime(LocalTime.of(23, 59));
    }

    /**
     * Parses a {@code HH:MM} cutoff string, returning null when it is absent or malformed.
     *
     * @param cutoff the cutoff value.
     * @return the parsed time, or null.
     */
    private LocalTime parseCutoff(String cutoff) {
        if (cutoff == null || cutoff.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(cutoff);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the lateness of a purchase need (F10.7).
     * <p>
     * A received or cancelled need is never late. Otherwise the need is {@code CONFIRMED} late once the
     * latest supply end — the unit availability minus the station staging lead (the F5 latest end) — is
     * passed; it is at {@code RISK} when the order-by instant has passed while the need is still only
     * {@code PROPOSED}, or when the due instant has passed while the supply is not received.
     *
     * @param need the purchase need.
     * @return the lateness state.
     */
    public SupplyLateState lateState(CateringPurchaseNeed need) {
        if (need.state == PurchaseNeedState.RECEIVED || need.state == PurchaseNeedState.CANCELLED) {
            return SupplyLateState.NONE;
        }
        LocalDateTime now = DateTimeProvider.now();
        CateringUnit unit = Hibernate.unproxy(need.unit, CateringUnit.class);
        CateringItem item = unit == null ? null : Hibernate.unproxy(unit.item, CateringItem.class);
        FabricationStation station = item == null ? null : Hibernate.unproxy(item.station, FabricationStation.class);
        if (unit != null && unit.availableAt != null) {
            LocalDateTime latestEnd = unit.availableAt.minusMinutes(parameters.stagingLeadMinutes(need.store, station));
            if (now.isAfter(latestEnd)) {
                return SupplyLateState.CONFIRMED;
            }
        }
        boolean orderLate = need.state == PurchaseNeedState.PROPOSED && need.orderBy != null && now.isAfter(need.orderBy);
        boolean deliveryLate = need.dueAt != null && now.isAfter(need.dueAt);
        return orderLate || deliveryLate ? SupplyLateState.RISK : SupplyLateState.NONE;
    }
}
