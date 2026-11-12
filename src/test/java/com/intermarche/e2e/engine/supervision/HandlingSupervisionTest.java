package com.intermarche.e2e.engine.supervision;

import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.executing.ReplenishmentExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.workforce.BuildingTransit;
import com.intermarche.backoffice.domain.workforce.EmployeePosition;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.handling.ReplenishmentOrder;
import com.intermarche.backoffice.domain.wms.handling.StockProtectionOrder;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.intermarche.backoffice.engine.supervision.HandlingSupervisionResource;
import com.intermarche.backoffice.engine.supervision.SupervisionModel.*;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Handling supervision: detections, certainty levels, grouping, proposals, revalidation and store scope.
 */
@QuarkusTest
@TestTransaction
@TestSecurity(user = "sup-admin", roles = {"ADMIN"})
class HandlingSupervisionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2031, 3, 12, 10, 0);

    @Inject HandlingSupervisionResource supervision;
    @Inject TemporalStockService stocks;
    @Inject EntityManager em;

    private LocalDateTime previousClock;
    private int sequence;

    /** Fixture of one store with a shop, a warehouse, two pickers and three products located in both buildings. */
    record Fixture(Store store, StoreBuilding shop, WarehouseBuilding warehouse, Employee anna, Employee bruno,
                   Product[] products, StoreShelfLocation[] shelves, WarehouseShelfLocation[] reserves) {}

    /**
     * Freezes the clock for deterministic detections.
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
     * @param code unique store code (also the prefix of every code)
     * @param withTransits whether directed transits are configured, which activates position checks
     * @return the fixture
     */
    Fixture fixture(String code, boolean withTransits) {
        var store = new Store();
        store.code = code;
        store.name = "Supervision " + code;
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
        var products = new Product[3];
        var shelves = new StoreShelfLocation[3];
        var reserves = new WarehouseShelfLocation[3];
        for (int i = 0; i < 3; i++) {
            var p = new Product();
            p.ean = (code + "000000000000").substring(0, 11) + i + "9";
            p.name = "Produit " + code + " " + i;
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
        if (withTransits) {
            transit(store, shop, warehouse, 10);
            transit(store, warehouse, shop, 10);
        }
        em.flush();
        return new Fixture(store, shop, warehouse, anna, bruno, products, shelves, reserves);
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
        e.email = badge.toLowerCase() + "@supervision.test";
        e.password = org.mindrot.jbcrypt.BCrypt.hashpw("test-password", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        e.role = role;
        e.active = true;
        e.persist();
        return e;
    }

    /**
     * Configures a directed transit.
     *
     * @param store store
     * @param from origin
     * @param to destination
     * @param minutes minutes
     */
    void transit(Store store, Building from, Building to, int minutes) {
        var t = new BuildingTransit();
        t.store = store;
        t.origin = from;
        t.destination = to;
        t.minutes = minutes;
        t.persist();
    }

    /**
     * Adds a planning entry.
     *
     * @param e employee
     * @param kind SHIFT, ABSENCE or TASK
     * @param building building
     * @param start start
     * @param end end
     * @return the entry
     */
    PlanningEntry entry(Employee e, String kind, Building building, LocalDateTime start, LocalDateTime end) {
        var p = new PlanningEntry();
        p.employee = e;
        p.kind = kind;
        p.title = kind + " " + e.firstName + " " + (++sequence);
        p.building = building;
        p.startsAt = start;
        p.endsAt = end;
        p.persist();
        return p;
    }

    /**
     * Gives both pickers a presence covering the whole morning and afternoon.
     *
     * @param f fixture
     */
    void presences(Fixture f) {
        entry(f.anna(), "SHIFT", f.warehouse(), NOW.minusHours(3), NOW.plusHours(8));
        entry(f.bruno(), "SHIFT", f.warehouse(), NOW.minusHours(3), NOW.plusHours(8));
    }

    /**
     * Creates a shelf replenishment from the warehouse to the shop.
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
     * Creates a truck unloading into the warehouse.
     *
     * @param f fixture
     * @param code code
     * @param assignee employee or null
     * @param start start
     * @param end end
     * @return the order
     */
    ReplenishmentOrder truck(Fixture f, String code, Employee assignee, LocalDateTime start, LocalDateTime end) {
        var o = shelving(f, code, assignee, start, end);
        o.operation = ReplenishmentOrder.Operation.TRUCK_UNLOADING;
        o.sourceBuilding = null;
        o.destinationBuilding = f.warehouse();
        o.truckReference = "CAMION";
        return o;
    }

    /**
     * Adds one product line to a replenishment.
     *
     * @param f fixture
     * @param o order
     * @param index product index
     * @param fromWarehouse whether the line is picked in the warehouse (otherwise it comes from a truck)
     * @param quantity quantity
     */
    void line(Fixture f, ReplenishmentOrder o, int index, boolean fromWarehouse, int quantity) {
        var account = stocks.account(f.store().id, f.products()[index].id);
        var destination = o.operation == ReplenishmentOrder.Operation.TRUCK_UNLOADING ? f.reserves()[index] : f.shelves()[index];
        o.supplies.add(stocks.planSupply(account.id, o.code + ":" + index, fromWarehouse ? f.reserves()[index].id : null, destination.id, BigDecimal.valueOf(quantity), o.requestedEnd));
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
        stocks.adjust(account.id, location.id, "sup-test-" + location.id + "-" + (++sequence), BigDecimal.valueOf(quantity), NOW.minusHours(5));
    }

    /**
     * Runs the supervision on the current day.
     *
     * @param f fixture
     * @return the report
     */
    Report report(Fixture f) {
        em.flush();
        return supervision.report(f.store().id, NOW.toLocalDate().atStartOfDay().toString(), NOW.toLocalDate().plusDays(1).atStartOfDay().toString());
    }

    /**
     * Finds an anomaly by identity.
     *
     * @param r report
     * @param id identity
     * @return the anomaly, if any
     */
    Optional<Anomaly> find(Report r, String id) {
        return r.anomalies().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    /**
     * Applies a proposal as the frontend does.
     *
     * @param f fixture
     * @param a anomaly
     * @param p proposal
     * @return the HTTP response
     */
    jakarta.ws.rs.core.Response apply(Fixture f, Anomaly a, Proposal p) {
        return supervision.apply(new ApplyRequest(f.store().id, a.id(), p.id(), p.basis(),
                NOW.toLocalDate().atStartOfDay(), NOW.toLocalDate().plusDays(1).atStartOfDay()));
    }

    /**
     * A late order is a proven blocking with its overdue end; its reschedule proposal is applied only after
     * revalidation, through the existing scheduling rules.
     */
    @Test
    void lateOrderIsBlockingAndRescheduleIsAppliedAfterRevalidation() {
        var f = fixture("SPLATE", false);
        presences(f);
        var late = shelving(f, "SPL-LATE", f.anna(), NOW.minusHours(2), NOW.minusHours(1));
        stock(f, f.reserves()[0], 50);
        line(f, late, 0, true, 5);
        var a = find(report(f), "LATE:" + late.id).orElseThrow();
        assertEquals(Certainty.BLOCKING, a.certainty());
        assertEquals(NOW.minusHours(1), a.deadline());
        assertTrue(a.explanation().contains("picking dans " + f.warehouse().buildingCode), a.explanation());
        assertEquals("OPEN_ORDER", a.actions().stream().filter(Action::primary).findFirst().orElseThrow().code());
        var p = a.proposals().stream().filter(x -> x.employeeId().equals(f.bruno().id)).findFirst().orElseThrow();
        assertEquals("RESCHEDULE", p.kind());
        assertEquals(NOW.plusMinutes(15), p.start());
        assertEquals(NOW.plusMinutes(75), p.end());
        assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, late.status, "a diagnosis never changes the order");
        var response = apply(f, a, p);
        assertEquals(200, response.getStatus());
        em.flush();
        em.refresh(late);
        assertEquals(f.bruno().id, late.assignee.id);
        assertEquals(NOW.plusMinutes(15), late.requestedStart);
        assertTrue(find(report(f), "LATE:" + late.id).isEmpty());
    }

    /**
     * An unassigned order only receives valid employees; a proposal that became invalid is refused with the precise
     * reason, without any write.
     */
    @Test
    void unassignedProposalsAreValidAndStaleProposalIsExplained() {
        var f = fixture("SPUNAS", false);
        presences(f);
        entry(f.bruno(), "ABSENCE", f.warehouse(), NOW.plusHours(2), NOW.plusHours(3));
        var order = shelving(f, "SPU-FREE", null, NOW.plusHours(2), NOW.plusMinutes(150));
        var a = find(report(f), "UNASSIGNED:" + order.id).orElseThrow();
        assertEquals(Certainty.RISK, a.certainty());
        assertEquals(List.of(f.anna().id), a.proposals().stream().map(Proposal::employeeId).toList());
        assertEquals("REVIEW_PROPOSALS", a.actions().stream().filter(Action::primary).findFirst().orElseThrow().code());
        entry(f.anna(), "ABSENCE", f.warehouse(), NOW.plusMinutes(140), NOW.plusMinutes(160));
        em.flush();
        var response = apply(f, a, a.proposals().get(0));
        assertEquals(409, response.getStatus());
        var result = (ApplyResult) response.getEntity();
        assertEquals("STALE", result.status());
        assertTrue(result.reasons().stream().anyMatch(r -> r.startsWith("Absence")), result.reasons().toString());
        assertNull(order.assignee);
        order.requestedStart = NOW.plusHours(4);
        order.requestedEnd = NOW.plusMinutes(270);
        em.flush();
        result = (ApplyResult) apply(f, a, a.proposals().get(0)).getEntity();
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("Créneau de SPU-FREE déplacé")), result.reasons().toString());
    }

    /**
     * Orders assigned during the same absence form one card; missing hours are reported as missing data, not as a conflict.
     */
    @Test
    void assignmentConflictsAreGroupedByCauseAndMissingHoursAreNotCertain() {
        var f = fixture("SPCONF", false);
        presences(f);
        var absence = entry(f.bruno(), "ABSENCE", f.warehouse(), NOW.plusHours(1), NOW.plusHours(3));
        var first = shelving(f, "SPC-ONE", f.bruno(), NOW.plusMinutes(70), NOW.plusMinutes(90));
        var second = shelving(f, "SPC-TWO", f.bruno(), NOW.plusMinutes(120), NOW.plusMinutes(140));
        var carl = employee(f.store(), "SPCONF-C", "Carl", "Sans", Employee.EmployeeRole.PICKER);
        var unknown = shelving(f, "SPC-HOURS", carl, NOW.plusHours(4), NOW.plusMinutes(270));
        var r = report(f);
        var grouped = find(r, "ASSIGNMENT:absence-" + absence.id).orElseThrow();
        assertEquals(Certainty.RISK, grouped.certainty());
        assertEquals(List.of(first.id, second.id), grouped.orders().stream().map(OrderRef::id).toList());
        assertTrue(grouped.proposals().stream().allMatch(p -> p.employeeId().equals(f.anna().id)));
        assertEquals(1, r.anomalies().stream().filter(x -> x.orders().stream().anyMatch(o -> o.id().equals(first.id)) && x.type() == Type.ASSIGNMENT).count());
        var missing = r.anomalies().stream().filter(x -> x.type() == Type.ASSIGNMENT && x.order().id().equals(unknown.id)).findFirst().orElseThrow();
        assertEquals(Certainty.MISSING_DATA, missing.certainty());
        assertTrue(missing.explanation().contains("ne peut pas être vérifiée"));
    }

    /**
     * Position checks: the declared position and the directed transit must allow arriving before the start; an
     * employee without known position is missing data; without configured transits the control stays inactive.
     */
    @Test
    void transitAndPositionRequireArrivalBeforeStart() {
        var f = fixture("SPTRAN", true);
        presences(f);
        var position = new EmployeePosition();
        position.employee = f.anna();
        position.building = f.shop();
        position.availableAt = NOW.minusMinutes(5);
        position.persist();
        var soon = shelving(f, "SPT-SOON", f.anna(), NOW.plusMinutes(5), NOW.plusMinutes(35));
        var a = find(report(f), "TRANSIT:" + soon.id).orElseThrow();
        assertEquals(Certainty.BLOCKING, a.certainty());
        assertTrue(a.explanation().contains("arrivée à 10:10"), a.explanation());
        assertEquals("OPEN_POSITION", a.actions().stream().filter(Action::primary).findFirst().orElseThrow().code());
        assertTrue(a.proposals().stream().allMatch(p -> p.employeeId().equals(f.bruno().id)), "only an employee on site is proposed");
        var later = shelving(f, "SPT-LATER", f.anna(), NOW.plusHours(1), NOW.plusMinutes(90));
        assertTrue(find(report(f), "TRANSIT:" + later.id).isEmpty(), "ten minutes are enough before 11:00");
        var nobody = employee(f.store(), "SPTRAN-C", "Chloé", "Inconnue", Employee.EmployeeRole.PICKER);
        entry(nobody, "SHIFT", null, NOW.minusHours(1), NOW.plusHours(8));
        var unknown = shelving(f, "SPT-UNK", nobody, NOW.plusHours(3), NOW.plusMinutes(200));
        assertEquals(Certainty.MISSING_DATA, find(report(f), "TRANSIT:" + unknown.id).orElseThrow().certainty());

        var free = fixture("SPNOTR", false);
        presences(free);
        var noControl = shelving(free, "SPN-SOON", free.anna(), NOW.plusMinutes(5), NOW.plusMinutes(35));
        var r = report(free);
        assertFalse(r.transitControl());
        assertTrue(find(r, "TRANSIT:" + noControl.id).isEmpty());
    }

    /**
     * Stock distinguishes a current shortage, a forecast shortage caused by earlier orders, an overdue supply and an
     * address without any inventory, which is unknown rather than empty.
     */
    @Test
    void stockDistinguishesCurrentShortageForecastLateSupplyAndUnknown() {
        var f = fixture("SPSTOK", false);
        presences(f);
        stock(f, f.reserves()[0], 3);
        stock(f, f.reserves()[1], 15);
        var now = shelving(f, "SPS-NOW", f.anna(), NOW.minusMinutes(10), NOW.plusMinutes(50));
        line(f, now, 0, true, 10);
        var first = shelving(f, "SPS-FIRST", f.bruno(), NOW.plusHours(1), NOW.plusMinutes(80));
        line(f, first, 1, true, 10);
        var next = shelving(f, "SPS-NEXT", f.anna(), NOW.plusHours(2), NOW.plusMinutes(140));
        line(f, next, 1, true, 10);
        line(f, next, 2, true, 4);
        var r = report(f);
        var current = find(r, "STOCK:" + now.id).orElseThrow();
        assertEquals(Certainty.BLOCKING, current.certainty());
        assertEquals("SHORTAGE_NOW", current.products().get(0).state());
        assertEquals(0, new BigDecimal("3").compareTo(current.products().get(0).available()));
        assertNotNull(current.products().get(0).accountId());
        assertEquals("OPEN_STOCK", current.actions().stream().filter(Action::primary).findFirst().orElseThrow().code());
        assertTrue(find(r, "STOCK:" + first.id).isEmpty(), "15 units cover the first order");
        var forecast = find(r, "STOCK:" + next.id).orElseThrow();
        assertEquals(Certainty.RISK, forecast.certainty());
        var shortage = forecast.products().stream().filter(p -> p.productId().equals(f.products()[1].id)).findFirst().orElseThrow();
        assertEquals("SHORTAGE_FORECAST", shortage.state());
        assertTrue(shortage.explanation().contains("SPS-FIRST"), shortage.explanation());
        var unknown = forecast.products().stream().filter(p -> p.productId().equals(f.products()[2].id)).findFirst().orElseThrow();
        assertEquals("UNKNOWN", unknown.state());
        assertNull(unknown.available());

        var truck = truck(f, "SPS-TRUCK", f.bruno(), NOW.minusHours(3), NOW.minusHours(2));
        line(f, truck, 2, false, 20);
        var r2 = report(f);
        var lateSupply = find(r2, "STOCK:" + next.id).orElseThrow().products().stream().filter(p -> p.productId().equals(f.products()[2].id)).findFirst().orElseThrow();
        assertEquals("LATE_SUPPLY", lateSupply.state());
        assertEquals(0, new BigDecimal("20").compareTo(lateSupply.overdue()));

        var onlyUnknown = shelving(f, "SPS-UNK", f.bruno(), NOW.plusHours(5), NOW.plusMinutes(320));
        var otherAccount = new Product();
        otherAccount.ean = "9933000000019";
        otherAccount.name = "Sans inventaire";
        otherAccount.productType = ProductType.UNIT;
        otherAccount.temperatureZone = TemperatureZone.AMBIENT;
        otherAccount.persist();
        var place = new WarehouseShelfLocation();
        place.product = otherAccount; place.area = "RES"; place.aisle = "UNK"; place.unit = "R9"; place.elevation = "1"; place.position = "1";
        place.persist();
        f.warehouse().locations.add(place);
        var shelf = new StoreShelfLocation();
        shelf.product = otherAccount; shelf.area = "EPI"; shelf.aisle = "UNK"; shelf.unit = "B9"; shelf.elevation = "1"; shelf.position = "1";
        shelf.persist();
        f.shop().locations.add(shelf);
        em.flush();
        var account = stocks.account(f.store().id, otherAccount.id);
        onlyUnknown.supplies.add(stocks.planSupply(account.id, "SPS-UNK:0", place.id, shelf.id, BigDecimal.ONE, onlyUnknown.requestedEnd));
        assertEquals(Certainty.MISSING_DATA, find(report(f), "STOCK:" + onlyUnknown.id).orElseThrow().certainty());
    }

    /**
     * Shelf replenishment depends on its truck unloading: a later truck is a risk, a late truck is folded into the
     * truck's own card as a consequence, a cancelled truck blocks; the shelf put-away waits for the warehouse picking.
     */
    @Test
    void shelfReplenishmentDependsOnTruckAndOnItsPickingStage() {
        var f = fixture("SPDEPS", true);
        presences(f);
        stock(f, f.reserves()[0], 100);
        var truck = truck(f, "SPD-TRUCK", f.bruno(), NOW.plusHours(1), NOW.plusHours(2));
        var shelf = shelving(f, "SPD-SHELF", f.anna(), NOW.plusMinutes(90), NOW.plusMinutes(150));
        shelf.predecessor = truck;
        line(f, shelf, 0, true, 5);
        var a = find(report(f), "DEPENDENCY:" + shelf.id).orElseThrow();
        assertEquals(Certainty.RISK, a.certainty());
        assertEquals("OPEN_PREREQUISITE", a.actions().stream().filter(Action::primary).findFirst().orElseThrow().code());
        assertEquals(truck.id, a.context().get("prerequisiteId"));

        truck.requestedStart = NOW.minusHours(2);
        truck.requestedEnd = NOW.minusHours(1);
        var r = report(f);
        var late = find(r, "LATE:" + truck.id).orElseThrow();
        assertEquals(Severity.CRITICAL, late.severity());
        assertEquals(List.of(shelf.id), late.consequences().stream().map(OrderRef::id).toList());
        assertTrue(late.impact().contains("SPD-SHELF"));
        assertTrue(find(r, "DEPENDENCY:" + shelf.id).isEmpty(), "the consequence is not repeated in a second card");

        truck.status = BaseHandlingOrder.HandlingOrderStatus.CANCELLED;
        assertEquals(Certainty.BLOCKING, find(report(f), "DEPENDENCY:" + shelf.id).orElseThrow().certainty());

        shelf.predecessor = null;
        shelf.requestedStart = NOW.minusMinutes(40);
        shelf.requestedEnd = NOW.plusMinutes(20);
        shelf.status = BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
        var source = (ReplenishmentExecution) shelf.createExecution(f.anna());
        source.phase = "SOURCE";
        source.persist();
        var stage = find(report(f), "DEPENDENCY:" + shelf.id).orElseThrow();
        assertTrue(stage.explanation().contains("picking dans l’entrepôt n’est pas terminé (0/1"), stage.explanation());
        assertEquals(Certainty.RISK, stage.certainty());
    }

    /**
     * A late order still waiting for its prerequisite is one card whose explanation gives the cause, with access to the
     * prerequisite; a truck that is not late keeps no card of its own.
     */
    @Test
    void lateOrderWaitingForItsPrerequisiteIsOneCard() {
        var f = fixture("SPFOLD", false);
        presences(f);
        stock(f, f.reserves()[0], 100);
        var truck = truck(f, "SPF-TRUCK", f.bruno(), NOW.minusMinutes(30), NOW.plusHours(1));
        var shelf = shelving(f, "SPF-SHELF", f.anna(), NOW.minusHours(2), NOW.minusHours(1));
        shelf.predecessor = truck;
        line(f, shelf, 0, true, 5);
        var r = report(f);
        var late = find(r, "LATE:" + shelf.id).orElseThrow();
        assertTrue(late.explanation().contains("Cause : ") && late.explanation().contains("SPF-TRUCK"), late.explanation());
        assertTrue(late.orders().stream().anyMatch(o -> o.id().equals(truck.id) && "PREREQUISITE".equals(o.role())));
        assertTrue(late.actions().stream().anyMatch(a -> a.code().equals("OPEN_PREREQUISITE")));
        assertEquals(truck.id, late.context().get("prerequisiteId"));
        assertTrue(find(r, "DEPENDENCY:" + shelf.id).isEmpty());
    }

    /**
     * Destination buffers: deactivated, currently full (without inventing a future release) or incompatible;
     * a compatible destination is proposed and applied after validation.
     */
    @Test
    void destinationBuffersAreCheckedAndCompatibleDestinationIsApplied() {
        var f = fixture("SPBUFF", false);
        presences(f);
        stock(f, f.shelves()[0], 30);
        var off = buffer(f, "SPB-OFF", false, 5, 0);
        var full = buffer(f, "SPB-FULL", true, 1, 0);
        var frozen = buffer(f, "SPB-COLD", true, 0, 4);
        var good = buffer(f, "SPB-OK", true, 3, 0);
        var tote = new Tote();
        tote.code = "SPB-TOTE";
        tote.store = f.store();
        tote.toteType = Tote.ToteType.STANDARD;
        tote.volume = new BigDecimal("40");
        tote.persist();
        var loaded = LoadedTote.useAnEmptyTote(tote);
        loaded.storageType = TemperatureZone.AMBIENT;
        loaded.buffer = full;
        loaded.persist();
        var protectOff = protection(f, "SPB-P-OFF", off);
        var protectFull = protection(f, "SPB-P-FULL", full);
        var protectCold = protection(f, "SPB-P-COLD", frozen);
        var r = report(f);
        var inactive = find(r, "BUFFER:" + off.id + ":INACTIVE").orElseThrow();
        assertEquals(Certainty.RISK, inactive.certainty());
        assertEquals("OPEN_BUFFERS", inactive.actions().stream().filter(Action::primary).findFirst().orElseThrow().code());
        assertEquals(List.of(good.id), inactive.proposals().stream().map(Proposal::bufferId).toList(), "only buffers with a free ambient place now");
        var saturated = find(r, "BUFFER:" + full.id + ":FULL").orElseThrow();
        assertTrue(saturated.explanation().contains("aucune libération future"), saturated.explanation());
        assertEquals(1, saturated.buffers().get(0).zones().get(0).occupied());
        assertTrue(find(r, "BUFFER:" + frozen.id + ":INCOMPATIBLE").isPresent());
        assertEquals(List.of(protectCold.id), find(r, "BUFFER:" + frozen.id + ":INCOMPATIBLE").orElseThrow().orders().stream().map(OrderRef::id).toList());
        var response = apply(f, inactive, inactive.proposals().get(0));
        assertEquals(200, response.getStatus());
        assertEquals(good.id, protectOff.protectionBuffer.id);
        assertTrue(find(report(f), "BUFFER:" + off.id + ":INACTIVE").isEmpty());
        assertNotNull(protectFull);
    }

    /**
     * Creates a buffer in the shop.
     *
     * @param f fixture
     * @param code code
     * @param active active flag
     * @param ambient ambient capacity
     * @param frozenCapacity frozen capacity
     * @return the buffer
     */
    Buffer buffer(Fixture f, String code, boolean active, int ambient, int frozenCapacity) {
        var b = new Buffer();
        b.building = f.shop();
        b.bufferCode = code;
        b.active = active;
        b.ambientCapacity = ambient;
        b.frozenCapacity = frozenCapacity;
        b.persist();
        return b;
    }

    /**
     * Creates a stock protection order of an ambient product towards a buffer.
     *
     * @param f fixture
     * @param code code
     * @param buffer protection buffer
     * @return the order
     */
    StockProtectionOrder protection(Fixture f, String code, Buffer buffer) {
        var o = new StockProtectionOrder();
        o.store = f.store();
        o.code = code;
        o.operation = ReplenishmentOrder.Operation.STOCK_PROTECTION;
        o.sourceBuilding = f.shop();
        o.destinationBuilding = f.shop();
        o.protectionBuffer = buffer;
        o.assignee = f.anna();
        o.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        o.requestedStart = NOW.plusHours(1).plusMinutes(sequence++ * 20L);
        o.requestedEnd = o.requestedStart.plusMinutes(15);
        o.persist();
        var account = stocks.account(f.store().id, f.products()[0].id);
        o.supplies.add(stocks.planSupply(account.id, code + ":0", f.shelves()[0].id, f.reserves()[0].id, BigDecimal.ONE, o.requestedEnd));
        return o;
    }

    /**
     * A manager only sees and changes his own store: another store is forbidden, even with a proposal built for it.
     */
    @Test
    @TestSecurity(user = "SPSCOP-M", roles = {"MANAGER"})
    void managerIsRestrictedToHisStore() {
        var own = fixture("SPSCOP", false);
        employee(own.store(), "SPSCOP-M", "Maud", "Manager", Employee.EmployeeRole.MANAGER);
        var other = fixture("SPOTHR", false);
        presences(other);
        var foreign = shelving(other, "SPO-FREE", null, NOW.plusHours(2), NOW.plusMinutes(150));
        em.flush();
        assertEquals(own.store().id, supervision.report(null, null, null).storeId());
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> supervision.report(other.store().id, null, null));
        var basis = new Basis(foreign.id, foreign.version, foreign.status.name(), null, foreign.requestedStart, foreign.requestedEnd, null);
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> supervision.apply(new ApplyRequest(other.store().id, "UNASSIGNED:" + foreign.id, "ASSIGN:x", basis, null, null)));
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> supervision.apply(new ApplyRequest(null, "UNASSIGNED:" + foreign.id, "ASSIGN:x", basis, null, null)));
        assertNull(foreign.assignee);
    }

    /**
     * The analysis does not issue one query per order: its statement count stays bounded when orders multiply.
     */
    @Test
    void analysisQueriesDoNotGrowWithTheNumberOfOrders() {
        var f = fixture("SPQRY", true);
        presences(f);
        stock(f, f.reserves()[0], 1000);
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            for (int i = 0; i < 3; i++) line(f, shelving(f, "SPQ-A" + i, i % 2 == 0 ? f.anna() : null, NOW.plusMinutes(30 + i * 40L), NOW.plusMinutes(60 + i * 40L)), 0, true, 1);
            report(f);
            em.clear();
            statistics.clear();
            report(f);
            long small = statistics.getPrepareStatementCount();
            for (int i = 0; i < 12; i++) line(f, shelving(f, "SPQ-B" + i, i % 2 == 0 ? Employee.findById(f.bruno().id) : null, NOW.plusMinutes(300 + i * 20L), NOW.plusMinutes(315 + i * 20L)), 0, true, 1);
            report(f);
            em.clear();
            statistics.clear();
            report(f);
            long large = statistics.getPrepareStatementCount();
            assertTrue(large <= small + 6, "statements: " + small + " for 3 orders, " + large + " for 15 orders");
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }
}
