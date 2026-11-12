package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.store.BaseLocation;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.wms.handling.PreparingOrder;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.intermarche.backoffice.engine.supervision.SupervisionSnapshot.real;

/**
 * Customer commitments and transversal data of one store for the activity supervision, loaded with a bounded number
 * of grouped queries: no query per order, line or product, and never the whole catalogue.
 */
final class ActivitySnapshot {

    /** Maximum number of open commitments analyzed in one call. */
    static final int ORDER_LIMIT = 400;
    /** Order statuses that end a commitment. */
    static final List<String> CLOSED = List.of("SHIPPED", "CANCELLED", "CANCELED", "DELIVERED");
    /** Lot statuses that declare the preparation complete, even without recorded picks. */
    static final Set<Lot.LotStatus> PREPARED = EnumSet.of(Lot.LotStatus.COLLECTED, Lot.LotStatus.PACKED, Lot.LotStatus.DELIVERED);
    /** Maximum size of an IN list. */
    private static final int CHUNK = 900;

    /** Stage labels, in physical flow order (stages are counted independently, not as a funnel). */
    static final Map<String, String> STAGES = new LinkedHashMap<>();

    static {
        STAGES.put("NOT_ROUTED", "Produits non répartis");
        STAGES.put("TO_PLAN", "Préparation à planifier");
        STAGES.put("PLANNED", "Préparation planifiée");
        STAGES.put("IN_PROGRESS", "En préparation");
        STAGES.put("PREPARED", "Préparée, dépôt à confirmer");
        STAGES.put("PREPARED_UNTRACKED", "Préparée, bacs non tracés");
        STAGES.put("READY", "Prête au départ");
        STAGES.put("PARTIALLY_SHIPPED", "Partiellement expédiée");
    }

    /**
     * One fulfillment line.
     *
     * @param id line identifier
     * @param chunkId chunk
     * @param lineNumber line number
     * @param product product
     * @param requested requested quantity
     * @param locationId pick location
     * @param locationCode location code
     */
    record Line(Long id, Long chunkId, int lineNumber, Product product, BigDecimal requested, Long locationId, String locationCode) {}

    /**
     * One chunk of a commitment.
     *
     * @param id chunk identifier
     * @param ready whether the chunk was routed (READY)
     * @param label label
     * @param deadline delivery date
     * @param building building
     */
    record Chunk(Long id, boolean ready, String label, LocalDateTime deadline, Building building) {}

    /** Progress of one lot line. */
    static final class LotLineState {
        Long id;
        Long lotId;
        Long lineId;
        BigDecimal quantity;
        Long locationId;
        String locationCode;
        BigDecimal picked = BigDecimal.ZERO;
        BigDecimal shipped = BigDecimal.ZERO;
        final List<Long> picks = new ArrayList<>();
        final Set<Long> totes = new LinkedHashSet<>();

        /**
         * Returns the quantity still to prepare: none once the lot is declared prepared.
         *
         * @param lotStatus status of the lot
         * @return the remaining quantity
         */
        BigDecimal remaining(Lot.LotStatus lotStatus) {
            if (PREPARED.contains(lotStatus)) return BigDecimal.ZERO;
            return quantity.subtract(picked).max(BigDecimal.ZERO);
        }
    }

    /** A customer commitment with its progress. */
    static final class Commitment {
        Long id;
        String code;
        String customer;
        String status;
        LocalDateTime orderDate;
        LocalDateTime deadline;
        ReservationRecord.ReservationStatus routing;
        final List<Chunk> chunks = new ArrayList<>();
        final Map<Long, Line> lines = new LinkedHashMap<>();
        final Map<Long, Lot.LotStatus> lots = new LinkedHashMap<>();
        final Map<Long, Long> chunkOfLot = new HashMap<>();
        final List<LotLineState> lotLines = new ArrayList<>();
        final Map<Long, List<BaseHandlingOrder>> preparations = new HashMap<>();
        final Map<Long, Long> bufferOfTote = new LinkedHashMap<>();
        final Set<Long> totesOutsideBuffer = new LinkedHashSet<>();
        final Map<Long, String> toteCodes = new HashMap<>();
        final Set<Long> loadedTotes=new HashSet<>();
        final Map<Long,String> parcelAddresses=new HashMap<>();
        int readyParcelItems,unlocatedParcelItems;
        String stage;

        /**
         * Returns the buildings of the chunks.
         *
         * @return buildings without duplicates
         */
        List<Building> buildings() {
            var result = new LinkedHashMap<Long, Building>();
            for (var c : chunks) if (c.building() != null) result.putIfAbsent(c.building().id, c.building());
            return new ArrayList<>(result.values());
        }

        /**
         * Returns the active collecting and packing orders, those that withdraw the remaining quantities.
         *
         * @return the orders, without duplicates
         */
        List<BaseHandlingOrder> activeWithdrawals() {
            var result = new LinkedHashMap<Long, BaseHandlingOrder>();
            for (var list : preparations.values())
                for (var o : list) if (!(o instanceof GroupingOrder) && SupervisionSnapshot.ACTIVE.contains(o.status)) result.putIfAbsent(o.id, o);
            return new ArrayList<>(result.values());
        }

        /**
         * Returns every active preparation (collecting, packing and grouping).
         *
         * @return the orders, without duplicates
         */
        List<BaseHandlingOrder> activePreparations() {
            var result = new LinkedHashMap<Long, BaseHandlingOrder>();
            for (var list : preparations.values())
                for (var o : list) if (SupervisionSnapshot.ACTIVE.contains(o.status)) result.putIfAbsent(o.id, o);
            return new ArrayList<>(result.values());
        }

        /**
         * Tells whether a lot is withdrawn by an active collecting or packing order.
         *
         * @param lotId lot
         * @return true when planned
         */
        boolean planned(Long lotId) {
            return preparations.getOrDefault(lotId, List.of()).stream()
                    .anyMatch(o -> !(o instanceof GroupingOrder) && SupervisionSnapshot.ACTIVE.contains(o.status));
        }

        /**
         * Returns the lot lines that still need a withdrawal.
         *
         * @return the lot lines with a remaining quantity
         */
        List<LotLineState> remainingLines() {
            return lotLines.stream().filter(l -> l.remaining(lots.get(l.lotId)).signum() > 0).toList();
        }
    }

    final Store store;
    final LocalDateTime now;
    final LocalDateTime end;
    final LocalDateTime since;
    final List<Commitment> commitments = new ArrayList<>();
    final Map<Long, Commitment> byId = new HashMap<>();
    final Map<Long, Commitment> byLotLine = new HashMap<>();
    final Map<Long, Set<Long>> commitmentsOfPreparation = new HashMap<>();
    boolean limited;
    long olderOverdue;
    final List<PlanningEntry> entries = new ArrayList<>();
    final Map<String, Long> alertStates = new HashMap<>();
    long staleAccounts;
    LocalDateTime oldestCheck;
    final List<Object[]> tensionAccounts = new ArrayList<>();
    final List<ReplenishmentDefinition> definitions = new ArrayList<>();
    final List<ExpectedSupply> overdueSupplies = new ArrayList<>();
    final Map<Long, Long> definitionOfSupply = new HashMap<>();
    final Map<Long, Long> orderOfOverdueSupply = new HashMap<>();

    /**
     * Creates an empty snapshot.
     *
     * @param store the store
     * @param now reference time
     * @param end horizon end
     * @param since oldest overdue deadline loaded
     */
    private ActivitySnapshot(Store store, LocalDateTime now, LocalDateTime end, LocalDateTime since) {
        this.store = store;
        this.now = now;
        this.end = end;
        this.since = since;
    }

    /**
     * Loads the open commitments of the window and their progress.
     *
     * @param em entity manager
     * @param store the store, already scoped to the caller
     * @param now reference time
     * @param end horizon end
     * @param since oldest overdue deadline loaded
     * @return the snapshot
     */
    static ActivitySnapshot load(EntityManager em, Store store, LocalDateTime now, LocalDateTime end, LocalDateTime since) {
        var a = new ActivitySnapshot(store, now, end, since);
        a.loadCommitments(em);
        a.loadProgress(em);
        for (var c : a.commitments) a.stage(c);
        return a;
    }

    /**
     * Selects the open orders whose deadline falls in the window (or is unknown for a recent order), then their chunks.
     *
     * @param em entity manager
     */
    private void loadCommitments(EntityManager em) {
        var rows = em.createQuery("select o.id, min(c.deliveryDate) from Fulfillment f join f.order o join f.chunks c"
                        + " where o.store = :s and (o.status is null or o.status not in :closed)"
                        + " group by o.id, o.orderDate"
                        + " having (min(c.deliveryDate) >= :since and min(c.deliveryDate) < :end) or (min(c.deliveryDate) is null and o.orderDate >= :since)"
                        + " order by min(c.deliveryDate) nulls last, o.id", Object[].class)
                .setParameter("s", store).setParameter("closed", CLOSED).setParameter("since", since).setParameter("end", end)
                .setMaxResults(ORDER_LIMIT + 1).getResultList();
        limited = rows.size() > ORDER_LIMIT;
        var ids = rows.subList(0, Math.min(rows.size(), ORDER_LIMIT)).stream().map(r -> (Long) r[0]).toList();
        olderOverdue = em.createQuery("select count(distinct o.id) from Fulfillment f join f.order o where o.store = :s"
                        + " and (o.status is null or o.status not in :closed)"
                        + " and (select min(c.deliveryDate) from FulfillmentChunk c where c.fulfillment = f) < :since", Long.class)
                .setParameter("s", store).setParameter("closed", CLOSED).setParameter("since", since).getSingleResult();
        loadOrders(em, ids);
    }

    /**
     * Loads one order of the store, whatever its deadline and status, for the detail view.
     *
     * @param em entity manager
     * @param store the store, already scoped to the caller
     * @param now reference time
     * @param orderId order identifier
     * @return the snapshot, empty when the order does not belong to the store
     */
    static ActivitySnapshot loadOne(EntityManager em, Store store, LocalDateTime now, Long orderId) {
        var a = new ActivitySnapshot(store, now, now, now);
        var ids = em.createQuery("select o.id from Fulfillment f join f.order o where o.store = :s and o.id = :id", Long.class)
                .setParameter("s", store).setParameter("id", orderId).getResultList();
        a.loadOrders(em, ids);
        a.loadProgress(em);
        for (var c : a.commitments) a.stage(c);
        return a;
    }

    /**
     * Loads orders, chunks, lines and lots of the given orders.
     *
     * @param em entity manager
     * @param ids order identifiers, in deadline order
     */
    private void loadOrders(EntityManager em, List<Long> ids) {
        if (ids.isEmpty()) return;
        for (var part : parts(ids)) {
            for (Object[] row : em.createQuery("select o.id, o.orderId, o.status, o.orderDate, cu, f.reservationRecord.status from Fulfillment f join f.order o"
                    + " left join o.customer cu where o.id in :ids", Object[].class).setParameter("ids", part).getResultList()) {
                var c = new Commitment();
                c.id = (Long) row[0];
                c.code = (String) row[1];
                c.status = (String) row[2];
                c.orderDate = (LocalDateTime) row[3];
                Customer customer = row[4] == null ? null : real(row[4], Customer.class);
                c.customer = customer == null ? "" : (Objects.toString(customer.firstName, "") + " " + Objects.toString(customer.lastName, "")).trim();
                c.routing = (ReservationRecord.ReservationStatus) row[5];
                byId.put(c.id, c);
            }
        }
        for (Long id : ids) if (byId.containsKey(id)) commitments.add(byId.get(id));
        var chunkIds = new ArrayList<Long>();
        var commitmentOfChunk = new HashMap<Long, Commitment>();
        for (var part : parts(ids)) {
            for (Object[] row : em.createQuery("select c.id, c.type, c.label, c.deliveryDate, b, f.order.id from FulfillmentChunk c join c.fulfillment f"
                    + " left join c.building b where f.order.id in :ids", Object[].class).setParameter("ids", part).getResultList()) {
                var commitment = byId.get((Long) row[5]);
                var chunk = new Chunk((Long) row[0], FulfillmentChunk.READY.equals(row[1]), (String) row[2], (LocalDateTime) row[3],
                        row[4] == null ? null : real(row[4], Building.class));
                commitment.chunks.add(chunk);
                commitmentOfChunk.put(chunk.id(), commitment);
                chunkIds.add(chunk.id());
                if (chunk.deadline() != null && (commitment.deadline == null || chunk.deadline().isBefore(commitment.deadline))) commitment.deadline = chunk.deadline();
            }
        }
        for (var part : parts(chunkIds)) {
            for (Object[] row : em.createQuery("select c.id, l.id, l.lineNumber, l.requestedQuantity, p, loc from FulfillmentChunk c join c.lines l join l.product p"
                    + " left join l.location loc where c.id in :ids", Object[].class).setParameter("ids", part).getResultList()) {
                var location = row[5] == null ? null : real(row[5], BaseLocation.class);
                var line = new Line((Long) row[1], (Long) row[0], ((Number) row[2]).intValue(), real(row[4], Product.class), (BigDecimal) row[3],
                        location == null ? null : location.id, location == null ? null : location.locationCode);
                commitmentOfChunk.get(line.chunkId()).lines.put(line.id(), line);
            }
            for (Object[] row : em.createQuery("select lot.id, lot.status, lot.chunk.id from Lot lot where lot.chunk.id in :ids", Object[].class)
                    .setParameter("ids", part).getResultList()) {
                var commitment = commitmentOfChunk.get((Long) row[2]);
                commitment.lots.put((Long) row[0], (Lot.LotStatus) row[1]);
                commitment.chunkOfLot.put((Long) row[0], (Long) row[2]);
            }
        }
    }

    /**
     * Loads lot lines, picks, shipped quantities, totes and the preparations of the lots.
     *
     * @param em entity manager
     */
    private void loadProgress(EntityManager em) {
        var commitmentOfLot = new HashMap<Long, Commitment>();
        for (var c : commitments) for (Long lot : c.lots.keySet()) commitmentOfLot.put(lot, c);
        if (commitmentOfLot.isEmpty()) return;
        var lotIds = new ArrayList<>(commitmentOfLot.keySet());
        var lotLines = new HashMap<Long, LotLineState>();
        for (var part : parts(lotIds)) {
            for (Object[] row : em.createQuery("select lot.id, ll.id, fl.id, loc, ll.quantity from Lot lot join lot.lines ll left join ll.fulfillmentLine fl"
                    + " left join ll.location loc where lot.id in :ids", Object[].class).setParameter("ids", part).getResultList()) {
                var state = new LotLineState();
                state.lotId = (Long) row[0];
                state.id = (Long) row[1];
                state.lineId = (Long) row[2];
                var location = row[3] == null ? null : real(row[3], BaseLocation.class);
                var commitment = commitmentOfLot.get(state.lotId);
                var line = state.lineId == null ? null : commitment.lines.get(state.lineId);
                state.locationId = location != null ? location.id : line == null ? null : line.locationId();
                state.locationCode = location != null ? location.locationCode : line == null ? null : line.locationCode();
                state.quantity = row[4] == null ? BigDecimal.ZERO : (BigDecimal) row[4];
                commitment.lotLines.add(state);
                lotLines.put(state.id, state);
                byLotLine.put(state.id, commitment);
            }
            for (Object[] row : em.createQuery("select lot.id, o from PreparingOrder o join o.lots lot where lot.id in :ids", Object[].class)
                    .setParameter("ids", part).getResultList()) {
                var order = real(row[1], BaseHandlingOrder.class);
                var commitment = commitmentOfLot.get((Long) row[0]);
                var list = commitment.preparations.computeIfAbsent((Long) row[0], k -> new ArrayList<>());
                if (list.stream().noneMatch(o -> o.id.equals(order.id))) list.add(order);
                commitmentsOfPreparation.computeIfAbsent(order.id, k -> new LinkedHashSet<>()).add(commitment.id);
            }
        }
        if (lotLines.isEmpty()) return;
        var pickOwner = new HashMap<Long, LotLineState>();
        for (var part : parts(new ArrayList<>(lotLines.keySet()))) {
            for (Object[] row : em.createQuery("select p.lotLine.id, p.id, p.quantity, t.id, b.id, tt.code,t.loadingDeparture.id from PickedItem p left join p.loadedTote t"
                            + " left join t.buffer b left join t.tote tt where p.lotLine.id in :ids and p.status = :picked", Object[].class)
                    .setParameter("ids", part).setParameter("picked", PickedItem.PickStatus.PICKED).getResultList()) {
                var state = lotLines.get((Long) row[0]);
                state.picked = state.picked.add((BigDecimal) row[2]);
                state.picks.add((Long) row[1]);
                pickOwner.put((Long) row[1], state);
                var commitment = commitmentOfLot.get(state.lotId);
                if (row[3] != null) {
                    state.totes.add((Long) row[3]);
                    commitment.toteCodes.put((Long) row[3], (String) row[5]);
                    if (row[4] != null) commitment.bufferOfTote.put((Long) row[3], (Long) row[4]);
                    else if(row[6]!=null)commitment.loadedTotes.add((Long)row[3]);else commitment.totesOutsideBuffer.add((Long) row[3]);
                }
            }
        }
        if (pickOwner.isEmpty()) return;
        var shippedTotes = new HashSet<Long>();
        for (var part : parts(new ArrayList<>(pickOwner.keySet()))) {
            for (Object[] row : em.createQuery("select sl.pickedItem.id, sl.quantity, tote.id from ShipmentLine sl left join sl.loadedTote tote where sl.pickedItem.id in :ids", Object[].class)
                    .setParameter("ids", part).getResultList()) {
                var state = pickOwner.get((Long) row[0]);
                state.shipped = state.shipped.add(row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
                shippedTotes.add((Long) row[2]);
            }
        }
        for(var part:parts(new ArrayList<>(pickOwner.keySet())))for(Object[] row:em.createQuery("select pc.pickedItem.id,p.code,p.currentAddress,p.state,b.bufferCode from ParcelContent pc join pc.parcel p left join p.currentBuffer b where pc.pickedItem.id in :ids and p.materialized=true",Object[].class).setParameter("ids",part).getResultList()){
            var state=pickOwner.get((Long)row[0]);var commitment=commitmentOfLot.get(state.lotId);String address=Objects.toString(row[2],Objects.toString(row[4],"Adresse à vérifier"));commitment.parcelAddresses.put((Long)row[0],"Colis "+row[1]+" · "+address);if("LOADED".equals(row[3])||row[4]!=null)commitment.readyParcelItems++;else if(!"DISPATCHED".equals(row[3]))commitment.unlocatedParcelItems++;
        }
        for (var c : commitments) {
            for (Long tote : shippedTotes) { c.bufferOfTote.remove(tote); c.totesOutsideBuffer.remove(tote); c.loadedTotes.remove(tote); }
        }
    }

    /**
     * Computes the stage of a commitment from its routing, lots, preparations, picks, totes and shipments.
     *
     * @param c the commitment
     */
    private void stage(Commitment c) {
        if (c.chunks.isEmpty() || c.chunks.stream().anyMatch(ch -> !ch.ready()) || c.lots.isEmpty()) { c.stage = "NOT_ROUTED"; return; }
        boolean shippedSome = c.lotLines.stream().anyMatch(l -> l.shipped.signum() > 0);
        var remaining = c.remainingLines();
        if (!remaining.isEmpty()) {
            if (remaining.stream().anyMatch(l -> !c.planned(l.lotId))) c.stage = "TO_PLAN";
            else if (c.lotLines.stream().anyMatch(l -> l.picked.signum() > 0)
                    || c.activeWithdrawals().stream().anyMatch(o -> o.status == BaseHandlingOrder.HandlingOrderStatus.PROCESSING)) c.stage = "IN_PROGRESS";
            else c.stage = "PLANNED";
            if (shippedSome) c.stage = "PARTIALLY_SHIPPED";
            return;
        }
        if (shippedSome) { c.stage = "PARTIALLY_SHIPPED"; return; }
        // Collected, but still to pack or to group: the preparation goes on.
        if (!c.activePreparations().isEmpty()) { c.stage = "IN_PROGRESS"; return; }
        boolean tracked = c.lotLines.stream().anyMatch(l -> !l.picks.isEmpty());
        if (!tracked) c.stage = "PREPARED_UNTRACKED";
        else c.stage = c.totesOutsideBuffer.isEmpty() && c.unlocatedParcelItems==0 && (!c.bufferOfTote.isEmpty()||!c.loadedTotes.isEmpty()||c.readyParcelItems>0) ? "READY" : "PREPARED";
    }

    /**
     * Returns the locations and products of the remaining withdrawals that no active preparation covers, which the
     * handling analysis must load for the activity evaluation.
     *
     * @return locations and products
     */
    Set<Long> unplannedLocations() {
        var result = new HashSet<Long>();
        for (var c : commitments) if ("TO_PLAN".equals(c.stage) || "PARTIALLY_SHIPPED".equals(c.stage))
            for (var l : c.remainingLines()) if (!c.planned(l.lotId) && l.locationId != null) result.add(l.locationId);
        return result;
    }

    /**
     * Returns the products of the open commitments.
     *
     * @return product identifiers
     */
    Set<Long> products() {
        var result = new HashSet<Long>();
        for (var c : commitments) for (var l : c.lines.values()) result.add(l.product().id);
        return result;
    }

    /**
     * Loads the planning entries overlapping the workload window.
     *
     * @param em entity manager
     * @param from window start
     * @param to window end
     */
    void loadPlanning(EntityManager em, LocalDateTime from, LocalDateTime to) {
        entries.addAll(em.createQuery("select e from PlanningEntry e join fetch e.employee where e.employee.store = :s and e.cancelled = false"
                + " and e.startsAt < :to and e.endsAt > :from", PlanningEntry.class).setParameter("s", store).setParameter("from", from).setParameter("to", to).getResultList());
    }

    /**
     * Loads the availability states of the stock accounts maintained by the stock alerts, with their freshness.
     *
     * @param em entity manager
     * @param staleBefore pending evaluations older than this are stale
     */
    void loadStockStates(EntityManager em, LocalDateTime staleBefore) {
        for (Object[] row : em.createQuery("select a.alertStatus, count(a) from StockAccount a where a.store = :s group by a.alertStatus", Object[].class)
                .setParameter("s", store).getResultList())
            alertStates.put(row[0] == null ? "UNEVALUATED" : (String) row[0], (Long) row[1]);
        Object[] stale = em.createQuery("select count(a), min(a.alertCheckedAt) from StockAccount a where a.store = :s and ((a.alertDirty = true and a.updatedAt < :before)"
                        + " or a.alertNextCheckAt < :before or (a.alertCheckedAt is null and a.createdAt < :before))", Object[].class)
                .setParameter("s", store).setParameter("before", staleBefore).getSingleResult();
        staleAccounts = ((Number) stale[0]).longValue();
        oldestCheck = em.createQuery("select min(a.alertCheckedAt) from StockAccount a where a.store = :s", LocalDateTime.class).setParameter("s", store).getSingleResult();
        var needed = products();
        var states = List.of("UNAVAILABLE", "TENSION");
        if (!needed.isEmpty())
            for (var part : parts(new ArrayList<>(needed)))
                tensionAccounts.addAll(em.createQuery("select a.id, p, a.alertStatus from StockAccount a join a.product p where a.store = :s and a.alertStatus in :states and p.id in :ids order by p.name", Object[].class)
                        .setParameter("s", store).setParameter("states", states).setParameter("ids", part).getResultList());
        var known = new HashSet<Long>();
        for (var row : tensionAccounts) known.add((Long) row[0]);
        if (tensionAccounts.size() < 24)
            for (Object[] row : em.createQuery("select a.id, p, a.alertStatus from StockAccount a join a.product p where a.store = :s and a.alertStatus in :states order by a.alertStatus desc, p.name", Object[].class)
                    .setParameter("s", store).setParameter("states", states).setMaxResults(24).getResultList())
                if (known.add((Long) row[0]) && tensionAccounts.size() < 24) tensionAccounts.add(row);
    }

    /**
     * Loads the replenishment definitions without handling order and the overdue expected supplies of the window.
     *
     * @param em entity manager
     */
    void loadSupplies(EntityManager em) {
        definitions.addAll(em.createQuery("select distinct d from ReplenishmentDefinition d left join fetch d.lines l left join fetch l.product"
                        + " where d.store = :s and d.handlingOrder is null and d.start < :end and d.end >= :since order by d.start", ReplenishmentDefinition.class)
                .setParameter("s", store).setParameter("end", end).setParameter("since", since).getResultList());
        overdueSupplies.addAll(em.createQuery("select x from ExpectedSupply x join fetch x.account a join fetch a.product where a.store = :s and x.status = :expected"
                        + " and x.availableAt < :now and x.availableAt >= :since order by x.availableAt, x.id", ExpectedSupply.class)
                .setParameter("s", store).setParameter("expected", ExpectedSupply.SupplyStatus.EXPECTED).setParameter("now", now).setParameter("since", since)
                .setMaxResults(300).getResultList());
        if (overdueSupplies.isEmpty()) return;
        var ids = overdueSupplies.stream().map(x -> x.id).toList();
        for (Object[] row : em.createQuery("select l.forecast.id, d.id from ReplenishmentDefinition d join d.lines l where l.forecast.id in :ids", Object[].class)
                .setParameter("ids", ids).getResultList())
            definitionOfSupply.put((Long) row[0], (Long) row[1]);
        for (Object[] row : em.createQuery("select s.id, o.id from ReplenishmentOrder o join o.supplies s where s.id in :ids", Object[].class)
                .setParameter("ids", ids).getResultList())
            orderOfOverdueSupply.put((Long) row[0], (Long) row[1]);
    }

    /**
     * Returns the current physical stock of products by building, for the products given.
     *
     * @param em entity manager
     * @param products product identifiers
     * @return quantities by product, then building
     */
    Map<Long, Map<Long, BigDecimal>> physicalByBuilding(EntityManager em, Collection<Long> products) {
        var result = new HashMap<Long, Map<Long, BigDecimal>>();
        if (products.isEmpty()) return result;
        for (Object[] row : em.createQuery("select a.product.id, b.id, sum(m.quantity) from StockMovement m join m.account a, Store s join s.buildings b join b.locations l"
                        + " where s = :s and a.store = :s and l.id = m.location.id and a.product.id in :ids and m.effectiveAt <= :now group by a.product.id, b.id", Object[].class)
                .setParameter("s", store).setParameter("ids", products).setParameter("now", now).getResultList())
            result.computeIfAbsent((Long) row[0], k -> new HashMap<>()).put((Long) row[1], (BigDecimal) row[2]);
        return result;
    }

    /**
     * Splits identifiers into IN-list sized parts.
     *
     * @param ids identifiers
     * @param <T> identifier type
     * @return the parts
     */
    static <T> List<List<T>> parts(List<T> ids) {
        var result = new ArrayList<List<T>>();
        for (int i = 0; i < ids.size(); i += CHUNK) result.add(ids.subList(i, Math.min(ids.size(), i + CHUNK)));
        return result;
    }
}
