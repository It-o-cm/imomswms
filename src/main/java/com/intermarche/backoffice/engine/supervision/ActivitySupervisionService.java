package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.engine.storeops.GlobalPlanningService;
import com.intermarche.backoffice.engine.supervision.ActivityModel.*;
import com.intermarche.backoffice.engine.supervision.SupervisionModel.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.Hibernate;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Transversal supervision of the logistics activity of a store, for the manager.
 * <p>
 * It composes the handling supervision (same diagnostics, identities, proposals and revalidated application) with the
 * customer commitments, the stock availability states, replenishments, team capacity, buffers and shipments. A cause
 * is linked to a commitment only when the data establish the dependency: a preparation order containing the order's
 * lot, or a supply without which the quantity at the pick location does not cover the need. Problems sharing only a
 * time or a place are shown as concomitant, never as causes. It never writes.
 */
@ApplicationScoped
public class ActivitySupervisionService {

    private static final Logger LOG = Logger.getLogger(ActivitySupervisionService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final Set<String> PREPARED_STAGES = Set.of("PREPARED", "PREPARED_UNTRACKED", "READY", "PARTIALLY_SHIPPED");
    private static final int GRAPH_ORDERS = 12;

    @Inject EntityManager em;
    @Inject HandlingSupervisionService handling;
    @ConfigProperty(name = "storepilot.stock.alert-horizon-hours", defaultValue = "24") int alertHorizonHours;

    /**
     * How a commitment depends on a cause.
     *
     * @param kind ESTABLISHED or ESTIMATED
     * @param via explanation of the dependency
     * @param path intermediate nodes from the cause to the order
     * @param shelfShort whether the pick location cannot cover the need without the cause's supply
     */
    private record Link(String kind, String via, List<GraphNode> path, boolean shelfShort) {}

    /** Situation under construction. */
    private static final class Draft {
        String id;
        String kind;
        final LinkedHashSet<Domain> domains = new LinkedHashSet<>();
        ActivityModel.Certainty certainty;
        Severity handlingSeverity;
        boolean critical;
        String title;
        String cause;
        String causeLabel;
        String causeNode;
        String impact;
        final List<String> consequences = new ArrayList<>();
        final List<String> uncertainties = new ArrayList<>();
        LocalDateTime deadline;
        String deadlineLabel;
        final LinkedHashMap<Long, OrderRef> operations = new LinkedHashMap<>();
        final List<ProductLine> products = new ArrayList<>();
        final LinkedHashMap<Long, BufferRef> buffers = new LinkedHashMap<>();
        final LinkedHashMap<Long, BuildingRef> buildings = new LinkedHashMap<>();
        EmployeeRef employee;
        final List<String> anomalies = new ArrayList<>();
        final List<SituationProposal> proposals = new ArrayList<>();
        final List<Action> actions = new ArrayList<>();
        final List<Relation> related = new ArrayList<>();
        final Map<String, Object> context = new LinkedHashMap<>();
        final List<GraphNode> extraNodes = new ArrayList<>();
        final List<GraphEdge> extraEdges = new ArrayList<>();
    }

    /** State of one analysis. */
    private static final class Run {
        Store store;
        SupervisionSettings settings;
        int horizon;
        LocalDateTime now;
        LocalDateTime end;
        ActivitySnapshot a;
        OperationalDiagnostics.Result operationalCases;
        SupervisionSnapshot h;
        SupervisionModel.Report handlingReport;
        final Map<String, Anomaly> anomalies = new LinkedHashMap<>();
        final Map<Long, List<Anomaly>> anomaliesOfOrder = new HashMap<>();
        final Map<String, Draft> drafts = new LinkedHashMap<>();
        final Map<String, LinkedHashMap<Long, Link>> links = new LinkedHashMap<>();
        final Map<String, String> folded = new HashMap<>();
        final List<Source> sources = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<WorkloadSlot> slots = new ArrayList<>();
        final List<BufferBar> bufferBars = new ArrayList<>();
        Map<Long, Building> buildings = new HashMap<>();
    }

    /**
     * Analyzes the activity of a store over a forecast horizon.
     *
     * @param store the store, already restricted to the caller's scope
     * @param horizonHours horizon in hours, null for the store default
     * @return the report
     */
    public ActivityModel.Report analyze(Store store, Integer horizonHours) {
        var settings = SupervisionSettings.load(store);
        int horizon = horizonHours == null ? settings.horizonHours() : horizonHours;
        if (horizon < 1 || horizon > settings.maxHorizonHours())
            throw new IllegalArgumentException("Horizon de supervision invalide : de 1 à " + settings.maxHorizonHours() + " heures.");
        var r = new Run();
        r.store = store;
        r.settings = settings;
        r.horizon = horizon;
        r.now = DateTimeProvider.now();
        r.end = r.now.plusHours(horizon);
        r.a = ActivitySnapshot.load(em, store, r.now, r.end, r.now.minusHours(settings.overdueLookbackHours()));
        for (Building b : em.createQuery("select b from Store s join s.buildings b where s = :s", Building.class).setParameter("s", store).getResultList()) {
            var real = Hibernate.unproxy(b, Building.class);
            r.buildings.put(real.id, real);
        }
        try {
            var analysis = handling.analyze(store, r.now, r.end, r.a.unplannedLocations(), r.a.products());
            r.h = analysis.snapshot();
            r.operationalCases=analysis.operationalCases();
            r.handlingReport = analysis.report();
            for (var anomaly : r.handlingReport.anomalies()) r.anomalies.put(anomaly.id(), anomaly);
            r.sources.add(new Source("HANDLING", "Supervision de la manutention", r.handlingReport.limited() ? "PARTIAL" : "OK",
                    r.handlingReport.limited() ? "Plus de " + SupervisionSnapshot.ORDER_LIMIT + " ordres ouverts : seuls les premiers sont analysés."
                            : r.handlingReport.analyzedOrders() + " ordre(s) de manutention analysé(s).", r.handlingReport.generatedAt()));
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            LOG.warn("Handling supervision unavailable for the activity supervision", e);
            r.sources.add(new Source("HANDLING", "Supervision de la manutention", "UNAVAILABLE",
                    "Diagnostic de manutention momentanément indisponible : " + e.getMessage(), r.now));
        }
        LocalDateTime slotStart = floor(r.now, settings.capacitySlotMinutes());
        r.a.loadPlanning(em, slotStart.toLocalDate().atStartOfDay(), r.end);
        r.a.loadStockStates(em, r.now.minusMinutes(settings.staleStockMinutes()));
        r.a.loadSupplies(em);

        indexHandling(r);
        preparationLinks(r);
        supplyLinks(r);
        unplanned(r);
        planning(r);
        routing(r);
        latePlans(r);
        shipping(r);
        capacity(r, slotStart);
        buffers(r);
        definitions(r);
        stockStates(r);
        handlingSituations(r);
        operationalCases(r);
        overdue(r);
        missingDeadlines(r);
        if (r.h == null) handlingUnavailable(r);
        return report(r);
    }

    /**
     * Returns the preparation stage of one customer order of the store, as the supervision computes it.
     *
     * @param store the store, already restricted to the caller's scope
     * @param orderId order identifier
     * @return the stage, empty when the order has no fulfillment yet
     */
    public java.util.Optional<String> orderStage(Store store, Long orderId) {
        var a = ActivitySnapshot.loadOne(em, store, DateTimeProvider.now(), orderId);
        return a.commitments.isEmpty() ? java.util.Optional.empty() : java.util.Optional.ofNullable(a.commitments.get(0).stage);
    }

    /**
     * Returns the progress of one customer order of the store, loaded on demand for the detail view, with its chunks
     * in the shape of the existing fulfillment chunk editor.
     *
     * @param store the store, already restricted to the caller's scope
     * @param orderId order identifier
     * @return the detail
     * @throws java.util.NoSuchElementException when the order does not belong to the store
     */
    public OrderDetail order(Store store, Long orderId) {
        var now = DateTimeProvider.now();
        var a = ActivitySnapshot.loadOne(em, store, now, orderId);
        if (a.commitments.isEmpty()) throw new NoSuchElementException("Commande introuvable dans ce magasin.");
        var c = a.commitments.get(0);
        var accounts = new HashMap<Long, Long>();
        var products = c.lines.values().stream().map(l -> l.product().id).distinct().toList();
        if (!products.isEmpty())
            for (Object[] row : em.createQuery("select a.product.id, a.id from StockAccount a where a.store = :s and a.product.id in :ids", Object[].class)
                    .setParameter("s", store).setParameter("ids", products).getResultList())
                accounts.put((Long) row[0], (Long) row[1]);
        var bufferIds = new HashSet<>(c.bufferOfTote.values());
        var bufferCodes = new HashMap<Long, String>();
        if (!bufferIds.isEmpty())
            for (Object[] row : em.createQuery("select b.id, b.bufferCode from Buffer b where b.id in :ids", Object[].class).setParameter("ids", bufferIds).getResultList())
                bufferCodes.put((Long) row[0], (String) row[1]);
        var buildingOfChunk = new HashMap<Long, Building>();
        for (var ch : c.chunks) buildingOfChunk.put(ch.id(), ch.building());
        var lines = new ArrayList<ActivityModel.OrderLine>();
        for (var line : c.lines.values()) {
            BigDecimal prepared = BigDecimal.ZERO, shipped = BigDecimal.ZERO;
            var totes = new LinkedHashSet<String>();
            for (var l : c.lotLines) {
                if (!line.id().equals(l.lineId)) continue;
                prepared = prepared.add(l.quantity.subtract(l.remaining(c.lots.get(l.lotId))));
                shipped = shipped.add(l.shipped);
                for(Long pick:l.picks)if(c.parcelAddresses.containsKey(pick))totes.add(c.parcelAddresses.get(pick));
                for (Long tote : l.totes) {
                    Long buffer = c.bufferOfTote.get(tote);
                    String code = c.toteCodes.getOrDefault(tote, "?");
                    totes.add(code + (buffer != null ? " · " + bufferCodes.getOrDefault(buffer, "buffer") : c.loadedTotes.contains(tote)?" · chargé, remise à confirmer":c.totesOutsideBuffer.contains(tote) ? " · hors buffer" : " · expédié"));
                }
            }
            var where = buildingOfChunk.get(line.chunkId());
            lines.add(new ActivityModel.OrderLine(line.lineNumber(), line.product().id, accounts.get(line.product().id), line.product().name, line.product().ean,
                    unit(line.product()), line.requested(), prepared.min(line.requested()), shipped, line.requested().subtract(prepared).max(BigDecimal.ZERO),
                    line.locationCode(), where == null ? null : building(where), List.copyOf(totes)));
        }
        lines.sort(Comparator.comparingInt(ActivityModel.OrderLine::lineNumber));
        var chunks = new ArrayList<Map<String, Object>>();
        var ids = c.chunks.stream().map(ActivitySnapshot.Chunk::id).toList();
        for (var chunk : em.createQuery("select distinct c from FulfillmentChunk c left join fetch c.lines l left join fetch l.product left join fetch l.location where c.id in :ids order by c.id",
                com.intermarche.backoffice.domain.oms.FulfillmentChunk.class).setParameter("ids", ids).getResultList()) {
            var view = new LinkedHashMap<String, Object>();
            view.put("id", chunk.id);
            view.put("type", chunk.type);
            view.put("label", chunk.label);
            view.put("deliveryDate", chunk.deliveryDate);
            var b = chunk.building == null ? null : Hibernate.unproxy(chunk.building, Building.class);
            view.put("building", b == null ? null : Map.of("id", b.id, "buildingCode", b.buildingCode));
            var chunkLines = new ArrayList<Map<String, Object>>();
            for (var l : chunk.lines.stream().sorted(Comparator.comparingInt(x -> x.lineNumber)).toList()) {
                var p = Hibernate.unproxy(l.product, Product.class);
                var loc = l.location == null ? null : Hibernate.unproxy(l.location, BaseLocation.class);
                var product = new LinkedHashMap<String, Object>();
                product.put("id", p.id); product.put("name", p.name); product.put("ean", p.ean);
                product.put("referenceWeight", p.referenceWeight); product.put("referenceVolume", p.referenceVolume); product.put("temperatureZone", p.temperatureZone);
                var location = new LinkedHashMap<String, Object>();
                if (loc != null) { location.put("id", loc.id); location.put("area", loc instanceof ShelfLocation sl ? sl.area : null); location.put("aisle", loc instanceof ShelfLocation sl2 ? sl2.aisle : null); }
                var row = new LinkedHashMap<String, Object>();
                row.put("id", l.id); row.put("lineNumber", l.lineNumber); row.put("requestedQuantity", l.requestedQuantity);
                row.put("product", product); row.put("location", loc == null ? null : location);
                chunkLines.add(row);
            }
            view.put("lines", chunkLines);
            view.put("fulfillment", Map.of("id", chunk.fulfillment == null ? 0L : Hibernate.unproxy(chunk.fulfillment, com.intermarche.backoffice.domain.oms.Fulfillment.class).id, "orderId", c.code));
            String area = common(chunkLines, "area"), aisle = common(chunkLines, "aisle");
            view.put("location", Map.of("building", b == null ? "N/A" : b.buildingCode, "area", area, "aisle", aisle));
            chunks.add(view);
        }
        var preparations = new ArrayList<OrderRef>();
        var seen = new HashSet<Long>();
        for (var list : c.preparations.values())
            for (var o : list) if (seen.add(o.id))
                preparations.add(new OrderRef(o.id, o.code, HandlingSupervisionService.kind(o), null, o.status.name(), o.requestedStart, o.requestedEnd,
                        o.assignee == null ? null : handling.employee(Hibernate.unproxy(o.assignee, Employee.class)), buildingsOf(o), "PREPARATION", null));
        preparations.sort(Comparator.comparing((OrderRef o) -> o.start() == null ? LocalDateTime.MAX : o.start()));
        return new OrderDetail(c.id, c.code, c.customer, c.status, c.deadline, c.stage, STAGE(c.stage), lines, chunks, preparations);
    }

    /**
     * Returns the location field shared by every line, like the fulfillment chunk list.
     *
     * @param lines chunk lines
     * @param field area or aisle
     * @return the common value, or an empty text
     */
    @SuppressWarnings("unchecked")
    private static String common(List<Map<String, Object>> lines, String field) {
        String value = null;
        for (var l : lines) {
            var location = (Map<String, Object>) l.get("location");
            Object v = location == null ? null : location.get(field);
            if (v == null) return "";
            if (value == null) value = v.toString();
            else if (!value.equals(v.toString())) return "";
        }
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------ handling composition

    /**
     * Indexes the handling anomalies by the handling orders they concern (subject, grouped and consequence orders).
     *
     * @param r analysis state
     */
    private void indexHandling(Run r) {
        for (var anomaly : r.anomalies.values()) {
            var ids = new LinkedHashSet<Long>();
            ids.add(anomaly.order().id());
            for (var o : anomaly.orders()) if (!"PREREQUISITE".equals(o.role())) ids.add(o.id());
            for (var o : anomaly.consequences()) ids.add(o.id());
            for (Long id : ids) r.anomaliesOfOrder.computeIfAbsent(id, k -> new ArrayList<>()).add(anomaly);
        }
    }

    /**
     * Links every commitment to the anomalies of the preparation orders that contain its lots.
     *
     * @param r analysis state
     */
    private void preparationLinks(Run r) {
        for (var c : r.a.commitments) {
            for (var p : c.activePreparations()) {
                for (var anomaly : r.anomaliesOfOrder.getOrDefault(p.id, List.of())) {
                    link(r, anomaly.id(), c, "ESTABLISHED", HandlingSupervisionService.label(p) + " " + p.code + " prépare cette commande.",
                            anomaly.order().id().equals(p.id) ? List.of() : List.of(operationNode(p, "prépare la commande")), false);
                }
            }
        }
    }

    /**
     * Links the commitments whose planned withdrawals depend on a supply of a replenishment with an anomaly, from the
     * availability evaluated by the handling supervision.
     *
     * @param r analysis state
     */
    private void supplyLinks(Run r) {
        if (r.h == null) return;
        for (var assessment : r.h.availability.history()) {
            if (!(assessment.reference() instanceof SupervisionSnapshot.Need need) || need.lotLineId() == null) continue;
            var c = r.a.byLotLine.get(need.lotLineId());
            if (c == null) continue;
            var preparation = r.h.known.get(need.orderId());
            var supplies = new ArrayList<ExpectedSupply>(assessment.requiredSupplies());
            if (LocationAvailability.LATE_SUPPLY.equals(assessment.state())) supplies.addAll(assessment.overdueSupplies());
            for (var supply : supplies) {
                Long orderId = r.h.orderOfSupply.get(supply.id);
                if (orderId == null) continue;
                var replenishment = r.h.known.get(orderId);
                for (var anomaly : r.anomaliesOfOrder.getOrDefault(orderId, List.of())) {
                    var path = new ArrayList<GraphNode>();
                    if (replenishment != null && !anomaly.order().id().equals(orderId)) path.add(operationNode(replenishment, "approvisionne l’emplacement"));
                    if (preparation != null) path.add(operationNode(preparation, "prélève " + quantity(need.quantity(), need.product()) + " de " + need.product().name));
                    link(r, anomaly.id(), c, "ESTABLISHED", dependencyText(r, assessment, need.product(), replenishment), path, shortWithout(assessment));
                }
            }
        }
    }

    /**
     * Evaluates, with the same availability state and rules as the handling supervision, the remaining withdrawals of
     * the commitments that no preparation order covers yet, at their deadline.
     *
     * @param r analysis state
     */
    private void unplanned(Run r) {
        if (r.h == null) return;
        var ordered = new ArrayList<>(r.a.commitments);
        ordered.sort(Comparator.comparing((ActivitySnapshot.Commitment c) -> c.deadline == null ? LocalDateTime.MAX : c.deadline).thenComparing(c -> c.id));
        for (var c : ordered) {
            if ("NOT_ROUTED".equals(c.stage)) continue;
            LocalDateTime neededAt = c.deadline == null ? r.end : c.deadline;
            boolean begun = c.deadline != null && !c.deadline.isAfter(r.now);
            for (var l : c.remainingLines()) {
                if (c.planned(l.lotId)) continue;
                var line = l.lineId == null ? null : c.lines.get(l.lineId);
                if (line == null) continue;
                Product product = line.product();
                BigDecimal quantity = l.remaining(c.lots.get(l.lotId));
                var a = r.h.availability.assess("C:" + c.id, c.code, l, l.locationId, l.locationCode, quantity, neededAt, begun);
                Long accountId = r.h.accountOfProduct.get(product.id);
                var where = a.buildingId() == null ? null : r.buildings.get(a.buildingId());
                if (a.available()) {
                    for (var supply : a.requiredSupplies()) {
                        Long orderId = r.h.orderOfSupply.get(supply.id);
                        if (orderId == null) continue;
                        var replenishment = r.h.known.get(orderId);
                        for (var anomaly : r.anomaliesOfOrder.getOrDefault(orderId, List.of()))
                            link(r, anomaly.id(), c, "ESTABLISHED", dependencyText(r, a, product, replenishment),
                                    replenishment != null && !anomaly.order().id().equals(orderId) ? List.of(operationNode(replenishment, "approvisionne l’emplacement")) : List.of(), shortWithout(a));
                    }
                    continue;
                }
                switch (a.state()) {
                    case LocationAvailability.LATE_SUPPLY -> lateSupply(r, c, a, product, accountId, where);
                    case LocationAvailability.UNKNOWN -> {
                        var d = draft(r, "STOCK_UNKNOWN:" + (a.buildingId() == null ? 0 : a.buildingId()), "STOCK_UNKNOWN", ActivityModel.Certainty.MISSING_DATA, Domain.STOCK);
                        if (d.title == null) {
                            d.title = "Stock inconnu pour des commandes à préparer";
                            d.causeLabel = "Stock inconnu" + (where == null ? "" : " · " + where.buildingCode);
                            d.cause = "Aucun inventaire n’est enregistré aux emplacements de prélèvement" + (where == null ? "" : " de " + where.buildingCode)
                                    + " : la disponibilité ne peut pas être vérifiée, ce qui ne signifie pas un manque.";
                            d.uncertainties.add("Un inventaire de ces emplacements permettrait de confirmer ou d’écarter le risque.");
                            if (where != null) d.buildings.put(where.id, building(where));
                            d.actions.add(new Action("OPEN_STOCK", "Ouvrir le stock des produits", true));
                            d.actions.add(new Action("OPEN_COMMITMENT", "Examiner la commande", false));
                        }
                        addProduct(d, c, product, accountId, quantity, null, a, where, unknownText(a));
                        link(r, d.id, c, "ESTIMATED", "Stock inconnu à " + Objects.toString(l.locationCode, "un emplacement non renseigné") + " pour " + quantity(quantity, product) + " de " + product.name + ".", List.of(), false);
                        deadline(d, c.deadline, "Échéance client");
                    }
                    default -> shortage(r, c, a, product, accountId, quantity, where);
                }
            }
        }
    }

    /**
     * Records a shortage of an unplanned withdrawal, with the warehouse stock that could replenish the shelf.
     *
     * @param r analysis state
     * @param c commitment
     * @param a assessment
     * @param product product
     * @param accountId stock account
     * @param quantity quantity needed
     * @param where building of the location
     */
    private void shortage(Run r, ActivitySnapshot.Commitment c, LocationAvailability.Assessment a, Product product, Long accountId,
                          BigDecimal quantity, Building where) {
        boolean now = LocationAvailability.SHORTAGE_NOW.equals(a.state());
        var d = draft(r, "STOCK_SHORTAGE:" + (a.buildingId() == null ? 0 : a.buildingId()), "STOCK_SHORTAGE",
                now ? ActivityModel.Certainty.BLOCKING : ActivityModel.Certainty.RISK, Domain.STOCK);
        if (now) d.certainty = ActivityModel.Certainty.BLOCKING;
        if (d.title == null) {
            d.title = "Quantités insuffisantes à la date requise";
            d.causeLabel = "Stock insuffisant" + (where == null ? "" : " · " + where.buildingCode);
            if (where != null) d.buildings.put(where.id, building(where));
            d.context.put("destinationBuildingId", where == null ? null : where.id);
            d.context.put("stockBuildingId", where == null ? null : where.id);
            d.actions.add(new Action("OPEN_STOCK", "Ouvrir le stock des produits", false));
            d.actions.add(new Action("OPEN_COMMITMENT", "Examiner la commande", false));
        }
        BigDecimal available = a.free().add(a.incoming()).max(BigDecimal.ZERO);
        String text = (now ? "Manque actuel : " : "Manque à l’échéance : ") + quantity(available, product) + " disponible(s) à " + a.locationCode()
                + (a.incoming().signum() > 0 ? " avec les entrées prévues" : "") + " pour " + quantity(quantity, product) + "."
                + (a.earlierConsumers().isEmpty() ? "" : " Déjà réservé par " + String.join(", ", a.earlierConsumers()) + ".");
        addProduct(d, c, product, accountId, quantity, a.physical(), a, where, text);
        link(r, d.id, c, "ESTABLISHED", product.name + " : " + quantity(available, product) + " disponible(s) pour " + quantity(quantity, product) + " à " + a.locationCode() + ".",
                List.of(new GraphNode("product:" + product.id, "PRODUCT", product.name, text, now ? "BLOCKING" : "RISK")), true);
        deadline(d, c.deadline, "Échéance client");
    }

    /**
     * Records an unplanned withdrawal waiting for an overdue supply: linked to the replenishment's anomaly when the
     * handling supervision has one, otherwise to a late-supply situation.
     *
     * @param r analysis state
     * @param c commitment
     * @param a assessment
     * @param product product
     * @param accountId stock account
     * @param where building of the location
     */
    private void lateSupply(Run r, ActivitySnapshot.Commitment c, LocationAvailability.Assessment a, Product product, Long accountId, Building where) {
        for (var supply : a.overdueSupplies()) {
            Long orderId = r.h.orderOfSupply.get(supply.id);
            var anomalies = orderId == null ? List.<Anomaly>of() : r.anomaliesOfOrder.getOrDefault(orderId, List.of());
            if (!anomalies.isEmpty()) {
                var replenishment = r.h.known.get(orderId);
                for (var anomaly : anomalies)
                    link(r, anomaly.id(), c, "ESTABLISHED", dependencyText(r, a, product, replenishment),
                            replenishment != null && !anomaly.order().id().equals(orderId) ? List.of(operationNode(replenishment, "approvisionne l’emplacement")) : List.of(), true);
                continue;
            }
            Long definition = r.a.definitionOfSupply.get(supply.id);
            if (definition != null && orderId == null) {
                // Announced by a replenishment without handling order: that situation is the cause.
                link(r, "DEFINITION:" + definition, c, "ESTABLISHED", product.name + " : le complément annoncé pour le " + time(r, supply.availableAt)
                        + " n’est pas rangé (aucun ordre de manutention).", List.of(new GraphNode("product:" + product.id, "PRODUCT", product.name, "Emplacement " + a.locationCode(), "BLOCKING")), true);
                continue;
            }
            var d = supplyDraft(r, supply, orderId == null ? null : r.h.known.get(orderId));
            link(r, d.id, c, "ESTABLISHED", product.name + " : le complément attendu le " + time(r, supply.availableAt) + " n’est pas réceptionné.",
                    List.of(new GraphNode("product:" + product.id, "PRODUCT", product.name, "Emplacement " + a.locationCode(), "BLOCKING")), true);
            deadline(d, c.deadline, "Échéance client");
        }
    }

    /**
     * Returns the situation of an overdue supply that no handling diagnostic covers.
     *
     * @param r analysis state
     * @param supply the supply
     * @param order its replenishment order, if any
     * @return the situation
     */
    private Draft supplyDraft(Run r, ExpectedSupply supply, BaseHandlingOrder order) {
        String key = order != null ? "SUPPLY_LATE:order-" + order.id : "SUPPLY_LATE:" + supply.reference.replaceFirst(":[^:]*$", "");
        var d = draft(r, key, "SUPPLY_LATE", ActivityModel.Certainty.BLOCKING, Domain.REPLENISHMENT);
        if (d.title == null) {
            String ref = order != null ? order.code : supply.reference.replaceFirst(":[^:]*$", "");
            d.title = "Arrivage attendu non réceptionné";
            d.causeLabel = "Arrivage " + ref;
            d.cause = "L’arrivage " + ref + " était attendu le " + time(r, supply.availableAt) + " et n’est pas réceptionné : ses quantités ne sont pas comptées comme disponibles.";
            if (order != null) d.operations.put(order.id, r.h == null ? null : handling.ref(r.h, order, "SUBJECT"));
            d.actions.add(new Action(order != null ? "OPEN_ORDER" : "OPEN_STOCK", order != null ? "Examiner l’ordre" : "Ouvrir le stock des produits", true));
            d.context.put("supplyReference", ref);
            if (order != null) { d.context.put("orderId", order.id); d.context.put("orderKind", HandlingSupervisionService.kind(order)); }
            deadline(d, supply.availableAt, "Arrivée prévue dépassée");
        }
        return d;
    }

    // ------------------------------------------------------------------ commitments

    /**
     * Detects the commitments to prepare soon that have no preparation order.
     *
     * @param r analysis state
     */
    private void planning(Run r) {
        for (var c : r.a.commitments) {
            if (c.deadline == null || c.remainingLines().stream().allMatch(l -> c.planned(l.lotId))) continue;
            if (!"TO_PLAN".equals(c.stage) && !"PARTIALLY_SHIPPED".equals(c.stage)) continue;
            long minutes = Duration.between(r.now, c.deadline).toMinutes();
            if (minutes > r.settings.planningLeadMinutes()) continue;
            var d = draft(r, "PLANNING", "PLANNING", minutes < 0 ? ActivityModel.Certainty.BLOCKING : ActivityModel.Certainty.RISK, Domain.ORDERS);
            if (minutes < 0) d.certainty = ActivityModel.Certainty.BLOCKING;
            if (d.title == null) {
                d.domains.add(Domain.HANDLING);
                d.title = "Préparations non planifiées à l’approche de l’échéance";
                d.causeLabel = "Aucun ordre de préparation";
                d.cause = "Des lots restent à préparer sans ordre de collecte ni d’emballage alors que l’échéance tombe dans moins de "
                        + duration(r.settings.planningLeadMinutes()) + ".";
                d.uncertainties.add("Les ordres sont créés par les règles de manutention du magasin : aucune règle ne couvre peut-être ces commandes, ou leur évaluation n’a pas encore eu lieu.");
                d.actions.add(new Action("OPEN_RULES", "Ouvrir les règles de manutention", true));
                d.actions.add(new Action("OPEN_COMMITMENT", "Examiner la commande", false));
                d.context.put("rulesCategory", "ORDER_HANDLER");
            }
            long lines = c.remainingLines().stream().filter(l -> !c.planned(l.lotId)).count();
            link(r, d.id, c, "ESTABLISHED", lines + " ligne(s) sans ordre de préparation, échéance " + time(r, c.deadline) + ".", List.of(), false);
            for (var b : c.buildings()) d.buildings.putIfAbsent(b.id, building(b));
            deadline(d, c.deadline, "Échéance client");
        }
    }

    /**
     * Detects the commitments whose products could not be routed, so that no lot can be prepared.
     *
     * @param r analysis state
     */
    private void routing(Run r) {
        for (var c : r.a.commitments) {
            if (!"NOT_ROUTED".equals(c.stage)) continue;
            var d = draft(r, "ROUTING", "ROUTING", ActivityModel.Certainty.BLOCKING, Domain.ORDERS);
            if (d.title == null) {
                d.title = "Commandes bloquées : produits non répartis";
                d.causeLabel = "Répartition impossible";
                d.cause = "Les règles de répartition n’ont pas pu affecter tous les produits à un emplacement : aucun lot de préparation n’est créé.";
                d.actions.add(new Action("OPEN_COMMITMENT", "Examiner la commande et ses emplacements", true));
                d.actions.add(new Action("OPEN_RULES", "Ouvrir les règles de répartition", false));
                d.context.put("rulesCategory", "FULFILLER");
            }
            long pending = c.chunks.stream().filter(ch -> !ch.ready()).count();
            link(r, d.id, c, "ESTABLISHED", pending > 0 ? pending + " partie(s) de la commande non répartie(s)." : "Aucun lot créé pour la commande.", List.of(), false);
            for (var b : c.buildings()) d.buildings.putIfAbsent(b.id, building(b));
            deadline(d, c.deadline, "Échéance client");
        }
    }

    /**
     * Detects preparations planned to end after the deadline of the commitments they prepare, established by the
     * planning itself. Late preparations are already covered by the handling diagnostic.
     *
     * @param r analysis state
     */
    private void latePlans(Run r) {
        for (var c : r.a.commitments) {
            if (c.deadline == null || PREPARED_STAGES.contains(c.stage)) continue;
            BaseHandlingOrder last = null;
            for (var p : c.activePreparations()) {
                if (r.h != null && r.anomaliesOfOrder.getOrDefault(p.id, List.of()).stream().anyMatch(x -> x.type() == SupervisionModel.Type.LATE)) { last = null; break; }
                if (p.requestedEnd != null && (last == null || p.requestedEnd.isAfter(last.requestedEnd))) last = p;
            }
            if (last == null || !last.requestedEnd.isAfter(c.deadline)) continue;
            var p = last;
            var d = draft(r, "LATE_PLAN:" + p.id, "LATE_PLAN", c.deadline.isAfter(r.now) ? ActivityModel.Certainty.RISK : ActivityModel.Certainty.BLOCKING, Domain.HANDLING);
            if (!c.deadline.isAfter(r.now)) d.certainty = ActivityModel.Certainty.BLOCKING;
            if (d.title == null) {
                d.title = "Préparation planifiée après l’échéance";
                d.causeLabel = p.code;
                d.cause = HandlingSupervisionService.label(p) + " " + p.code + " est planifié(e) de " + time(r, p.requestedStart) + " à " + time(r, p.requestedEnd)
                        + " : il se termine après l’échéance des commandes qu’il prépare. Le retard est établi par le planning lui-même.";
                if (r.h != null) d.operations.put(p.id, handling.ref(r.h, p, "SUBJECT"));
                d.actions.add(new Action("COMPARE_SOLUTIONS", "Comparer des solutions de planning", true));
                d.actions.add(new Action("OPEN_ORDER", "Examiner l’ordre", false));
                d.context.put("orderId", p.id);
                d.context.put("orderKind", HandlingSupervisionService.kind(p));
                d.context.put("planningStart", HandlingSupervisionService.nextQuarter(r.now));
                d.context.put("planningEnd", p.requestedEnd.toLocalDate().plusDays(1).atStartOfDay());
                for (var b : buildingsOf(p)) d.buildings.putIfAbsent(b.id(), b);
            }
            link(r, d.id, c, "ESTABLISHED", "Fin prévue de " + p.code + " à " + time(r, p.requestedEnd) + " pour une échéance à " + time(r, c.deadline) + ".", List.of(), false);
            deadline(d, c.deadline, "Échéance client");
        }
    }

    /**
     * Detects prepared commitments not shipped after their deadline, and prepared totes still outside a buffer close
     * to the departure. A prepared order waiting for its departure is normal and not reported.
     *
     * @param r analysis state
     */
    private void shipping(Run r) {
        var bufferCodes = new HashMap<Long, String>();
        if (r.h != null) for (var b : r.h.buffers) bufferCodes.put(b.id, b.bufferCode);
        for (var c : r.a.commitments) {
            if (c.deadline == null || !PREPARED_STAGES.contains(c.stage)) continue;
            long minutes = Duration.between(r.now, c.deadline).toMinutes();
            if (minutes < 0) {
                var d = draft(r, "SHIPPING_LATE", "SHIPPING_LATE", ActivityModel.Certainty.BLOCKING, Domain.SHIPPING);
                if (d.title == null) {
                    d.title = "Commandes préparées non expédiées à l’échéance";
                    d.causeLabel = "Départ non confirmé";
                    d.cause = "Les commandes sont préparées mais aucun départ n’a été confirmé avant leur échéance.";
                    d.actions.add(new Action("OPEN_SHIPPING", "Ouvrir l’expédition", true));
                    d.actions.add(new Action("OPEN_BUFFERS", "Examiner les buffers", false));
                    d.actions.add(new Action("OPEN_COMMITMENT", "Examiner la commande", false));
                }
                long late = -minutes;
                boolean immobilized = late >= r.settings.immobilizationHours() * 60L && !c.bufferOfTote.isEmpty();
                String via = STAGE(c.stage) + " · échéance dépassée de " + duration(late)
                        + (c.bufferOfTote.isEmpty() ? "" : " · " + c.bufferOfTote.size() + " bac(s) dans " + c.bufferOfTote.values().stream().distinct().map(id -> bufferCodes.getOrDefault(id, "?")).collect(Collectors.joining(", ")))
                        + (immobilized ? " (immobilisé(s) depuis plus de " + r.settings.immobilizationHours() + " h)" : "")
                        + ("PREPARED_UNTRACKED".equals(c.stage) ? " · aucun bac enregistré : le départ ne peut pas être confirmé dans Storepilot" : "");
                link(r, d.id, c, "ESTABLISHED", via, List.of(), false);
                if ("PREPARED_UNTRACKED".equals(c.stage) && d.uncertainties.stream().noneMatch(u -> u.startsWith("Préparation déclarée")))
                    d.uncertainties.add("Préparation déclarée par les lots sans bac enregistré : l’emplacement des produits n’est pas connu.");
                for (var b : c.buildings()) d.buildings.putIfAbsent(b.id, building(b));
                for (Long bufferId : c.bufferOfTote.values()) if (r.h != null) r.h.buffers.stream().filter(b -> b.id.equals(bufferId)).findFirst().ifPresent(b -> d.buffers.putIfAbsent(b.id, bufferRef(r, b, "IMMOBILIZED", "Bacs de commandes en retard d’expédition.")));
                if (!d.buffers.isEmpty()) d.context.put("bufferBuildingCode", d.buffers.values().iterator().next().building().code());
                deadline(d, c.deadline, "Échéance client dépassée");
            } else if ("PREPARED".equals(c.stage) && minutes <= r.settings.shippingLeadMinutes()) {
                var d = draft(r, "SHIPPING_DEPOSIT", "SHIPPING_DEPOSIT", ActivityModel.Certainty.RISK, Domain.SHIPPING);
                if (d.title == null) {
                    d.domains.add(Domain.BUFFERS);
                    d.title = "Bacs préparés hors buffer avant le départ";
                    d.causeLabel = "Dépôt non confirmé";
                    d.cause = "Des bacs préparés ne sont déposés dans aucun buffer à moins de " + duration(r.settings.shippingLeadMinutes()) + " de l’échéance : leur départ ne peut pas être confirmé.";
                    d.actions.add(new Action("OPEN_SHIPPING", "Ouvrir l’expédition", true));
                    d.actions.add(new Action("OPEN_BUFFERS", "Examiner les buffers", false));
                }
                link(r, d.id, c, "ESTABLISHED", c.totesOutsideBuffer.size() + " bac(s) hors buffer : " + c.totesOutsideBuffer.stream().map(t -> c.toteCodes.getOrDefault(t, "?")).collect(Collectors.joining(", ")) + ".", List.of(), false);
                for (var b : c.buildings()) d.buildings.putIfAbsent(b.id, building(b));
                deadline(d, c.deadline, "Échéance client");
            }
        }
    }

    /**
     * Reports the overdue commitments for which no cause could be established.
     *
     * @param r analysis state
     */
    private void overdue(Run r) {
        var threatened = new HashSet<Long>();
        for (var e : r.links.entrySet()) threatened.addAll(e.getValue().keySet());
        for (var c : r.a.commitments) {
            if (c.deadline == null || !c.deadline.isBefore(r.now) || threatened.contains(c.id)) continue;
            var d = draft(r, "OVERDUE", "OVERDUE", ActivityModel.Certainty.BLOCKING, Domain.ORDERS);
            if (d.title == null) {
                d.title = "Échéances dépassées sans cause établie";
                d.causeLabel = "Cause non établie";
                d.cause = "Ces commandes ont dépassé leur échéance sans qu’aucune donnée ne permette d’en établir la cause.";
                d.uncertainties.add("Vérifiez l’avancement réel : une préparation ou un départ non enregistrés expliquent souvent cet écart.");
                d.actions.add(new Action("OPEN_COMMITMENT", "Examiner la commande", true));
            }
            link(r, d.id, c, "ESTABLISHED", STAGE(c.stage) + " · échéance dépassée de " + duration(Duration.between(c.deadline, r.now).toMinutes()) + ".", List.of(), false);
            for (var b : c.buildings()) d.buildings.putIfAbsent(b.id, building(b));
            deadline(d, c.deadline, "Échéance client dépassée");
        }
    }

    /**
     * Reports the recent open commitments without deadline, whose risk cannot be evaluated.
     *
     * @param r analysis state
     */
    private void missingDeadlines(Run r) {
        for (var c : r.a.commitments) {
            if (c.deadline != null || PREPARED_STAGES.contains(c.stage)) continue;
            var d = draft(r, "DEADLINE_MISSING", "DEADLINE_MISSING", ActivityModel.Certainty.MISSING_DATA, Domain.ORDERS);
            if (d.title == null) {
                d.title = "Commandes sans échéance";
                d.causeLabel = "Échéance inconnue";
                d.cause = "Aucune date de livraison n’est renseignée : il est impossible de savoir si ces commandes sont menacées.";
                d.actions.add(new Action("OPEN_COMMITMENT", "Examiner la commande", true));
            }
            link(r, d.id, c, "ESTABLISHED", STAGE(c.stage) + ".", List.of(), false);
        }
    }

    // ------------------------------------------------------------------ team capacity

    /**
     * Computes the planned load and the capacity of the team by slot and reports the consecutive slots where the load
     * exceeds the capacity. The handling orders without employee of such slots are folded into the situation when the
     * free capacity cannot absorb them: their individual proposals remain available.
     *
     * @param r analysis state
     * @param from first slot start
     */
    private void capacity(Run r, LocalDateTime from) {
        int slotMinutes = r.settings.capacitySlotMinutes();
        var employees = new HashSet<Long>();
        for (var e : r.a.entries) {
            var person = Hibernate.unproxy(e.employee, Employee.class);
            if (person.active && person.role != Employee.EmployeeRole.ADMIN) employees.add(person.id);
        }
        var days = new HashSet<java.time.LocalDate>();
        var hours = new HashSet<String>();
        for (var e : r.a.entries) if ("SHIFT".equals(e.kind))
            for (var d = e.startsAt.toLocalDate(); !d.isAfter(e.endsAt.toLocalDate()); d = d.plusDays(1)) {
                days.add(d);
                hours.add(Hibernate.unproxy(e.employee, Employee.class).id + "@" + d);
            }
        var orders = r.h == null ? List.<BaseHandlingOrder>of() : r.h.orders.stream()
                .filter(o -> o.status != BaseHandlingOrder.HandlingOrderStatus.FAILED && o.requestedEnd.isAfter(r.now)).toList();
        Draft current = null;
        var window = new ArrayList<WorkloadSlot>();
        for (var start = from; start.isBefore(r.end); start = start.plusMinutes(slotMinutes)) {
            var stop = start.plusMinutes(slotMinutes);
            int capacity = 0, absences = 0, tasks = 0, transits = 0, assigned = 0, untracked = 0, unassigned = 0;
            for (Long person : employees) {
                var shifts = intervals(r.a.entries, person, "SHIFT", start, stop);
                var absent = intervals(r.a.entries, person, "ABSENCE", start, stop);
                int present = measure(shifts);
                int away = measure(intersect(shifts, absent));
                capacity += present - away;
                absences += away;
            }
            for (var e : r.a.entries) {
                if (!employees.contains(Hibernate.unproxy(e.employee, Employee.class).id)) continue;
                if ("TASK".equals(e.kind)) tasks += overlap(e.startsAt, e.endsAt, start, stop);
                if ("TRANSIT".equals(e.kind)) transits += overlap(e.startsAt, e.endsAt, start, stop);
            }
            for (var o : orders) {
                int minutes = overlap(o.requestedStart, o.requestedEnd, start, stop);
                if (o.assignee == null) unassigned += minutes;
                else if (hours.contains(Hibernate.unproxy(o.assignee, Employee.class).id + "@" + start.toLocalDate())) assigned += minutes;
                else untracked += minutes;
            }
            int load = assigned + unassigned + tasks + transits;
            boolean known = days.contains(start.toLocalDate());
            int shortfall = known ? Math.max(0, load - capacity) : 0;
            boolean isCurrent = !r.now.isBefore(start) && r.now.isBefore(stop);
            String situation = null;
            if (shortfall > 0) {
                if (current == null) {
                    current = capacityDraft(r, start, isCurrent);
                    window.clear();
                }
                current.context.put("planningEnd", stop);
                situation = current.id;
            } else if (current != null) {
                finishCapacity(r, current, window, orders);
                current = null;
            }
            if (!known && load + untracked > 0) {
                var d = draft(r, "HOURS_MISSING", "HOURS_MISSING", ActivityModel.Certainty.MISSING_DATA, Domain.TEAM);
                if (d.title == null) {
                    d.title = "Horaires de l’équipe non saisis";
                    d.causeLabel = "Présences non saisies";
                    d.cause = "Du travail est planifié sur des journées sans aucune présence saisie : la capacité de l’équipe est inconnue, pas nulle.";
                    d.actions.add(new Action("OPEN_PLANNING", "Ouvrir le planning de l’équipe", true));
                    d.context.put("planningStart", start);
                }
                d.context.put("planningEnd", stop);
                deadline(d, start, "Début de la charge non couverte");
                situation = d.id;
            }
            var slot = new WorkloadSlot(start, stop, isCurrent, known ? capacity : null, assigned, untracked, unassigned, tasks, transits, absences, shortfall, situation);
            r.slots.add(slot);
            if (shortfall > 0) window.add(slot);
        }
        if (current != null) finishCapacity(r, current, window, orders);
    }

    /**
     * Starts a capacity situation.
     *
     * @param r analysis state
     * @param start first overloaded slot
     * @param current whether the slot contains now
     * @return the situation
     */
    private Draft capacityDraft(Run r, LocalDateTime start, boolean current) {
        var d = draft(r, "CAPACITY:" + start, "CAPACITY", current ? ActivityModel.Certainty.BLOCKING : ActivityModel.Certainty.RISK, Domain.TEAM);
        d.title = "Capacité de l’équipe insuffisante";
        d.deadline = start;
        d.deadlineLabel = current ? "Déficit en cours" : "Début du déficit";
        d.context.put("planningStart", start.isBefore(r.now) ? HandlingSupervisionService.nextQuarter(r.now) : start);
        d.actions.add(new Action("COMPARE_SOLUTIONS", "Comparer des solutions de planning", true));
        d.actions.add(new Action("OPEN_PLANNING", "Ouvrir le planning de l’équipe", false));
        return d;
    }

    /**
     * Completes a capacity situation once its consecutive overloaded slots are known.
     *
     * @param r analysis state
     * @param d situation
     * @param window overloaded slots
     * @param orders open handling orders of the horizon
     */
    private void finishCapacity(Run r, Draft d, List<WorkloadSlot> window, List<BaseHandlingOrder> orders) {
        if (window.isEmpty()) return;
        var start = window.get(0).start();
        var stop = window.get(window.size() - 1).end();
        int capacity = window.stream().mapToInt(s -> s.capacity() == null ? 0 : s.capacity()).sum();
        int load = window.stream().mapToInt(s -> s.assigned() + s.unassigned() + s.tasks() + s.transits()).sum();
        int unassigned = window.stream().mapToInt(WorkloadSlot::unassigned).sum();
        int committed = window.stream().mapToInt(s -> s.assigned() + s.tasks() + s.transits()).sum();
        int free = Math.max(0, capacity - committed);
        d.causeLabel = "Capacité " + time(r, start) + "–" + time(r, stop);
        d.cause = "De " + time(r, start) + " à " + time(r, stop) + ", " + duration(load) + " de travail sont planifiées (manutentions, tâches et transits) pour "
                + duration(capacity) + " de présence hors absences : déficit de " + duration(load - capacity) + ".";
        d.context.put("planningEnd", stop);
        deadline(d, start, "Début du déficit");
        var inWindow = orders.stream().filter(o -> o.requestedStart.isBefore(stop) && o.requestedEnd.isAfter(start)).toList();
        var unstaffed = inWindow.stream().filter(o -> o.assignee == null).toList();
        if (unassigned > free && !unstaffed.isEmpty()) {
            d.consequences.add(unstaffed.size() + " ordre(s) sans employé (" + duration(unassigned) + ") pour " + duration(free) + " de capacité libre : ils ne pourront pas tous être affectés.");
            for (var o : unstaffed) {
                if (r.h != null) d.operations.put(o.id, handling.ref(r.h, o, "CONCERNED"));
                for (var b : buildingsOf(o)) d.buildings.putIfAbsent(b.id(), b);
                String anomalyId = "UNASSIGNED:" + o.id;
                if (r.anomalies.containsKey(anomalyId)) fold(r, anomalyId, d);
            }
        } else if (!inWindow.isEmpty()) {
            d.uncertainties.add("Les ordres sans employé tiennent dans la capacité libre : le déficit vient du travail déjà affecté, des tâches ou des transits.");
            for (var o : inWindow) {
                for (var anomaly : r.anomaliesOfOrder.getOrDefault(o.id, List.of()))
                    if (anomaly.type() == SupervisionModel.Type.ASSIGNMENT && d.related.stream().noneMatch(x -> x.situationId().equals(anomaly.id())))
                        d.related.add(new Relation(anomaly.id(), "CONCOMITANT", "Affectation incompatible sur le même créneau (" + anomaly.order().code() + ")."));
            }
        }
        var node = new GraphNode("slot:" + start, "SLOT", time(r, start) + "–" + time(r, stop), d.cause, d.certainty.name());
        d.extraNodes.add(node);
        d.causeNode = node.id();
    }

    /**
     * Folds a handling anomaly into an activity situation: its commitments, proposals and orders move there, and the
     * anomaly keeps its identity inside the situation.
     *
     * @param r analysis state
     * @param anomalyId the anomaly
     * @param d the situation
     */
    private void fold(Run r, String anomalyId, Draft d) {
        if (r.folded.containsKey(anomalyId)) return;
        var anomaly = r.anomalies.get(anomalyId);
        r.folded.put(anomalyId, d.id);
        d.anomalies.add(anomalyId);
        for (var p : anomaly.proposals()) d.proposals.add(new SituationProposal(anomalyId, p));
        if (!anomaly.proposals().isEmpty() && d.actions.stream().noneMatch(a -> "REVIEW_PROPOSALS".equals(a.code())))
            d.actions.add(Math.min(1, d.actions.size()), new Action("REVIEW_PROPOSALS", "Voir les propositions", false));
        if (d.employee == null) d.employee = anomaly.employee();
        if (d.handlingSeverity == null || anomaly.severity().ordinal() < d.handlingSeverity.ordinal()) d.handlingSeverity = anomaly.severity();
    }

    // ------------------------------------------------------------------ buffers

    /**
     * Computes the current occupancy of the buffers and reports those receiving planned deposits while full or above
     * the saturation threshold. No future occupancy is projected: departures are not planned in Storepilot.
     *
     * @param r analysis state
     */
    private void buffers(Run r) {
        if (r.h == null) return;
        var deposits = new HashMap<Long, List<BaseHandlingOrder>>();
        for (var e : r.h.bufferNeeds.entrySet()) {
            var o = r.h.known.get(e.getKey());
            if (o == null || !SupervisionSnapshot.ACTIVE.contains(o.status)) continue;
            for (var need : e.getValue()) if (!need.alreadyDeposited()) {
                var list = deposits.computeIfAbsent(need.buffer().id, k -> new ArrayList<>());
                if (!list.contains(o)) list.add(o);
            }
        }
        var immobilized = new HashMap<Long, Integer>();
        for (var c : r.a.commitments)
            if (c.deadline != null && Duration.between(c.deadline, r.now).toHours() >= r.settings.immobilizationHours())
                for (Long buffer : c.bufferOfTote.values()) immobilized.merge(buffer, 1, Integer::sum);
        for (var b : r.h.buffers) {
            var zones = new ArrayList<BufferZoneBar>();
            boolean full = false, near = false;
            for (var z : List.of(TemperatureZone.AMBIENT, TemperatureZone.CHILLED, TemperatureZone.FROZEN)) {
                int capacity = r.h.capacity(b, z), used = r.h.occupied(b, z);
                if (capacity == 0 && used == 0) continue;
                int percent = capacity == 0 ? 100 : (int) Math.round(100.0 * used / capacity);
                zones.add(new BufferZoneBar(z.name(), used, capacity, percent));
                if (used >= capacity) full = true;
                else if (percent >= r.settings.bufferSaturationPercent()) near = true;
            }
            var planned = deposits.getOrDefault(b.id, List.of());
            String state = !b.active ? "INACTIVE" : full ? "FULL" : near ? "NEAR" : "OK";
            String situation = null;
            if (b.active && (full || near) && !planned.isEmpty()) situation = bufferSituation(r, b, full, planned, zones);
            r.bufferBars.add(new BufferBar(b.id, b.bufferCode, building(Hibernate.unproxy(b.building, Building.class)), b.active, zones, planned.size(),
                    immobilized.getOrDefault(b.id, 0), state, situation));
        }
    }

    /**
     * Creates the saturation situation of a buffer receiving planned deposits.
     *
     * @param r analysis state
     * @param b buffer
     * @param full whether a temperature is full
     * @param planned operations planning a deposit
     * @param zones occupancy by temperature
     * @return the situation identifier
     */
    private String bufferSituation(Run r, Buffer b, boolean full, List<BaseHandlingOrder> planned, List<BufferZoneBar> zones) {
        boolean begun = planned.stream().anyMatch(o -> !o.requestedStart.isAfter(r.now));
        var d = draft(r, "BUFFER_LOAD:" + b.id, "BUFFER_LOAD", full && begun ? ActivityModel.Certainty.BLOCKING : ActivityModel.Certainty.RISK, Domain.BUFFERS);
        var where = Hibernate.unproxy(b.building, Building.class);
        String occupancy = zones.stream().map(z -> HandlingSupervisionService.zoneLabel(TemperatureZone.valueOf(z.zone())) + " " + z.occupied() + "/" + z.capacity()).collect(Collectors.joining(" · "));
        d.title = full ? "Buffer saturé avec des dépôts planifiés" : "Buffer proche de la saturation";
        d.causeLabel = b.bufferCode;
        d.cause = "Le buffer " + b.bufferCode + " (" + where.buildingCode + ") est " + (full ? "plein" : "occupé au-delà de " + r.settings.bufferSaturationPercent() + " %")
                + " actuellement (" + occupancy + ") alors que " + planned.size() + " opération(s) doivent y déposer des bacs.";
        d.uncertainties.add("Occupation constatée maintenant : aucune projection n’est calculée, car les départs et le nombre exact de bacs à déposer ne sont pas planifiés.");
        d.buildings.put(where.id, building(where));
        d.buffers.put(b.id, bufferRef(r, b, full ? "FULL" : "NEAR", d.cause));
        d.context.put("bufferIds", List.of(b.id));
        d.context.put("bufferBuildingCode", where.buildingCode);
        d.actions.add(new Action("OPEN_BUFFERS", "Examiner les buffers", true));
        var node = new GraphNode("buffer:" + b.id, "BUFFER", b.bufferCode, d.cause, d.certainty.name());
        d.extraNodes.add(node);
        d.causeNode = node.id();
        for (var o : planned) {
            d.operations.put(o.id, handling.ref(r.h, o, "CONCERNED"));
            deadline(d, o.requestedStart, "Premier dépôt prévu");
            String anomalyId = "BUFFER:" + b.id + ":FULL";
            if (full && r.anomalies.containsKey(anomalyId)) fold(r, anomalyId, d);
            for (Long commitment : r.a.commitmentsOfPreparation.getOrDefault(o.id, Set.of())) {
                var c = r.a.byId.get(commitment);
                if (c != null) link(r, d.id, c, full ? "ESTABLISHED" : "ESTIMATED", o.code + " doit déposer ses bacs dans " + b.bufferCode + ".",
                        List.of(operationNode(o, "dépose dans " + b.bufferCode)), false);
            }
        }
        if (d.proposals.isEmpty()) d.consequences.add("Aucune destination de remplacement n’est proposée automatiquement : examinez les buffers.");
        else d.actions.add(1, new Action("REVIEW_PROPOSALS", "Changer de destination", false));
        return d.id;
    }

    // ------------------------------------------------------------------ replenishments

    /**
     * Reports the announced replenishments without handling order and the overdue supplies no handling diagnostic
     * covers. Commitments short of a product the replenishment announces for the same building are linked as estimated.
     *
     * @param r analysis state
     */
    private void definitions(Run r) {
        var shortages = new HashMap<String, List<Map.Entry<ActivitySnapshot.Commitment, ProductLine>>>();
        for (var d : r.drafts.values()) {
            if (!"STOCK_SHORTAGE".equals(d.kind)) continue;
            for (var p : d.products) {
                var c = r.a.byId.get(p.orderId());
                if (c != null && p.building() != null) shortages.computeIfAbsent(p.productId() + ":" + p.building().id(), k -> new ArrayList<>()).add(Map.entry(c, p));
            }
        }
        var covered = new HashSet<Long>();
        for (ReplenishmentDefinition def : r.a.definitions) {
            boolean arrived = def.end.isBefore(r.now);
            var d = draft(r, "DEFINITION:" + def.id, "DEFINITION", arrived ? ActivityModel.Certainty.BLOCKING : ActivityModel.Certainty.RISK, Domain.REPLENISHMENT);
            var destination = Hibernate.unproxy(def.destinationBuilding, Building.class);
            d.title = arrived ? "Arrivée annoncée sans ordre de rangement" : "Réapprovisionnement sans ordre de manutention";
            d.causeLabel = def.code;
            d.cause = "Le réapprovisionnement " + def.code + " (" + def.lines.size() + " produit(s), disponible le " + time(r, def.end) + " dans " + destination.buildingCode
                    + ") n’a pas d’ordre de manutention : " + (arrived ? "les produits arrivés ne sont pas rangés." : "personne n’est prévu pour le rangement.");
            d.buildings.put(destination.id, building(destination));
            if (def.sourceBuilding != null) { var source = Hibernate.unproxy(def.sourceBuilding, Building.class); d.buildings.putIfAbsent(source.id, building(source)); }
            d.context.put("definitionId", def.id);
            d.actions.add(new Action("DEFINE_HANDLING", "Définir l’ordre de manutention", true));
            d.actions.add(new Action("OPEN_DEFINITION", "Voir le réapprovisionnement", false));
            deadline(d, arrived ? def.end : def.start, arrived ? "Arrivée prévue dépassée" : "Début prévu");
            for (var line : def.lines) {
                if (line.forecast != null) covered.add(Hibernate.unproxy(line.forecast, ExpectedSupply.class).id);
                var product = Hibernate.unproxy(line.product, Product.class);
                for (var e : shortages.getOrDefault(product.id + ":" + destination.id, List.of()))
                    link(r, d.id, e.getKey(), "ESTIMATED", def.code + " annonce " + quantity(line.quantity, product) + " de " + product.name
                            + " pour " + destination.buildingCode + ", sans rangement défini.", List.of(), false);
            }
        }
        for (var supply : r.a.overdueSupplies) {
            // Supplies of a replenishment order are diagnosed by the handling supervision; announced ones by their definition.
            if (covered.contains(supply.id) || r.a.definitionOfSupply.containsKey(supply.id) || r.a.orderOfOverdueSupply.containsKey(supply.id)) continue;
            supplyDraft(r, supply, null);
        }
    }

    // ------------------------------------------------------------------ stock states

    /**
     * Reports the stock availability evaluations pending for too long: the diagnostic is temporarily unavailable.
     *
     * @param r analysis state
     */
    private void stockStates(Run r) {
        if (r.a.staleAccounts == 0) return;
        var d = draft(r, "STOCK_STALE", "STOCK_STALE", ActivityModel.Certainty.STALE, Domain.STOCK);
        d.title = "Disponibilités de stock à recalculer";
        d.causeLabel = "Diagnostic de stock périmé";
        d.cause = r.a.staleAccounts + " produit(s) attendent un recalcul de leur disponibilité depuis plus de " + duration(r.settings.staleStockMinutes())
                + " : leur état « indisponible » ou « en tension » peut être périmé.";
        d.uncertainties.add("Les indicateurs de stock de cette tuile reposent sur ces états : ils seront fiables après le prochain recalcul automatique.");
        d.actions.add(new Action("REFRESH", "Actualiser", true));
    }

    // ------------------------------------------------------------------ handling situations

    /**
     * Turns every handling anomaly that no activity situation folded into a situation of the same identity.
     *
     * @param r analysis state
     */
    private void handlingSituations(Run r) {
        for (var anomaly : r.anomalies.values()) {
            if (r.folded.containsKey(anomaly.id())) continue;
            var d = new Draft();
            d.id = anomaly.id();
            d.kind = "HANDLING_" + anomaly.type().name();
            d.certainty = ActivityModel.Certainty.valueOf(anomaly.certainty().name());
            d.handlingSeverity = anomaly.severity();
            String kind = anomaly.order().kind();
            boolean supply = "REPLENISHMENT".equals(kind) || "STOCK_PROTECTION".equals(kind);
            switch (anomaly.type()) {
                case BUFFER -> d.domains.add(Domain.BUFFERS);
                case ASSIGNMENT, TRANSIT, UNASSIGNED -> d.domains.add(Domain.TEAM);
                case STOCK -> d.domains.add(Domain.STOCK);
                default -> { }
            }
            d.domains.add(supply ? Domain.REPLENISHMENT : Domain.HANDLING);
            d.title = anomaly.title();
            d.causeLabel = anomaly.order().code() + " · " + anomaly.title();
            d.cause = anomaly.explanation();
            d.impact = anomaly.impact();
            d.deadline = anomaly.deadline();
            d.deadlineLabel = anomaly.deadlineLabel();
            for (var o : anomaly.orders()) d.operations.putIfAbsent(o.id(), o);
            for (var o : anomaly.consequences()) d.operations.putIfAbsent(o.id(), o);
            d.products.addAll(anomaly.products());
            for (var b : anomaly.buffers()) d.buffers.putIfAbsent(b.id(), b);
            for (var b : anomaly.buildings()) d.buildings.putIfAbsent(b.id(), b);
            d.employee = anomaly.employee();
            d.anomalies.add(anomaly.id());
            for (var p : anomaly.proposals()) d.proposals.add(new SituationProposal(anomaly.id(), p));
            d.actions.addAll(anomaly.actions());
            d.actions.add(new Action("OPEN_HANDLING_DETAIL", "Voir le diagnostic de manutention", false));
            d.context.putAll(anomaly.context());
            r.drafts.put(d.id, d);
        }
        // Commitments linked to a folded anomaly move to the situation that folded it.
        for (var e : r.folded.entrySet()) {
            var moved = r.links.remove(e.getKey());
            if (moved == null) continue;
            var target = r.links.computeIfAbsent(e.getValue(), k -> new LinkedHashMap<>());
            moved.forEach(target::putIfAbsent);
        }
    }

    /**
     * Reports that the handling diagnostic is temporarily unavailable, so that no false "all good" is shown.
     *
     * @param r analysis state
     */
    private void handlingUnavailable(Run r) {
        var d = draft(r, "HANDLING_UNAVAILABLE", "HANDLING_UNAVAILABLE", ActivityModel.Certainty.STALE, Domain.HANDLING);
        d.title = "Diagnostic de manutention indisponible";
        d.causeLabel = "Diagnostic indisponible";
        d.cause = r.sources.stream().filter(s -> "HANDLING".equals(s.code())).map(Source::message).findFirst().orElse("La supervision de la manutention n’a pas pu être calculée.");
        d.uncertainties.add("Les retards, affectations, transits, stocks prévus et buffers des ordres de manutention ne sont pas analysés.");
        d.actions.add(new Action("REFRESH", "Actualiser", true));
    }

    // ------------------------------------------------------------------ report

    /**
     * Freezes the situations, ranks them and computes the indicators and chart series.
     *
     * @param r analysis state
     * @return the report
     */
    private ActivityModel.Report report(Run r) {
        var situationsOf = new HashMap<Long, List<String>>();
        for (var e : r.links.entrySet()) {
            if (!r.drafts.containsKey(e.getKey())) continue;
            for (Long order : e.getValue().keySet()) situationsOf.computeIfAbsent(order, k -> new ArrayList<>()).add(e.getKey());
        }
        var frozen = new ArrayList<Situation>();
        for (var d : r.drafts.values()) frozen.add(freeze(r, d, r.links.getOrDefault(d.id, new LinkedHashMap<>()), situationsOf));
        frozen.sort(Comparator.comparing(Situation::severity)
                .thenComparing(s -> s.deadline() == null ? LocalDateTime.MAX : s.deadline())
                .thenComparing(s -> -s.commitments().size())
                .thenComparing(s -> solution(s) ? 0 : 1)
                .thenComparing(Situation::id));
        var situations = new ArrayList<Situation>();
        for (int i = 0; i < frozen.size(); i++) {
            var s = frozen.get(i);
            situations.add(new Situation(s.id(), s.kind(), s.domains(), s.certainty(), s.severity(), i + 1, s.priorityReasons(), s.title(), s.summary(),
                    s.cause(), s.consequences(), s.impact(), s.uncertainties(), s.deadline(), s.deadlineLabel(), s.commitments(), s.overdueCommitments(),
                    s.operations(), s.products(), s.buffers(), s.buildings(), s.employee(), s.handlingAnomalyIds(), s.proposals(), s.actions(), s.related(),
                    s.graph(), s.context()));
        }
        var threatened = new HashSet<Long>();
        var multiple = new HashSet<Long>();
        var checks = new HashSet<Long>();
        var counted = new HashMap<Long, Integer>();
        for (var s : situations) {
            for (var c : s.commitments()) {
                if (s.certainty() == ActivityModel.Certainty.BLOCKING || s.certainty() == ActivityModel.Certainty.RISK) {
                    threatened.add(c.id());
                    if (counted.merge(c.id(), 1, Integer::sum) > 1) multiple.add(c.id());
                } else checks.add(c.id());
            }
        }
        checks.removeAll(threatened);
        var counters = new LinkedHashMap<String, Long>();
        for (var c : ActivityModel.Certainty.values()) counters.put(c.name(), situations.stream().filter(s -> s.certainty() == c).count());
        for (var v : Severity.values()) counters.put(v.name(), situations.stream().filter(s -> s.severity() == v).count());
        for (var d : Domain.values()) counters.put(d.name(), situations.stream().filter(s -> s.domains().contains(d)).count());
        var charts = new Charts(deadlineChart(r, situationsOf, threatened, checks), new WorkloadChart(r.settings.capacitySlotMinutes(), List.copyOf(r.slots), List.of(
                "Capacité : minutes de présence saisies (SHIFT) de l’équipe, absences déduites ; inconnue les jours sans aucune présence saisie.",
                "Charge : ordres de manutention ouverts sur leur créneau planifié (affectés ou non), tâches et transits planifiés entre bâtiments.",
                "Non suivi : ordres affectés à un employé sans aucun horaire saisi ce jour-là ; ils ne sont pas comparés à la capacité (la supervision de la manutention les signale).",
                "Déficit : charge au-delà de la capacité. Les ordres en retard ne sont pas reprojetés : leur nouveau créneau n’est pas connu.")),
                stageBars(r, threatened), List.copyOf(r.bufferBars), stockChart(r),caseAges(r,situations));
        var kpis = kpis(r, situations, threatened, multiple);
        sources(r);
        notes(r);
        var buildings = r.buildings.values().stream().map(this::building).sorted(Comparator.comparing(BuildingRef::code)).toList();
        var domains = Arrays.stream(Domain.values()).filter(d -> situations.stream().anyMatch(s -> s.domains().contains(d))).toList();
        var horizons = new TreeSet<>(List.of(4, 8, 24, 48, 72));
        horizons.add(r.settings.horizonHours());
        horizons.removeIf(h -> h > r.settings.maxHorizonHours());
        horizons.add(r.settings.maxHorizonHours());
        var components = new LinkedHashMap<String, Anomaly>();
        for (var s : situations) for (var id : s.handlingAnomalyIds()) if (r.anomalies.containsKey(id)) components.put(id, r.anomalies.get(id));
        var link = new HandlingLink(r.handlingReport == null ? r.now : r.handlingReport.start(), r.handlingReport == null ? r.end : r.handlingReport.end(),
                r.handlingReport == null ? 0 : r.handlingReport.analyzedOrders(), components);
        return new ActivityModel.Report(r.store.id, r.store.code, r.now, r.now, r.end, new AppliedSettings(r.horizon, r.settings), kpis, charts, situations,
                counters, threatened.size(), new ActivityModel.Filters(buildings, domains, new ArrayList<>(horizons)), List.copyOf(r.sources), List.copyOf(r.notes), link);
    }

    /**
     * Freezes one situation: commitments, summary, severity, priority reasons and graph.
     *
     * @param r analysis state
     * @param d draft
     * @param links its commitments
     * @param situationsOf situations of every commitment
     * @return the situation, not yet ranked
     */
    private Situation freeze(Run r, Draft d, LinkedHashMap<Long, Link> links, Map<Long, List<String>> situationsOf) {
        var commitments = new ArrayList<Commitment>();
        int overdue = 0;
        boolean shelfShort = false;
        for (var e : links.entrySet()) {
            var c = r.a.byId.get(e.getKey());
            if (c == null) continue;
            boolean late = c.deadline != null && c.deadline.isBefore(r.now);
            if (late) overdue++;
            shelfShort |= e.getValue().shelfShort();
            var others = situationsOf.getOrDefault(c.id, List.of()).stream().filter(id -> !id.equals(d.id)).toList();
            commitments.add(new Commitment(c.id, c.code, c.customer, c.deadline, c.stage, STAGE(c.stage), late, e.getValue().kind(), e.getValue().via(),
                    c.buildings().stream().map(this::building).toList(), others));
            for (var b : c.buildings()) d.buildings.putIfAbsent(b.id, building(b));
        }
        commitments.sort(Comparator.comparing((Commitment c) -> c.deadline() == null ? LocalDateTime.MAX : c.deadline()).thenComparing(Commitment::code));
        if (!commitments.isEmpty()) {
            d.domains.add(Domain.ORDERS);
            var first = commitments.stream().map(Commitment::deadline).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
            if (first != null && (d.deadline == null || first.isBefore(d.deadline) && !d.kind.startsWith("HANDLING_"))) { d.deadline = first; d.deadlineLabel = first.isBefore(r.now) ? "Échéance client dépassée" : "Échéance client"; }
            d.context.put("commitmentIds", commitments.stream().map(Commitment::id).toList());
            d.context.put("commitmentCode", commitments.get(0).code());
            if (d.cause != null && d.impact == null)
                d.impact = commitments.size() + " commande(s) client concernée(s)" + (overdue > 0 ? ", dont " + overdue + " déjà en retard" : "") + ".";
        }
        if (d.cause == null) d.cause = d.title;
        var severity = severity(r, d, commitments.size(), overdue);
        String summary = summary(r, d, commitments, overdue, shelfShort);
        var products = d.products.stream().collect(Collectors.toMap(p -> p.orderId() + ":" + p.productId() + ":" + p.locationCode(), p -> p, (a, b) -> a, LinkedHashMap::new)).values();
        d.context.put("accountIds", products.stream().map(ProductLine::accountId).filter(Objects::nonNull).distinct().toList());
        d.context.put("productIds", products.stream().map(ProductLine::productId).distinct().toList());
        d.context.put("buildingIds", new ArrayList<>(d.buildings.keySet()));
        var actions = d.actions.stream().collect(Collectors.toMap(Action::code, a -> a, (a, b) -> a.primary() ? a : b, LinkedHashMap::new)).values().stream()
                .filter(a -> !"REVIEW_PROPOSALS".equals(a.code()) || !d.proposals.isEmpty()).toList();
        if (actions.stream().noneMatch(Action::primary) && !actions.isEmpty()) {
            var list = new ArrayList<>(actions);
            list.set(0, new Action(list.get(0).code(), list.get(0).label(), true));
            actions = list;
        }
        if ("STOCK_SHORTAGE".equals(d.kind)) actions = stockActions(r, d, actions);
        var situation = new Situation(d.id, d.kind, List.copyOf(d.domains), d.certainty, severity, 0, List.of(), d.title, summary, d.cause,
                List.copyOf(d.consequences), d.impact, List.copyOf(d.uncertainties), d.deadline, d.deadlineLabel, commitments, overdue,
                d.operations.values().stream().filter(Objects::nonNull).toList(), List.copyOf(products), List.copyOf(d.buffers.values()),
                List.copyOf(d.buildings.values()), d.employee, List.copyOf(d.anomalies), List.copyOf(d.proposals), actions, List.copyOf(d.related),
                graph(r, d, links), new LinkedHashMap<>(d.context));
        return withReasons(r, situation, d);
    }

    /**
     * Adds the replenishment definition action to a shelf shortage when a warehouse holds the missing products.
     *
     * @param r analysis state
     * @param d situation
     * @param actions current actions
     * @return the actions
     */
    private List<Action> stockActions(Run r, Draft d, List<Action> actions) {
        Object destination = d.context.get("destinationBuildingId");
        if (!(destination instanceof Long target) || !(r.buildings.get(target) instanceof StoreBuilding)) return actions;
        var products = d.products.stream().map(ProductLine::productId).distinct().toList();
        var stock = r.a.physicalByBuilding(em, products);
        Long source = null;
        var available = new ArrayList<String>();
        for (var p : d.products) {
            for (var e : stock.getOrDefault(p.productId(), Map.of()).entrySet()) {
                if (!(r.buildings.get(e.getKey()) instanceof WarehouseBuilding) || e.getValue().signum() <= 0) continue;
                if (source == null) source = e.getKey();
                String text = p.name() + " : " + e.getValue().stripTrailingZeros().toPlainString() + " " + p.unit() + " dans " + r.buildings.get(e.getKey()).buildingCode;
                if (!available.contains(text)) available.add(text);
            }
        }
        if (source == null) {
            var list = new ArrayList<>(actions);
            d.consequences.add("Aucun stock physique de ces produits n’est enregistré dans les entrepôts du magasin.");
            return list;
        }
        d.context.put("sourceBuildingId", source);
        d.consequences.add("Stock disponible en entrepôt pour un réachalandage : " + String.join(" ; ", available) + ".");
        var list = new ArrayList<Action>();
        list.add(new Action("DEFINE_REPLENISHMENT", "Définir un réachalandage", true));
        for (var a : actions) list.add(new Action(a.code(), a.label(), false));
        return list;
    }

    /**
     * Returns the situation with its explainable priority criteria.
     *
     * @param r analysis state
     * @param s situation
     * @param d draft
     * @return the situation with its reasons
     */
    private Situation withReasons(Run r, Situation s, Draft d) {
        var reasons = new ArrayList<String>();
        reasons.add("Gravité " + severityLabel(s.severity()) + " : " + certaintyLabel(s.certainty()) + ".");
        if (d.handlingSeverity != null && s.severity().ordinal() < d.handlingSeverity.ordinal())
            reasons.add("Gravité relevée par rapport à la vue manutention (" + severityLabel(d.handlingSeverity) + ") : " + s.commitments().size() + " commande(s) client concernée(s).");
        if (s.deadline() == null) reasons.add("Aucune échéance connue.");
        else if (s.deadline().isBefore(r.now)) reasons.add(s.deadlineLabel() + " depuis " + duration(Duration.between(s.deadline(), r.now).toMinutes()) + ".");
        else reasons.add(s.deadlineLabel() + " dans " + duration(Duration.between(r.now, s.deadline()).toMinutes()) + " (" + time(r, s.deadline()) + ").");
        reasons.add(s.commitments().isEmpty() ? "Aucune commande client concernée identifiée."
                : s.commitments().size() + " commande(s) client concernée(s)" + (s.overdueCommitments() > 0 ? ", dont " + s.overdueCommitments() + " en retard" : "") + ".");
        if (!s.proposals().isEmpty()) reasons.add(s.proposals().size() + " proposition(s) validable(s) disponible(s).");
        else if (solution(s)) reasons.add("Décision possible : " + s.actions().stream().filter(Action::primary).findFirst().orElse(s.actions().get(0)).label().toLowerCase(Locale.ROOT) + ".");
        else reasons.add("Aucune solution automatique : décision à prendre dans la vue concernée.");
        return new Situation(s.id(), s.kind(), s.domains(), s.certainty(), s.severity(), s.rank(), List.copyOf(reasons), s.title(), s.summary(), s.cause(),
                s.consequences(), s.impact(), s.uncertainties(), s.deadline(), s.deadlineLabel(), s.commitments(), s.overdueCommitments(), s.operations(),
                s.products(), s.buffers(), s.buildings(), s.employee(), s.handlingAnomalyIds(), s.proposals(), s.actions(), s.related(), s.graph(), s.context());
    }

    /**
     * Tells whether a decision is directly available: a proposal, or an action that changes the situation.
     *
     * @param s situation
     * @return true when a solution exists
     */
    private static boolean solution(Situation s) {
        return !s.proposals().isEmpty() || s.actions().stream().anyMatch(a -> Set.of("DEFINE_REPLENISHMENT", "DEFINE_HANDLING", "COMPARE_SOLUTIONS", "OPEN_SHIPPING").contains(a.code()));
    }

    /**
     * Severity: proven problems are high, critical when commitments are already late or numerous; risks rise as their
     * deadline approaches and with the number of commitments; missing data stays low unless imminent. A handling
     * diagnostic is never shown less severe than in the handling view.
     *
     * @param r analysis state
     * @param d draft
     * @param commitments number of commitments
     * @param overdue number of overdue commitments
     * @return the severity
     */
    private Severity severity(Run r, Draft d, int commitments, int overdue) {
        long minutes = d.deadline == null ? Long.MAX_VALUE : Duration.between(r.now, d.deadline).toMinutes();
        int critical = r.settings.criticalOrderCount();
        Severity result = switch (d.certainty) {
            case BLOCKING -> overdue > 0 || commitments >= critical || d.critical ? Severity.CRITICAL : Severity.HIGH;
            case RISK -> minutes <= r.settings.urgentMinutes() || commitments >= critical ? Severity.HIGH : minutes <= r.horizon * 60L ? Severity.MEDIUM : Severity.LOW;
            case MISSING_DATA -> minutes <= 2L * r.settings.urgentMinutes() ? Severity.MEDIUM : Severity.LOW;
            case STALE -> commitments > 0 || "HANDLING_UNAVAILABLE".equals(d.kind) ? Severity.MEDIUM : Severity.LOW;
        };
        if (d.handlingSeverity != null && d.handlingSeverity.ordinal() < result.ordinal()) result = d.handlingSeverity;
        return result;
    }

    /**
     * Writes the one-sentence summary relating the cause to its impact on the commitments.
     *
     * @param r analysis state
     * @param d draft
     * @param commitments commitments
     * @param overdue overdue commitments
     * @param shelfShort whether the pick locations lack stock without the cause's supply
     * @return the summary
     */
    private String summary(Run r, Draft d, List<Commitment> commitments, int overdue, boolean shelfShort) {
        String cause = causePhrase(r, d, commitments.size()) + (shelfShort && d.kind.startsWith("HANDLING_") ? " et les quantités disponibles aux emplacements de prélèvement ne suffisent pas sans cet approvisionnement" : "");
        if (commitments.isEmpty()) return capitalize(cause) + ".";
        int n = commitments.size();
        String who = n == 1 ? "1 commande" : n + " commandes";
        String verb;
        if (d.kind.equals("DEADLINE_MISSING")) verb = n == 1 ? "ne peut pas être évaluée" : "ne peuvent pas être évaluées";
        else if (d.certainty == ActivityModel.Certainty.MISSING_DATA || d.certainty == ActivityModel.Certainty.STALE) verb = n == 1 ? "est à vérifier" : "sont à vérifier";
        else if (overdue == n) verb = n == 1 ? "a dépassé son échéance" : "ont dépassé leur échéance";
        else verb = (n == 1 ? "risque de manquer son échéance" : "risquent de manquer leur échéance") + (overdue > 0 ? " (" + overdue + " déjà en retard)" : "");
        return who + " " + verb + " : " + cause + ".";
    }

    /**
     * Describes the cause of a situation as a clause.
     *
     * @param r analysis state
     * @param d draft
     * @param n number of commitments, for the agreement of the possessives
     * @return the clause
     */
    private String causePhrase(Run r, Draft d, int n) {
        boolean one = n == 1;
        if (d.kind.startsWith("HANDLING_") && !d.anomalies.isEmpty()) {
            var a = r.anomalies.get(d.anomalies.get(0));
            String subject = labelOf(a.order()) + " " + a.order().code();
            return switch (a.type()) {
                case LATE -> subject + " est en retard";
                case UNASSIGNED -> subject + " n’a aucun employé affecté";
                case DEPENDENCY -> subject + " attend une étape préalable non terminée";
                case ASSIGNMENT -> a.certainty() == SupervisionModel.Certainty.MISSING_DATA ? "les horaires de l’employé affecté à " + a.order().code() + " ne sont pas renseignés"
                        : "l’affectation de " + a.order().code() + " est incompatible avec le planning de l’employé";
                case TRANSIT -> a.certainty() == SupervisionModel.Certainty.MISSING_DATA ? "la position de l’employé affecté à " + a.order().code() + " est inconnue"
                        : "l’employé affecté à " + a.order().code() + " ne peut pas être sur place à temps";
                case FIELD_OPERATION -> a.explanation();
                case STOCK -> "des quantités manquent pour " + subject;
                case BUFFER -> "le buffer de destination de " + a.order().code() + " pose problème (" + a.title().toLowerCase(Locale.ROOT) + ")";
            };
        }
        return switch (d.kind) {
            case "CAPACITY" -> "la capacité de l’équipe est insuffisante (" + Objects.toString(d.causeLabel, "").replace("Capacité ", "") + ")";
            case "BUFFER_LOAD" -> "le buffer " + d.causeLabel + (d.title.contains("saturé") ? " est saturé" : " est proche de la saturation");
            case "STOCK_SHORTAGE" -> "les quantités disponibles aux emplacements de prélèvement sont insuffisantes à la date requise";
            case "STOCK_UNKNOWN" -> "le stock des emplacements de prélèvement est inconnu";
            case "SUPPLY_LATE" -> "l’arrivage " + Objects.toString(d.context.get("supplyReference"), "") + " attendu n’est pas réceptionné";
            case "DEFINITION" -> "le réapprovisionnement " + d.causeLabel + " n’a pas d’ordre de manutention";
            case "PLANNING" -> (one ? "sa" : "leur") + " préparation n’est pas planifiée";
            case "ROUTING" -> (one ? "ses" : "leurs") + " produits n’ont pas pu être répartis";
            case "LATE_PLAN" -> (one ? "sa" : "leur") + " préparation " + d.causeLabel + " est planifiée après l’échéance";
            case "SHIPPING_LATE" -> one ? "elle est préparée mais son départ n’est pas confirmé" : "elles sont préparées mais leur départ n’est pas confirmé";
            case "SHIPPING_DEPOSIT" -> "des bacs préparés ne sont pas déposés en buffer";
            case "OVERDUE" -> "aucune cause n’a pu être établie à partir des données";
            case "DEADLINE_MISSING" -> (one ? "sa" : "leur") + " date de livraison n’est pas renseignée";
            case "HOURS_MISSING" -> "des journées de travail planifié n’ont aucune présence saisie";
            case "STOCK_STALE" -> d.cause.substring(0, 1).toLowerCase(Locale.ROOT) + d.cause.substring(1).replaceFirst("\\.$", "");
            default -> d.title.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Builds the dependency graph of a situation: cause, intermediate operations, commitments.
     *
     * @param r analysis state
     * @param d draft
     * @param links commitments
     * @return the graph
     */
    private Graph graph(Run r, Draft d, LinkedHashMap<Long, Link> links) {
        var nodes = new LinkedHashMap<String, GraphNode>();
        var edges = new LinkedHashMap<String, GraphEdge>();
        String cause = d.causeNode;
        if (cause == null) {
            cause = "cause:" + d.id;
            nodes.put(cause, new GraphNode(cause, "CAUSE", Objects.toString(d.causeLabel, d.title), d.cause, d.certainty.name()));
        }
        for (var n : d.extraNodes) nodes.putIfAbsent(n.id(), n);
        for (var o : d.operations.values()) {
            if (o == null || d.anomalies.stream().anyMatch(id -> r.anomalies.containsKey(id) && r.anomalies.get(id).order().id().equals(o.id()))) continue;
            if (!"CAPACITY".equals(d.kind) && !"BUFFER_LOAD".equals(d.kind)) continue;
            String id = "operation:" + o.id();
            nodes.putIfAbsent(id, new GraphNode(id, "OPERATION", o.code(), labelOf(o) + " · " + time(r, o.start()) + "–" + time(r, o.end()), "RISK"));
            edges.putIfAbsent(cause + ">" + id, new GraphEdge(cause, id, "ESTABLISHED", "CAPACITY".equals(d.kind) ? "sans employé" : "dépôt prévu"));
        }
        int drawn = 0;
        for (var e : links.entrySet()) {
            var c = r.a.byId.get(e.getKey());
            if (c == null) continue;
            if (drawn == GRAPH_ORDERS) break;
            drawn++;
            String previous = cause;
            for (var step : e.getValue().path()) {
                nodes.putIfAbsent(step.id(), step);
                edges.putIfAbsent(previous + ">" + step.id(), new GraphEdge(previous, step.id(), e.getValue().kind(), step.detail()));
                previous = step.id();
            }
            String id = "order:" + c.id;
            boolean late = c.deadline != null && c.deadline.isBefore(r.now);
            nodes.putIfAbsent(id, new GraphNode(id, "ORDER", c.code, (c.deadline == null ? "Sans échéance" : (late ? "Échéance dépassée " : "Échéance ") + time(r, c.deadline)) + " · " + STAGE(c.stage), late ? "BLOCKING" : d.certainty.name()));
            edges.putIfAbsent(previous + ">" + id, new GraphEdge(previous, id, e.getValue().kind(), e.getValue().via()));
        }
        int hidden = (int) links.keySet().stream().filter(r.a.byId::containsKey).count() - drawn;
        return new Graph(List.copyOf(nodes.values()), List.copyOf(edges.values()), Math.max(0, hidden));
    }

    /**
     * Builds the deadline timeline: overdue commitments, then hourly buckets of the horizon.
     *
     * @param r analysis state
     * @param situationsOf situations of each commitment
     * @param threatened commitments threatened by a proven or forecast problem
     * @param checks commitments only concerned by missing or stale data
     * @return the chart
     */
    private DeadlineChart deadlineChart(Run r, Map<Long, List<String>> situationsOf, Set<Long> threatened, Set<Long> checks) {
        int bucket = r.horizon <= 8 ? 30 : r.horizon <= 48 ? 60 : 180;
        var buckets = new ArrayList<DeadlineBucket>();
        var points = new ArrayList<DeadlinePoint>();
        int late = 0;
        var start = floor(r.now, bucket);
        var counts = new LinkedHashMap<LocalDateTime, int[]>();
        for (var t = start; t.isBefore(r.end); t = t.plusMinutes(bucket)) counts.put(t, new int[4]);
        for (var c : r.a.commitments) {
            if (c.deadline == null) continue;
            String state = c.deadline.isBefore(r.now) ? "LATE" : threatened.contains(c.id) ? "THREATENED" : checks.contains(c.id) ? "CHECK"
                    : "READY".equals(c.stage) ? "READY" : "ON_TRACK";
            if ("LATE".equals(state)) late++;
            else {
                var key = floor(c.deadline, bucket);
                var count = counts.get(key);
                if (count != null) count[switch (state) { case "THREATENED" -> 2; case "CHECK" -> 3; case "READY" -> 1; default -> 0; }]++;
            }
            points.add(new DeadlinePoint(c.id, c.code, c.deadline, state, c.stage, situationsOf.getOrDefault(c.id, List.of())));
        }
        for (var e : counts.entrySet()) buckets.add(new DeadlineBucket(e.getKey(), e.getKey().plusMinutes(bucket), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]));
        points.sort(Comparator.comparing(DeadlinePoint::deadline).thenComparing(DeadlinePoint::code));
        int limit = 200;
        return new DeadlineChart(r.now, r.end, bucket, late, buckets, points.size() > limit ? List.copyOf(points.subList(0, limit)) : points, Math.max(0, points.size() - limit));
    }

    /**
     * Counts the commitments of each stage with their age and delay.
     *
     * @param r analysis state
     * @param threatened threatened commitments
     * @return the bars, in physical flow order
     */
    private List<StageBar> stageBars(Run r, Set<Long> threatened) {
        var result = new ArrayList<StageBar>();
        for (var e : ActivitySnapshot.STAGES.entrySet()) {
            var list = r.a.commitments.stream().filter(c -> e.getKey().equals(c.stage)).toList();
            if (list.isEmpty()) continue;
            var oldest = list.stream().filter(c -> c.orderDate != null).min(Comparator.comparing(c -> c.orderDate)).orElse(null);
            Long maxOverdue = list.stream().filter(c -> c.deadline != null && c.deadline.isBefore(r.now)).map(c -> Duration.between(c.deadline, r.now).toMinutes()).max(Long::compare).orElse(null);
            result.add(new StageBar(e.getKey(), e.getValue(), list.size(), (int) list.stream().filter(c -> threatened.contains(c.id)).count(),
                    (int) list.stream().filter(c -> c.deadline != null && c.deadline.isBefore(r.now)).count(),
                    oldest == null ? null : Duration.between(oldest.orderDate, r.now).toMinutes(), oldest == null ? null : oldest.code, maxOverdue,
                    list.stream().map(c -> c.id).toList()));
        }
        return result;
    }

    /**
     * Builds the stock chart from the availability states maintained by the stock alerts.
     *
     * @param r analysis state
     * @return the chart
     */
    private StockChart stockChart(Run r) {
        var needed = new HashMap<Long, Integer>();
        for (var c : r.a.commitments) {
            var seen = new HashSet<Long>();
            for (var l : c.lines.values()) if (seen.add(l.product().id)) needed.merge(l.product().id, 1, Integer::sum);
        }
        var products = new ArrayList<StockProduct>();
        for (Object[] row : r.a.tensionAccounts) {
            var p = SupervisionSnapshot.real(row[1], Product.class);
            products.add(new StockProduct((Long) row[0], p.id, p.name, p.ean, unit(p), p.productType.name(), (String) row[2], needed.getOrDefault(p.id, 0)));
        }
        products.sort(Comparator.comparing((StockProduct p) -> -p.neededBy()).thenComparing(p -> "UNAVAILABLE".equals(p.status()) ? 0 : 1).thenComparing(StockProduct::name));
        long total = r.a.alertStates.values().stream().mapToLong(Long::longValue).sum();
        return new StockChart((int) total, r.a.alertStates.getOrDefault("UNAVAILABLE", 0L).intValue(), r.a.alertStates.getOrDefault("TENSION", 0L).intValue(),
                r.a.alertStates.getOrDefault("AVAILABLE", 0L).intValue(), r.a.alertStates.getOrDefault("UNEVALUATED", 0L).intValue(), (int) r.a.staleAccounts,
                alertHorizonHours, r.a.oldestCheck, List.copyOf(products));
    }

    /**
     * Computes the indicators of the overview, each with its precise definition and the situations it selects.
     *
     * @param r analysis state
     * @param situations ranked situations
     * @param threatened threatened commitments
     * @param multiple commitments threatened by several situations
     * @return the indicators
     */
    private List<Kpi> kpis(Run r, List<Situation> situations, Set<Long> threatened, Set<Long> multiple) {
        var result = new ArrayList<Kpi>();
        var threatening = situations.stream().filter(s -> !s.commitments().isEmpty() && (s.certainty() == ActivityModel.Certainty.BLOCKING || s.certainty() == ActivityModel.Certainty.RISK)).map(Situation::id).toList();
        result.add(new Kpi("THREATENED_ORDERS", "Commandes menacées", (long) threatened.size(), String.valueOf(threatened.size()),
                multiple.isEmpty() ? "sur " + r.a.commitments.size() + " commande(s) ouvertes" : "dont " + multiple.size() + " par plusieurs situations",
                "Commandes non expédiées, d’échéance dépassée ou comprise dans l’horizon, compromises par au moins un problème avéré ou un risque prévisionnel. Une commande touchée par plusieurs problèmes compte une fois.",
                threatened.isEmpty() ? "OK" : "CRITICAL", threatening, true));
        var overdueIds = r.a.commitments.stream().filter(c -> c.deadline != null && c.deadline.isBefore(r.now)).map(c -> c.id).collect(Collectors.toSet());
        result.add(new Kpi("OVERDUE_ORDERS", "Échéances dépassées", (long) overdueIds.size(), String.valueOf(overdueIds.size()),
                r.a.olderOverdue > 0 ? "+ " + r.a.olderOverdue + " plus ancienne(s) que " + r.settings.overdueLookbackHours() + " h" : "commandes non expédiées",
                "Commandes ouvertes dont l’échéance client (première date de livraison) est passée sans expédition complète.",
                overdueIds.isEmpty() ? "OK" : "CRITICAL", situations.stream().filter(s -> s.overdueCommitments() > 0).map(Situation::id).toList(), true));
        var blocked = new LinkedHashSet<Long>();
        var blockedSituations = new ArrayList<String>();
        for (var s : situations) {
            boolean any = false;
            for (var id : s.handlingAnomalyIds()) {
                var a = r.anomalies.get(id);
                if (a != null && a.certainty() == SupervisionModel.Certainty.BLOCKING) { blocked.add(a.order().id()); any = true; }
            }
            if (any) blockedSituations.add(s.id());
        }
        result.add(new Kpi("BLOCKED_OPERATIONS", "Opérations bloquées", r.h == null ? null : (long) blocked.size(), r.h == null ? "—" : String.valueOf(blocked.size()),
                r.handlingReport == null ? "diagnostic indisponible" : "sur " + r.handlingReport.analyzedOrders() + " ordre(s) de manutention",
                "Ordres de manutention visés par un problème avéré de la supervision de la manutention (retard, absence d’employé au démarrage, manque actuel, dépendance bloquante…).",
                r.h == null ? "UNKNOWN" : blocked.isEmpty() ? "OK" : "WARNING", blockedSituations, r.h != null));
        int shortfall = r.slots.stream().mapToInt(WorkloadSlot::shortfall).sum();
        long overloaded = r.slots.stream().filter(s -> s.shortfall() > 0).count();
        boolean unknown = r.slots.stream().anyMatch(s -> s.capacity() == null && s.assigned() + s.untracked() + s.unassigned() + s.tasks() + s.transits() > 0);
        result.add(new Kpi("CAPACITY", "Déficit de capacité", (long) shortfall, duration(shortfall),
                overloaded > 0 ? "sur " + overloaded + " tranche(s)" + (unknown ? " · horaires manquants" : "") : unknown ? "horaires manquants : capacité inconnue" : "charge couverte",
                "Somme, sur l’horizon, de la charge planifiée (manutentions, tâches, transits) au-delà des présences saisies, absences déduites, par tranche de " + r.settings.capacitySlotMinutes() + " min.",
                shortfall > 0 ? "WARNING" : unknown ? "UNKNOWN" : "OK", situations.stream().filter(s -> s.domains().get(0) == Domain.TEAM).map(Situation::id).toList(), r.h != null));
        var stock = stockChart(r);
        result.add(new Kpi("STOCK", "Stock à risque", (long) (stock.unavailable() + stock.tension()), String.valueOf(stock.unavailable() + stock.tension()),
                stock.unavailable() + " indisponible(s) · " + stock.tension() + " en tension" + (stock.stale() > 0 ? " · " + stock.stale() + " à recalculer" : ""),
                "États des comptes de stock : indisponible quand le solde disponible après engagements et marge est épuisé maintenant ; en tension quand l’épuisement est prévu dans les " + alertHorizonHours + " h.",
                stock.stale() > 0 ? "UNKNOWN" : stock.unavailable() > 0 ? "CRITICAL" : stock.tension() > 0 ? "WARNING" : "OK",
                situations.stream().filter(s -> s.domains().contains(Domain.STOCK)).map(Situation::id).toList(), stock.total() > 0));
        long full = r.bufferBars.stream().filter(b -> "FULL".equals(b.state())).count();
        long near = r.bufferBars.stream().filter(b -> "NEAR".equals(b.state())).count();
        result.add(new Kpi("BUFFERS", "Buffers saturés", r.h == null ? null : full, r.h == null ? "—" : String.valueOf(full),
                near > 0 ? "+ " + near + " au-delà de " + r.settings.bufferSaturationPercent() + " %" : "occupation actuelle",
                "Buffers actifs dont au moins une température est pleine maintenant. Aucune projection : les départs ne sont pas planifiés.",
                r.h == null ? "UNKNOWN" : r.bufferBars.stream().anyMatch(b -> b.situationId() != null) ? "WARNING" : "OK",
                situations.stream().filter(s -> s.domains().contains(Domain.BUFFERS)).map(Situation::id).toList(), r.h != null && !r.bufferBars.isEmpty()));
        long ready = r.a.commitments.stream().filter(c -> "READY".equals(c.stage)).count();
        long lateShipping = situations.stream().filter(s -> "SHIPPING_LATE".equals(s.kind())).mapToLong(s -> s.commitments().size()).sum();
        result.add(new Kpi("READY_ORDERS", "Prêtes au départ", ready, String.valueOf(ready), lateShipping > 0 ? lateShipping + " préparée(s) en retard d’expédition" : "en attente normale de leur départ",
                "Commandes entièrement préparées dont les bacs sont déposés en buffer, en attente de la confirmation du départ.",
                lateShipping > 0 ? "WARNING" : "INFO", situations.stream().filter(s -> s.domains().contains(Domain.SHIPPING)).map(Situation::id).toList(), true));
        return result;
    }

    /**
     * Completes the data sources with the commitments, planning, stock and buffers.
     *
     * @param r analysis state
     */
    private void sources(Run r) {
        r.sources.add(new Source("ORDERS", "Commandes clients", r.a.limited ? "PARTIAL" : "OK",
                r.a.limited ? "Plus de " + ActivitySnapshot.ORDER_LIMIT + " commandes ouvertes dans l’horizon : seules les premières échéances sont analysées."
                        : r.a.commitments.isEmpty() ? "Aucune commande ouverte dans l’horizon." : r.a.commitments.size() + " commande(s) ouverte(s) analysée(s).", r.now));
        boolean shifts = r.a.entries.stream().anyMatch(e -> "SHIFT".equals(e.kind));
        boolean unknown = r.slots.stream().anyMatch(s -> s.capacity() == null && s.assigned() + s.untracked() + s.unassigned() + s.tasks() + s.transits() > 0);
        r.sources.add(new Source("PLANNING", "Planning de l’équipe", !shifts ? "NO_DATA" : unknown ? "PARTIAL" : "OK",
                !shifts ? "Aucune présence saisie sur l’horizon : la capacité de l’équipe est inconnue." : unknown ? "Certaines journées chargées n’ont aucune présence saisie." : "Présences, absences, tâches et transits pris en compte.", r.now));
        long total = r.a.alertStates.values().stream().mapToLong(Long::longValue).sum();
        r.sources.add(new Source("STOCK", "Disponibilité du stock", total == 0 ? "NO_DATA" : r.a.staleAccounts > 0 ? "STALE" : "OK",
                total == 0 ? "Aucun compte de stock dans ce magasin." : r.a.staleAccounts > 0 ? r.a.staleAccounts + " état(s) de disponibilité en attente de recalcul."
                        : "États recalculés automatiquement à chaque mouvement et à chaque échéance.", r.a.oldestCheck));
        r.sources.add(new Source("BUFFERS", "Buffers", r.h == null ? "UNAVAILABLE" : r.bufferBars.isEmpty() ? "NO_DATA" : "OK",
                r.h == null ? "Occupation non chargée." : r.bufferBars.isEmpty() ? "Aucun buffer configuré." : "Occupation constatée maintenant, sans projection.", r.now));
    }

    /**
     * Lists the limits and hypotheses of the analysis.
     *
     * @param r analysis state
     */
    private void notes(Run r) {
        r.notes.add("Seules les commandes dont l’échéance tombe dans l’horizon (ou est dépassée depuis moins de " + r.settings.overdueLookbackHours() + " h) sont analysées.");
        r.notes.add("Un lien de cause à effet n’est retenu que s’il est établi par les données : lot préparé par l’ordre, ou approvisionnement sans lequel la quantité à l’emplacement de prélèvement ne couvre pas le besoin. Les liens estimés sont signalés comme tels.");
        r.notes.add("Une commande à préparer est évaluée à son échéance : les quantités sont réservées dans l’ordre des ordres planifiés, puis des échéances.");
        r.notes.add("Les buffers sont évalués sur leur occupation actuelle : aucune libération ni départ futur n’est supposé.");
        if (r.a.olderOverdue > 0) r.notes.add(r.a.olderOverdue + " commande(s) ont dépassé leur échéance depuis plus de " + r.settings.overdueLookbackHours() + " h : elles ne sont pas détaillées.");
        r.notes.add("Seuils : " + r.settings.source() + ".");
        r.notes.addAll(r.settings.warnings());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Returns the draft of a situation, creating it when needed; a later detection never lowers its certainty.
     *
     * @param r analysis state
     * @param id identity
     * @param kind situation code
     * @param certainty certainty of the detection
     * @param domain cause domain
     * @return the draft
     */
    private CaseAgeChart caseAges(Run r,List<Situation> situations){
        var buckets=new ArrayList<CaseAgeBucket>();long[] bounds={0,60,240,1440,Long.MAX_VALUE};String[] names={"Moins d’une heure","1 à 4 heures","4 à 24 heures","Plus de 24 heures"};
        for(int n=0;n<4;n++){final long from=bounds[n],to=bounds[n+1];var ids=situations.stream().filter(s->s.context().get("ageMinutes") instanceof Number age&&age.longValue()>=from&&age.longValue()<to).map(Situation::id).distinct().toList();buckets.add(new CaseAgeBucket("AGE_"+n,names[n],ids.size(),ids));}
        return new CaseAgeChart(r.now,"Dossiers ouverts effectivement chargés : terrain, qualité, comptages, retours, départs, impressions. Une identité source n’est comptée qu’une fois. Les limites de lecture sont précisées dans les sources.",buckets);
    }

    private void operationalCases(Run r) {
        var loaded=r.operationalCases==null?OperationalDiagnostics.load(r.store):r.operationalCases;
        r.sources.add(new Source("FIELD_OPERATIONS","Dossiers opérationnels",loaded.limited()?"PARTIAL":"OK",loaded.limited()?"Lecture limitée à 200 dossiers par famille ; ouvrez la tuile métier pour la suite.":"Dossiers terrain, qualité, comptages, retours, départs et impressions lus à la source.",r.now));
        for(var c:loaded.cases()) {
            var d=draft(r,c.id(),"OPERATIONAL_CASE",c.blocking()?ActivityModel.Certainty.BLOCKING:ActivityModel.Certainty.RISK,Domain.valueOf(c.domain()));
            d.title=c.title();d.cause=c.detail();d.impact="Décision attendue dans le dossier source.";d.context.putAll(c.context());if(c.handlingOrderId()!=null)d.context.put("orderId",c.handlingOrderId());d.context.put("sourceCaseId",c.id());d.context.put("openedAt",c.since());d.context.put("ageMinutes",Math.max(0,Duration.between(c.since(),r.now).toMinutes()));d.actions.add(new Action(c.action(),c.actionLabel(),true));
            for(var commitment:r.a.commitments)if(c.customerOrderId()!=null&&c.customerOrderId().equals(commitment.id)||c.productId()!=null&&commitment.lines.values().stream().anyMatch(l->c.productId().equals(l.product().id)))link(r,d.id,commitment,c.customerOrderId()!=null?"ESTABLISHED":"ESTIMATED",c.customerOrderId()!=null?"Départ de cette commande":"Dossier portant sur un produit de la commande ; vérifiez le lot concerné",List.of(),false);
        }
    }

    private Draft draft(Run r, String id, String kind, ActivityModel.Certainty certainty, Domain domain) {
        var d = r.drafts.get(id);
        if (d == null) {
            d = new Draft();
            d.id = id;
            d.kind = kind;
            d.certainty = certainty;
            d.domains.add(domain);
            r.drafts.put(id, d);
        } else if (certainty.ordinal() < d.certainty.ordinal()) d.certainty = certainty;
        return d;
    }

    /**
     * Links a commitment to a situation, keeping the first explanation.
     *
     * @param r analysis state
     * @param situation situation identity (handling anomaly or activity situation)
     * @param c commitment
     * @param kind ESTABLISHED or ESTIMATED
     * @param via explanation
     * @param path intermediate nodes
     * @param shelfShort whether the location lacks stock without the cause's supply
     */
    private void link(Run r, String situation, ActivitySnapshot.Commitment c, String kind, String via, List<GraphNode> path, boolean shelfShort) {
        var map = r.links.computeIfAbsent(situation, k -> new LinkedHashMap<>());
        var existing = map.get(c.id);
        if (existing == null || "ESTIMATED".equals(existing.kind()) && "ESTABLISHED".equals(kind))
            map.put(c.id, new Link(kind, via, path, shelfShort || existing != null && existing.shelfShort()));
        else if (shelfShort && !existing.shelfShort()) map.put(c.id, new Link(existing.kind(), existing.via(), existing.path(), true));
    }

    /**
     * Keeps the earliest deadline of a situation.
     *
     * @param d draft
     * @param at candidate deadline
     * @param label its meaning
     */
    private static void deadline(Draft d, LocalDateTime at, String label) {
        if (at == null) return;
        if (d.deadline == null || at.isBefore(d.deadline)) { d.deadline = at; d.deadlineLabel = label; }
    }

    /**
     * Adds a product line to a stock situation.
     *
     * @param d draft
     * @param c commitment
     * @param p product
     * @param accountId stock account
     * @param required quantity needed
     * @param physical physical balance, null when unknown
     * @param a assessment
     * @param where building
     * @param text explanation
     */
    private void addProduct(Draft d, ActivitySnapshot.Commitment c, Product p, Long accountId, BigDecimal required, BigDecimal physical,
                            LocationAvailability.Assessment a, Building where, String text) {
        d.products.add(new ProductLine(c.id, p.id, accountId, p.name, p.ean, unit(p), p.productType.name(), required, physical, a.incoming(), a.overdue(),
                where == null ? null : building(where), a.locationCode(), a.state(), c.code + " · " + text));
    }

    /**
     * Explains an unknown stock.
     *
     * @param a assessment
     * @return the text
     */
    private static String unknownText(LocationAvailability.Assessment a) {
        return switch (Objects.toString(a.unknownReason(), "")) {
            case "NO_LOCATION" -> "Emplacement de prélèvement non renseigné.";
            case "OUTSIDE_STORE" -> "Emplacement " + a.locationCode() + " hors des bâtiments du magasin.";
            default -> "Aucun inventaire enregistré à " + a.locationCode() + " : le stock est inconnu, pas nécessairement nul.";
        };
    }

    /**
     * Explains why a withdrawal depends on a replenishment.
     *
     * @param r analysis state
     * @param a assessment
     * @param product product
     * @param replenishment replenishment order, if known
     * @return the text
     */
    private String dependencyText(Run r, LocationAvailability.Assessment a, Product product, BaseHandlingOrder replenishment) {
        String source = replenishment == null ? "un approvisionnement" : replenishment.code;
        BigDecimal free = a.free() == null ? BigDecimal.ZERO : a.free().max(BigDecimal.ZERO);
        return "Sans " + source + ", " + quantity(free, product) + " de " + product.name + " disponible(s) à " + a.locationCode() + " pour " + quantity(a.quantity(), product)
                + " nécessaire(s) à " + time(r, a.neededAt()) + ".";
    }

    /**
     * Tells whether the free stock of an assessment does not cover its need on its own.
     *
     * @param a assessment
     * @return true when the need depends on supplies
     */
    private static boolean shortWithout(LocationAvailability.Assessment a) {
        return a.free() != null && a.free().compareTo(a.quantity()) < 0;
    }

    /**
     * Builds the graph node of a handling order.
     *
     * @param o order
     * @param role what it does for the next node
     * @return the node
     */
    private GraphNode operationNode(BaseHandlingOrder o, String role) {
        return new GraphNode("operation:" + o.id, "OPERATION", o.code, role, "OK");
    }

    /**
     * Builds the buffer view of a situation.
     *
     * @param r analysis state
     * @param b buffer
     * @param status FULL, NEAR or IMMOBILIZED
     * @param explanation explanation
     * @return the view
     */
    private BufferRef bufferRef(Run r, Buffer b, String status, String explanation) {
        var zones = new ArrayList<BufferZone>();
        for (var z : List.of(TemperatureZone.AMBIENT, TemperatureZone.CHILLED, TemperatureZone.FROZEN)) {
            int capacity = r.h.capacity(b, z), used = r.h.occupied(b, z);
            zones.add(new BufferZone(z.name(), used, capacity, Math.max(0, capacity - used)));
        }
        return new BufferRef(b.id, b.bufferCode, building(Hibernate.unproxy(b.building, Building.class)), b.active, zones, status, explanation);
    }

    /**
     * Returns the buildings of a handling order.
     *
     * @param raw order
     * @return the buildings
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
     * Builds a building reference.
     *
     * @param b building
     * @return the reference with STORE or WAREHOUSE type
     */
    BuildingRef building(Building b) {
        var real = Hibernate.unproxy(b, Building.class);
        return new BuildingRef(real.id, real.buildingCode, real instanceof WarehouseBuilding ? "WAREHOUSE" : "STORE");
    }

    /**
     * Returns the label of an order reference type, starting with a lower-case article.
     *
     * @param o order reference
     * @return the label
     */
    private static String labelOf(OrderRef o) {
        return switch (o.kind()) {
            case "STOCK_PROTECTION" -> "la mise en sécurité";
            case "REPLENISHMENT" -> "TRUCK_UNLOADING".equals(o.operation()) ? "le déchargement camion" : "le réachalandage";
            case "GROUPING" -> "le regroupement";
            case "PACKING" -> "le packing";
            case "ZONE_COLLECTING" -> "la collecte de zone";
            default -> "la collecte produits";
        };
    }

    /**
     * Returns the label of a stage.
     *
     * @param stage stage code
     * @return the label
     */
    private static String STAGE(String stage) {
        return ActivitySnapshot.STAGES.getOrDefault(stage, stage);
    }

    /**
     * Returns the French label of a severity.
     *
     * @param s severity
     * @return the label
     */
    private static String severityLabel(Severity s) {
        return switch (s) {
            case CRITICAL -> "critique";
            case HIGH -> "élevée";
            case MEDIUM -> "moyenne";
            case LOW -> "faible";
        };
    }

    /**
     * Returns the French label of a certainty.
     *
     * @param c certainty
     * @return the label
     */
    private static String certaintyLabel(ActivityModel.Certainty c) {
        return switch (c) {
            case BLOCKING -> "problème avéré";
            case RISK -> "risque prévisionnel";
            case MISSING_DATA -> "donnée manquante";
            case STALE -> "donnée périmée ou diagnostic indisponible";
        };
    }

    /**
     * Capitalizes a sentence.
     *
     * @param text text
     * @return the text with an upper-case first letter
     */
    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * Returns the start of the slot containing an instant.
     *
     * @param at instant
     * @param minutes slot duration
     * @return the slot start
     */
    static LocalDateTime floor(LocalDateTime at, int minutes) {
        var day = at.toLocalDate().atStartOfDay();
        long elapsed = Duration.between(day, at).toMinutes();
        return day.plusMinutes(elapsed - elapsed % minutes);
    }

    /**
     * Returns the minutes of the overlap of two periods.
     *
     * @param start first start
     * @param end first end
     * @param from second start
     * @param to second end
     * @return the overlap in minutes
     */
    static int overlap(LocalDateTime start, LocalDateTime end, LocalDateTime from, LocalDateTime to) {
        if (start == null || end == null) return 0;
        var a = start.isAfter(from) ? start : from;
        var b = end.isBefore(to) ? end : to;
        return b.isAfter(a) ? (int) Duration.between(a, b).toMinutes() : 0;
    }

    /**
     * Returns the merged intervals of one kind of planning entry of an employee inside a slot.
     *
     * @param entries planning entries
     * @param employee employee
     * @param kind entry kind
     * @param from slot start
     * @param to slot end
     * @return merged intervals clipped to the slot
     */
    private static List<LocalDateTime[]> intervals(List<PlanningEntry> entries, Long employee, String kind, LocalDateTime from, LocalDateTime to) {
        var list = new ArrayList<LocalDateTime[]>();
        for (var e : entries) {
            if (!kind.equals(e.kind) || !Objects.equals(Hibernate.unproxy(e.employee, Employee.class).id, employee)) continue;
            var a = e.startsAt.isAfter(from) ? e.startsAt : from;
            var b = e.endsAt.isBefore(to) ? e.endsAt : to;
            if (b.isAfter(a)) list.add(new LocalDateTime[]{a, b});
        }
        list.sort(Comparator.comparing(x -> x[0]));
        var merged = new ArrayList<LocalDateTime[]>();
        for (var x : list) {
            if (!merged.isEmpty() && !x[0].isAfter(merged.get(merged.size() - 1)[1])) {
                var last = merged.get(merged.size() - 1);
                if (x[1].isAfter(last[1])) last[1] = x[1];
            } else merged.add(new LocalDateTime[]{x[0], x[1]});
        }
        return merged;
    }

    /**
     * Intersects two sets of merged intervals.
     *
     * @param a first set
     * @param b second set
     * @return the intersection
     */
    private static List<LocalDateTime[]> intersect(List<LocalDateTime[]> a, List<LocalDateTime[]> b) {
        var result = new ArrayList<LocalDateTime[]>();
        for (var x : a) for (var y : b) {
            var start = x[0].isAfter(y[0]) ? x[0] : y[0];
            var end = x[1].isBefore(y[1]) ? x[1] : y[1];
            if (end.isAfter(start)) result.add(new LocalDateTime[]{start, end});
        }
        return result;
    }

    /**
     * Returns the total minutes of intervals.
     *
     * @param intervals intervals
     * @return minutes
     */
    private static int measure(List<LocalDateTime[]> intervals) {
        return intervals.stream().mapToInt(x -> (int) Duration.between(x[0], x[1]).toMinutes()).sum();
    }

    /**
     * Formats a time, with the day when it is not today.
     *
     * @param r analysis state
     * @param at instant
     * @return the text
     */
    private static String time(Run r, LocalDateTime at) {
        if (at == null) return "—";
        return at.toLocalDate().equals(r.now.toLocalDate()) ? at.format(TIME) : at.format(DAY_TIME);
    }

    /**
     * Formats a duration in minutes.
     *
     * @param minutes minutes
     * @return the text
     */
    static String duration(long minutes) {
        if (minutes < 60) return minutes + " min";
        if (minutes < 48 * 60) return minutes / 60 + " h " + String.format("%02d", minutes % 60);
        return minutes / (24 * 60) + " j " + (minutes % (24 * 60)) / 60 + " h";
    }

    /**
     * Formats a quantity with the unit of its product.
     *
     * @param value quantity
     * @param p product
     * @return the text
     */
    private static String quantity(BigDecimal value, Product p) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString() + " " + unit(p);
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
}
