package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.executing.ReplenishmentExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.workforce.BuildingTransit;
import com.intermarche.backoffice.domain.workforce.EmployeePosition;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.engine.storeops.ConstrainedPlanningSolver;
import com.intermarche.backoffice.engine.storeops.PlanningAssignmentService;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Everything the supervision needs about one store, loaded with a bounded number of grouped queries
 * (no query per order, line or product, and never the whole product catalogue).
 */
final class SupervisionSnapshot {

    /** Statuses of orders that still require work. */
    static final List<BaseHandlingOrder.HandlingOrderStatus> ACTIVE = List.of(
            BaseHandlingOrder.HandlingOrderStatus.OPEN, BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,
            BaseHandlingOrder.HandlingOrderStatus.PROCESSING, BaseHandlingOrder.HandlingOrderStatus.FAILED);

    /** Maximum number of open orders analyzed in one call. */
    static final int ORDER_LIMIT = 300;

    /**
     * Quantity still to move for one product of one order.
     *
     * @param stage PICKING (warehouse or shelf withdrawal) or COLLECTING (customer preparation)
     * @param lotLineId lot line of a customer preparation, null for a replenishment line
     */
    record Need(Long orderId, Product product, Long locationId, String locationCode, BigDecimal quantity, String stage, Long lotLineId) {}

    /** A buffer reserved by an order as its destination, with the temperatures it must accept. */
    record BufferNeed(Long orderId, Buffer buffer, Set<TemperatureZone> zones, boolean alreadyDeposited, boolean editable) {}

    final Store store;
    final LocalDateTime now;
    final LocalDateTime start;
    final LocalDateTime end;
    final List<BaseHandlingOrder> orders = new ArrayList<>();
    boolean limited;
    final Map<Long, BaseHandlingOrder> known = new HashMap<>();
    final Map<Long, List<HandlingExecution>> executions = new HashMap<>();
    final Map<Long, Building> buildings = new HashMap<>();
    final Map<Long, Employee> employees = new HashMap<>();
    final Map<Long, EmployeePosition> positions = new HashMap<>();
    final Map<String, Integer> transits = new HashMap<>();
    boolean transitControl;
    PlanningAssignmentService.Context planning;
    final Map<Long, List<Need>> needs = new HashMap<>();
    final Map<Long, BigDecimal> physicalByLocation = new HashMap<>();
    final Set<Long> locationsWithHistory = new HashSet<>();
    final List<ExpectedSupply> incoming = new ArrayList<>();
    final Map<Long, Long> buildingOfLocation = new HashMap<>();
    final Map<Long, Long> orderOfSupply = new HashMap<>();
    final Map<Long, Long> accountOfProduct = new HashMap<>();
    final Map<Long, Set<TemperatureZone>> zonesByOrder = new HashMap<>();
    final List<Buffer> buffers = new ArrayList<>();
    final Map<Long, Map<TemperatureZone, Integer>> occupied = new HashMap<>();
    final Map<Long, Map<TemperatureZone, Integer>> cabinetCapacity = new HashMap<>();
    final Map<Long, Long> predecessorOf = new HashMap<>();
    final Map<Long, List<Long>> successorsOf = new HashMap<>();
    final Map<Long, List<Long>> prerequisitesOf = new HashMap<>();
    final Map<Long, List<Long>> dependentsOf = new HashMap<>();
    final Map<Long, List<BufferNeed>> bufferNeeds = new HashMap<>();
    final Map<Long, int[]> pickingProgress = new HashMap<>();
    final Set<Long> extraLocations = new HashSet<>();
    final Set<Long> extraProducts = new HashSet<>();
    LocationAvailability availability;

    /**
     * Creates an empty snapshot for a store and a period.
     *
     * @param store the analyzed store
     * @param now the reference time
     * @param start period start
     * @param end period end
     */
    private SupervisionSnapshot(Store store, LocalDateTime now, LocalDateTime start, LocalDateTime end) {
        this.store = store;
        this.now = now;
        this.start = start;
        this.end = end;
    }

    /**
     * Loads the snapshot of a store.
     *
     * @param em entity manager
     * @param assignments planning assignment engine, used to preload its context
     * @param store the store, already scoped to the caller
     * @param now reference time
     * @param start period start
     * @param end period end
     * @return the loaded snapshot
     */
    static SupervisionSnapshot load(EntityManager em, PlanningAssignmentService assignments, Store store,
                                    LocalDateTime now, LocalDateTime start, LocalDateTime end) {
        return load(em, assignments, store, now, start, end, Set.of(), Set.of());
    }

    /**
     * Loads the snapshot of a store, with the stock of additional locations and products evaluated afterwards by the
     * activity supervision on the same availability state.
     *
     * @param em entity manager
     * @param assignments planning assignment engine, used to preload its context
     * @param store the store, already scoped to the caller
     * @param now reference time
     * @param start period start
     * @param end period end
     * @param locations additional stock locations to load
     * @param products additional products whose stock accounts are needed
     * @return the loaded snapshot
     */
    static SupervisionSnapshot load(EntityManager em, PlanningAssignmentService assignments, Store store,
                                    LocalDateTime now, LocalDateTime start, LocalDateTime end, Set<Long> locations, Set<Long> products) {
        var s = new SupervisionSnapshot(store, now, start, end);
        s.extraLocations.addAll(locations);
        s.extraProducts.addAll(products);
        s.loadReferences(em);
        s.loadOrders(em);
        s.loadDependencies(em);
        s.loadExecutions(em);
        s.loadNeeds(em);
        s.loadStock(em);
        s.loadBuffers(em);
        LocalDateTime from = s.orders.stream().map(o -> o.requestedStart).min(LocalDateTime::compareTo).orElse(start);
        LocalDateTime to = s.orders.stream().map(o -> o.requestedEnd).max(LocalDateTime::compareTo).orElse(end);
        if (now.isBefore(from)) from = now;
        if (now.isAfter(to)) to = now;
        // The slot of a late order may be moved to now: the context covers today and every analyzed day.
        s.planning = assignments.context(store, from.toLocalDate().minusDays(1).atStartOfDay(),
                to.toLocalDate().plusDays(2).atStartOfDay());
        return s;
    }

    /**
     * Loads buildings, employees, positions and directed transits of the store.
     *
     * @param em entity manager
     */
    private void loadReferences(EntityManager em) {
        for (Building b : em.createQuery("select b from Store s join s.buildings b where s = :s", Building.class)
                .setParameter("s", store).getResultList()) {
            Building real = Hibernate.unproxy(b, Building.class);
            buildings.put(real.id, real);
        }
        for (Employee e : em.createQuery("select e from Employee e where e.store = :s", Employee.class)
                .setParameter("s", store).getResultList()) employees.put(e.id, e);
        for (EmployeePosition p : em.createQuery("select p from EmployeePosition p join fetch p.building where p.employee.store = :s",
                EmployeePosition.class).setParameter("s", store).getResultList())
            positions.put(Hibernate.unproxy(p.employee, Employee.class).id, p);
        for (BuildingTransit t : em.createQuery("select t from BuildingTransit t where t.store = :s", BuildingTransit.class)
                .setParameter("s", store).getResultList())
            transits.put(ConstrainedPlanningSolver.edge(id(t.origin), id(t.destination)), t.minutes);
        transitControl = !transits.isEmpty();
    }

    /**
     * Loads the open orders of the period, plus the late ones when the period includes the present.
     *
     * @param em entity manager
     */
    private void loadOrders(EntityManager em) {
        boolean includeLate = !start.isAfter(now);
        String jpql = "select o from BaseHandlingOrder o left join fetch o.assignee where o.store = :s and o.status in :active"
                + " and o.id not in (select fo.id from FabricationOrder fo where fo.proposalState = 'PROPOSED')"
                + " and o.requestedStart is not null and o.requestedEnd is not null and o.requestedStart < :end"
                + " and (o.requestedEnd > :start" + (includeLate ? " or o.requestedEnd <= :now" : "") + ")"
                + " order by o.requestedStart, o.id";
        var query = em.createQuery(jpql, BaseHandlingOrder.class).setParameter("s", store).setParameter("active", ACTIVE)
                .setParameter("end", end).setParameter("start", start).setMaxResults(ORDER_LIMIT + 1);
        if (includeLate) query.setParameter("now", now);
        var rows = query.getResultList();
        limited = rows.size() > ORDER_LIMIT;
        for (var o : rows.subList(0, Math.min(rows.size(), ORDER_LIMIT))) {
            var real = Hibernate.unproxy(o, BaseHandlingOrder.class);
            orders.add(real);
            known.put(real.id, real);
        }
    }

    /**
     * Loads replenishment predecessors and successors, and the preparations a grouping waits for.
     *
     * @param em entity manager
     */
    private void loadDependencies(EntityManager em) {
        var ids = ids();
        if (ids.isEmpty()) return;
        for (Object[] row : em.createQuery("select r.id, p from ReplenishmentOrder r join r.predecessor p where r.id in :ids", Object[].class)
                .setParameter("ids", ids).getResultList()) {
            var p = real(row[1], BaseHandlingOrder.class);
            known.putIfAbsent(p.id, p);
            predecessorOf.put((Long) row[0], p.id);
            successorsOf.computeIfAbsent(p.id, k -> new ArrayList<>()).add((Long) row[0]);
        }
        for (Object[] row : em.createQuery("select p.id, r from ReplenishmentOrder r join r.predecessor p where p.id in :ids and r.status in :active", Object[].class)
                .setParameter("ids", ids).setParameter("active", ACTIVE).getResultList()) {
            var r = real(row[1], BaseHandlingOrder.class);
            known.putIfAbsent(r.id, r);
            predecessorOf.put(r.id, (Long) row[0]);
            var list = successorsOf.computeIfAbsent((Long) row[0], k -> new ArrayList<>());
            if (!list.contains(r.id)) list.add(r.id);
        }
        // Preparations sharing a lot follow the physical flow: collecting, then packing, then grouping.
        for (Object[] row : em.createQuery("select distinct g, c from PreparingOrder g join g.lots l, PreparingOrder c join c.lots l2"
                        + " where l2.id = l.id and c.id <> g.id and (g.id in :ids or c.id in :ids)", Object[].class)
                .setParameter("ids", ids).getResultList()) {
            var g = real(row[0], BaseHandlingOrder.class);
            var c = real(row[1], BaseHandlingOrder.class);
            if (rank(c) >= rank(g)) continue;
            known.putIfAbsent(g.id, g);
            known.putIfAbsent(c.id, c);
            var prerequisites = prerequisitesOf.computeIfAbsent(g.id, k -> new ArrayList<>());
            if (!prerequisites.contains(c.id)) prerequisites.add(c.id);
            var dependents = dependentsOf.computeIfAbsent(c.id, k -> new ArrayList<>());
            if (!dependents.contains(g.id)) dependents.add(g.id);
        }
    }

    /**
     * Returns the position of a preparation in the physical flow.
     *
     * @param order a preparation order
     * @return 1 for collecting, 2 for packing, 3 for grouping
     */
    static int rank(BaseHandlingOrder order) {
        return order instanceof GroupingOrder ? 3 : order instanceof PackingOrder ? 2 : 1;
    }

    /**
     * Loads the executions of every known order.
     *
     * @param em entity manager
     */
    private void loadExecutions(EntityManager em) {
        if (known.isEmpty()) return;
        for (Object[] row : em.createQuery("select e.handlingOrder.id, e from HandlingExecution e where e.handlingOrder.id in :ids", Object[].class)
                .setParameter("ids", known.keySet()).getResultList())
            executions.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add(real(row[1], HandlingExecution.class));
    }

    /**
     * Loads the remaining quantities to withdraw: lot lines of preparations, and replenishment lines with a stock source.
     *
     * @param em entity manager
     */
    private void loadNeeds(EntityManager em) {
        var preparing = orders.stream().filter(o -> o instanceof PreparingOrder).map(o -> o.id).toList();
        if (!preparing.isEmpty()) {
            var lines = em.createQuery("select o.id, ll.id, p, loc, ll.quantity from PreparingOrder o join o.lots lot join lot.lines ll"
                            + " join ll.product p left join ll.location loc where o.id in :ids and lot.status <> :cancelled", Object[].class)
                    .setParameter("ids", preparing).setParameter("cancelled", com.intermarche.backoffice.domain.wms.Lot.LotStatus.CANCELED).getResultList();
            var lineIds = lines.stream().map(r -> (Long) r[1]).toList();
            var picked = new HashMap<Long, BigDecimal>();
            if (!lineIds.isEmpty())
                for (Object[] row : em.createQuery("select p.lotLine.id, sum(p.quantity) from PickedItem p where p.lotLine.id in :ids and p.status = :picked group by p.lotLine.id", Object[].class)
                        .setParameter("ids", lineIds).setParameter("picked", PickedItem.PickStatus.PICKED).getResultList())
                    picked.put((Long) row[0], (BigDecimal) row[1]);
            for (Object[] row : lines) {
                Long orderId = (Long) row[0];
                var product = real(row[2], Product.class);
                zonesByOrder.computeIfAbsent(orderId, k -> new HashSet<>()).add(capacityZone(product.temperatureZone));
                // A grouping moves already collected totes: it does not withdraw stock.
                if (known.get(orderId) instanceof GroupingOrder) continue;
                var location = row[3] == null ? null : real(row[3], BaseLocation.class);
                BigDecimal remaining = ((BigDecimal) row[4]).subtract(picked.getOrDefault((Long) row[1], BigDecimal.ZERO));
                if (remaining.signum() > 0)
                    needs.computeIfAbsent(orderId, k -> new ArrayList<>()).add(new Need(orderId, product,
                            location == null ? null : location.id, location == null ? null : location.locationCode, remaining, "COLLECTING", (Long) row[1]));
            }
        }
        var replenishments = orders.stream().filter(o -> o instanceof ReplenishmentOrder).map(o -> o.id).toList();
        if (replenishments.isEmpty()) return;
        for (Object[] row : em.createQuery("select o.id, s, p, src, dst from ReplenishmentOrder o join o.supplies s join s.account a join a.product p"
                        + " left join s.source src join s.destination dst where o.id in :ids", Object[].class)
                .setParameter("ids", replenishments).getResultList()) {
            Long orderId = (Long) row[0];
            var supply = (ExpectedSupply) row[1];
            var product = real(row[2], Product.class);
            var source = row[3] == null ? null : real(row[3], BaseLocation.class);
            var destination = real(row[4], BaseLocation.class);
            zonesByOrder.computeIfAbsent(orderId, k -> new HashSet<>()).add(capacityZone(product.temperatureZone));
            var progress = pickingProgress.computeIfAbsent(orderId, k -> new int[2]);
            if (supply.status != ExpectedSupply.SupplyStatus.CANCELLED) {
                progress[1]++;
                if (supply.status == ExpectedSupply.SupplyStatus.RECEIVED || supply.pickedQuantity().compareTo(supply.quantity) >= 0) progress[0]++;
            }
            if (destination instanceof BufferToteStockLocation place && supply.status == ExpectedSupply.SupplyStatus.EXPECTED) {
                var load = Hibernate.unproxy(place.loadedTote, LoadedTote.class);
                var buffer = Hibernate.unproxy(place.buffer, Buffer.class);
                boolean deposited = load.buffer != null && Objects.equals(Hibernate.unproxy(load.buffer, Buffer.class).id, buffer.id);
                bufferNeeds.computeIfAbsent(orderId, k -> new ArrayList<>()).add(new BufferNeed(orderId, buffer, Set.of(capacityZone(load.storageType)), deposited, false));
            }
            if (source == null || supply.status != ExpectedSupply.SupplyStatus.EXPECTED) continue;
            BigDecimal remaining = known.get(orderId) instanceof StockProtectionOrder
                    ? supply.quantity.subtract(supply.received) : supply.quantity.subtract(supply.pickedQuantity());
            if (remaining.signum() > 0)
                needs.computeIfAbsent(orderId, k -> new ArrayList<>()).add(new Need(orderId, product, source.id, source.locationCode, remaining, "PICKING", null));
        }
    }

    /**
     * Loads balances, ledger history, expected arrivals and building of every location concerned by a need,
     * and the stock accounts of the concerned products only, then prepares the shared availability state.
     *
     * @param em entity manager
     */
    private void loadStock(EntityManager em) {
        var locations = new HashSet<Long>(extraLocations);
        var products = new HashSet<Long>(extraProducts);
        for (var list : needs.values()) for (var n : list) {
            if (n.locationId() != null) locations.add(n.locationId());
            products.add(n.product().id);
        }
        if (!locations.isEmpty()) {
            for (Object[] row : em.createQuery("select m.location.id, sum(case when m.effectiveAt <= :now then m.quantity else 0 end), count(m)"
                            + " from StockMovement m where m.location.id in :ids group by m.location.id", Object[].class)
                    .setParameter("now", now).setParameter("ids", locations).getResultList()) {
                physicalByLocation.put((Long) row[0], (BigDecimal) row[1]);
                if (((Number) row[2]).longValue() > 0) locationsWithHistory.add((Long) row[0]);
            }
            incoming.addAll(em.createQuery("select s from ExpectedSupply s where s.destination.id in :ids and s.status = :expected", ExpectedSupply.class)
                    .setParameter("ids", locations).setParameter("expected", ExpectedSupply.SupplyStatus.EXPECTED).getResultList());
            if (!incoming.isEmpty())
                for (Object[] row : em.createQuery("select s.id, o.id from ReplenishmentOrder o join o.supplies s where s.id in :ids", Object[].class)
                        .setParameter("ids", incoming.stream().map(x -> x.id).toList()).getResultList())
                    orderOfSupply.put((Long) row[0], (Long) row[1]);
            for (Object[] row : em.createQuery("select l.id, b.id from Store s join s.buildings b join b.locations l where s = :s and l.id in :ids", Object[].class)
                    .setParameter("s", store).setParameter("ids", locations).getResultList())
                buildingOfLocation.put((Long) row[0], (Long) row[1]);
        }
        if (!products.isEmpty())
            for (Object[] row : em.createQuery("select a.product.id, a.id from StockAccount a where a.store = :s and a.product.id in :ids", Object[].class)
                    .setParameter("s", store).setParameter("ids", products).getResultList())
                accountOfProduct.put((Long) row[0], (Long) row[1]);
        availability = new LocationAvailability(now, physicalByLocation, locationsWithHistory, incoming, buildingOfLocation);
    }

    /**
     * Loads every buffer of the store with its current occupancy and cabinet capacities, in three grouped queries.
     * Capacities follow the rule of the buffer occupancy view: cabinet cells when a temperature has cabinets, otherwise the buffer capacity.
     *
     * @param em entity manager
     */
    private void loadBuffers(EntityManager em) {
        buffers.addAll(em.createQuery("select b from Buffer b where b.building.id in (select x.id from Store s join s.buildings x where s = :s) order by b.bufferCode", Buffer.class)
                .setParameter("s", store).getResultList());
        if (buffers.isEmpty()) return;
        var ids = buffers.stream().map(b -> b.id).toList();
        for (Object[] row : em.createQuery("select t.buffer.id, t.storageType, count(t) from LoadedTote t where t.status = :inUse and t.buffer.id in :ids group by t.buffer.id, t.storageType", Object[].class)
                .setParameter("inUse", LoadedTote.Status.IN_USE).setParameter("ids", ids).getResultList())
            occupied.computeIfAbsent((Long) row[0], k -> new EnumMap<>(TemperatureZone.class))
                    .merge(capacityZone((TemperatureZone) row[1]), ((Number) row[2]).intValue(), Integer::sum);
        for (Object[] row : em.createQuery("select c.buffer.id, c.zone, sum(c.columns * c.rows) from BufferCabinet c where c.buffer.id in :ids group by c.buffer.id, c.zone", Object[].class)
                .setParameter("ids", ids).getResultList())
            cabinetCapacity.computeIfAbsent((Long) row[0], k -> new EnumMap<>(TemperatureZone.class))
                    .put((TemperatureZone) row[1], ((Number) row[2]).intValue());
        for (var o : orders) {
            Buffer target = o instanceof GroupingOrder g ? g.buffer : o instanceof StockProtectionOrder p ? p.protectionBuffer : null;
            if (target != null) {
                var zones = zonesByOrder.getOrDefault(o.id, Set.of());
                bufferNeeds.computeIfAbsent(o.id, k -> new ArrayList<>()).add(0, new BufferNeed(o.id, Hibernate.unproxy(target, Buffer.class), zones, false, true));
            }
        }
    }

    /**
     * Returns the capacity of a buffer for a temperature.
     *
     * @param buffer the buffer
     * @param zone the capacity temperature
     * @return number of tote places
     */
    int capacity(Buffer buffer, TemperatureZone zone) {
        var cabinets = cabinetCapacity.get(buffer.id);
        if (cabinets != null && cabinets.containsKey(zone)) return cabinets.get(zone);
        return buffer.getCapacityForZone(zone);
    }

    /**
     * Returns the current number of totes of a temperature in a buffer.
     *
     * @param buffer the buffer
     * @param zone the capacity temperature
     * @return occupied places
     */
    int occupied(Buffer buffer, TemperatureZone zone) {
        return occupied.getOrDefault(buffer.id, Map.of()).getOrDefault(zone, 0);
    }

    /**
     * Returns the identifiers of the analyzed orders.
     *
     * @return identifiers
     */
    List<Long> ids() {
        return orders.stream().map(o -> o.id).toList();
    }

    /**
     * Returns the directed walking time between two buildings.
     *
     * @param from origin building
     * @param to destination building
     * @return minutes, 0 for the same building, -1 when no route is configured, null when a building is unknown
     */
    Integer travel(Long from, Long to) {
        if (from == null || to == null) return null;
        if (from.equals(to)) return 0;
        return transits.getOrDefault(ConstrainedPlanningSolver.edge(from, to), -1);
    }

    /**
     * Maps a product temperature to the buffer capacity it consumes.
     *
     * @param zone product or tote temperature
     * @return the capacity temperature
     */
    static TemperatureZone capacityZone(TemperatureZone zone) {
        return zone == TemperatureZone.HOUSEHOLD ? TemperatureZone.AMBIENT : zone;
    }

    /**
     * Unproxies a query result column.
     *
     * @param value the column value
     * @param type the expected type
     * @param <T> the expected type
     * @return the real entity
     */
    static <T> T real(Object value, Class<T> type) {
        return type.cast(Hibernate.unproxy(value));
    }

    /**
     * Returns the persistent identifier of a possibly proxied building.
     *
     * @param building the building
     * @return its identifier, or null
     */
    static Long id(Building building) {
        return building == null ? null : Hibernate.unproxy(building, Building.class).id;
    }

    /**
     * Tells whether the warehouse picking stage of a two-stage replenishment is complete.
     *
     * @param order the order
     * @return true when every line has been picked
     */
    boolean pickingDone(BaseHandlingOrder order) {
        var progress = pickingProgress.get(order.id);
        return progress != null && progress[0] >= progress[1];
    }

    /**
     * Tells whether an order has already been started by an operator.
     *
     * @param order the order
     * @return true when a non-cancelled execution exists
     */
    boolean started(BaseHandlingOrder order) {
        return executions.getOrDefault(order.id, List.of()).stream().anyMatch(e -> e.status != HandlingExecution.ExecutionStatus.CANCELLED);
    }

    /**
     * Tells whether all executions of an order are finished, which the late-order alert also treats as done.
     *
     * @param order the order
     * @return true when executions exist and are all DONE
     */
    boolean executionsDone(BaseHandlingOrder order) {
        var list = executions.getOrDefault(order.id, List.of());
        return !list.isEmpty() && list.stream().allMatch(e -> e.status == HandlingExecution.ExecutionStatus.DONE);
    }

    /**
     * Returns the phase currently executed for a replenishment, from its executions.
     *
     * @param order the order
     * @return SOURCE, DESTINATION or null
     */
    String activePhase(BaseHandlingOrder order) {
        return executions.getOrDefault(order.id, List.of()).stream()
                .filter(e -> e instanceof ReplenishmentExecution && e.status == HandlingExecution.ExecutionStatus.IN_PROGRESS)
                .map(e -> ((ReplenishmentExecution) e).phase).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
