package com.intermarche.backoffice.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.catering.*;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.stock.StockCommitment;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder.FabricationProposalState;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates fabrication proposals (F8) and creates the component commitments (F9).
 * <p>
 * The manager may change the day, the time range and the employee before validating; the server
 * rechecks the window (F5), the capacity (F7) and the station qualification, and refuses an
 * out-of-window, over-capacity, unqualified or stale request. On validation the order becomes
 * {@code VALIDATED}/{@code SCHEDULED}, its units become {@code PLANNED}, and a {@link
 * com.intermarche.backoffice.domain.stock.StockCommitment} is created for each INGREDIENT component
 * of the frozen recipe; {@code componentShortagePolicy = BLOCK} refuses an unavailable component.
 */
@ApplicationScoped
public class CateringValidationService {

    @Inject
    CateringParameters parameters;
    @Inject
    CateringRecipeResolver recipes;
    @Inject
    FabricationWindow windows;
    @Inject
    CateringCapacity capacity;
    @Inject
    CateringUnitStateService states;
    @Inject
    TemporalStockService stocks;
    @Inject
    ObjectMapper mapper;

    /** A validation request for one order. */
    public static class OrderValidation {
        public Long id;
        public Long revision;
        public String productionDate;
        public String start;
        public String end;
        public Long employeeId;
    }

    /**
     * Validates a batch of proposals (F8).
     *
     * @param store   the store.
     * @param inputs  the per-order validation requests.
     * @param enforceRevision whether to enforce the expected revision (false for the automatic path).
     * @return the ids of the validated orders.
     */
    @Transactional
    public List<Long> validate(Store store, List<OrderValidation> inputs, boolean enforceRevision) {
        List<Long> validated = new ArrayList<>();
        for (OrderValidation input : inputs) {
            FabricationOrder order = input.id == null ? null : FabricationOrder.findById(input.id);
            if (order == null || !Hibernate.unproxy(order.store, Store.class).id.equals(store.id)
                    || order.proposalState != FabricationProposalState.PROPOSED) {
                throw new OperatorFailure(409, "STALE_REVISION", "Proposition introuvable ou déjà validée");
            }
            if (enforceRevision && input.revision != null && !Objects.equals(order.version, input.revision)) {
                throw new OperatorFailure(409, "STALE_REVISION", "La proposition a changé, actualisez");
            }
            LocalDate day = input.productionDate != null ? LocalDate.parse(input.productionDate) : order.productionDate;
            LocalDateTime start = input.start != null ? LocalDateTime.parse(input.start) : order.requestedStart;
            LocalDateTime end = input.end != null ? LocalDateTime.parse(input.end) : order.requestedEnd;
            Employee employee = input.employeeId != null ? Employee.findById(input.employeeId) : order.proposedEmployee;
            FabricationStation station = Hibernate.unproxy(order.station, FabricationStation.class);

            if (employee != null && FabricationStationEmployee.count("station = ?1 and employee = ?2", station, employee) == 0) {
                throw new OperatorFailure(409, "EMPLOYEE_NOT_QUALIFIED", "Employé non habilité au poste");
            }
            int dayCapacity = capacity.dayCapacityMinutes(station, day);
            if (dayCapacity > 0) {
                int load = otherLoad(store, station, day, order);
                int planned = order.plannedMinutes == null ? 0 : order.plannedMinutes;
                if (load + planned > dayCapacity) {
                    throw new OperatorFailure(409, "OVER_CAPACITY", "Capacité du poste dépassée");
                }
            }
            if (!windowAdmissible(store, order, station, day, start, end)) {
                throw new OperatorFailure(409, "OUT_OF_WINDOW", "Créneau de fabrication hors fenêtre");
            }

            commit(store, order, station);

            order.proposalState = FabricationProposalState.VALIDATED;
            order.status = HandlingOrderStatus.SCHEDULED;
            order.productionDate = day;
            order.requestedStart = start;
            order.requestedEnd = end;
            order.assignee = employee;
            for (FabricationLine line : FabricationLine.<FabricationLine>list("order", order)) {
                for (FabricationLineUnit lineUnit : FabricationLineUnit.<FabricationLineUnit>list("line", line)) {
                    CateringUnit unit = Hibernate.unproxy(lineUnit.unit, CateringUnit.class);
                    if (unit.state == CateringUnitState.RECEIVED) {
                        states.markPlanned(unit); // F3: increments the unit and the published order revision
                    }
                }
            }
            validated.add(order.id);
        }
        return validated;
    }

    /**
     * Sums the planned minutes of the other non-cancelled station orders of a day (F7).
     *
     * @param store    the store.
     * @param station  the station.
     * @param day      the day.
     * @param excluded the order being validated.
     * @return the other load in minutes.
     */
    private int otherLoad(Store store, FabricationStation station, LocalDate day, FabricationOrder excluded) {
        int load = 0;
        for (FabricationOrder order : FabricationOrder.<FabricationOrder>list(
                "store = ?1 and station = ?2 and productionDate = ?3 and status <> ?4",
                store, station, day, HandlingOrderStatus.CANCELLED)) {
            if (!Objects.equals(order.id, excluded.id) && order.plannedMinutes != null
                    && order.proposalState == FabricationProposalState.VALIDATED) {
                load += order.plannedMinutes;
            }
        }
        return load;
    }

    /**
     * Checks that the chosen day and range are admissible for every unit of the order (F5).
     *
     * @param store   the store.
     * @param order   the order.
     * @param station the station.
     * @param day     the chosen day.
     * @param start   the chosen start.
     * @param end     the chosen end.
     * @return true if admissible.
     */
    private boolean windowAdmissible(Store store, FabricationOrder order, FabricationStation station,
                                     LocalDate day, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.toLocalDate().equals(day) || end.isBefore(start)) {
            return false;
        }
        if (station.opensAt != null && start.toLocalTime().isBefore(station.opensAt)) {
            return false;
        }
        if (station.closesAt != null && end.toLocalTime().isAfter(station.closesAt)) {
            return false;
        }
        LocalDateTime now = DateTimeProvider.now();
        for (FabricationLine line : FabricationLine.<FabricationLine>list("order", order)) {
            for (FabricationLineUnit lineUnit : FabricationLineUnit.<FabricationLineUnit>list("line", line)) {
                CateringUnit unit = Hibernate.unproxy(lineUnit.unit, CateringUnit.class);
                var window = windows.compute(unit.availableAt, null, null,
                        parameters.stagingLeadMinutes(store, station), parameters.maxAdvanceHours(store, line.item),
                        parameters.shelfLifeHours(store, line.item), parameters.consumptionMarginHours(store), station, now);
                if (!window.days().contains(day)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Creates the component commitments of an order (F9).
     *
     * @param store   the store.
     * @param order   the order.
     * @param station the station.
     */
    private void commit(Store store, FabricationOrder order, FabricationStation station) {
        Building building = station.building == null ? null : Hibernate.unproxy(station.building, Building.class);
        if (building == null) {
            return; // no building: components cannot be committed in the demo
        }
        boolean block = "BLOCK".equals(parameters.componentShortagePolicy(store));
        for (FabricationLine line : FabricationLine.<FabricationLine>list("order", order)) {
            FabricationLineUnit anyUnit = FabricationLineUnit.find("line", line).firstResult();
            JsonNode configuration = null;
            if (anyUnit != null) {
                CateringUnit unit = Hibernate.unproxy(anyUnit.unit, CateringUnit.class);
                if (unit.sourcingMode == SourcingMode.SUPPLIED) {
                    continue; // F10.4/F10.5: a mise-en-support line commits its article at reception, not here
                }
                configuration = parse(unit.configuration);
            }
            var recipe = recipes.resolve(line.item, line.variantId, configuration, line.plannedQuantity,
                    parameters.defaultLaborSeconds(store));
            for (var entry : recipe.components().entrySet()) {
                Product component = entry.getKey();
                BigDecimal quantity = entry.getValue();
                var account = stocks.account(store.id, component.id);
                String reference = "FAB:" + order.id + ":" + line.id + ":" + component.ean;
                try {
                    stocks.reserve(account.id, reference, building.id, quantity, order.requestedStart, block);
                } catch (IllegalArgumentException e) {
                    throw new OperatorFailure(409, "COMPONENT_SHORTAGE",
                            "Composant indisponible à la date de l'ordre : " + component.ean);
                }
            }
        }
    }

    /**
     * Cancels every active component commitment of a fabrication order in the current transaction
     * (T2.070): a cancelled unit or order releases the stock it had reserved.
     *
     * @param order the fabrication order.
     */
    @Transactional
    public void cancelOrderCommitments(FabricationOrder order) {
        for (StockCommitment commitment : StockCommitment.<StockCommitment>list(
                "reference like ?1 and status = ?2", "FAB:" + order.id + ":%",
                StockCommitment.CommitmentStatus.ACTIVE)) {
            stocks.cancelCommitment(commitment.id);
        }
    }

    /**
     * Lowers the active component commitments of an order to match its current (reduced) line
     * quantities, in the same transaction (T2.070): each active commitment is lowered to the
     * recipe-recomputed demand when the demand has dropped. A still-committed quantity is never
     * raised here (a raised reference would conflict with the existing commitment).
     *
     * @param order the fabrication order.
     */
    @Transactional
    public void reduceOrderCommitments(FabricationOrder order) {
        Store store = Hibernate.unproxy(order.store, Store.class);
        for (FabricationLine line : FabricationLine.<FabricationLine>list("order", order)) {
            FabricationLineUnit anyUnit = FabricationLineUnit.find("line", line).firstResult();
            JsonNode configuration = anyUnit == null ? null
                    : parse(Hibernate.unproxy(anyUnit.unit, CateringUnit.class).configuration);
            var recipe = recipes.resolve(line.item, line.variantId, configuration, line.plannedQuantity,
                    parameters.defaultLaborSeconds(store));
            for (var entry : recipe.components().entrySet()) {
                String reference = "FAB:" + order.id + ":" + line.id + ":" + entry.getKey().ean;
                StockCommitment commitment = StockCommitment.<StockCommitment>find("reference = ?1 and status = ?2",
                        reference, StockCommitment.CommitmentStatus.ACTIVE).firstResult();
                if (commitment != null && entry.getValue().compareTo(commitment.remainingQuantity) < 0) {
                    commitment.quantity = entry.getValue();
                    commitment.remainingQuantity = entry.getValue();
                }
            }
        }
    }

    /**
     * Parses a configuration JSON, or returns null.
     *
     * @param json the JSON.
     * @return the node, or null.
     */
    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
