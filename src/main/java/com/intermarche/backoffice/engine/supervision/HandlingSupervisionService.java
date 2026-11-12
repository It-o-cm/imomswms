package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.workforce.EmployeePosition;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.engine.storeops.GlobalPlanningService;
import com.intermarche.backoffice.engine.storeops.PlanningAssignmentService;
import com.intermarche.backoffice.engine.supervision.SupervisionModel.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handling supervision for managers: detects the situations that need a decision and computes valid proposals.
 * <p>
 * It reuses the existing engines: {@link PlanningAssignmentService} for staffing rules, the directed transits and
 * declared positions of the transit control, the stock ledger and expected supplies, and the buffer capacity rules.
 * It never writes: changes go through {@link SupervisionActionService} after an explicit validation.
 */
@ApplicationScoped
public class HandlingSupervisionService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final List<String> ASSIGNMENT_PRIORITY = List.of("INACTIVE", "ROLE", "ABSENCE", "HANDLING", "TASK", "TRANSIT", "OUT_OF_SHIFT", "NO_SHIFT");

    @Inject EntityManager em;
    @Inject PlanningAssignmentService assignments;

    /** Mutable anomaly under construction. */
    private static final class Draft {
        String id;
        Type type;
        Certainty certainty;
        Severity severity;
        String title;
        String explanation;
        String impact;
        LocalDateTime deadline;
        String deadlineLabel;
        BaseHandlingOrder order;
        final List<OrderRef> orders = new ArrayList<>();
        final List<OrderRef> consequences = new ArrayList<>();
        final LinkedHashMap<Long, BuildingRef> buildings = new LinkedHashMap<>();
        EmployeeRef employee;
        final List<ProductLine> products = new ArrayList<>();
        final List<BufferRef> buffers = new ArrayList<>();
        final List<Proposal> proposals = new ArrayList<>();
        final List<Action> actions = new ArrayList<>();
        final Map<String, Object> context = new LinkedHashMap<>();
        boolean critical;
    }

    /** Result of a position and transit check for one employee and one order. */
    record TransitCheck(String status, Long origin, Long target, Integer minutes, LocalDateTime freeAt,
                        LocalDateTime arrival, String explanation, boolean real) {
        /**
         * Tells whether the employee can be on site in time.
         *
         * @return true for OK
         */
        boolean feasible() {
            return "OK".equals(status);
        }
    }

    /**
     * Result of an analysis together with the data it was computed on, so that the activity supervision of the same
     * package composes the handling diagnostics instead of recomputing them.
     *
     * @param report the handling report, identical to the one of the handling view
     * @param snapshot the loaded data, including the availability state after the handling needs
     */
    record Analysis(Report report, SupervisionSnapshot snapshot,OperationalDiagnostics.Result operationalCases) {}

    /**
     * Analyzes the open handling orders of a store for a period.
     *
     * @param store the store, already restricted to the caller's scope
     * @param start period start
     * @param end period end
     * @return the supervision report
     */
    public Report analyze(Store store, LocalDateTime start, LocalDateTime end) {
        return analyze(store, start, end, Set.of(), Set.of()).report();
    }

    /**
     * Analyzes the open handling orders of a store for a period, also loading the stock of locations and products that
     * the caller evaluates afterwards on the same availability state. The extra data never changes the handling report.
     *
     * @param store the store, already restricted to the caller's scope
     * @param start period start
     * @param end period end
     * @param extraLocations additional stock locations
     * @param extraProducts additional products
     * @return the report and its data
     */
    Analysis analyze(Store store, LocalDateTime start, LocalDateTime end, Set<Long> extraLocations, Set<Long> extraProducts) {
        if (start == null || end == null || !end.isAfter(start) || end.isAfter(start.plusDays(31)))
            throw new IllegalArgumentException("Période de supervision invalide (31 jours maximum).");
        LocalDateTime now = DateTimeProvider.now();
        var s = SupervisionSnapshot.load(em, assignments, store, now, start, end, extraLocations, extraProducts);
        var drafts = new ArrayList<Draft>();
        var late = new LinkedHashMap<Long, Draft>();
        for (var o : s.orders) late(s, o).ifPresent(d -> { late.put(o.id, d); drafts.add(d); });
        for (var o : s.orders) dependency(s, o, late).ifPresent(drafts::add);
        for (var o : s.orders) unassigned(s, o, late).ifPresent(drafts::add);
        drafts.addAll(assignmentConflicts(s, late));
        if (s.transitControl) for (var o : s.orders) transit(s, o, late).ifPresent(drafts::add);
        drafts.addAll(stock(s, late));
        drafts.addAll(buffers(s));
        var caseDiagnostics=OperationalDiagnostics.load(store);
        var combined=new ArrayList<Anomaly>();
        for(var c:caseDiagnostics.cases()){var order=c.handlingOrderId()==null?null:s.orders.stream().filter(o->o.id.equals(c.handlingOrderId())).findFirst().orElse(null);if(order==null)continue;var reference=ref(s,order,"SUBJECT");var context=new LinkedHashMap<String,Object>(c.context());context.put("sourceCaseId",c.id());context.put("openedAt",c.since());combined.add(new Anomaly(c.id(),Type.FIELD_OPERATION,c.blocking()?Certainty.BLOCKING:Certainty.RISK,c.blocking()?Severity.HIGH:Severity.MEDIUM,c.title(),c.detail(),"Décision attendue dans le dossier source.",null,null,reference,List.of(reference),List.of(),reference.buildings(),reference.assignee(),List.of(),List.of(),List.of(),List.of(new Action(c.action(),c.actionLabel(),true)),context));}
        combined.addAll(drafts.stream().map(d -> finish(s, d)).toList());
        var anomalies = combined.stream()
                .sorted(Comparator.comparing(Anomaly::severity).thenComparing(Anomaly::certainty)
                        .thenComparing(a -> a.deadline() == null ? LocalDateTime.MAX : a.deadline()).thenComparing(Anomaly::id))
                .toList();
        var counters = new LinkedHashMap<String, Long>();
        for (var c : Certainty.values()) counters.put(c.name(), anomalies.stream().filter(a -> a.certainty() == c).count());
        for (var v : Severity.values()) counters.put(v.name(), anomalies.stream().filter(a -> a.severity() == v).count());
        for (var t : Type.values()) counters.put(t.name(), anomalies.stream().filter(a -> a.type() == t).count());
        var notes = new ArrayList<String>();
        if(caseDiagnostics.limited())notes.add("Dossiers opérationnels : lecture limitée à 200 dossiers ouverts par famille.");
        if (s.limited) notes.add("Plus de " + SupervisionSnapshot.ORDER_LIMIT + " ordres ouverts : seuls les premiers sont analysés. Réduisez la période.");
        if (!s.transitControl) notes.add("Aucun trajet entre bâtiments n’est configuré : le contrôle des positions et des transits est inactif, comme au démarrage des manutentions.");
        notes.add("Les capacités des buffers sont l’occupation actuelle : aucune libération future n’est supposée.");
        var filters = new Filters(s.buildings.values().stream().map(this::building).sorted(Comparator.comparing(BuildingRef::code)).toList(),
                s.employees.values().stream().filter(e -> e.role != Employee.EmployeeRole.ADMIN).map(this::employee)
                        .sorted(Comparator.comparing(EmployeeRef::name)).toList(),
                List.of("PRODUCT_COLLECTING", "ZONE_COLLECTING", "PACKING", "GROUPING", "REPLENISHMENT", "STOCK_PROTECTION", "CANCELLATION_DISPOSITION", "INVENTORY"));
        return new Analysis(new Report(store.id, store.code, now, start, end, s.orders.size(), s.limited, s.transitControl, counters, anomalies, filters, notes), s,caseDiagnostics);
    }

    // ------------------------------------------------------------------ late orders

    /**
     * Detects an order still open after the end of its planned slot, with the rule of the late-order alert.
     *
     * @param s snapshot
     * @param o order
     * @return the anomaly, if late
     */
    private Optional<Draft> late(SupervisionSnapshot s, BaseHandlingOrder o) {
        if (!o.requestedEnd.isBefore(s.now) || s.executionsDone(o)) return Optional.empty();
        var d = draft(s, "LATE:" + o.id, Type.LATE, Certainty.BLOCKING, o);
        long minutes = Duration.between(o.requestedEnd, s.now).toMinutes();
        d.title = "Ordre non terminé après son créneau";
        var text = new StringBuilder(label(o) + " " + o.code + " devait être terminé à " + time(s, o.requestedEnd)
                + " (retard de " + duration(minutes) + "). Statut : " + statusLabel(o.status) + ".");
        String stage = stage(s, o);
        if (stage != null) text.append(" Étape : ").append(stage).append('.');
        if (o.assignee == null) text.append(" Aucun employé n’est affecté.");
        if (o.status == BaseHandlingOrder.HandlingOrderStatus.FAILED) text.append(" L’exécution a échoué.");
        d.explanation = text.toString();
        d.deadline = o.requestedEnd;
        d.deadlineLabel = "Fin planifiée dépassée";
        if (o instanceof PackingOrder p && p.deadLine != null) {
            d.deadline = p.deadLine;
            d.deadlineLabel = p.deadLine.isBefore(s.now) ? "Échéance client dépassée" : "Échéance client";
            d.critical = p.deadLine.isBefore(s.now);
        }
        d.critical |= minutes > 60 || o.status == BaseHandlingOrder.HandlingOrderStatus.FAILED;
        d.actions.add(new Action("OPEN_ORDER", "Examiner l’ordre", true));
        if (!s.started(o) && o.status != BaseHandlingOrder.HandlingOrderStatus.FAILED) {
            LocalDateTime slot = nextQuarter(s.now);
            d.proposals.addAll(staffing(s, o, slot, slot.plus(Duration.between(o.requestedStart, o.requestedEnd)), "RESCHEDULE", false));
            if (!d.proposals.isEmpty()) d.actions.add(new Action("REVIEW_PROPOSALS", "Réaffecter ou replanifier", false));
        }
        d.actions.add(new Action("COMPARE_SOLUTIONS", "Comparer des solutions de planning", false));
        planningPeriod(s, d, o);
        return Optional.of(d);
    }

    // ------------------------------------------------------------------ dependencies

    /**
     * Detects an operation whose prerequisite (truck unloading before shelf replenishment, collecting before packing
     * or grouping) prevents or threatens its start. Prerequisites that are themselves late are folded into their own card.
     *
     * @param s snapshot
     * @param o order
     * @param late late cards by order
     * @return the anomaly, if any
     */
    private Optional<Draft> dependency(SupervisionSnapshot s, BaseHandlingOrder o, Map<Long, Draft> late) {
        var prerequisites = new ArrayList<BaseHandlingOrder>();
        Long predecessor = s.predecessorOf.get(o.id);
        if (predecessor != null) prerequisites.add(s.known.get(predecessor));
        for (Long id : s.prerequisitesOf.getOrDefault(o.id, List.of())) prerequisites.add(s.known.get(id));
        var pending = prerequisites.stream().filter(p -> p != null && p.status != BaseHandlingOrder.HandlingOrderStatus.DONE).toList();
        var problems = new ArrayList<String>();
        var reported = new ArrayList<BaseHandlingOrder>();
        Certainty certainty = null;
        for (var p : pending) {
            boolean closed = p.status == BaseHandlingOrder.HandlingOrderStatus.CANCELLED || p.status == BaseHandlingOrder.HandlingOrderStatus.FAILED;
            boolean startReached = !o.requestedStart.isAfter(s.now);
            boolean endsAfterStart = p.requestedEnd != null && p.requestedEnd.isAfter(o.requestedStart);
            if (!closed && late.containsKey(p.id) && !late.containsKey(o.id)) {
                var card = late.get(p.id);
                card.consequences.add(ref(s, o, "CONSEQUENCE"));
                card.critical = true;
                card.impact = "Bloque " + card.consequences.stream().map(OrderRef::code).collect(Collectors.joining(", "))
                        + " (" + card.consequences.stream().map(c -> label(s.known.get(c.id())) + " prévu à " + time(s, c.start())).collect(Collectors.joining(" ; ")) + ").";
                continue;
            }
            if (!closed && !startReached && !endsAfterStart) continue;
            reported.add(p);
            if (closed) {
                problems.add("L’opération préalable " + p.code + " est " + statusLabel(p.status).toLowerCase(Locale.ROOT) + " : " + o.code + " ne peut pas démarrer sans décision.");
                certainty = Certainty.BLOCKING;
            } else if (startReached) {
                problems.add(o.code + " aurait dû commencer à " + time(s, o.requestedStart) + " mais " + p.code + " (" + label(p) + ") n’est pas terminé (" + statusLabel(p.status).toLowerCase(Locale.ROOT) + ").");
                certainty = Certainty.BLOCKING;
            } else {
                problems.add(p.code + " (" + label(p) + ") finit à " + time(s, p.requestedEnd) + ", après le début prévu de " + o.code + " à " + time(s, o.requestedStart) + ".");
                if (certainty == null) certainty = Certainty.RISK;
            }
        }
        // Two-stage shelf replenishment: the shelf put-away can start only once the warehouse picking is complete.
        if (o instanceof ReplenishmentOrder r && !(o instanceof StockProtectionOrder) && r.sourceBuilding != null && !late.containsKey(o.id)
                && o.status == BaseHandlingOrder.HandlingOrderStatus.PROCESSING && !s.pickingDone(o) && !shortNow(s, o)) {
            var middle = o.requestedStart.plus(Duration.between(o.requestedStart, o.requestedEnd).dividedBy(2));
            if (!s.now.isBefore(middle)) {
                int[] progress = s.pickingProgress.getOrDefault(o.id, new int[2]);
                problems.add("Mise en rayon en attente : le picking dans l’entrepôt n’est pas terminé (" + progress[0] + "/" + progress[1]
                        + " lignes prélevées) alors que la moitié du créneau est écoulée. Le rangement en magasin ne peut commencer qu’après un picking complet.");
                if (certainty == null) certainty = Certainty.RISK;
            }
        }
        if (problems.isEmpty()) return Optional.empty();
        if (late.containsKey(o.id)) {
            // A late order waiting for its prerequisite is one situation: the cause completes the late card.
            var card = late.get(o.id);
            card.explanation += " Cause : " + String.join(" ", problems);
            for (var p : reported) { card.orders.add(ref(s, p, "PREREQUISITE")); addBuildings(s, card, p); }
            if (!reported.isEmpty()) {
                card.context.put("prerequisiteId", reported.get(0).id);
                card.context.put("prerequisiteKind", kind(reported.get(0)));
                card.actions.add(1, new Action("OPEN_PREREQUISITE", "Voir l’opération préalable", false));
            }
            return Optional.empty();
        }
        var d = draft(s, "DEPENDENCY:" + o.id, Type.DEPENDENCY, certainty, o);
        d.title = reported.isEmpty() ? "Étape de réachalandage en attente" : "Opération préalable non terminée";
        d.explanation = String.join(" ", problems);
        d.deadline = o.requestedStart.isAfter(s.now) ? o.requestedStart : o.requestedEnd;
        d.deadlineLabel = o.requestedStart.isAfter(s.now) ? "Début prévu" : "Fin prévue";
        for (var p : reported) { d.orders.add(ref(s, p, "PREREQUISITE")); addBuildings(s, d, p); }
        if (!reported.isEmpty()) {
            d.actions.add(new Action("OPEN_PREREQUISITE", "Voir l’opération préalable", true));
            d.context.put("prerequisiteId", reported.get(0).id);
            d.context.put("prerequisiteKind", kind(reported.get(0)));
        }
        d.actions.add(new Action("OPEN_ORDER", "Examiner l’ordre", reported.isEmpty()));
        d.actions.add(new Action("COMPARE_SOLUTIONS", "Comparer des solutions de planning", false));
        planningPeriod(s, d, o);
        return Optional.of(d);
    }

    /**
     * Tells whether the warehouse picking of an order already lacks physical stock: the stock card then explains why the
     * put-away waits, without a second card for the same cause.
     *
     * @param s snapshot
     * @param o order
     * @return true when a picking line exceeds the current balance of its location
     */
    private static boolean shortNow(SupervisionSnapshot s, BaseHandlingOrder o) {
        return s.needs.getOrDefault(o.id, List.of()).stream().anyMatch(n -> "PICKING".equals(n.stage()) && n.locationId() != null
                && s.physicalByLocation.getOrDefault(n.locationId(), BigDecimal.ZERO).compareTo(n.quantity()) < 0);
    }

    // ------------------------------------------------------------------ staffing

    /**
     * Detects an open order without employee.
     *
     * @param s snapshot
     * @param o order
     * @param late late cards by order, which already cover overdue unassigned orders
     * @return the anomaly, if any
     */
    private Optional<Draft> unassigned(SupervisionSnapshot s, BaseHandlingOrder o, Map<Long, Draft> late) {
        if (o.assignee != null || late.containsKey(o.id) || s.started(o)) return Optional.empty();
        if (o.status != BaseHandlingOrder.HandlingOrderStatus.OPEN && o.status != BaseHandlingOrder.HandlingOrderStatus.SCHEDULED) return Optional.empty();
        boolean begun = !o.requestedStart.isAfter(s.now);
        var d = draft(s, "UNASSIGNED:" + o.id, Type.UNASSIGNED, begun ? Certainty.BLOCKING : Certainty.RISK, o);
        d.title = "Ordre sans employé";
        LocalDateTime slotStart = begun ? nextQuarter(s.now) : o.requestedStart;
        LocalDateTime slotEnd = slotStart.plus(Duration.between(o.requestedStart, o.requestedEnd));
        d.proposals.addAll(staffing(s, o, slotStart, slotEnd, begun ? "RESCHEDULE" : "ASSIGN", s.transitControl));
        d.explanation = (begun ? "Le créneau a commencé à " + time(s, o.requestedStart) + " sans employé affecté : l’ordre n’est pas exécuté."
                : "Aucun employé n’est affecté : l’ordre ne sera pas exécuté à " + time(s, o.requestedStart) + " sans décision.")
                + (d.proposals.isEmpty() ? " Aucun employé présent et disponible sur le créneau : comparez des solutions de planning."
                : " " + d.proposals.size() + " affectation(s) valide(s) proposée(s).");
        d.deadline = o.requestedStart;
        d.deadlineLabel = begun ? "Début dépassé" : "Début prévu";
        d.actions.add(new Action("REVIEW_PROPOSALS", "Choisir un employé", !d.proposals.isEmpty()));
        d.actions.add(new Action("COMPARE_SOLUTIONS", "Comparer des solutions de planning", d.proposals.isEmpty()));
        d.actions.add(new Action("OPEN_ORDER", "Examiner l’ordre", false));
        planningPeriod(s, d, o);
        return Optional.of(d);
    }

    /**
     * Detects assignments incompatible with hours, absences, skills or other work, grouped by cause.
     *
     * @param s snapshot
     * @param late late cards by order
     * @return the anomalies
     */
    private List<Draft> assignmentConflicts(SupervisionSnapshot s, Map<Long, Draft> late) {
        var groups = new LinkedHashMap<String, Draft>();
        var reasonsByGroup = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var o : s.orders) {
            if (o.assignee == null || late.containsKey(o.id) || s.started(o) || !o.requestedEnd.isAfter(s.now)) continue;
            if (o.status != BaseHandlingOrder.HandlingOrderStatus.OPEN && o.status != BaseHandlingOrder.HandlingOrderStatus.SCHEDULED) continue;
            var person = Hibernate.unproxy(o.assignee, Employee.class);
            List<PlanningAssignmentService.Reason> reasons;
            try { reasons = assignments.reasons(s.planning, person, o.requestedStart, o.requestedEnd, null, o.id, "HANDLING", null); }
            catch (IllegalArgumentException e) { continue; }
            if (reasons.isEmpty()) continue;
            var main = reasons.stream().min(Comparator.comparingInt(r -> ASSIGNMENT_PRIORITY.indexOf(r.code()))).orElseThrow();
            String key = switch (main.code()) {
                case "ABSENCE", "TASK", "TRANSIT" -> main.code().toLowerCase(Locale.ROOT) + "-" + main.entryId();
                case "HANDLING" -> "overlap-" + person.id + "-" + Math.min(o.id, main.orderId());
                case "NO_SHIFT", "OUT_OF_SHIFT" -> main.code().toLowerCase(Locale.ROOT) + "-" + person.id + "-" + o.requestedStart.toLocalDate();
                default -> main.code().toLowerCase(Locale.ROOT) + "-" + person.id;
            };
            boolean missing = reasons.stream().allMatch(r -> "NO_SHIFT".equals(r.code()));
            var d = groups.get(key);
            if (d == null) {
                d = draft(s, "ASSIGNMENT:" + key, Type.ASSIGNMENT, missing ? Certainty.MISSING_DATA
                        : !o.requestedStart.isAfter(s.now) ? Certainty.BLOCKING : Certainty.RISK, o);
                d.title = missing ? "Horaires de l’employé non renseignés" : "Affectation incompatible";
                d.deadline = o.requestedStart;
                d.deadlineLabel = "Début prévu";
                groups.put(key, d);
                reasonsByGroup.put(key, new LinkedHashSet<>());
                planningPeriod(s, d, o);
            } else {
                d.orders.add(ref(s, o, "CONCERNED"));
                addBuildings(s, d, o);
                if (!missing && d.certainty == Certainty.MISSING_DATA) { d.certainty = Certainty.RISK; d.title = "Affectation incompatible"; }
                if (!o.requestedStart.isAfter(s.now) && !missing) d.certainty = Certainty.BLOCKING;
            }
            for (var r : reasons) reasonsByGroup.get(key).add(o.code + " : " + r.message() + (r.start() != null && !"NO_SHIFT".equals(r.code()) ? " (" + time(s, r.start()) + "–" + time(s, r.end()) + ")" : ""));
            if (d.proposals.size() < 6) d.proposals.addAll(staffing(s, o, o.requestedStart, o.requestedEnd, "ASSIGN", s.transitControl).stream().limit(2).toList());
        }
        for (var e : groups.entrySet()) {
            var d = e.getValue();
            String who = d.employee == null ? "L’employé" : d.employee.name();
            d.explanation = (d.certainty == Certainty.MISSING_DATA
                    ? "Aucun horaire n’est saisi pour " + who + " ce jour-là : la compatibilité de l’affectation ne peut pas être vérifiée. "
                    : who + " ne peut pas réaliser " + (d.orders.size() > 1 ? "ces " + d.orders.size() + " ordres" : "cet ordre") + " tel que planifié. ")
                    + String.join(" ; ", reasonsByGroup.get(e.getKey())) + ".";
            d.actions.add(new Action("REVIEW_PROPOSALS", "Corriger l’affectation", !d.proposals.isEmpty()));
            d.actions.add(new Action("COMPARE_SOLUTIONS", "Comparer des solutions de planning", d.proposals.isEmpty() && d.certainty != Certainty.MISSING_DATA));
            d.actions.add(new Action("OPEN_PLANNING", "Ouvrir le planning de l’équipe", d.proposals.isEmpty() && d.certainty == Certainty.MISSING_DATA));
            d.actions.add(new Action("OPEN_ORDER", "Examiner l’ordre", false));
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * Computes valid staffing proposals for a slot with the assignment engine, optionally keeping only employees
     * able to reach the operation's building in time.
     *
     * @param s snapshot
     * @param o order
     * @param start proposed slot start
     * @param end proposed slot end
     * @param kind ASSIGN or RESCHEDULE
     * @param transitAware filter on position and transit feasibility
     * @return at most three proposals
     */
    List<Proposal> staffing(SupervisionSnapshot s, BaseHandlingOrder o, LocalDateTime start, LocalDateTime end, String kind, boolean transitAware) {
        List<PlanningAssignmentService.Candidate> candidates;
        try { candidates = assignments.candidates(s.planning, start, end, null, o.id, "HANDLING", null); }
        catch (IllegalArgumentException e) { return List.of(); }
        Long current = o.assignee == null ? null : Hibernate.unproxy(o.assignee, Employee.class).id;
        var result = new ArrayList<Proposal>();
        for (var c : candidates) {
            if ("ASSIGN".equals(kind) && Objects.equals(c.employeeId(), current)) continue;
            String details = "Présent(e) sur tout le créneau, sans absence ni tâche concurrente · " + c.bookedMinutes() + " min déjà affectées ce jour-là.";
            if (transitAware) {
                var check = transitCheck(s, o, c.employeeId(), start);
                if (!check.feasible() && !"UNKNOWN_ORIGIN".equals(check.status())) continue;
                details += " " + check.explanation();
            }
            boolean moved = !start.equals(o.requestedStart);
            String label = (moved ? "Replanifier à " + time(s, start) + " avec " : "Affecter à ") + c.name();
            result.add(new Proposal(kind + ":" + o.id + ":" + c.employeeId() + ":" + start, moved ? "RESCHEDULE" : "ASSIGN", label, details,
                    o.id, o.code, c.employeeId(), c.name(), null, null, start, end, basis(o)));
            if (result.size() == 3) break;
        }
        return result;
    }

    // ------------------------------------------------------------------ positions and transits

    /**
     * Detects an employee whose position, or the transit from the previous operation, is incompatible with the
     * operation, plus missing or too long internal routes of a two-building replenishment.
     *
     * @param s snapshot
     * @param o order
     * @param late late cards by order
     * @return the anomaly, if any
     */
    private Optional<Draft> transit(SupervisionSnapshot s, BaseHandlingOrder o, Map<Long, Draft> late) {
        if (late.containsKey(o.id) || !o.requestedEnd.isAfter(s.now) || s.started(o)) return Optional.empty();
        if (o.status != BaseHandlingOrder.HandlingOrderStatus.OPEN && o.status != BaseHandlingOrder.HandlingOrderStatus.SCHEDULED) return Optional.empty();
        var problems = new ArrayList<String>();
        Certainty certainty = null;
        TransitCheck check = null;
        if (o.assignee != null) {
            Long assignee = Hibernate.unproxy(o.assignee, Employee.class).id;
            check = transitCheck(s, o, assignee, o.requestedStart);
            // Without hours that day the position is unknown for the same reason: the assignment card already says so.
            boolean noHours = "UNKNOWN_ORIGIN".equals(check.status()) && shift(s, assignee, o.requestedStart) == null;
            if (!check.feasible() && !noHours) {
                problems.add(check.explanation());
                certainty = switch (check.status()) {
                    case "UNKNOWN_ORIGIN", "UNKNOWN_TARGET" -> Certainty.MISSING_DATA;
                    default -> check.real() && !o.requestedStart.isAfter(s.now.plusMinutes(Math.max(0, check.minutes() == null ? 0 : check.minutes())))
                            ? Certainty.BLOCKING : Certainty.RISK;
                };
            }
        }
        if (o instanceof ReplenishmentOrder r && !(o instanceof StockProtectionOrder) && r.sourceBuilding != null && r.destinationBuilding != null) {
            Integer internal = s.travel(SupervisionSnapshot.id(r.sourceBuilding), SupervisionSnapshot.id(r.destinationBuilding));
            long available = Duration.between(o.requestedStart, o.requestedEnd).toMinutes();
            if (internal != null && internal < 0) {
                problems.add("Aucun trajet " + r.sourceBuilding.buildingCode + " → " + r.destinationBuilding.buildingCode
                        + " n’est configuré : le picking et la mise en rayon ne peuvent pas être enchaînés.");
                certainty = certainty == Certainty.BLOCKING ? certainty : Certainty.RISK;
            } else if (internal != null && internal > available) {
                problems.add("Le créneau (" + available + " min) est plus court que le trajet " + r.sourceBuilding.buildingCode + " → "
                        + r.destinationBuilding.buildingCode + " (" + internal + " min) entre le picking et la mise en rayon.");
                certainty = certainty == Certainty.BLOCKING ? certainty : Certainty.RISK;
            }
        }
        if (problems.isEmpty()) return Optional.empty();
        var d = draft(s, "TRANSIT:" + o.id, Type.TRANSIT, certainty, o);
        d.title = certainty == Certainty.MISSING_DATA ? "Position ou bâtiment inconnu" : "Position ou transit incompatible";
        d.explanation = String.join(" ", problems);
        d.deadline = o.requestedStart;
        d.deadlineLabel = "Début prévu";
        if (check != null) {
            if (check.origin() != null && s.buildings.containsKey(check.origin())) d.buildings.putIfAbsent(check.origin(), building(s.buildings.get(check.origin())));
            d.context.put("originBuildingId", check.origin());
            d.context.put("targetBuildingId", check.target());
            if (check.arrival() != null && check.arrival().isAfter(o.requestedStart))
                d.impact = "Démarrage possible au plus tôt à " + time(s, check.arrival()) + " au lieu de " + time(s, o.requestedStart) + ".";
        }
        d.proposals.addAll(staffing(s, o, o.requestedStart, o.requestedEnd, "ASSIGN", true));
        d.actions.add(new Action("OPEN_POSITION", "Examiner le transit", o.assignee != null));
        d.actions.add(new Action("REVIEW_PROPOSALS", "Proposer un employé sur place", o.assignee == null && !d.proposals.isEmpty()));
        d.actions.add(new Action("COMPARE_SOLUTIONS", "Comparer des solutions de planning", o.assignee == null && d.proposals.isEmpty()));
        d.actions.add(new Action("OPEN_ORDER", "Examiner l’ordre", false));
        planningPeriod(s, d, o);
        return Optional.of(d);
    }

    /**
     * Checks whether an employee can be in the building of an operation at a given start, from the previous planned
     * operation, the current declared position (for imminent work) or the building of the covering presence.
     *
     * @param s snapshot
     * @param o order
     * @param employeeId employee
     * @param start operation start
     * @return the check result
     */
    TransitCheck transitCheck(SupervisionSnapshot s, BaseHandlingOrder o, Long employeeId, LocalDateTime start) {
        var targetBuilding = requiredBuilding(s, o);
        Long target = SupervisionSnapshot.id(targetBuilding);
        var person = s.employees.get(employeeId);
        String name = person == null ? "L’employé" : person.firstName + " " + person.lastName;
        if (target == null)
            return new TransitCheck("UNKNOWN_TARGET", null, null, null, null, null, "Le bâtiment de " + o.code + " n’est pas renseigné : la position de " + name + " ne peut pas être vérifiée.", false);
        Long origin = null;
        LocalDateTime freeAt = null;
        String from = null;
        boolean real = false;
        var day = start.toLocalDate().atStartOfDay();
        for (var other : s.planning.orders()) {
            if (other.id.equals(o.id) || !Objects.equals(Hibernate.unproxy(other.assignee, Employee.class).id, employeeId)) continue;
            if (other.requestedEnd.isAfter(start) || other.requestedEnd.isBefore(day)) continue;
            if (freeAt == null || other.requestedEnd.isAfter(freeAt)) {
                freeAt = other.requestedEnd;
                origin = SupervisionSnapshot.id(GlobalPlanningService.endBuilding(other));
                from = "fin de " + other.code;
            }
        }
        for (var e : s.planning.entries()) {
            if (!Set.of("TASK", "TRANSIT").contains(e.kind) || e.building == null || !Objects.equals(Hibernate.unproxy(e.employee, Employee.class).id, employeeId)) continue;
            if (e.endsAt.isAfter(start) || e.endsAt.isBefore(day)) continue;
            if (freeAt == null || e.endsAt.isAfter(freeAt)) {
                freeAt = e.endsAt;
                origin = SupervisionSnapshot.id(e.building);
                from = "fin de « " + e.title + " »";
            }
        }
        boolean imminent = !start.isAfter(s.now.plusHours(2));
        if ((freeAt == null || !freeAt.isAfter(s.now)) && imminent) {
            EmployeePosition declared = s.positions.get(employeeId);
            if (declared != null) {
                origin = SupervisionSnapshot.id(declared.building);
                freeAt = declared.availableAt.isAfter(s.now) ? declared.availableAt : s.now;
                from = declared.availableAt.isAfter(s.now) ? "arrivée de son transit en cours" : "position déclarée";
                real = true;
            } else {
                var shift = shift(s, employeeId, s.now);
                if (shift != null && shift.building != null) {
                    origin = SupervisionSnapshot.id(shift.building);
                    freeAt = s.now;
                    from = "bâtiment de sa présence en cours";
                    real = true;
                }
            }
        }
        if (origin == null) {
            var shift = shift(s, employeeId, start);
            if (shift != null && shift.building != null) {
                origin = SupervisionSnapshot.id(shift.building);
                freeAt = shift.startsAt;
                from = "bâtiment de sa présence";
            }
        }
        if (origin == null)
            return new TransitCheck("UNKNOWN_ORIGIN", null, target, null, null, null, "La position de " + name + " avant " + o.code + " est inconnue (aucune position déclarée ni bâtiment de présence).", false);
        Integer minutes = s.travel(origin, target);
        String fromCode = s.buildings.containsKey(origin) ? s.buildings.get(origin).buildingCode : "?";
        String toCode = targetBuilding.buildingCode;
        if (minutes != null && minutes < 0)
            return new TransitCheck("NO_ROUTE", origin, target, minutes, freeAt, null, name + " sera dans " + fromCode + " (" + from + ") : aucun trajet " + fromCode + " → " + toCode + " n’est configuré.", real);
        LocalDateTime arrival = freeAt.plusMinutes(minutes == null ? 0 : minutes);
        if (arrival.isAfter(start))
            return new TransitCheck("LATE_ARRIVAL", origin, target, minutes, freeAt, arrival, name + " sera dans " + fromCode + " (" + from + ", " + time(s, freeAt) + ") : avec "
                    + minutes + " min de trajet vers " + toCode + ", arrivée à " + time(s, arrival) + " pour un début à " + time(s, start) + ".", real);
        return new TransitCheck("OK", origin, target, minutes, freeAt, arrival, minutes == 0 ? "Déjà sur place (" + toCode + ")." : "Trajet " + fromCode + " → " + toCode + " de " + minutes + " min compatible.", real);
    }

    /**
     * Returns the presence of an employee covering an instant.
     *
     * @param s snapshot
     * @param employeeId employee
     * @param at instant
     * @return the latest covering shift, or null
     */
    private PlanningEntry shift(SupervisionSnapshot s, Long employeeId, LocalDateTime at) {
        return s.planning.entries().stream().filter(e -> "SHIFT".equals(e.kind) && Objects.equals(Hibernate.unproxy(e.employee, Employee.class).id, employeeId)
                && !e.startsAt.isAfter(at) && e.endsAt.isAfter(at)).max(Comparator.comparing(e -> e.startsAt)).orElse(null);
    }

    /**
     * Returns the building where the next step of an order happens: the shelf put-away building once the warehouse
     * picking of a shelf replenishment is complete, otherwise its start building.
     *
     * @param s snapshot
     * @param o order
     * @return the building, or null when unknown
     */
    static Building requiredBuilding(SupervisionSnapshot s, BaseHandlingOrder o) {
        if (o instanceof ReplenishmentOrder r && !(o instanceof StockProtectionOrder) && r.sourceBuilding != null && s.pickingDone(o)) return r.destinationBuilding;
        var b = GlobalPlanningService.startBuilding(o);
        return b == null ? null : Hibernate.unproxy(b, Building.class);
    }

    // ------------------------------------------------------------------ stock

    /**
     * Checks, in planned order, that the quantities still to withdraw are physically available at their locations at
     * the planned time, distinguishing current shortage, forecast shortage, overdue supply and unknown stock.
     *
     * @param s snapshot
     * @param late late cards by order: a shortage caused only by their overdue supplies is shown there as a consequence
     * @return one anomaly per order with a problem
     */
    private List<Draft> stock(SupervisionSnapshot s, Map<Long, Draft> late) {
        var result = new ArrayList<Draft>();
        for (var o : s.orders) {
            var lines = s.needs.get(o.id);
            if (lines == null) continue;
            var issues = new ArrayList<ProductLine>();
            var waitedOrders = new LinkedHashSet<Long>();
            Certainty certainty = null;
            boolean begun = !o.requestedStart.isAfter(s.now);
            for (var n : lines) {
                Long accountId = s.accountOfProduct.get(n.product().id);
                var a = s.availability.assess("H:" + o.id, o.code, n, n.locationId(), n.locationCode(), n.quantity(), o.requestedStart, begun);
                if (a.available()) continue;
                BuildingRef where = a.buildingId() == null || !s.buildings.containsKey(a.buildingId()) ? null : building(s.buildings.get(a.buildingId()));
                String earlier = a.earlierConsumers().isEmpty() ? "" : " Déjà réservé par " + String.join(", ", a.earlierConsumers()) + " planifié(s) avant.";
                String state = a.state();
                String text = switch (state) {
                    case LocationAvailability.UNKNOWN -> switch (a.unknownReason()) {
                        case "NO_LOCATION" -> "Emplacement de prélèvement non renseigné.";
                        case "OUTSIDE_STORE" -> "Emplacement " + n.locationCode() + " hors des bâtiments du magasin.";
                        default -> "Aucun inventaire enregistré à " + n.locationCode() + " : le stock est inconnu, pas nécessairement nul.";
                    };
                    case LocationAvailability.LATE_SUPPLY -> {
                        a.overdueSupplies().stream().map(x -> s.orderOfSupply.get(x.id)).filter(Objects::nonNull).forEach(waitedOrders::add);
                        yield "Le complément dépend d’un approvisionnement attendu avant " + time(s, s.now) + " et non réceptionné (" + qty(a.overdue(), n.product()) + ")." + earlier;
                    }
                    case LocationAvailability.SHORTAGE_NOW -> "Manque actuel : " + qty(a.free().max(BigDecimal.ZERO), n.product()) + " disponible(s) à " + n.locationCode() + " pour " + qty(n.quantity(), n.product()) + " à " + (n.stage().equals("PICKING") ? "prélever" : "collecter") + "." + earlier;
                    default -> "Manque prévu à " + time(s, o.requestedStart) + " : " + qty(a.free().add(a.incoming()).max(BigDecimal.ZERO), n.product()) + " disponible(s) avec les entrées prévues pour " + qty(n.quantity(), n.product()) + "." + earlier;
                };
                Certainty line = switch (state) {
                    case LocationAvailability.UNKNOWN -> Certainty.MISSING_DATA;
                    case LocationAvailability.SHORTAGE_NOW -> Certainty.BLOCKING;
                    case LocationAvailability.LATE_SUPPLY -> begun ? Certainty.BLOCKING : Certainty.RISK;
                    default -> Certainty.RISK;
                };
                certainty = certainty == null || line.ordinal() < certainty.ordinal() ? line : certainty;
                issues.add(new ProductLine(o.id, n.product().id, accountId, n.product().name, n.product().ean, unit(n.product()), n.product().productType.name(), n.quantity(), a.physical(),
                        a.incoming(), a.overdue(), where, n.locationCode(), state, text));
            }
            if (issues.isEmpty()) continue;
            if (issues.stream().allMatch(p -> "LATE_SUPPLY".equals(p.state())) && !waitedOrders.isEmpty() && waitedOrders.stream().allMatch(late::containsKey)) {
                // The shortage only waits for late supplies: it is a consequence of those late cards, not a new cause.
                for (Long id : waitedOrders) {
                    var card = late.get(id);
                    if (card.consequences.stream().noneMatch(c -> c.id().equals(o.id))) card.consequences.add(ref(s, o, "CONSEQUENCE"));
                    card.critical = true;
                    String note = o.code + " attend ces quantités pour " + time(s, o.requestedStart) + ".";
                    if (card.impact == null || !card.impact.contains(note)) card.impact = (card.impact == null ? "" : card.impact + " ") + note;
                }
                continue;
            }
            var d = draft(s, "STOCK:" + o.id, Type.STOCK, certainty, o);
            long shortages = issues.stream().filter(p -> !"UNKNOWN".equals(p.state())).count();
            long unknown = issues.size() - shortages;
            boolean picking = lines.get(0).stage().equals("PICKING") && o instanceof ReplenishmentOrder r && r.sourceBuilding != null;
            d.title = shortages == 0 ? "Stock inconnu pour des lignes" : "Quantités indisponibles au moment prévu";
            d.explanation = (picking ? "Picking dans " + r(o).sourceBuilding.buildingCode + " avant mise en rayon : " : "")
                    + (shortages > 0 ? shortages + " produit(s) ne pourront pas être " + (picking ? "prélevés" : "collectés") + " en totalité" + (begun ? "." : " à " + time(s, o.requestedStart) + ".") : "")
                    + (unknown > 0 ? (shortages > 0 ? " " : "") + unknown + " ligne(s) sans stock connu : à vérifier, sans conclure à un manque." : "");
            d.impact = shortages > 0 ? "Préparation incomplète de " + o.code + " si rien n’est fait." : null;
            d.deadline = begun ? o.requestedEnd : o.requestedStart;
            d.deadlineLabel = begun ? "Fin prévue" : "Début prévu";
            d.products.addAll(issues);
            for (var p : issues) if (p.building() != null) d.buildings.putIfAbsent(p.building().id(), p.building());
            d.context.put("accountIds", issues.stream().map(ProductLine::accountId).filter(Objects::nonNull).distinct().toList());
            if (!issues.isEmpty() && issues.get(0).building() != null) d.context.put("stockBuildingId", issues.get(0).building().id());
            boolean hasAccounts = issues.stream().anyMatch(p -> p.accountId() != null);
            d.actions.add(new Action("OPEN_STOCK", "Ouvrir le stock des produits", hasAccounts));
            d.actions.add(new Action("OPEN_ORDER", "Examiner les lignes de l’ordre", !hasAccounts));
            planningPeriod(s, d, o);
            result.add(d);
        }
        return result;
    }

    /**
     * Casts an order to a replenishment.
     *
     * @param o order
     * @return the replenishment
     */
    private static ReplenishmentOrder r(BaseHandlingOrder o) {
        return (ReplenishmentOrder) o;
    }

    // ------------------------------------------------------------------ buffers

    /**
     * Checks destination buffers (grouping buffer, protection buffer, buffer totes of replenishment lines) for
     * deactivation, current saturation and temperature compatibility, grouped by buffer and cause. Capacities are the
     * current occupancy: no future release is assumed.
     *
     * @param s snapshot
     * @return the anomalies
     */
    private List<Draft> buffers(SupervisionSnapshot s) {
        var groups = new LinkedHashMap<String, Draft>();
        for (var o : s.orders) {
            if (o instanceof GroupingOrder g && g.buffer == null || o instanceof StockProtectionOrder p && p.protectionBuffer == null) {
                var d = draft(s, "BUFFER:missing-" + o.id, Type.BUFFER, Certainty.MISSING_DATA, o);
                d.title = "Buffer de destination non renseigné";
                d.explanation = label(o) + " " + o.code + " n’a pas de buffer de destination : sa capacité ne peut pas être vérifiée.";
                d.deadline = o.requestedStart;
                d.deadlineLabel = "Début prévu";
                if (!s.started(o)) d.proposals.addAll(destinations(s, o, null, s.zonesByOrder.getOrDefault(o.id, Set.of())));
                for (var p : d.proposals) s.buffers.stream().filter(b -> b.id.equals(p.bufferId())).findFirst().ifPresent(b -> d.buffers.add(bufferRef(s, b, "CANDIDATE", "Compatible actuellement.")));
                d.actions.add(new Action("REVIEW_PROPOSALS", "Choisir un buffer", !d.proposals.isEmpty()));
                d.actions.add(new Action("OPEN_BUFFERS", "Examiner les buffers", d.proposals.isEmpty()));
                d.actions.add(new Action("OPEN_ORDER", "Examiner l’ordre", false));
                groups.put(d.id, d);
            }
            for (var need : s.bufferNeeds.getOrDefault(o.id, List.of())) {
                var b = need.buffer();
                String cause = null;
                String text = null;
                if (!b.active) { cause = "INACTIVE"; text = "Le buffer " + b.bufferCode + " est désactivé."; }
                else for (var zone : need.zones()) {
                    int capacity = s.capacity(b, zone);
                    int used = s.occupied(b, zone);
                    if (capacity == 0) { cause = "INCOMPATIBLE"; text = "Le buffer " + b.bufferCode + " n’a aucune place " + zoneLabel(zone) + "."; break; }
                    if (!need.alreadyDeposited() && used >= capacity) { cause = "FULL"; text = "Le buffer " + b.bufferCode + " est plein actuellement en " + zoneLabel(zone) + " (" + used + "/" + capacity + " bacs)."; break; }
                }
                if (cause == null) continue;
                String key = "BUFFER:" + b.id + ":" + cause;
                boolean begun = !o.requestedStart.isAfter(s.now);
                var d = groups.get(key);
                if (d == null) {
                    d = draft(s, key, Type.BUFFER, begun ? Certainty.BLOCKING : Certainty.RISK, o);
                    d.title = switch (cause) {
                        case "INACTIVE" -> "Buffer de destination désactivé";
                        case "INCOMPATIBLE" -> "Buffer de destination incompatible";
                        default -> "Buffer de destination plein";
                    };
                    d.explanation = text + ("FULL".equals(cause) ? " Occupation constatée maintenant ; aucune libération future n’est projetée." : "");
                    d.deadline = o.requestedStart;
                    d.deadlineLabel = "Début prévu";
                    d.buffers.add(bufferRef(s, b, cause, text));
                    d.buildings.putIfAbsent(SupervisionSnapshot.id(b.building), building(Hibernate.unproxy(b.building, Building.class)));
                    d.context.put("bufferIds", new ArrayList<>(List.of(b.id)));
                    d.context.put("bufferBuildingCode", Hibernate.unproxy(b.building, Building.class).buildingCode);
                    groups.put(key, d);
                } else {
                    d.orders.add(ref(s, o, "CONCERNED"));
                    addBuildings(s, d, o);
                    if (begun) d.certainty = Certainty.BLOCKING;
                }
                if (need.editable() && !s.started(o)) {
                    var proposals = destinations(s, o, b, need.zones());
                    d.proposals.addAll(proposals);
                    for (var p : proposals)
                        if (d.buffers.stream().noneMatch(x -> x.id().equals(p.bufferId())))
                            s.buffers.stream().filter(x -> x.id.equals(p.bufferId())).findFirst().ifPresent(x -> groups.get(key).buffers.add(bufferRef(s, x, "CANDIDATE", "Compatible actuellement.")));
                }
            }
        }
        for (var d : groups.values()) {
            if (d.id.startsWith("BUFFER:missing-")) continue;
            if (d.orders.size() > 1) d.impact = d.orders.size() + " ordres déposent dans ce buffer.";
            d.actions.add(new Action("OPEN_BUFFERS", "Examiner les buffers", true));
            d.actions.add(new Action("REVIEW_PROPOSALS", "Changer de destination", false));
            d.actions.add(new Action("OPEN_ORDER", "Examiner l’ordre", false));
            if (d.proposals.isEmpty())
                d.explanation += " Aucune destination de remplacement ne peut être appliquée automatiquement" + (d.orders.stream().anyMatch(r -> "REPLENISHMENT".equals(r.kind())) ? " pour les bacs de réapprovisionnement : corrigez le dépôt depuis Buffers." : ".");
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * Proposes compatible destination buffers: active, with a free place now for every needed temperature,
     * buffers of the current building first.
     *
     * @param s snapshot
     * @param o order
     * @param current current destination, excluded
     * @param zones needed temperatures
     * @return at most three proposals
     */
    List<Proposal> destinations(SupervisionSnapshot s, BaseHandlingOrder o, Buffer current, Set<TemperatureZone> zones) {
        Long building = current == null ? SupervisionSnapshot.id(GlobalPlanningService.startBuilding(o)) : SupervisionSnapshot.id(current.building);
        var result = new ArrayList<Proposal>();
        s.buffers.stream().filter(b -> b.active && (current == null || !b.id.equals(current.id)))
                .filter(b -> !zones.isEmpty() && zones.stream().allMatch(z -> s.capacity(b, z) > s.occupied(b, z)))
                .sorted(Comparator.comparing((Buffer b) -> Objects.equals(SupervisionSnapshot.id(b.building), building) ? 0 : 1).thenComparing(b -> b.bufferCode))
                .limit(3).forEach(b -> {
                    String free = zones.stream().sorted().map(z -> zoneLabel(z) + " : " + (s.capacity(b, z) - s.occupied(b, z)) + " place(s) libre(s)").collect(Collectors.joining(" · "));
                    var where = Hibernate.unproxy(b.building, Building.class);
                    result.add(new Proposal("DESTINATION:" + o.id + ":" + b.id, "DESTINATION", "Diriger vers " + b.bufferCode + " (" + where.buildingCode + ")",
                            free + " actuellement" + (Objects.equals(where.id, building) ? "." : " · autre bâtiment : vérifiez le trajet."), o.id, o.code,
                            null, null, b.id, b.bufferCode, o.requestedStart, o.requestedEnd, basis(o)));
                });
        return result;
    }

    /**
     * Builds the buffer view of a card.
     *
     * @param s snapshot
     * @param b buffer
     * @param status INACTIVE, FULL, INCOMPATIBLE or CANDIDATE
     * @param explanation explanation
     * @return the view
     */
    private BufferRef bufferRef(SupervisionSnapshot s, Buffer b, String status, String explanation) {
        var zones = new ArrayList<BufferZone>();
        for (var z : List.of(TemperatureZone.AMBIENT, TemperatureZone.CHILLED, TemperatureZone.FROZEN)) {
            int capacity = s.capacity(b, z), used = s.occupied(b, z);
            zones.add(new BufferZone(z.name(), used, capacity, Math.max(0, capacity - used)));
        }
        return new BufferRef(b.id, b.bufferCode, building(Hibernate.unproxy(b.building, Building.class)), b.active, zones, status, explanation);
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Starts a card for an order.
     *
     * @param s snapshot
     * @param id stable identity
     * @param type type
     * @param certainty certainty
     * @param o subject order
     * @return the draft
     */
    private Draft draft(SupervisionSnapshot s, String id, Type type, Certainty certainty, BaseHandlingOrder o) {
        var d = new Draft();
        d.id = id;
        d.type = type;
        d.certainty = certainty;
        d.order = o;
        d.orders.add(ref(s, o, "SUBJECT"));
        addBuildings(s, d, o);
        if (o.assignee != null) d.employee = employee(Hibernate.unproxy(o.assignee, Employee.class));
        return d;
    }

    /**
     * Adds the buildings of an order to a card.
     *
     * @param s snapshot
     * @param d card
     * @param o order
     */
    private void addBuildings(SupervisionSnapshot s, Draft d, BaseHandlingOrder o) {
        for (var b : buildingsOf(o)) d.buildings.putIfAbsent(b.id(), b);
    }

    /**
     * Stores the planning period of reused planning views: the rest of the order's day, never in the past.
     *
     * @param s snapshot
     * @param d card
     * @param o order
     */
    private void planningPeriod(SupervisionSnapshot s, Draft d, BaseHandlingOrder o) {
        LocalDateTime dayStart = o.requestedStart.toLocalDate().atStartOfDay();
        LocalDateTime from = nextQuarter(s.now).isAfter(dayStart) ? nextQuarter(s.now) : dayStart;
        LocalDateTime to = o.requestedStart.toLocalDate().plusDays(1).atStartOfDay();
        if (!to.isAfter(from)) to = from.toLocalDate().plusDays(1).atStartOfDay();
        d.context.put("planningStart", from);
        d.context.put("planningEnd", to);
        d.context.put("orderId", o.id);
        d.context.put("orderKind", kind(o));
        d.context.put("orderIds", d.orders.stream().map(OrderRef::id).toList());
        if (d.employee != null) d.context.put("employeeId", d.employee.id());
    }

    /**
     * Completes and freezes a card, computing its severity.
     *
     * @param s snapshot
     * @param d card
     * @return the anomaly
     */
    private Anomaly finish(SupervisionSnapshot s, Draft d) {
        d.context.put("orderIds", d.orders.stream().map(OrderRef::id).toList());
        d.context.put("buildingIds", new ArrayList<>(d.buildings.keySet()));
        d.severity = severity(s, d);
        var proposals = d.proposals.stream().collect(Collectors.toMap(Proposal::id, p -> p, (a, b) -> a, LinkedHashMap::new)).values();
        return new Anomaly(d.id, d.type, d.certainty, d.severity, d.title, d.explanation, d.impact, d.deadline, d.deadlineLabel,
                ref(s, d.order, "SUBJECT"), List.copyOf(d.orders), List.copyOf(d.consequences), List.copyOf(d.buildings.values()),
                d.employee, List.copyOf(d.products), List.copyOf(d.buffers), List.copyOf(proposals),
                d.actions.stream().filter(a -> !"REVIEW_PROPOSALS".equals(a.code()) || !proposals.isEmpty()).toList(), new LinkedHashMap<>(d.context));
    }

    /**
     * Severity: proven blockings are high, critical when they last for more than an hour, fail, miss a customer
     * deadline or block other orders; risks rise as their deadline approaches; missing data stays low unless imminent.
     *
     * @param s snapshot
     * @param d card
     * @return the severity
     */
    private Severity severity(SupervisionSnapshot s, Draft d) {
        long minutes = d.deadline == null ? Long.MAX_VALUE : Duration.between(s.now, d.deadline).toMinutes();
        return switch (d.certainty) {
            case BLOCKING -> d.critical || !d.consequences.isEmpty() ? Severity.CRITICAL : Severity.HIGH;
            case RISK -> minutes <= 60 ? Severity.HIGH : minutes <= 24 * 60 ? Severity.MEDIUM : Severity.LOW;
            case MISSING_DATA -> minutes <= 120 ? Severity.MEDIUM : Severity.LOW;
        };
    }

    /**
     * Captures the state of an order on which a proposal relies.
     *
     * @param o order
     * @return the basis
     */
    static Basis basis(BaseHandlingOrder o) {
        Buffer buffer = o instanceof GroupingOrder g ? g.buffer : o instanceof StockProtectionOrder p ? p.protectionBuffer : null;
        return new Basis(o.id, o.version, o.status.name(), o.assignee == null ? null : Hibernate.unproxy(o.assignee, Employee.class).id,
                o.requestedStart, o.requestedEnd, buffer == null ? null : Hibernate.unproxy(buffer, Buffer.class).id);
    }

    /**
     * Builds the reference of an order.
     *
     * @param s snapshot
     * @param o order
     * @param role SUBJECT, CONCERNED, PREREQUISITE or CONSEQUENCE
     * @return the reference
     */
    OrderRef ref(SupervisionSnapshot s, BaseHandlingOrder o, String role) {
        return new OrderRef(o.id, o.code, kind(o), o instanceof ReplenishmentOrder r && r.operation != null ? r.operation.name() : null,
                o.status.name(), o.requestedStart, o.requestedEnd, o.assignee == null ? null : employee(Hibernate.unproxy(o.assignee, Employee.class)),
                buildingsOf(o), role, stage(s, o));
    }

    /**
     * Returns the buildings an order involves.
     *
     * @param raw order
     * @return the buildings, in flow order
     */
    private List<BuildingRef> buildingsOf(BaseHandlingOrder raw) {
        var o = Hibernate.unproxy(raw, BaseHandlingOrder.class);
        var list = new ArrayList<Building>();
        if (o instanceof ReplenishmentOrder r) { list.add(r.sourceBuilding); list.add(r.destinationBuilding); }
        else list.add(GlobalPlanningService.startBuilding(o));
        var result = new ArrayList<BuildingRef>();
        for (var b : list) if (b != null && result.stream().noneMatch(x -> x.id().equals(SupervisionSnapshot.id(b)))) result.add(building(Hibernate.unproxy(b, Building.class)));
        return result;
    }

    /**
     * Describes the execution stage of an order, including both stages of a shelf replenishment.
     *
     * @param s snapshot
     * @param o order
     * @return the stage, or null
     */
    private String stage(SupervisionSnapshot s, BaseHandlingOrder o) {
        if (!(o instanceof ReplenishmentOrder r) || o instanceof StockProtectionOrder) return null;
        if (r.sourceBuilding == null) return "déchargement du camion" + (r.truckReference == null ? "" : " " + r.truckReference);
        int[] progress = s.pickingProgress.getOrDefault(o.id, new int[2]);
        if (s.pickingDone(o)) return "picking entrepôt terminé, mise en rayon dans " + r.destinationBuilding.buildingCode + " à faire";
        return "picking dans " + r.sourceBuilding.buildingCode + " (" + progress[0] + "/" + progress[1] + " lignes), puis mise en rayon dans " + r.destinationBuilding.buildingCode;
    }

    /**
     * Returns the frontend registry key of an order.
     *
     * @param raw order
     * @return the key
     */
    static String kind(BaseHandlingOrder raw) {
        var o = Hibernate.unproxy(raw, BaseHandlingOrder.class);
        if (o instanceof com.intermarche.backoffice.domain.wms.handling.InventoryOrder) return "INVENTORY";
        if (o instanceof com.intermarche.backoffice.domain.wms.handling.CancellationDispositionOrder) return "CANCELLATION_DISPOSITION";
        if (o instanceof StockProtectionOrder) return "STOCK_PROTECTION";
        if (o instanceof ReplenishmentOrder) return "REPLENISHMENT";
        if (o instanceof GroupingOrder) return "GROUPING";
        if (o instanceof PackingOrder) return "PACKING";
        if (o instanceof ZoneCollectingOrder) return "ZONE_COLLECTING";
        return "PRODUCT_COLLECTING";
    }

    /**
     * Returns the French label of an order type.
     *
     * @param raw order
     * @return the label
     */
    static String label(BaseHandlingOrder raw) {
        var o = Hibernate.unproxy(raw, BaseHandlingOrder.class);
        if (o instanceof com.intermarche.backoffice.domain.wms.handling.InventoryOrder) return "L’inventaire ciblé";
        if (o instanceof com.intermarche.backoffice.domain.wms.handling.CancellationDispositionOrder) return "Le traitement après annulation";
        if (o instanceof StockProtectionOrder) return "La mise en sécurité";
        if (o instanceof ReplenishmentOrder r) return r.sourceBuilding == null ? "Le déchargement camion" : "Le réachalandage";
        if (o instanceof GroupingOrder) return "Le regroupement";
        if (o instanceof PackingOrder) return "Le packing";
        if (o instanceof ZoneCollectingOrder) return "La collecte de zone";
        return "La collecte produits";
    }

    /**
     * Returns the French label of a status.
     *
     * @param status status
     * @return the label
     */
    static String statusLabel(BaseHandlingOrder.HandlingOrderStatus status) {
        return switch (status) {
            case OPEN -> "Ouvert";
            case SCHEDULED -> "Planifié";
            case PROCESSING -> "En cours";
            case DONE -> "Terminé";
            case CANCELLED -> "Annulé";
            case FAILED -> "En échec";
        };
    }

    /**
     * Builds a building reference.
     *
     * @param b building
     * @return the reference with STORE or WAREHOUSE type
     */
    BuildingRef building(Building b) {
        return new BuildingRef(b.id, b.buildingCode, b instanceof WarehouseBuilding ? "WAREHOUSE" : "STORE");
    }

    /**
     * Builds an employee reference.
     *
     * @param e employee
     * @return the reference
     */
    EmployeeRef employee(Employee e) {
        return new EmployeeRef(e.id, e.firstName + " " + e.lastName, e.badgeId);
    }

    /**
     * Formats a time, with the day when it is not today.
     *
     * @param s snapshot
     * @param at instant
     * @return the text
     */
    static String time(SupervisionSnapshot s, LocalDateTime at) {
        if (at == null) return "—";
        return at.toLocalDate().equals(s.now.toLocalDate()) ? at.format(TIME) : at.format(DAY_TIME);
    }

    /**
     * Formats a duration in minutes.
     *
     * @param minutes minutes
     * @return the text
     */
    private static String duration(long minutes) {
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h " + String.format("%02d", minutes % 60);
    }

    /**
     * Rounds an instant up to the next quarter of an hour.
     *
     * @param at instant
     * @return the next quarter
     */
    static LocalDateTime nextQuarter(LocalDateTime at) {
        var base = at.withSecond(0).withNano(0);
        int remainder = base.getMinute() % 15;
        return base.plusMinutes(15 - remainder);
    }

    /**
     * Formats a quantity with its unit.
     *
     * @param value quantity
     * @param p product
     * @return the text
     */
    private static String qty(BigDecimal value, Product p) {
        return value.stripTrailingZeros().toPlainString() + " " + unit(p);
    }

    /**
     * Returns the unit of a product.
     *
     * @param p product
     * @return kg, L or the unit name
     */
    private static String unit(Product p) {
        return p.productType == ProductType.WEIGHT ? "kg" : p.productType == ProductType.VOLUME ? "L" : p.unitName == null ? "pcs" : p.unitName;
    }

    /**
     * Returns the French label of a temperature.
     *
     * @param zone temperature
     * @return the label
     */
    static String zoneLabel(TemperatureZone zone) {
        return switch (zone) {
            case CHILLED -> "frais";
            case FROZEN -> "surgelé";
            default -> "ambiant";
        };
    }
}
