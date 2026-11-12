package com.intermarche.e2e.engine.supervision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Configuration;
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
import com.intermarche.backoffice.engine.supervision.ActivityModel;
import com.intermarche.backoffice.engine.supervision.ActivityModel.Situation;
import com.intermarche.backoffice.engine.supervision.ActivitySupervisionResource;
import com.intermarche.backoffice.engine.supervision.HandlingSupervisionResource;
import com.intermarche.backoffice.engine.supervision.SupervisionModel;
import com.intermarche.backoffice.engine.supervision.SupervisionSettings;
import com.intermarche.backoffice.rules.RuleSchemaRegistry;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Activity supervision: composition of the handling diagnostics, cause and consequence links, distinct counting,
 * temporal projections, normal states, missing and stale data, store scope, revalidation and contextual references.
 */
@QuarkusTest
@TestTransaction
@TestSecurity(user = "act-admin", roles = {"ADMIN"})
class ActivitySupervisionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2031, 3, 12, 10, 0);

    @Inject ActivitySupervisionResource activity;
    @Inject HandlingSupervisionResource handling;
    @Inject TemporalStockService stocks;
    @Inject RuleSchemaRegistry rules;
    @Inject EntityManager em;

    private LocalDateTime previousClock;
    private int sequence;

    /** One store with a shop, a warehouse, two pickers, a customer and four products located in both buildings. */
    record Fixture(Store store, StoreBuilding shop, WarehouseBuilding warehouse, Employee anna, Employee bruno, Customer customer,
                   Product[] products, StoreShelfLocation[] shelves, WarehouseShelfLocation[] reserves) {}

    /** A customer order with its single routed chunk and lot. */
    record Commitment(Order order, FulfillmentChunk chunk, Lot lot, List<LotLine> lines) {}

    /**
     * Freezes the clock for deterministic projections.
     */
    @BeforeEach
    void freeze() {
        previousClock = DateTimeProvider.getFixedDateTime();
        DateTimeProvider.setFixedDateTime(NOW);
    }

    /**
     * Restores the clock.
     */
    @AfterEach
    void restore() {
        if (previousClock == null) DateTimeProvider.clear();
        else DateTimeProvider.setFixedDateTime(previousClock);
    }

    /**
     * Creates a store fixture.
     *
     * @param code unique store code, also the prefix of every code
     * @return the fixture
     */
    Fixture fixture(String code) {
        var store = new Store();
        store.code = code;
        store.name = "Activité " + code;
        store.persist();
        var shop = new StoreBuilding();
        shop.buildingCode = code + "-SHOP";
        shop.persist();
        var warehouse = new WarehouseBuilding();
        warehouse.buildingCode = code + "-WH";
        warehouse.persist();
        store.buildings.add(shop);
        store.buildings.add(warehouse);
        var anna = employee(store, code + "-A", "Anna", "Picker", Employee.EmployeeRole.PICKER);
        var bruno = employee(store, code + "-B", "Bruno", "Picker", Employee.EmployeeRole.PICKER);
        var customer = new Customer();
        customer.customerId = code + "-CUST";
        customer.firstName = "Claire";
        customer.lastName = "Client";
        customer.email = code.toLowerCase() + "@client.test";
        customer.persist();
        var products = new Product[4];
        var shelves = new StoreShelfLocation[4];
        var reserves = new WarehouseShelfLocation[4];
        for (int i = 0; i < 4; i++) {
            var p = new Product();
            p.ean = (code + "000000000000").substring(0, 11) + i + "7";
            p.name = "Article " + code + " " + i;
            p.productType = ProductType.UNIT;
            p.temperatureZone = TemperatureZone.AMBIENT;
            p.persist();
            products[i] = p;
            var shelf = new StoreShelfLocation();
            shelf.product = p; shelf.area = "EPI"; shelf.aisle = code.substring(0, 3); shelf.unit = "B" + i; shelf.elevation = "1"; shelf.position = "1";
            shelf.persist();
            shop.locations.add(shelf);
            shelves[i] = shelf;
            var reserve = new WarehouseShelfLocation();
            reserve.product = p; reserve.area = "RES"; reserve.aisle = code.substring(0, 3); reserve.unit = "R" + i; reserve.elevation = "1"; reserve.position = "1";
            reserve.persist();
            warehouse.locations.add(reserve);
            reserves[i] = reserve;
        }
        em.flush();
        return new Fixture(store, shop, warehouse, anna, bruno, customer, products, shelves, reserves);
    }

    /**
     * Creates an employee.
     *
     * @param store store
     * @param badge badge
     * @param first first name
     * @param last last name
     * @param role role
     * @return the employee
     */
    Employee employee(Store store, String badge, String first, String last, Employee.EmployeeRole role) {
        var e = new Employee();
        e.store = store;
        e.badgeId = badge;
        e.firstName = first;
        e.lastName = last;
        e.email = badge.toLowerCase() + "@activity.test";
        e.password = org.mindrot.jbcrypt.BCrypt.hashpw("test-password", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        e.role = role;
        e.active = true;
        e.persist();
        return e;
    }

    /**
     * Adds a planning entry.
     *
     * @param e employee
     * @param kind SHIFT, ABSENCE, TASK or TRANSIT
     * @param building building
     * @param start start
     * @param end end
     */
    void entry(Employee e, String kind, Building building, LocalDateTime start, LocalDateTime end) {
        var p = new PlanningEntry();
        p.employee = e;
        p.kind = kind;
        p.title = kind + " " + e.firstName + " " + (++sequence);
        p.building = building;
        p.startsAt = start;
        p.endsAt = end;
        p.persist();
    }

    /**
     * Gives both pickers a presence in the shop covering the day.
     *
     * @param f fixture
     */
    void presences(Fixture f) {
        entry(f.anna(), "SHIFT", f.shop(), NOW.minusHours(3), NOW.plusHours(9));
        entry(f.bruno(), "SHIFT", f.shop(), NOW.minusHours(3), NOW.plusHours(9));
    }

    /**
     * Creates a routed customer order: one chunk in the shop with one line per product index of a positive quantity,
     * and its pending lot.
     *
     * @param f fixture
     * @param code order code
     * @param deadline delivery date, null for none
     * @param quantities quantity by product index
     * @return the commitment
     */
    Commitment commitment(Fixture f, String code, LocalDateTime deadline, int... quantities) {
        var order = new Order();
        order.orderId = code;
        order.orderDate = NOW.minusHours(6);
        order.channel = "DRIVE";
        order.status = "VALIDATED";
        order.store = f.store();
        order.customer = f.customer();
        order.persist();
        var fulfillment = new Fulfillment();
        fulfillment.order = order;
        fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.DONE;
        var chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Default";
        chunk.deliveryDate = deadline;
        chunk.building = f.shop();
        int number = 0;
        for (int i = 0; i < quantities.length; i++) {
            if (quantities[i] <= 0) continue;
            var line = new FulfillmentLine();
            line.lineNumber = ++number;
            line.product = f.products()[i];
            line.requestedQuantity = BigDecimal.valueOf(quantities[i]);
            line.location = f.shelves()[i];
            chunk.addLine(line);
        }
        fulfillment.addChunk(chunk);
        fulfillment.persist();
        em.flush();
        var lot = new Lot();
        lot.status = Lot.LotStatus.PENDING;
        lot.store = f.store();
        lot.chunk = chunk;
        lot.fulfillment = fulfillment;
        lot.customer = f.customer();
        lot.persist();
        var lines = new ArrayList<LotLine>();
        for (var fl : chunk.lines.stream().sorted(Comparator.comparingInt(l -> l.lineNumber)).toList()) {
            var ll = new LotLine();
            ll.setFulfillmentLine(fl);
            ll.product = fl.product;
            ll.quantity = fl.requestedQuantity;
            ll.persist();
            lot.addLine(ll);
            lines.add(ll);
        }
        em.flush();
        return new Commitment(order, chunk, lot, lines);
    }

    /**
     * Creates a scheduled product collecting order in the shop for lots.
     *
     * @param f fixture
     * @param code code
     * @param assignee employee or null
     * @param start start
     * @param end end
     * @param lots lots collected
     * @return the order
     */
    ProductCollectingOrder collecting(Fixture f, String code, Employee assignee, LocalDateTime start, LocalDateTime end, Lot... lots) {
        var o = new ProductCollectingOrder();
        o.store = f.store();
        o.code = code;
        o.building = f.shop();
        o.assignee = assignee;
        o.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        o.requestedStart = start;
        o.requestedEnd = end;
        for (var lot : lots) o.addLot(lot);
        o.persist();
        return o;
    }

    /**
     * Creates a scheduled shelf replenishment from the warehouse to the shop.
     *
     * @param f fixture
     * @param code code
     * @param assignee employee or null
     * @param start start
     * @param end end
     * @return the order
     */
    ReplenishmentOrder shelving(Fixture f, String code, Employee assignee, LocalDateTime start, LocalDateTime end) {
        var o = new ReplenishmentOrder();
        o.store = f.store();
        o.code = code;
        o.operation = ReplenishmentOrder.Operation.SHELF_REPLENISHMENT;
        o.sourceBuilding = f.warehouse();
        o.destinationBuilding = f.shop();
        o.assignee = assignee;
        o.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        o.requestedStart = start;
        o.requestedEnd = end;
        o.persist();
        return o;
    }

    /**
     * Adds a warehouse-to-shelf line to a replenishment, available at its end.
     *
     * @param f fixture
     * @param o order
     * @param index product index
     * @param quantity quantity
     */
    void line(Fixture f, ReplenishmentOrder o, int index, int quantity) {
        var account = stocks.account(f.store().id, f.products()[index].id);
        o.supplies.add(stocks.planSupply(account.id, o.code + ":" + index, f.reserves()[index].id, f.shelves()[index].id, BigDecimal.valueOf(quantity), o.requestedEnd));
    }

    /**
     * Records an initial physical stock.
     *
     * @param f fixture
     * @param location location
     * @param quantity quantity
     */
    void stock(Fixture f, ShelfLocation location, int quantity) {
        var account = stocks.account(f.store().id, location.product.id);
        stocks.adjust(account.id, location.id, "act-test-" + location.id + "-" + (++sequence), BigDecimal.valueOf(quantity), NOW.minusHours(5));
    }

    /**
     * Creates a buffer of the shop.
     *
     * @param f fixture
     * @param code code
     * @param ambient ambient capacity
     * @return the buffer
     */
    Buffer buffer(Fixture f, String code, int ambient) {
        var b = new Buffer();
        b.building = f.shop();
        b.bufferCode = code;
        b.locationDescription = "Quai";
        b.active = true;
        b.ambientCapacity = ambient;
        b.persist();
        return b;
    }

    /**
     * Creates a tote in use, deposited in a buffer or not.
     *
     * @param f fixture
     * @param code tote code
     * @param buffer buffer, or null
     * @return the loaded tote
     */
    LoadedTote tote(Fixture f, String code, Buffer buffer) {
        var t = new Tote();
        t.code = code;
        t.volume = new BigDecimal("40");
        t.toteType = Tote.ToteType.STANDARD;
        t.store = f.store();
        t.persist();
        var load = LoadedTote.useAnEmptyTote(t);
        load.storageType = TemperatureZone.AMBIENT;
        load.buffer = buffer;
        load.persist();
        return load;
    }

    /**
     * Records the complete pick of a lot line into a tote and declares the lot collected.
     *
     * @param c commitment
     * @param line lot line
     * @param tote tote
     */
    void pick(Commitment c, LotLine line, LoadedTote tote) {
        var p = new PickedItem();
        p.lotLine = line;
        p.product = line.product;
        p.loadedTote = tote;
        p.quantity = line.quantity;
        p.status = PickedItem.PickStatus.PICKED;
        p.persist();
        c.lot().status = Lot.LotStatus.COLLECTED;
    }

    /**
     * Runs the activity supervision with the store default horizon.
     *
     * @param f fixture
     * @return the report
     */
    ActivityModel.Report report(Fixture f) {
        em.flush();
        return activity.report(f.store().id, null);
    }

    /**
     * Finds a situation by identity.
     *
     * @param r report
     * @param id identity
     * @return the situation, if any
     */
    Optional<Situation> find(ActivityModel.Report r, String id) {
        return r.situations().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /**
     * Returns the identifiers of the commitments of a situation.
     *
     * @param s situation
     * @return order identifiers
     */
    Set<Long> orders(Situation s) {
        var result = new HashSet<Long>();
        for (var c : s.commitments()) result.add(c.id());
        return result;
    }

    /**
     * The handling diagnostics are composed with the same identity, nature, title and proposals; the commitments
     * prepared by an order that depends on an unassigned shelf replenishment are linked to it, and the summary states
     * the cause and the impact.
     */
    @Test
    void handlingDiagnosticKeepsItsIdentityAndCarriesItsCommitments() {
        var f = fixture("ACTLNK");
        presences(f);
        stock(f, f.shelves()[0], 2);
        stock(f, f.reserves()[0], 60);
        var rea = shelving(f, "ACL-REA", null, NOW.plusMinutes(60), NOW.plusMinutes(90));
        line(f, rea, 0, 24);
        var c1 = commitment(f, "ACL-0001", NOW.plusHours(4), 4);
        var c2 = commitment(f, "ACL-0002", NOW.plusHours(5), 4);
        var c3 = commitment(f, "ACL-0003", NOW.plusHours(6), 4);
        var col = collecting(f, "ACL-COL", f.anna(), NOW.plusMinutes(120), NOW.plusMinutes(150), c1.lot(), c2.lot(), c3.lot());
        var r = report(f);
        var handlingReport = handling.report(f.store().id, r.handling().start().toString(), r.handling().end().toString());
        var anomaly = handlingReport.anomalies().stream().filter(a -> a.id().equals("UNASSIGNED:" + rea.id)).findFirst().orElseThrow();
        var s = find(r, "UNASSIGNED:" + rea.id).orElseThrow();
        assertEquals(anomaly.certainty().name(), s.certainty().name());
        assertEquals(anomaly.title(), s.title());
        assertEquals(List.of(anomaly.id()), s.handlingAnomalyIds());
        assertEquals(anomaly.proposals().stream().map(SupervisionModel.Proposal::id).toList(), s.proposals().stream().map(p -> p.proposal().id()).toList());
        assertTrue(r.handling().anomalies().containsKey(anomaly.id()), "the full handling anomaly is available for the handling detail");
        assertEquals(Set.of(c1.order().id, c2.order().id, c3.order().id), orders(s));
        assertTrue(s.commitments().stream().allMatch(c -> "ESTABLISHED".equals(c.link())));
        assertTrue(s.summary().startsWith("3 commandes risquent de manquer leur échéance : le réachalandage ACL-REA n’a aucun employé affecté"), s.summary());
        assertTrue(s.summary().contains("ne suffisent pas sans cet approvisionnement"), s.summary());
        assertTrue(s.domains().containsAll(List.of(ActivityModel.Domain.TEAM, ActivityModel.Domain.REPLENISHMENT, ActivityModel.Domain.ORDERS)), s.domains().toString());
        assertTrue(s.graph().edges().stream().anyMatch(e -> e.to().equals("operation:" + col.id) && "ESTABLISHED".equals(e.kind())), s.graph().toString());
        assertTrue(s.graph().nodes().stream().anyMatch(n -> n.id().equals("order:" + c1.order().id)));
        assertTrue(s.priorityReasons().stream().anyMatch(x -> x.contains("3 commande(s) client")), s.priorityReasons().toString());
        assertEquals("HIGH", s.severity().name(), "a risk on three commitments is raised to high");
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, rea.status, "the analysis never writes");
    }

    /**
     * A shelf stock that covers the need on its own does not make the order depend on a concomitant replenishment;
     * a need that requires the supply does.
     */
    @Test
    void dependencyIsEstablishedOnlyWhenTheQuantitiesRequireTheSupply() {
        var f = fixture("ACTDEP");
        presences(f);
        stock(f, f.shelves()[1], 10);
        stock(f, f.reserves()[1], 40);
        var rea = shelving(f, "ADP-REA", null, NOW.plusMinutes(60), NOW.plusMinutes(90));
        line(f, rea, 1, 20);
        var covered = commitment(f, "ADP-0001", NOW.plusHours(3), 0, 4);
        var dependent = commitment(f, "ADP-0002", NOW.plusHours(4), 0, 15);
        var r = report(f);
        var s = find(r, "UNASSIGNED:" + rea.id).orElseThrow();
        assertEquals(Set.of(dependent.order().id), orders(s), "only the order that needs the supply depends on it");
        assertTrue(s.commitments().get(0).via().contains("Sans ADP-REA, 6 pcs"), s.commitments().get(0).via());
        assertTrue(r.situations().stream().noneMatch(x -> x.commitments().stream().anyMatch(c -> c.id().equals(covered.order().id)) && !x.kind().equals("PLANNING")),
                "the covered order is threatened by nothing but its planning");
    }

    /**
     * A commitment concerned by several problems is counted once in the threatened orders, while each problem keeps it.
     */
    @Test
    void orderConcernedBySeveralProblemsIsCountedOnce() {
        var f = fixture("ACTDUP");
        presences(f);
        stock(f, f.shelves()[0], 1);
        stock(f, f.reserves()[0], 30);
        var rea = shelving(f, "ADU-REA", null, NOW.plusMinutes(30), NOW.plusMinutes(60));
        line(f, rea, 0, 10);
        stock(f, f.shelves()[2], 1);
        var c = commitment(f, "ADU-0001", NOW.plusHours(2), 5, 0, 3);
        var r = report(f);
        var dependency = find(r, "UNASSIGNED:" + rea.id).orElseThrow();
        var shortage = find(r, "STOCK_SHORTAGE:" + f.shop().id).orElseThrow();
        var planning = find(r, "PLANNING").orElseThrow();
        for (var s : List.of(dependency, shortage, planning)) assertTrue(orders(s).contains(c.order().id), s.id());
        assertEquals(1, r.threatenedOrders());
        var kpi = r.kpis().stream().filter(k -> k.code().equals("THREATENED_ORDERS")).findFirst().orElseThrow();
        assertEquals(1L, kpi.value());
        assertTrue(kpi.detail().contains("dont 1 par plusieurs situations"), kpi.detail());
        assertTrue(kpi.situationIds().containsAll(List.of(dependency.id(), shortage.id(), planning.id())));
        var commitment = shortage.commitments().stream().filter(x -> x.id().equals(c.order().id)).findFirst().orElseThrow();
        assertTrue(commitment.otherSituations().containsAll(List.of(dependency.id(), planning.id())), commitment.otherSituations().toString());
    }

    /**
     * Unplanned needs are projected at their deadline with the shared availability rules: a forecast shortage is a
     * risk with a warehouse replenishment action, a current shortage is proven, an unknown stock is missing data,
     * an overdue external supply is a late arrival, and supplies after the deadline are not counted.
     */
    @Test
    void temporalProjectionsDistinguishForecastCurrentUnknownAndLateSupply() {
        var f = fixture("ACTPRJ");
        presences(f);
        stock(f, f.shelves()[0], 5);
        stock(f, f.reserves()[0], 50);
        var account0 = stocks.account(f.store().id, f.products()[0].id);
        stocks.planSupply(account0.id, "EXT-AFTER:0", null, f.shelves()[0].id, BigDecimal.valueOf(20), NOW.plusHours(8));
        var forecast = commitment(f, "APJ-0001", NOW.plusHours(3), 8);
        stock(f, f.shelves()[1], 2);
        stock(f, f.shelves()[1], -2);
        var current = commitment(f, "APJ-0002", NOW.minusMinutes(30), 0, 2);
        var unknown = commitment(f, "APJ-0003", NOW.plusHours(2), 0, 0, 1);
        var account3 = stocks.account(f.store().id, f.products()[3].id);
        stocks.planSupply(account3.id, "EXT-LATE:3", null, f.shelves()[3].id, BigDecimal.valueOf(10), NOW.minusMinutes(45));
        var late = commitment(f, "APJ-0004", NOW.plusHours(2), 0, 0, 0, 4);
        var r = report(f);
        var shortage = find(r, "STOCK_SHORTAGE:" + f.shop().id).orElseThrow();
        assertEquals(ActivityModel.Certainty.BLOCKING, shortage.certainty(), "a current shortage makes the grouped shortage proven");
        assertEquals(Set.of(forecast.order().id, current.order().id), orders(shortage));
        var forecastLine = shortage.products().stream().filter(p -> p.orderId().equals(forecast.order().id)).findFirst().orElseThrow();
        assertEquals("SHORTAGE_FORECAST", forecastLine.state(), "the supply arriving after the deadline is not counted");
        assertEquals("SHORTAGE_NOW", shortage.products().stream().filter(p -> p.orderId().equals(current.order().id)).findFirst().orElseThrow().state());
        assertEquals("DEFINE_REPLENISHMENT", shortage.actions().get(0).code(), "the warehouse holds the missing product");
        assertEquals(f.warehouse().id, shortage.context().get("sourceBuildingId"));
        assertEquals(f.shop().id, shortage.context().get("destinationBuildingId"));
        assertTrue(shortage.consequences().stream().anyMatch(x -> x.contains("50 pcs dans " + f.warehouse().buildingCode)), shortage.consequences().toString());
        var missing = find(r, "STOCK_UNKNOWN:" + f.shop().id).orElseThrow();
        assertEquals(ActivityModel.Certainty.MISSING_DATA, missing.certainty());
        assertEquals("ESTIMATED", missing.commitments().get(0).link());
        assertEquals(Set.of(unknown.order().id), orders(missing));
        var arrival = find(r, "SUPPLY_LATE:EXT-LATE").orElseThrow();
        assertEquals(ActivityModel.Certainty.BLOCKING, arrival.certainty());
        assertEquals(Set.of(late.order().id), orders(arrival));
        assertTrue(arrival.summary().startsWith("1 commande risque de manquer son échéance : l’arrivage EXT-LATE attendu n’est pas réceptionné"), arrival.summary());
    }

    /**
     * A prepared order waiting for its departure and an order to prepare far ahead are normal: no situation.
     */
    @Test
    void normalStatesAreNotReported() {
        var f = fixture("ACTNRM");
        presences(f);
        var dock = buffer(f, "ANR-BUF", 10);
        stock(f, f.shelves()[0], 50);
        var ready = commitment(f, "ANR-0001", NOW.plusHours(3), 2);
        pick(ready, ready.lines().get(0), tote(f, "ANR-T1", dock));
        var later = commitment(f, "ANR-0002", NOW.plusHours(10), 2);
        var r = report(f);
        for (var s : r.situations()) {
            assertFalse(orders(s).contains(ready.order().id), s.id());
            assertFalse(orders(s).contains(later.order().id), s.id());
        }
        var point = r.charts().deadlines().points().stream().filter(p -> p.orderId().equals(ready.order().id)).findFirst().orElseThrow();
        assertEquals("READY", point.state());
        assertEquals("ON_TRACK", r.charts().deadlines().points().stream().filter(p -> p.orderId().equals(later.order().id)).findFirst().orElseThrow().state());
        assertEquals(0, r.threatenedOrders());
        assertEquals(1L, r.kpis().stream().filter(k -> k.code().equals("READY_ORDERS")).findFirst().orElseThrow().value());
        assertTrue(r.charts().stages().stream().anyMatch(b -> b.stage().equals("READY") && b.count() == 1 && b.orderIds().contains(ready.order().id)));
    }

    /**
     * A prepared order past its deadline is a proven shipping problem with its immobilized totes; prepared totes
     * still outside a buffer close to the departure are a risk.
     */
    @Test
    void shippingLateAndUndepositedTotes() {
        var f = fixture("ACTSHP");
        presences(f);
        var dock = buffer(f, "ASH-BUF", 10);
        var overdue = commitment(f, "ASH-0001", NOW.minusHours(14), 2);
        pick(overdue, overdue.lines().get(0), tote(f, "ASH-T1", dock));
        var soon = commitment(f, "ASH-0002", NOW.plusMinutes(30), 0, 1);
        pick(soon, soon.lines().get(0), tote(f, "ASH-T2", null));
        var r = report(f);
        var late = find(r, "SHIPPING_LATE").orElseThrow();
        assertEquals(ActivityModel.Certainty.BLOCKING, late.certainty());
        assertEquals("CRITICAL", late.severity().name());
        assertEquals("OPEN_SHIPPING", late.actions().get(0).code());
        assertTrue(late.commitments().get(0).via().contains("immobilisé(s) depuis plus de 12 h"), late.commitments().get(0).via());
        assertEquals(dock.id, late.buffers().get(0).id());
        assertTrue(late.summary().startsWith("1 commande a dépassé son échéance"), late.summary());
        var deposit = find(r, "SHIPPING_DEPOSIT").orElseThrow();
        assertEquals(ActivityModel.Certainty.RISK, deposit.certainty());
        assertEquals(Set.of(soon.order().id), orders(deposit));
        assertTrue(deposit.commitments().get(0).via().contains("ASH-T2"));
        assertEquals(1, r.charts().buffers().stream().filter(b -> b.id().equals(dock.id)).findFirst().orElseThrow().immobilized());
    }

    /**
     * Load and capacity are computed by slot with presences, absences, tasks and transits; when the unassigned work
     * exceeds the free capacity, the unassigned diagnostics are folded into the capacity situation with their
     * proposals and commitments.
     */
    @Test
    void capacityShortfallFoldsTheUnassignedOrders() {
        var f = fixture("ACTCAP");
        entry(f.anna(), "SHIFT", f.shop(), NOW.plusHours(4), NOW.plusHours(6));
        entry(f.anna(), "ABSENCE", f.shop(), NOW.plusHours(5), NOW.plusMinutes(330));
        entry(f.bruno(), "SHIFT", f.shop(), NOW.plusHours(4), NOW.plusHours(6));
        entry(f.bruno(), "TRANSIT", f.shop(), NOW.plusHours(4), NOW.plusMinutes(250));
        stock(f, f.shelves()[0], 100);
        var c = commitment(f, "ACP-0001", NOW.plusHours(7), 3);
        var first = collecting(f, "ACP-COL1", null, NOW.plusHours(4), NOW.plusHours(6), c.lot());
        var second = collecting(f, "ACP-COL2", null, NOW.plusHours(4), NOW.plusHours(6));
        var third = collecting(f, "ACP-COL3", null, NOW.plusHours(4), NOW.plusHours(6));
        var r = report(f);
        var slot = r.charts().workload().slots().stream().filter(s -> s.start().equals(NOW.plusHours(5))).findFirst().orElseThrow();
        assertEquals(90, slot.capacity(), "60 + 60 minutes of presence minus a 30-minute absence");
        assertEquals(30, slot.absences());
        assertEquals(180, slot.unassigned());
        assertEquals(90, slot.shortfall());
        var transit = r.charts().workload().slots().stream().filter(s -> s.start().equals(NOW.plusHours(4))).findFirst().orElseThrow();
        assertEquals(10, transit.transits());
        var s = r.situations().stream().filter(x -> x.kind().equals("CAPACITY")).findFirst().orElseThrow();
        assertEquals(ActivityModel.Certainty.RISK, s.certainty());
        assertEquals(slot.situationId(), s.id());
        assertTrue(s.handlingAnomalyIds().containsAll(List.of("UNASSIGNED:" + first.id, "UNASSIGNED:" + second.id, "UNASSIGNED:" + third.id)));
        assertTrue(find(r, "UNASSIGNED:" + first.id).isEmpty(), "folded diagnostics are not duplicated");
        assertTrue(s.proposals().stream().allMatch(p -> p.anomalyId().startsWith("UNASSIGNED:")));
        assertEquals(Set.of(c.order().id), orders(s));
        assertEquals("COMPARE_SOLUTIONS", s.actions().get(0).code());
        assertTrue(s.cause().contains("déficit"), s.cause());
        var kpi = r.kpis().stream().filter(k -> k.code().equals("CAPACITY")).findFirst().orElseThrow();
        assertEquals(r.charts().workload().slots().stream().mapToLong(ActivityModel.WorkloadSlot::shortfall).sum(), kpi.value());
    }

    /**
     * A full buffer receiving a planned grouping is a situation folding the handling diagnostic and linking the
     * prepared commitment; a full buffer without planned deposit is shown in the chart only.
     */
    @Test
    void bufferSaturationNeedsPlannedDeposits() {
        var f = fixture("ACTBUF");
        presences(f);
        var full = buffer(f, "ABF-FULL", 2);
        tote(f, "ABF-T1", full);
        tote(f, "ABF-T2", full);
        var idle = buffer(f, "ABF-IDLE", 1);
        tote(f, "ABF-T3", idle);
        stock(f, f.shelves()[0], 20);
        var c = commitment(f, "ABF-0001", NOW.plusHours(4), 2);
        var g = new GroupingOrder();
        g.store = f.store();
        g.code = "ABF-GRP";
        g.assignee = f.anna();
        g.customer = f.customer();
        g.buffer = full;
        g.lots.add(c.lot());
        g.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        g.requestedStart = NOW.plusHours(2);
        g.requestedEnd = NOW.plusMinutes(150);
        g.persist();
        collecting(f, "ABF-COL", f.bruno(), NOW.plusHours(1), NOW.plusMinutes(90), c.lot());
        var r = report(f);
        var s = find(r, "BUFFER_LOAD:" + full.id).orElseThrow();
        assertTrue(s.handlingAnomalyIds().contains("BUFFER:" + full.id + ":FULL"));
        assertTrue(find(r, "BUFFER:" + full.id + ":FULL").isEmpty());
        assertEquals(Set.of(c.order().id), orders(s));
        assertTrue(s.uncertainties().get(0).contains("aucune projection"), s.uncertainties().toString());
        var idleBar = r.charts().buffers().stream().filter(b -> b.id().equals(idle.id)).findFirst().orElseThrow();
        assertEquals("FULL", idleBar.state());
        assertNull(idleBar.situationId(), "a full buffer without planned deposit is not a situation");
        assertEquals(1, r.charts().buffers().stream().filter(b -> b.id().equals(full.id)).findFirst().orElseThrow().plannedDeposits());
    }

    /**
     * Missing presences, an order without deadline and a stale stock evaluation never produce a false "all good".
     */
    @Test
    void missingAndStaleDataAreExplicit() {
        var f = fixture("ACTMIS");
        stock(f, f.shelves()[0], 50);
        var c = commitment(f, "AMS-0001", null, 1);
        collecting(f, "AMS-COL", f.anna(), NOW.plusHours(2), NOW.plusHours(3), c.lot());
        var account = stocks.account(f.store().id, f.products()[1].id);
        em.flush();
        em.createQuery("update StockAccount a set a.alertDirty = true, a.updatedAt = :old where a.id = :id")
                .setParameter("old", NOW.minusHours(2)).setParameter("id", account.id).executeUpdate();
        em.clear();
        var r = activity.report(f.store().id, null);
        var hours = find(r, "HOURS_MISSING").orElseThrow();
        assertEquals(ActivityModel.Certainty.MISSING_DATA, hours.certainty());
        assertTrue(r.sources().stream().anyMatch(s -> s.code().equals("PLANNING") && s.status().equals("NO_DATA")));
        assertTrue(r.charts().workload().slots().stream().anyMatch(s -> s.capacity() == null && s.untracked() > 0));
        assertEquals("UNKNOWN", r.kpis().stream().filter(k -> k.code().equals("CAPACITY")).findFirst().orElseThrow().tone());
        var deadline = find(r, "DEADLINE_MISSING").orElseThrow();
        assertEquals(Set.of(c.order().id), orders(deadline));
        var stale = find(r, "STOCK_STALE").orElseThrow();
        assertEquals(ActivityModel.Certainty.STALE, stale.certainty());
        assertTrue(r.sources().stream().anyMatch(s -> s.code().equals("STOCK") && s.status().equals("STALE")));
        assertEquals(0, r.threatenedOrders(), "missing data do not count as threatened orders");
    }

    /**
     * A preparation planned to end after the deadline is an established risk; an order whose products could not be
     * routed is blocked.
     */
    @Test
    void preparationAfterDeadlineAndRoutingFailure() {
        var f = fixture("ACTPLN");
        presences(f);
        stock(f, f.shelves()[0], 50);
        var c = commitment(f, "APL-0001", NOW.plusHours(2), 2);
        var col = collecting(f, "APL-COL", f.anna(), NOW.plusMinutes(150), NOW.plusMinutes(180), c.lot());
        var blocked = commitment(f, "APL-0002", NOW.plusHours(5), 1);
        blocked.chunk().type = FulfillmentChunk.PENDING;
        Lot.deleteById(blocked.lot().id);
        em.flush();
        var r = report(f);
        var plan = find(r, "LATE_PLAN:" + col.id).orElseThrow();
        assertEquals(ActivityModel.Certainty.RISK, plan.certainty());
        assertEquals(Set.of(c.order().id), orders(plan));
        assertEquals("COMPARE_SOLUTIONS", plan.actions().get(0).code());
        var routing = find(r, "ROUTING").orElseThrow();
        assertEquals(ActivityModel.Certainty.BLOCKING, routing.certainty());
        assertEquals(Set.of(blocked.order().id), orders(routing));
        assertEquals("FULFILLER", routing.context().get("rulesCategory"));
    }

    /**
     * A replenishment announced without handling order is a situation; a commitment short of one of its products in
     * the same building is linked as estimated, and its forecast supply is not reported twice.
     */
    @Test
    void announcedReplenishmentWithoutHandlingOrder() {
        var f = fixture("ACTDEF");
        presences(f);
        stock(f, f.shelves()[2], 1);
        stock(f, f.shelves()[3], 1);
        var estimated = commitment(f, "ADF-0001", NOW.plusHours(6), 0, 0, 5);
        var established = commitment(f, "ADF-0002", NOW.plusHours(6), 0, 0, 0, 5);
        var pending = new PendingSupplyLocation();
        pending.area = "EN_ATTENTE"; pending.aisle = "REAPPRO"; pending.unit = "CIBLE"; pending.position = "1"; pending.elevation = "1";
        pending.persist();
        f.shop().locations.add(pending);
        var d = new ReplenishmentDefinition();
        d.store = f.store();
        d.code = "ADF-APP";
        d.requestKey = f.store().id + ":adf-test-key";
        d.operation = ReplenishmentOrder.Operation.SHELF_REPLENISHMENT;
        d.sourceBuilding = f.warehouse();
        d.destinationBuilding = f.shop();
        d.start = NOW.minusHours(2);
        d.end = NOW.minusHours(1);
        for (int index : new int[]{2, 3}) {
            var l = new ReplenishmentDefinitionLine();
            l.product = f.products()[index];
            l.quantity = BigDecimal.TEN;
            // The announced quantity of the first product waits in the building's pending location, the second at its shelf.
            l.forecast = stocks.planSupply(stocks.account(f.store().id, f.products()[index].id).id, "ADF-APP:" + index, null,
                    index == 2 ? pending.id : f.shelves()[index].id, BigDecimal.TEN, NOW.minusHours(1));
            d.lines.add(l);
        }
        d.persist();
        var r = report(f);
        var s = find(r, "DEFINITION:" + d.id).orElseThrow();
        assertEquals(ActivityModel.Certainty.BLOCKING, s.certainty(), "the announced arrival time has passed");
        assertEquals("DEFINE_HANDLING", s.actions().get(0).code());
        assertEquals(d.id, s.context().get("definitionId"));
        assertEquals(Set.of(estimated.order().id, established.order().id), orders(s));
        assertEquals("ESTIMATED", s.commitments().stream().filter(c -> c.id().equals(estimated.order().id)).findFirst().orElseThrow().link());
        assertEquals("ESTABLISHED", s.commitments().stream().filter(c -> c.id().equals(established.order().id)).findFirst().orElseThrow().link());
        assertTrue(r.situations().stream().noneMatch(x -> x.kind().equals("SUPPLY_LATE")), "the forecast supplies belong to their definition");
    }

    /**
     * A proposal shown by the activity view is revalidated by the handling supervision: once stale it is refused
     * with the reason, a fresh one is applied and the situation disappears from both views.
     */
    @Test
    void proposalFromTheActivityViewIsRevalidated() {
        var f = fixture("ACTAPP");
        presences(f);
        stock(f, f.shelves()[0], 1);
        stock(f, f.reserves()[0], 30);
        var rea = shelving(f, "AAP-REA", null, NOW.plusMinutes(60), NOW.plusMinutes(90));
        line(f, rea, 0, 10);
        var c = commitment(f, "AAP-0001", NOW.plusHours(4), 5);
        collecting(f, "AAP-COL", f.bruno(), NOW.plusMinutes(120), NOW.plusMinutes(150), c.lot());
        var r = report(f);
        var s = find(r, "UNASSIGNED:" + rea.id).orElseThrow();
        var proposal = s.proposals().stream().filter(p -> f.anna().id.equals(p.proposal().employeeId())).findFirst().orElseThrow();
        rea.assignee = f.bruno();
        em.flush();
        var stale = handling.apply(new SupervisionModel.ApplyRequest(f.store().id, proposal.anomalyId(), proposal.proposal().id(), proposal.proposal().basis(),
                r.handling().start(), r.handling().end()));
        assertEquals(409, stale.getStatus());
        var refused = (SupervisionModel.ApplyResult) stale.getEntity();
        assertEquals("STALE", refused.status());
        assertFalse(refused.reasons().isEmpty());
        rea.assignee = null;
        em.flush();
        var fresh = report(f);
        var again = find(fresh, "UNASSIGNED:" + rea.id).orElseThrow().proposals().stream().filter(p -> f.anna().id.equals(p.proposal().employeeId())).findFirst().orElseThrow();
        var applied = handling.apply(new SupervisionModel.ApplyRequest(f.store().id, again.anomalyId(), again.proposal().id(), again.proposal().basis(),
                fresh.handling().start(), fresh.handling().end()));
        assertEquals(200, applied.getStatus());
        var after = report(f);
        assertTrue(find(after, "UNASSIGNED:" + rea.id).isEmpty());
        assertTrue(handling.report(f.store().id, after.handling().start().toString(), after.handling().end().toString()).anomalies().stream()
                .noneMatch(a -> a.id().equals("UNASSIGNED:" + rea.id)), "the resolution is reflected in both views");
    }

    /**
     * The order detail returns the line progress and the chunk in the shape of the existing chunk editor.
     */
    @Test
    void orderDetailIsLoadedOnDemand() {
        var f = fixture("ACTDTL");
        var dock = buffer(f, "ADT-BUF", 4);
        var c = commitment(f, "ADT-0001", NOW.plusHours(3), 2, 3);
        var p = new PickedItem();
        p.lotLine = c.lines().get(0);
        p.product = c.lines().get(0).product;
        p.loadedTote = tote(f, "ADT-T1", dock);
        p.quantity = BigDecimal.valueOf(2);
        p.status = PickedItem.PickStatus.PICKED;
        p.persist();
        em.flush();
        var detail = activity.order(c.order().id, f.store().id);
        assertEquals("ADT-0001", detail.code());
        assertEquals("TO_PLAN", detail.stage());
        var first = detail.lines().get(0);
        assertEquals(0, first.prepared().compareTo(BigDecimal.valueOf(2)));
        assertEquals(0, first.remaining().signum());
        assertEquals(List.of("ADT-T1 · ADT-BUF"), first.totes());
        assertEquals(0, detail.lines().get(1).remaining().compareTo(BigDecimal.valueOf(3)));
        var chunk = detail.chunks().get(0);
        for (var key : List.of("id", "type", "label", "deliveryDate", "building", "lines", "fulfillment", "location")) assertTrue(chunk.containsKey(key), key);
        assertEquals("ADT-0001", ((Map<?, ?>) chunk.get("fulfillment")).get("orderId"));
    }

    /**
     * A manager only sees his store, including the detail of an order.
     */
    @Test
    @TestSecurity(user = "ACTSCO-M", roles = {"MANAGER"})
    void managerIsRestrictedToHisStore() {
        var own = fixture("ACTSCO");
        employee(own.store(), "ACTSCO-M", "Maud", "Manager", Employee.EmployeeRole.MANAGER);
        var other = fixture("ACTOTH");
        var foreign = commitment(other, "AOT-0001", NOW.plusHours(2), 1);
        em.flush();
        assertEquals(own.store().id, activity.report(null, null).storeId());
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> activity.report(other.store().id, null));
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> activity.order(foreign.order().id, other.store().id));
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> activity.order(foreign.order().id, null));
    }

    /**
     * Thresholds come from the store configuration with documented defaults; invalid values are ignored with a
     * warning and refused by the rule validation; the horizon is bounded.
     */
    @Test
    void settingsComeFromTheStoreConfiguration() throws Exception {
        var f = fixture("ACTSET");
        presences(f);
        stock(f, f.shelves()[0], 50);
        var c = commitment(f, "AST-0001", NOW.plusHours(3), 1);
        assertTrue(find(report(f), "PLANNING").isPresent(), "the default planning lead is 240 minutes");
        var config = new Configuration();
        config.name = "Supervision ACTSET";
        config.type = SupervisionSettings.CONFIGURATION;
        config.priority = 10;
        config.specification = "[{\"type\":\"ActivitySupervision\",\"name\":\"Seuils\",\"planningLeadMinutes\":60,\"criticalOrderCount\":0,\"maxHorizonHours\":48}]";
        config.stores.add(f.store());
        config.persist();
        var r = report(f);
        assertTrue(find(r, "PLANNING").isEmpty(), "the store lead of 60 minutes is used");
        assertEquals(60, r.settings().values().planningLeadMinutes());
        assertEquals(3, r.settings().values().criticalOrderCount(), "an invalid value keeps its default");
        assertTrue(r.settings().values().warnings().stream().anyMatch(w -> w.contains("Commandes rendant un problème critique")));
        assertTrue(r.settings().values().source().contains("Supervision ACTSET"));
        assertFalse(r.filters().horizons().contains(72));
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> activity.report(f.store().id, 49));
        var mapper = new ObjectMapper();
        assertThrows(IllegalArgumentException.class, () -> rules.validate(SupervisionSettings.CONFIGURATION,
                mapper.readTree("{\"type\":\"ActivitySupervision\",\"bufferSaturationPercent\":20}"), List.of(f.store())));
        rules.validate(SupervisionSettings.CONFIGURATION, mapper.readTree("{\"type\":\"ActivitySupervision\",\"bufferSaturationPercent\":80}"), List.of(f.store()));
        assertEquals(24, SupervisionSettings.defaults().horizonHours());
        assertNotNull(c.order().id);
    }

    /**
     * The analysis does not issue one query per order, line or product.
     */
    @Test
    void queriesDoNotGrowWithTheNumberOfOrders() {
        var f = fixture("ACTQRY");
        presences(f);
        stock(f, f.shelves()[0], 500);
        stock(f, f.shelves()[1], 500);
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            for (int i = 0; i < 3; i++) commitment(f, "AQR-A" + i, NOW.plusHours(2 + i), 1, 1);
            report(f);
            em.clear();
            statistics.clear();
            activity.report(f.store().id, null);
            long small = statistics.getPrepareStatementCount();
            for (int i = 0; i < 20; i++) commitment(f, "AQR-B" + i, NOW.plusHours(2).plusMinutes(i * 10L), 1, 1);
            em.flush();
            em.clear();
            statistics.clear();
            activity.report(f.store().id, null);
            long large = statistics.getPrepareStatementCount();
            assertTrue(large <= small + 4, "statements: " + small + " for 3 orders, " + large + " for 23 orders");
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }
    @Test @TestTransaction @TestSecurity(user="admin",roles={"ADMIN"})
    void operationalCaseKeepsItsIdentityAcrossSupervisionsAndAgesExplainTheirSourceList(){
        var f=fixture("OPS-CASE");var customer=commitment(f,"OPS-CMD",NOW.plusHours(4),2);var mission=collecting(f,"OPS-COL",f.anna(),NOW.plusMinutes(30),NOW.plusMinutes(60),customer.lot());var incident=new com.intermarche.backoffice.domain.fieldops.FieldIncident();incident.store=f.store();incident.reporter="demo";incident.requestKey="case-test-key";incident.fingerprint="a".repeat(64);incident.kind="DAMAGED";incident.title="Produit endommagé";incident.comment="Contrôle nécessaire";incident.product=f.products()[0];incident.handlingOrder=mission;incident.persist();em.flush();
        var first=report(f);String identity="incident:"+incident.id;assertEquals(1,first.situations().stream().filter(x->x.id().equals(identity)).count());var specialized=handling.report(f.store().id,first.start().toString(),first.end().toString());assertEquals(1,specialized.anomalies().stream().filter(a->a.id().equals(identity)).count());var situation=find(first,identity).orElseThrow();assertEquals(incident.id,situation.context().get("incidentId"));assertTrue(situation.actions().stream().anyMatch(a->a.code().equals("OPEN_INCIDENT")));assertEquals(1,first.charts().caseAges().buckets().stream().mapToInt(ActivityModel.CaseAgeBucket::count).sum());assertTrue(first.charts().caseAges().buckets().stream().flatMap(b->b.situationIds().stream()).anyMatch(identity::equals));
        incident.state="RESOLVED";em.flush();var resolved=report(f);assertTrue(find(resolved,identity).isEmpty());assertEquals(0,resolved.charts().caseAges().buckets().stream().mapToInt(ActivityModel.CaseAgeBucket::count).sum());
    }
}
