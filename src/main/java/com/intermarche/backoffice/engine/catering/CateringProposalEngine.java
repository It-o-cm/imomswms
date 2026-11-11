package com.intermarche.backoffice.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.catering.*;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder.HandlingOrderStatus;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder;
import com.intermarche.backoffice.domain.wms.handling.FabricationOrder.FabricationProposalState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds fabrication-order proposals (F6, F7, F8 first half).
 * <p>
 * For each FABRICATED unit still RECEIVED, with a resolved station and an availability within the
 * proposal horizon, the engine computes the fabrication window (F5), resolves the recipe (F4), picks
 * the latest admissible day whose remaining station capacity suffices (F6.2, F7), groups units into
 * fabrication lines by {@code (station, day, article, variant, configurationKey)} (F6.3), and splits
 * the lines of a station and day into orders of at most {@code maxOrderMinutes} (F6.4). Proposals are
 * recomputed freely: existing PROPOSED orders are rebuilt each run; VALIDATED orders are untouched.
 */
@ApplicationScoped
public class CateringProposalEngine {

    @Inject
    CateringParameters parameters;
    @Inject
    CateringRecipeResolver recipes;
    @Inject
    FabricationWindow windows;
    @Inject
    ObjectMapper mapper;
    @Inject
    CateringValidationService validation;

    /** Reception information extracted from the order payload. */
    private record ReceptionInfo(LocalDate receptionDate, LocalDateTime slotEnd) {
    }

    /** A fabrication line being built, before persistence. */
    private static final class LineDraft {
        final FabricationStation station;
        final LocalDate day;
        final CateringItem item;
        final String variantId;
        final String configurationKey;
        final FabricationWindow.Window window;
        final List<CateringUnit> units = new ArrayList<>();
        BigDecimal plannedQuantity = BigDecimal.ZERO;
        long laborSeconds = 0;
        LocalDateTime earliestAvailable = LocalDateTime.MAX;

        LineDraft(FabricationStation station, LocalDate day, CateringItem item, String variantId,
                  String configurationKey, FabricationWindow.Window window) {
            this.station = station;
            this.day = day;
            this.item = item;
            this.variantId = variantId;
            this.configurationKey = configurationKey;
            this.window = window;
        }

        int minutes() {
            return (int) Math.ceil(laborSeconds / 60.0);
        }
    }

    /**
     * Recomputes the fabrication proposals of a store (F6-F8).
     *
     * @param store the store.
     */
    @Transactional
    public void recompute(Store store) {
        if (store == null || !parameters.enabled(store)) {
            return;
        }
        LocalDateTime now = DateTimeProvider.now();
        clearProposed(store);
        LocalDateTime horizonEnd = now.plusDays(parameters.proposalHorizonDays(store));

        List<CateringUnit> units = CateringUnit.list(
                "order.store = ?1 and state = ?2 and sourcingMode = ?3 and availableAt <= ?4 order by availableAt, id",
                store, CateringUnitState.RECEIVED, SourcingMode.FABRICATED, horizonEnd);

        Map<String, Integer> dayLoad = validatedLoad(store);
        boolean batch = parameters.batchAcrossOrders(store);
        Map<String, LineDraft> drafts = new LinkedHashMap<>();

        for (CateringUnit unit : units) {
            CateringItem item = unit.item;
            if (item == null) {
                continue;
            }
            FabricationStation station = resolveStation(item, store);
            if (station == null) {
                continue; // stays RECEIVED under CATERING_UNREFERENCED (F2, T6)
            }
            ReceptionInfo info = reception(unit);
            var window = windows.compute(unit.availableAt, info.receptionDate(), info.slotEnd(),
                    parameters.stagingLeadMinutes(store, station), parameters.maxAdvanceHours(store, item),
                    parameters.shelfLifeHours(store, item), parameters.consumptionMarginHours(store), station, now);
            if (window.empty()) {
                continue; // FABRICATION_WINDOW (T6)
            }
            var recipe = recipes.resolve(item, unit.variantId, configuration(unit), unit.quantity,
                    parameters.defaultLaborSeconds(store));
            if (!recipe.hasBase() && parameters.recipeRequired(store)) {
                blockUnit(unit); // CATERING_RECIPE_MISSING (T6)
                continue;
            }
            int unitMinutes = (int) Math.ceil(recipe.laborSeconds() / 60.0);
            LocalDate day = pickDay(window.days(), station, dayLoad, unitMinutes);
            if (day == null) {
                continue; // FABRICATION_CAPACITY (T6)
            }
            dayLoad.merge(station.id + "|" + day, unitMinutes, Integer::sum);
            String key = station.id + "|" + day + "|" + item.id + "|" + unit.variantId + "|" + unit.configurationKey
                    + (batch ? "" : "|" + unit.id);
            LineDraft draft = drafts.computeIfAbsent(key,
                    k -> new LineDraft(station, day, item, unit.variantId, unit.configurationKey, window));
            draft.units.add(unit);
            draft.plannedQuantity = draft.plannedQuantity.add(unit.quantity);
            draft.laborSeconds += recipe.laborSeconds();
            if (unit.availableAt.isBefore(draft.earliestAvailable)) {
                draft.earliestAvailable = unit.availableAt;
            }
        }

        buildOrders(store, drafts.values());

        if (parameters.autoValidateProposals(store)) {
            List<CateringValidationService.OrderValidation> inputs = FabricationOrder.<FabricationOrder>list(
                            "store = ?1 and proposalState = ?2", store, FabricationProposalState.PROPOSED)
                    .stream().map(order -> {
                        var validation = new CateringValidationService.OrderValidation();
                        validation.id = order.id;
                        return validation;
                    }).toList();
            if (!inputs.isEmpty()) {
                validation.validate(store, inputs, false);
            }
        }
    }

    /**
     * Groups line drafts of a station and day and splits them into orders (F6.4, F6.5).
     *
     * @param store  the store.
     * @param drafts the line drafts.
     */
    private void buildOrders(Store store, Collection<LineDraft> drafts) {
        Map<String, List<LineDraft>> byStationDay = new LinkedHashMap<>();
        for (LineDraft draft : drafts) {
            byStationDay.computeIfAbsent(draft.station.id + "|" + draft.day, k -> new ArrayList<>()).add(draft);
        }
        for (List<LineDraft> group : byStationDay.values()) {
            group.sort(Comparator.comparing(d -> d.earliestAvailable));
            FabricationStation station = group.get(0).station;
            int maxOrderMinutes = station.maxOrderMinutes != null ? station.maxOrderMinutes : 120;
            List<LineDraft> current = new ArrayList<>();
            int currentMinutes = 0;
            for (LineDraft draft : group) {
                int lineMinutes = draft.minutes();
                if (!current.isEmpty() && currentMinutes + lineMinutes > maxOrderMinutes) {
                    persistOrder(store, current);
                    current = new ArrayList<>();
                    currentMinutes = 0;
                }
                current.add(draft);
                currentMinutes += lineMinutes;
            }
            if (!current.isEmpty()) {
                persistOrder(store, current);
            }
        }
    }

    /**
     * Persists a fabrication order and its lines (F6.5).
     *
     * @param store the store.
     * @param lines the line drafts of the order.
     */
    private void persistOrder(Store store, List<LineDraft> lines) {
        LineDraft first = lines.get(0);
        FabricationOrder order = new FabricationOrder();
        order.store = store;
        order.station = first.station;
        order.productionDate = first.day;
        order.proposalState = FabricationProposalState.PROPOSED;
        order.status = HandlingOrderStatus.OPEN;
        order.code = HandlingOrderSequence.getNextCode(store, order.getPrefix());
        long totalLabor = lines.stream().mapToLong(d -> d.laborSeconds).sum();
        order.plannedMinutes = (int) Math.ceil(totalLabor / 60.0);
        order.proposedEmployee = qualifiedEmployee(first.station);
        LocalDateTime start = dayRangeStart(first);
        order.requestedStart = start;
        order.requestedEnd = start.plusMinutes(order.plannedMinutes);
        order.persist();
        for (LineDraft draft : lines) {
            FabricationLine line = new FabricationLine();
            line.order = order;
            line.item = draft.item;
            line.variantId = draft.variantId;
            line.configurationKey = draft.configurationKey;
            line.plannedQuantity = draft.plannedQuantity;
            line.state = FabricationLine.FabricationLineState.PLANNED;
            line.persist();
            for (CateringUnit unit : draft.units) {
                FabricationLineUnit lineUnit = new FabricationLineUnit();
                lineUnit.line = line;
                lineUnit.unit = unit;
                lineUnit.quantity = unit.quantity;
                lineUnit.persist();
            }
        }
    }

    /**
     * Returns the admissible range start for the order day.
     *
     * @param draft the first line draft.
     * @return the range start, or the day opening.
     */
    private LocalDateTime dayRangeStart(LineDraft draft) {
        return draft.window.ranges().stream()
                .filter(r -> r.start().toLocalDate().equals(draft.day))
                .map(FabricationWindow.Range::start)
                .findFirst()
                .orElse(draft.day.atStartOfDay());
    }

    /**
     * Picks the latest admissible day whose remaining capacity suffices (F6.2, F7).
     *
     * @param days        the admissible days, earliest first.
     * @param station     the station.
     * @param dayLoad     the accumulated load per station-day.
     * @param unitMinutes the minutes to add.
     * @return the chosen day, or null when no day has capacity.
     */
    private LocalDate pickDay(List<LocalDate> days, FabricationStation station, Map<String, Integer> dayLoad, int unitMinutes) {
        int capacity = station.dailyMinutes != null ? station.dailyMinutes : 0;
        for (int i = days.size() - 1; i >= 0; i--) {
            LocalDate day = days.get(i);
            int load = dayLoad.getOrDefault(station.id + "|" + day, 0);
            if (capacity == 0 || load + unitMinutes <= capacity) {
                return day;
            }
        }
        return null;
    }

    /**
     * Returns the load per station-day induced by the existing VALIDATED orders (F7).
     *
     * @param store the store.
     * @return the load map.
     */
    private Map<String, Integer> validatedLoad(Store store) {
        Map<String, Integer> load = new HashMap<>();
        for (FabricationOrder order : FabricationOrder.<FabricationOrder>list(
                "store = ?1 and proposalState = ?2 and status <> ?3",
                store, FabricationProposalState.VALIDATED, HandlingOrderStatus.CANCELLED)) {
            if (order.station != null && order.productionDate != null && order.plannedMinutes != null) {
                load.merge(order.station.id + "|" + order.productionDate, order.plannedMinutes, Integer::sum);
            }
        }
        return load;
    }

    /**
     * Removes the existing PROPOSED orders of a store and their lines.
     *
     * @param store the store.
     */
    private void clearProposed(Store store) {
        for (FabricationOrder order : FabricationOrder.<FabricationOrder>list(
                "store = ?1 and proposalState = ?2", store, FabricationProposalState.PROPOSED)) {
            for (FabricationLine line : FabricationLine.<FabricationLine>list("order", order)) {
                FabricationLineUnit.delete("line", line);
                line.delete();
            }
            order.delete();
        }
    }

    /**
     * Resolves the station of an article for a store (store override then datasheet).
     *
     * @param item  the datasheet.
     * @param store the store.
     * @return the station, or null.
     */
    private FabricationStation resolveStation(CateringItem item, Store store) {
        CateringItemStore override = CateringItemStore.find("item = ?1 and store = ?2 and active = true", item, store).firstResult();
        if (override != null && override.station != null) {
            return override.station;
        }
        return item.station;
    }

    /**
     * Returns a qualified employee of a station, or null.
     *
     * @param station the station.
     * @return the employee, or null.
     */
    private com.intermarche.backoffice.domain.store.Employee qualifiedEmployee(FabricationStation station) {
        FabricationStationEmployee qualification = FabricationStationEmployee.find("station", station).firstResult();
        return qualification == null ? null : qualification.employee;
    }

    /**
     * Blocks a unit whose recipe is required but missing (F4.5).
     *
     * @param unit the unit.
     */
    private void blockUnit(CateringUnit unit) {
        unit.state = CateringUnitState.BLOCKED;
        unit.revision++;
    }

    /**
     * Parses the configuration of a unit.
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
     * Extracts the reception date and slot end of a unit from its order payload.
     *
     * @param unit the unit.
     * @return the reception information.
     */
    private ReceptionInfo reception(CateringUnit unit) {
        try {
            Order order = unit.order;
            if (order.specification == null) {
                return new ReceptionInfo(null, null);
            }
            JsonNode root = mapper.readTree(order.specification);
            LocalDate receptionDate = null;
            JsonNode date = root.path("catering").path("reception").path("date");
            if (date.isTextual() && !date.asText().isBlank()) {
                receptionDate = LocalDate.parse(date.asText());
            }
            LocalDateTime slotEnd = null;
            JsonNode end = root.path("fulfillment").path("slot").path("end");
            if (end.isTextual() && !end.asText().isBlank()) {
                slotEnd = LocalDateTime.parse(end.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }
            return new ReceptionInfo(receptionDate, slotEnd);
        } catch (Exception e) {
            return new ReceptionInfo(null, null);
        }
    }
}
