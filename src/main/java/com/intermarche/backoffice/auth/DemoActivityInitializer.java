package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition;
import com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinitionLine;
import com.intermarche.backoffice.domain.stock.ExpectedSupply;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Targeted, additive activity supervision scenario for the LYO-042 demo store, anchored on the time of its first seed.
 * <p>
 * It adds customer orders prefixed {@code ACT-CMD-}, handling orders prefixed {@code ACT-}, one employee (Nina Robert),
 * one dock buffer and its totes. It only uses products untouched by the other scenarios: their shelf keeps its opening
 * stock, which the order quantities are sized against, and stock is only written on warehouse addresses without any
 * movement, so the existing stock scenarios keep their hypotheses. Once the first order exists, the scenario is never
 * reseeded or shifted: the manager's changes are preserved.
 * <ul>
 *   <li>Blocked shelf replenishment: ACT-RAY-BLOQUE has no employee while ACT-COL-MATIN needs its quantities for four
 *   orders, the shelf stock being taken first by ACT-COL-AVANT.</li>
 *   <li>Late arrival: the truck ACT-CAM-RETARD is overdue, ACT-COL-ARRIV waits for it for two orders.</li>
 *   <li>Stock tension: a reservation exhausts a product in the coming hours; an unplanned order lacks it on the shelf
 *   while the warehouse holds it, and an announced replenishment without handling order may cover it.</li>
 *   <li>Capacity shortfall: four unassigned operations in the evening for a single presence.</li>
 *   <li>Saturated buffer: ACT-BUF-QUAI is full while ACT-GRP-QUAI must deposit there.</li>
 *   <li>Threatened shipments: a prepared order past its deadline, prepared totes outside any buffer before the departure.</li>
 *   <li>Normal states: a prepared order waiting for its departure, an order to prepare far ahead.</li>
 *   <li>Blocked routing and preparation not planned before the deadline.</li>
 * </ul>
 */
@ApplicationScoped
public class DemoActivityInitializer {

    static final String MARKER = "ACT-CMD-0101";

    @Inject EntityManager em;
    @Inject TemporalStockService stocks;

    /**
     * Seeds the scenario once for the LYO-042 store.
     *
     * @param store the demo store
     */
    @Transactional
    public void seed(Store store) {
        if (!"LYO-042".equals(store.code) || Order.count("orderId", MARKER) > 0) return;
        em.lock(store, LockModeType.PESSIMISTIC_WRITE);
        StoreBuilding shop = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();
        WarehouseBuilding warehouse = WarehouseBuilding.find("buildingCode", "LYO-042-WH").firstResult();
        Customer customer = Customer.<Customer>findAll().firstResult();
        if (shop == null || warehouse == null || customer == null) return;
        var pairs = freshPairs(shop, warehouse);
        if (pairs.size() < 4) return;
        LocalDateTime now = DateTimeProvider.now().withSecond(0).withNano(0);
        Employee sofia = Employee.findByBadgeId("DEMO-S01");
        Employee hugo = Employee.findByBadgeId("DEMO-S02");
        Employee nina = employee(store, "DEMO-A01", "Nina", "Robert");
        entry(nina, "SHIFT", "Démo activité · présence du matin", shop, now.minusHours(1), now.plusHours(4));
        entry(nina, "SHIFT", "Démo activité · présence du soir", shop, now.plusHours(6), now.plusHours(10));

        ShelfLocation[] blocked = pairs.get(0), arrival = pairs.get(1), tension = pairs.get(2), plenty = pairs.get(3);
        stock(store, blocked[1], "demo-activity-blocked-reserve", new BigDecimal("80"), now);
        stock(store, tension[1], "demo-activity-tension-reserve", new BigDecimal("40"), now);

        // Blocked shelf replenishment: ACT-COL-AVANT first takes almost all the shelf stock for two orders, so that the
        // four orders collected afterwards by ACT-COL-MATIN all need ACT-RAY-BLOQUE; the last one is due before the collecting ends.
        int shelf = physical(blocked[0]);
        int each = 6;
        var before = new ArrayList<Lot>();
        before.add(order(store, shop, customer, "ACT-CMD-0001", now.plusMinutes(200), Map.of(blocked[0], (shelf - 2) / 2)).lot());
        before.add(order(store, shop, customer, "ACT-CMD-0002", now.plusMinutes(210), Map.of(blocked[0], shelf - 2 - (shelf - 2) / 2)).lot());
        collecting(store, shop, "ACT-COL-AVANT", nina, now.plusMinutes(20), now.plusMinutes(50), before);
        var shelving = replenishment(store, "ACT-RAY-BLOQUE", warehouse, shop, null, now.plusMinutes(75), now.plusMinutes(105));
        supply(store, shelving, blocked[1], blocked[0], new BigDecimal("30"));
        var morning = new ArrayList<Lot>();
        int[] offsets = {240, 255, 270, 150};
        for (int i = 0; i < offsets.length; i++)
            morning.add(order(store, shop, customer, "ACT-CMD-010" + (i + 1), now.plusMinutes(offsets[i]), Map.of(blocked[0], each)).lot());
        collecting(store, shop, "ACT-COL-MATIN", sofia, now.plusMinutes(130), now.plusMinutes(160), morning);

        // Late arrival: the truck unloads directly in the shop and is overdue; the shelf alone cannot serve both orders.
        int waitingEach = (physical(arrival[0]) + 4 + 1) / 2;
        var truck = replenishment(store, "ACT-CAM-RETARD", null, shop, hugo, now.minusMinutes(100), now.minusMinutes(40));
        supply(store, truck, null, arrival[0], new BigDecimal("30"));
        var waiting = new ArrayList<Lot>();
        waiting.add(order(store, shop, customer, "ACT-CMD-0201", now.plusMinutes(150), Map.of(arrival[0], waitingEach)).lot());
        waiting.add(order(store, shop, customer, "ACT-CMD-0202", now.plusMinutes(180), Map.of(arrival[0], waitingEach)).lot());
        collecting(store, shop, "ACT-COL-ARRIV", sofia, now.plusMinutes(100), now.plusMinutes(125), waiting);

        // Stock tension: a professional reservation exhausts the product within the alert horizon; the unplanned order
        // ACT-CMD-0402 lacks it on the shelf while the warehouse holds it.
        int tensionShelf = physical(tension[0]);
        var tensionAccount = stocks.account(store.id, Hibernate.unproxy(tension[0].product, Product.class).id);
        stocks.reserve(tensionAccount.id, "ACT-RESA-TENSION", shop.id, BigDecimal.valueOf(tensionShelf + 60L), now.plusHours(5), false);
        order(store, shop, customer, "ACT-CMD-0401", now.plusHours(6), Map.of(tension[0], 2));
        order(store, shop, customer, "ACT-CMD-0402", now.plusHours(7), Map.of(tension[0], tensionShelf + 2));
        definition(store, shop, tension[0], now);

        // Capacity shortfall in the evening: four unassigned operations of two hours for a single presence.
        var evening = new ArrayList<Lot>();
        evening.add(order(store, shop, customer, "ACT-CMD-0301", now.plusHours(9), Map.of(plenty[0], 2)).lot());
        evening.add(order(store, shop, customer, "ACT-CMD-0302", now.plusMinutes(570), Map.of(plenty[0], 2)).lot());
        collecting(store, shop, "ACT-COL-SOIR", null, now.plusHours(6), now.plusHours(8), evening);
        for (int i = 1; i <= 3; i++) {
            var late = replenishment(store, "ACT-RAY-SOIR-" + i, warehouse, shop, null, now.plusHours(6), now.plusHours(8));
            supply(store, late, blocked[1], blocked[0], new BigDecimal("2"));
        }

        // Saturated buffer: four totes of an order waiting for its departure, while a grouping must deposit there.
        Buffer dock = buffer(shop);
        var ready = order(store, shop, customer, "ACT-CMD-0601", now.plusHours(5), Map.of(plenty[0], 4));
        for (int i = 1; i <= 4; i++) pick(ready, ready.lines().get(0), tote(store, "ACT-BAC-0" + i, dock), BigDecimal.ONE);
        var deposit = order(store, shop, customer, "ACT-CMD-0501", now.plusHours(4), Map.of(plenty[0], 2));
        pick(deposit, deposit.lines().get(0), tote(store, "ACT-BAC-05", null), new BigDecimal("2"));
        grouping(store, sofia, customer, dock, deposit.lot(), now.plusMinutes(165), now.plusMinutes(190));

        // Threatened shipments: prepared but not shipped after the deadline, and prepared totes outside a buffer.
        Buffer main = Buffer.findByCode("LYO-042-BUF-01");
        var overdue = order(store, shop, customer, "ACT-CMD-0602", now.minusMinutes(90), Map.of(plenty[0], 1));
        pick(overdue, overdue.lines().get(0), tote(store, "ACT-BAC-06", main), BigDecimal.ONE);
        var departure = order(store, shop, customer, "ACT-CMD-0603", now.plusMinutes(40), Map.of(plenty[0], 1));
        pick(departure, departure.lines().get(0), tote(store, "ACT-BAC-07", null), BigDecimal.ONE);

        // Normal state far ahead, preparation not planned close to its deadline, blocked routing.
        order(store, shop, customer, "ACT-CMD-0902", now.plusHours(20), Map.of(plenty[0], 1));
        order(store, shop, customer, "ACT-CMD-0901", now.plusHours(3), Map.of(plenty[0], 1));
        order(store, shop, customer, "ACT-CMD-0801", now.plusHours(5), Map.of(plenty[0], 1), false);
        em.flush();
    }

    /** A demo customer order with its single chunk and lot. */
    record Created(Order order, Fulfillment fulfillment, FulfillmentChunk chunk, Lot lot, List<LotLine> lines) {}

    /**
     * Returns pairs (shop shelf, warehouse address) of unit products untouched by every other scenario: the shelf only
     * has its opening stock, the warehouse address has no movement, and neither the product's account nor its
     * addresses take part in a supply or a reservation. The shelves with the smallest stock come first.
     *
     * @param shop the shop
     * @param warehouse the main warehouse
     * @return pairs ordered by shelf stock
     */
    private List<ShelfLocation[]> freshPairs(StoreBuilding shop, WarehouseBuilding warehouse) {
        var candidates = new ArrayList<ShelfLocation[]>();
        var shelves = shop.locations.stream().map(l -> Hibernate.unproxy(l, ShelfLocation.class))
                .filter(l -> l instanceof StoreShelfLocation && l.product != null)
                .sorted(Comparator.comparing((ShelfLocation l) -> l.product.ean)).toList();
        var reserves = warehouse.locations.stream().map(l -> Hibernate.unproxy(l, ShelfLocation.class))
                .filter(l -> l instanceof WarehouseShelfLocation && l.product != null).toList();
        var used = new HashSet<Long>();
        for (var shelf : shelves) {
            Product product = Hibernate.unproxy(shelf.product, Product.class);
            if (product.productType != ProductType.UNIT || product.temperatureZone != TemperatureZone.AMBIENT || "3017620422003".equals(product.ean) || !used.add(product.id)) continue;
            var reserve = reserves.stream().filter(l -> Hibernate.unproxy(l.product, Product.class).id.equals(product.id)).findFirst().orElse(null);
            if (reserve == null || StockMovement.count("location", shelf) > 1 || StockMovement.count("location", reserve) > 0) continue;
            if (ExpectedSupply.count("account.product = ?1 and account.store.id in (select s.id from Store s join s.buildings b where b = ?2)", product, shop) > 0) continue;
            if (com.intermarche.backoffice.domain.stock.StockCommitment.count("account.product", product) > 0) continue;
            candidates.add(new ShelfLocation[]{shelf, reserve});
        }
        candidates.sort(Comparator.comparingInt((ShelfLocation[] pair) -> physical(pair[0])).thenComparing(pair -> pair[0].product.ean));
        return candidates.size() > 4 ? candidates.subList(0, 4) : candidates;
    }

    /**
     * Returns the current physical stock of an address.
     *
     * @param location address
     * @return the whole quantity
     */
    private int physical(ShelfLocation location) {
        BigDecimal value = em.createQuery("select coalesce(sum(m.quantity), 0) from StockMovement m where m.location = :l and m.effectiveAt <= :now", BigDecimal.class)
                .setParameter("l", location).setParameter("now", DateTimeProvider.now()).getSingleResult();
        return value.intValue();
    }

    /**
     * Finds or creates a demo picker of the store.
     *
     * @param store store
     * @param badge badge
     * @param first first name
     * @param last last name
     * @return the employee
     */
    private Employee employee(Store store, String badge, String first, String last) {
        Employee employee = Employee.findByBadgeId(badge);
        if (employee != null) {
            if (!Hibernate.unproxy(employee.store, Store.class).id.equals(store.id)) throw new IllegalStateException("Badge de démonstration déjà utilisé");
            return employee;
        }
        employee = new Employee();
        employee.store = store;
        employee.badgeId = badge;
        employee.firstName = first;
        employee.lastName = last;
        employee.email = badge.toLowerCase(Locale.ROOT) + "@demo.storepilot.test";
        employee.role = Employee.EmployeeRole.PICKER;
        employee.active = true;
        employee.password = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt());
        employee.persistAndFlush();
        return employee;
    }

    /**
     * Adds a planning entry once.
     *
     * @param employee employee
     * @param kind entry kind
     * @param title unique title
     * @param building building
     * @param start start
     * @param end end
     */
    private void entry(Employee employee, String kind, String title, Building building, LocalDateTime start, LocalDateTime end) {
        if (PlanningEntry.count("employee=?1 and title=?2", employee, title) > 0) return;
        var e = new PlanningEntry();
        e.employee = employee;
        e.kind = kind;
        e.title = title;
        e.building = building;
        e.startsAt = start;
        e.endsAt = end;
        e.persist();
    }

    /**
     * Records the initial physical stock of an address without history.
     *
     * @param store store
     * @param location address
     * @param key idempotency key
     * @param quantity quantity
     * @param at instant
     */
    private void stock(Store store, ShelfLocation location, String key, BigDecimal quantity, LocalDateTime at) {
        var account = stocks.account(store.id, Hibernate.unproxy(location.product, Product.class).id);
        stocks.adjust(account.id, location.id, key, quantity, at.minusHours(1));
    }

    /**
     * Creates a scheduled replenishment order.
     *
     * @param store store
     * @param code unique code
     * @param from source building, null for a truck
     * @param to destination building
     * @param assignee employee or null
     * @param start start
     * @param end end
     * @return the order
     */
    private ReplenishmentOrder replenishment(Store store, String code, Building from, Building to, Employee assignee, LocalDateTime start, LocalDateTime end) {
        var o = new ReplenishmentOrder();
        o.store = store;
        o.code = code;
        o.sourceBuilding = from;
        o.destinationBuilding = to;
        o.operation = from == null ? ReplenishmentOrder.Operation.TRUCK_UNLOADING : ReplenishmentOrder.Operation.SHELF_REPLENISHMENT;
        o.truckReference = from == null ? "CAMION-DEMO-ACTIVITE" : null;
        o.assignee = assignee;
        o.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        o.requestedStart = start;
        o.requestedEnd = end;
        o.persistAndFlush();
        return o;
    }

    /**
     * Adds one product movement to a replenishment, available at its end.
     *
     * @param store store
     * @param order order
     * @param source source address, null for a truck
     * @param destination destination address
     * @param quantity quantity
     */
    private void supply(Store store, ReplenishmentOrder order, ShelfLocation source, ShelfLocation destination, BigDecimal quantity) {
        Product product = Hibernate.unproxy(destination.product, Product.class);
        var account = stocks.account(store.id, product.id);
        order.supplies.add(stocks.planSupply(account.id, order.code + ":" + product.ean, source == null ? null : source.id, destination.id, quantity, order.requestedEnd));
    }

    /**
     * Creates a routed customer order with one chunk in the shop and its pending lot.
     *
     * @param store store
     * @param shop shop
     * @param customer customer
     * @param code order code
     * @param deadline delivery date
     * @param lines quantity by shelf
     * @return the created order
     */
    private Created order(Store store, StoreBuilding shop, Customer customer, String code, LocalDateTime deadline, Map<ShelfLocation, Integer> lines) {
        return order(store, shop, customer, code, deadline, lines, true);
    }

    /**
     * Creates a customer order with one chunk in the shop; a routed order gets its pending lot, an unrouted one keeps
     * a pending chunk and a failed routing, as when no fulfillment rule could locate its products.
     *
     * @param store store
     * @param shop shop
     * @param customer customer
     * @param code order code
     * @param deadline delivery date
     * @param lines quantity by shelf
     * @param routed whether the products were routed
     * @return the created order, without lot when not routed
     */
    Created order(Store store, StoreBuilding shop, Customer customer, String code, LocalDateTime deadline, Map<ShelfLocation, Integer> lines, boolean routed) {
        var order = new Order();
        order.orderId = code;
        order.orderDate = DateTimeProvider.now().minusHours(3);
        order.channel = "DRIVE";
        order.status = "VALIDATED";
        order.store = store;
        order.customer = customer;
        int ordered = 0;
        for (var e : lines.entrySet()) {
            var line = new com.intermarche.backoffice.domain.order.OrderLine();
            line.lineNumber = ++ordered;
            line.product = Hibernate.unproxy(e.getKey().product, Product.class);
            line.requestedQuantity = BigDecimal.valueOf(e.getValue());
            order.addLine(line);
        }
        order.persist();
        var fulfillment = new Fulfillment();
        fulfillment.order = order;
        fulfillment.reservationRecord.status = routed ? ReservationRecord.ReservationStatus.DONE : ReservationRecord.ReservationStatus.FAILED;
        var chunk = new FulfillmentChunk();
        chunk.type = routed ? FulfillmentChunk.READY : FulfillmentChunk.PENDING;
        chunk.label = "Default";
        chunk.deliveryDate = deadline;
        chunk.building = shop;
        int number = 0;
        for (var e : lines.entrySet()) {
            var line = new FulfillmentLine();
            line.lineNumber = ++number;
            line.product = Hibernate.unproxy(e.getKey().product, Product.class);
            line.requestedQuantity = BigDecimal.valueOf(e.getValue());
            line.location = e.getKey();
            chunk.addLine(line);
        }
        fulfillment.addChunk(chunk);
        fulfillment.persist();
        em.flush();
        if (!routed) return new Created(order, fulfillment, chunk, null, List.of());
        var lot = new Lot();
        lot.status = Lot.LotStatus.PENDING;
        lot.store = store;
        lot.chunk = chunk;
        lot.fulfillment = fulfillment;
        lot.customer = customer;
        lot.persist();
        var lotLines = new ArrayList<LotLine>();
        for (var fl : chunk.lines.stream().sorted(Comparator.comparingInt(l -> l.lineNumber)).toList()) {
            var ll = new LotLine();
            ll.setFulfillmentLine(fl);
            ll.product = fl.product;
            ll.quantity = fl.requestedQuantity;
            ll.persist();
            lot.addLine(ll);
            lotLines.add(ll);
        }
        em.flush();
        return new Created(order, fulfillment, chunk, lot, lotLines);
    }

    /**
     * Creates a scheduled product collecting order in the shop.
     *
     * @param store store
     * @param shop shop
     * @param code code
     * @param assignee employee or null
     * @param start start
     * @param end end
     * @param lots lots collected
     */
    private void collecting(Store store, StoreBuilding shop, String code, Employee assignee, LocalDateTime start, LocalDateTime end, List<Lot> lots) {
        var o = new ProductCollectingOrder();
        o.store = store;
        o.code = code;
        o.building = shop;
        o.assignee = assignee;
        o.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        o.requestedStart = start;
        o.requestedEnd = end;
        for (var lot : lots) {
            o.addLot(lot);
            for (var line : lot.lines) {
                String ean = Hibernate.unproxy(line.product, Product.class).ean;
                if (!o.eans.contains(ean)) o.eans.add(ean);
            }
        }
        o.persist();
    }

    /**
     * Creates a grouping depositing one lot into a buffer.
     *
     * @param store store
     * @param assignee employee
     * @param customer customer
     * @param buffer destination buffer
     * @param lot lot
     * @param start start
     * @param end end
     */
    private void grouping(Store store, Employee assignee, Customer customer, Buffer buffer, Lot lot, LocalDateTime start, LocalDateTime end) {
        var g = new GroupingOrder();
        g.store = store;
        g.code = "ACT-GRP-QUAI";
        g.assignee = assignee;
        g.customer = customer;
        g.buffer = buffer;
        g.lots.add(lot);
        g.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        g.requestedStart = start;
        g.requestedEnd = end;
        g.persist();
    }

    /**
     * Creates the small dock buffer of the scenario.
     *
     * @param shop shop
     * @return the buffer
     */
    private Buffer buffer(StoreBuilding shop) {
        Buffer b = Buffer.findByCode("ACT-BUF-QUAI");
        if (b != null) return b;
        b = new Buffer();
        b.building = shop;
        b.bufferCode = "ACT-BUF-QUAI";
        b.locationDescription = "Quai drive côté parking";
        b.active = true;
        b.ambientCapacity = 4;
        b.chilledCapacity = 0;
        b.frozenCapacity = 0;
        b.persist();
        return b;
    }

    /**
     * Creates a physical tote in use, deposited in a buffer or not.
     *
     * @param store store
     * @param code tote code
     * @param buffer buffer, or null
     * @return the loaded tote
     */
    private LoadedTote tote(Store store, String code, Buffer buffer) {
        var t = new Tote();
        t.code = code;
        t.volume = new BigDecimal("40");
        t.toteType = Tote.ToteType.STANDARD;
        t.store = store;
        t.persist();
        var load = LoadedTote.useAnEmptyTote(t);
        load.storageType = TemperatureZone.AMBIENT;
        load.buffer = buffer;
        load.persist();
        return load;
    }

    /**
     * Records a pick of a lot line into a tote and declares the lot collected once fully picked.
     *
     * @param order created order
     * @param line lot line
     * @param tote tote
     * @param quantity quantity
     */
    private void pick(Created order, LotLine line, LoadedTote tote, BigDecimal quantity) {
        var p = new PickedItem();
        p.lotLine = line;
        p.product = line.product;
        p.loadedTote = tote;
        p.quantity = quantity;
        p.status = PickedItem.PickStatus.PICKED;
        p.persist();
        tote.items.add(p);
        BigDecimal picked = PickedItem.<PickedItem>list("lotLine", line).stream().map(x -> x.quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (picked.compareTo(line.quantity) >= 0) order.lot().status = Lot.LotStatus.COLLECTED;
    }

    /**
     * Announces a truck delivery of the tension product into the shop without handling order, as the replenishment
     * tile does: the quantity waits in the shop's pending supply location until its put-away is defined.
     *
     * @param store store
     * @param shop destination shop
     * @param shelf shelf of the product
     * @param now reference time
     */
    private void definition(Store store, StoreBuilding shop, ShelfLocation shelf, LocalDateTime now) {
        PendingSupplyLocation pending = null;
        for (var l : shop.locations) if (Hibernate.unproxy(l, ShelfLocation.class) instanceof PendingSupplyLocation p) pending = p;
        if (pending == null) {
            pending = new PendingSupplyLocation();
            pending.area = "EN_ATTENTE";
            pending.aisle = "REAPPRO";
            pending.unit = "CIBLE";
            pending.position = "1";
            pending.elevation = "1";
            pending.persistAndFlush();
            shop.locations.add(pending);
            em.flush();
        }
        Product product = Hibernate.unproxy(shelf.product, Product.class);
        var d = new ReplenishmentDefinition();
        d.store = store;
        d.code = "ACT-APP-0001";
        d.requestKey = store.id + ":demo-activity-definition";
        d.operation = ReplenishmentOrder.Operation.TRUCK_UNLOADING;
        d.truckReference = "CAMION-DEMO-ACTIVITE-2";
        d.destinationBuilding = shop;
        d.start = now.plusHours(2);
        d.end = now.plusHours(3);
        var line = new ReplenishmentDefinitionLine();
        line.product = product;
        line.quantity = new BigDecimal("12");
        line.forecast = stocks.planSupply(stocks.account(store.id, product.id).id, d.code + ":" + product.id, null, pending.id, line.quantity, d.end);
        d.lines.add(line);
        d.persist();
    }
}
