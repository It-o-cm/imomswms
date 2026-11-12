package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.wms.handling.StockProtectionOrder;
import com.intermarche.backoffice.engine.storeops.PlanningAssignmentService;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import com.intermarche.backoffice.engine.supervision.SupervisionModel.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.Hibernate;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Applies a supervision proposal validated by the manager.
 * <p>
 * Under the store lock, the server compares the order with the state the proposal was computed on, recomputes the
 * anomaly with the same engines and checks that the exact proposal is still offered. Otherwise nothing is written
 * and the precise reasons are returned so that the manager can refresh.
 */
@ApplicationScoped
public class SupervisionActionService {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    @Inject EntityManager em;
    @Inject HandlingSupervisionService supervision;
    @Inject PlanningAssignmentService assignments;
    @Inject StoreOperationsResource operations;

    /**
     * Revalidates then applies one proposal.
     *
     * @param store the caller's store, already scoped
     * @param request the proposal to apply
     * @return APPLIED, or STALE with the reasons; nothing is written when stale
     */
    public ApplyResult apply(Store store, ApplyRequest request) {
        if (request == null || request.anomalyId() == null || request.proposalId() == null || request.basis() == null || request.basis().orderId() == null)
            throw new IllegalArgumentException("Proposition à appliquer requise.");
        em.lock(store, LockModeType.PESSIMISTIC_WRITE);
        BaseHandlingOrder order = BaseHandlingOrder.findById(request.basis().orderId());
        if (order == null || !Hibernate.unproxy(order.store, Store.class).id.equals(store.id))
            throw new jakarta.ws.rs.NotFoundException();
        order = Hibernate.unproxy(order, BaseHandlingOrder.class);
        em.refresh(order);
        var reasons = new ArrayList<>(changes(request.basis(), order));
        var start = request.start() == null ? order.requestedStart.toLocalDate().atStartOfDay() : request.start();
        var end = request.end() == null ? start.plusDays(1) : request.end();
        if (order.requestedStart != null && !order.requestedStart.isBefore(end)) end = order.requestedStart.toLocalDate().plusDays(1).atStartOfDay();
        var report = supervision.analyze(store, start, end);
        var anomaly = report.anomalies().stream().filter(a -> a.id().equals(request.anomalyId())).findFirst().orElse(null);
        var proposal = anomaly == null ? null : anomaly.proposals().stream().filter(p -> p.id().equals(request.proposalId())).findFirst().orElse(null);
        if (anomaly == null) reasons.add("La situation n’est plus détectée : elle a été résolue ou modifiée depuis l’affichage.");
        else if (proposal == null) reasons.addAll(whyNotOffered(store, order, request.proposalId()));
        if (!reasons.isEmpty() || proposal == null) {
            if (reasons.isEmpty()) reasons.add("Cette proposition n’est plus valide.");
            return new ApplyResult("STALE", "La proposition n’a pas été appliquée : la situation a changé.", reasons, anomaly);
        }
        return switch (proposal.kind()) {
            case "ASSIGN", "RESCHEDULE" -> assign(store, order, proposal);
            case "DESTINATION" -> destination(order, proposal);
            default -> throw new IllegalArgumentException("Proposition inconnue.");
        };
    }

    /**
     * Lists the differences between the state the proposal relied on and the current order.
     *
     * @param basis observed state
     * @param o current order
     * @return the differences, in French
     */
    List<String> changes(Basis basis, BaseHandlingOrder o) {
        var result = new ArrayList<String>();
        if (!Objects.equals(basis.status(), o.status.name()))
            result.add("Statut de " + o.code + " passé de « " + status(basis.status()) + " » à « " + HandlingSupervisionService.statusLabel(o.status) + " ».");
        Long assignee = o.assignee == null ? null : Hibernate.unproxy(o.assignee, Employee.class).id;
        if (!Objects.equals(basis.assigneeId(), assignee))
            result.add(o.code + " est maintenant " + (assignee == null ? "sans employé" : "affecté à " + o.assignee.getFullName()) + ".");
        if (!Objects.equals(basis.start(), o.requestedStart) || !Objects.equals(basis.end(), o.requestedEnd))
            result.add("Créneau de " + o.code + " déplacé : " + format(basis.start()) + "–" + format(basis.end()) + " → " + format(o.requestedStart) + "–" + format(o.requestedEnd) + ".");
        Buffer buffer = o instanceof GroupingOrder g ? g.buffer : o instanceof StockProtectionOrder p ? p.protectionBuffer : null;
        Long bufferId = buffer == null ? null : Hibernate.unproxy(buffer, Buffer.class).id;
        if (!Objects.equals(basis.bufferId(), bufferId))
            result.add("Buffer de destination de " + o.code + " modifié" + (buffer == null ? "" : " : " + buffer.bufferCode) + ".");
        if (result.isEmpty() && !Objects.equals(basis.version(), o.version))
            result.add(o.code + " a été modifié depuis l’affichage.");
        return result;
    }

    /**
     * Explains why a proposal is no longer offered, by evaluating the proposed employee or buffer again.
     *
     * @param store store
     * @param o order
     * @param proposalId proposal identity: KIND:order:employee:start or DESTINATION:order:buffer
     * @return the reasons
     */
    private List<String> whyNotOffered(Store store, BaseHandlingOrder o, String proposalId) {
        var parts = proposalId.split(":", 4);
        var result = new ArrayList<String>();
        try {
            if (parts[0].equals("DESTINATION") && parts.length >= 3) {
                Buffer buffer = Buffer.findById(Long.valueOf(parts[2]));
                if (buffer == null) result.add("Le buffer proposé n’existe plus.");
                else if (!buffer.active) result.add("Le buffer " + buffer.bufferCode + " a été désactivé.");
                else result.add("Le buffer " + buffer.bufferCode + " n’a plus de place libre compatible actuellement.");
            } else if (parts.length == 4) {
                Employee person = Employee.findById(Long.valueOf(parts[2]));
                var start = java.time.LocalDateTime.parse(parts[3]);
                var end = start.plus(java.time.Duration.between(o.requestedStart, o.requestedEnd));
                if (parts[0].equals("ASSIGN")) { start = o.requestedStart; end = o.requestedEnd; }
                if (person == null) result.add("L’employé proposé n’existe plus.");
                else {
                    var context = assignments.context(store, start.toLocalDate().atStartOfDay(), end.toLocalDate().plusDays(1).atStartOfDay());
                    for (var r : assignments.reasons(context, person, start, end, null, o.id, "HANDLING", null))
                        result.add(r.message() + (r.start() != null && !"NO_SHIFT".equals(r.code()) ? " (" + format(r.start()) + "–" + format(r.end()) + ")" : "") + ".");
                    if (result.isEmpty()) result.add(person.firstName + " " + person.lastName + " reste disponible, mais ne peut plus rejoindre le bâtiment à temps ou le créneau a changé.");
                }
            }
        } catch (RuntimeException e) {
            result.add("La proposition ne correspond plus à la situation actuelle.");
        }
        if (result.isEmpty()) result.add("La proposition ne correspond plus à la situation actuelle.");
        return result;
    }

    /**
     * Applies an assignment through the existing scheduling endpoint, which validates it again under the store lock.
     *
     * @param store store
     * @param o order
     * @param p proposal
     * @return the result
     */
    private ApplyResult assign(Store store, BaseHandlingOrder o, Proposal p) {
        if (p.start().isBefore(DateTimeProvider.now().withSecond(0).withNano(0)) && !p.start().equals(o.requestedStart))
            return new ApplyResult("STALE", "La proposition n’a pas été appliquée : le créneau proposé a commencé.", List.of("Le créneau proposé débutait à " + format(p.start()) + "."), null);
        var input = new StoreOperationsResource.PlanningInput();
        input.storeId = store.id;
        input.employeeId = p.employeeId();
        input.start = p.start();
        input.end = p.end();
        input.validatedAssignment = true;
        try {
            operations.scheduleOrder(o.id, input);
        } catch (jakarta.ws.rs.BadRequestException e) {
            String message = e.getResponse() != null && e.getResponse().getEntity() instanceof Map<?, ?> m ? String.valueOf(m.get("message")) : e.getMessage();
            return new ApplyResult("STALE", "La proposition n’a pas été appliquée.", List.of(message), null);
        }
        return new ApplyResult("APPLIED", o.code + " affecté à " + p.employeeName() + " de " + format(p.start()) + " à " + format(p.end()) + ".", List.of(), null);
    }

    /**
     * Changes the destination buffer of a grouping or stock protection that has not started.
     *
     * @param o order
     * @param p proposal
     * @return the result
     */
    private ApplyResult destination(BaseHandlingOrder o, Proposal p) {
        Buffer buffer = em.find(Buffer.class, p.bufferId(), LockModeType.PESSIMISTIC_WRITE);
        if (buffer == null || !buffer.active)
            return new ApplyResult("STALE", "La proposition n’a pas été appliquée.", List.of("Le buffer proposé n’est plus actif."), null);
        if (Building.findStoreByBuildingId(Hibernate.unproxy(buffer.building, Building.class).id) == null
                || !Building.findStoreByBuildingId(Hibernate.unproxy(buffer.building, Building.class).id).id.equals(Hibernate.unproxy(o.store, Store.class).id))
            throw new jakarta.ws.rs.ForbiddenException("Buffer hors de votre périmètre");
        if (o instanceof GroupingOrder g) g.buffer = buffer;
        else if (o instanceof StockProtectionOrder s) s.protectionBuffer = buffer;
        else throw new IllegalArgumentException("Destination non modifiable pour cet ordre.");
        em.flush();
        return new ApplyResult("APPLIED", o.code + " déposera désormais dans " + buffer.bufferCode + ".", List.of(), null);
    }

    /**
     * Formats an instant.
     *
     * @param at instant
     * @return the text
     */
    private static String format(java.time.LocalDateTime at) {
        return at == null ? "—" : at.format(AT);
    }

    /**
     * Returns the French label of a status name.
     *
     * @param name status name
     * @return the label
     */
    private static String status(String name) {
        try {
            return HandlingSupervisionService.statusLabel(BaseHandlingOrder.HandlingOrderStatus.valueOf(name));
        } catch (RuntimeException e) {
            return String.valueOf(name);
        }
    }
}
