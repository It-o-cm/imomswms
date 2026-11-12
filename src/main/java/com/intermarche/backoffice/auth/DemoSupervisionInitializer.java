package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.executing.ReplenishmentExecution;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.stock.StockMovement;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.workforce.EmployeePosition;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
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
 * Targeted, additive supervision scenario for the LYO-042 demo store, anchored on the time of its first seed.
 * <p>
 * It creates its own employees (Sofia Lambert, Hugo Martin, Léa Garnier), orders prefixed {@code SUP-} and one
 * deactivated buffer. Stock is only written on warehouse addresses that had no ledger history, so the existing stock
 * scenarios (Nutella, replenishments, buffers, planning) keep their hypotheses. Once any {@code SUP-} order exists,
 * the scenario is never reseeded or shifted: the manager's changes are preserved. Orders are never OPEN, so the
 * scheduler does not replan them.
 * <ul>
 *   <li>SUP-CAM-RETARD: truck unloading overdue, which blocks SUP-RAY-ATTENTE (grouped consequence, overdue supply).</li>
 *   <li>SUP-RAY-RETARD: shelf replenishment not done after its slot.</li>
 *   <li>SUP-RAY-SANS: no employee, with valid proposals.</li>
 *   <li>SUP-RAY-ABSENT: assigned during an absence; SUP-RAY-NOHOR: assigned to an employee without hours (missing data).</li>
 *   <li>SUP-RAY-TRANSIT: assignee declared in the shop while the picking starts in the warehouse sooner than the transit.</li>
 *   <li>SUP-RAY-MANQUE: picking in progress with a current shortage; SUP-RAY-PREVU: forecast shortage and unknown stock.</li>
 *   <li>SUP-GRP-BUFFER: grouping towards a deactivated buffer, with compatible destinations.</li>
 * </ul>
 */
@ApplicationScoped
public class DemoSupervisionInitializer {

    static final String MARKER = "SUP-CAM-RETARD";

    @Inject EntityManager em;
    @Inject TemporalStockService stocks;

    /**
     * Seeds the scenario once for the LYO-042 store.
     *
     * @param store the demo store
     */
    @Transactional
    public void seed(Store store) {
        if (!"LYO-042".equals(store.code) || BaseHandlingOrder.count("code like ?1", "SUP-%") > 0) return;
        em.lock(store, LockModeType.PESSIMISTIC_WRITE);
        StoreBuilding shop = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();
        WarehouseBuilding warehouse = WarehouseBuilding.find("buildingCode", "LYO-042-WH").firstResult();
        if (shop == null || warehouse == null) return;
        var pairs = freshPairs(shop, warehouse);
        if (pairs.size() < 5) return;
        LocalDateTime now = DateTimeProvider.now().withSecond(0).withNano(0);

        Employee sofia = employee(store, "DEMO-S01", "Sofia", "Lambert");
        Employee hugo = employee(store, "DEMO-S02", "Hugo", "Martin");
        Employee lea = employee(store, "DEMO-S03", "Léa", "Garnier");
        entry(sofia, "SHIFT", "Démo supervision · présence magasin", shop, now.minusHours(3), now.plusHours(6));
        entry(hugo, "SHIFT", "Démo supervision · présence entrepôt", warehouse, now.minusHours(3), now.plusHours(6));
        entry(hugo, "ABSENCE", "Démo supervision · rendez-vous Hugo", warehouse, now.plusMinutes(60), now.plusMinutes(120));
        if (EmployeePosition.count("employee", hugo) == 0) {
            var position = new EmployeePosition();
            position.employee = hugo;
            position.building = shop;
            position.availableAt = now;
            position.persist();
        }

        ShelfLocation[] late = pairs.get(0), shortage = pairs.get(1), forecast = pairs.get(2), unknown = pairs.get(3), plenty = pairs.get(4);
        stock(store, late[1], "demo-supervision-late", new BigDecimal("2"), now);
        stock(store, shortage[1], "demo-supervision-short", new BigDecimal("3"), now);
        stock(store, forecast[1], "demo-supervision-forecast", new BigDecimal("12"), now);
        stock(store, plenty[1], "demo-supervision-plenty", new BigDecimal("200"), now);

        var truck = order(store, MARKER, null, warehouse, hugo, now.minusMinutes(120), now.minusMinutes(60), BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        supply(store, truck, late[1].product, null, late[1], new BigDecimal("20"));
        var waiting = order(store, "SUP-RAY-ATTENTE", warehouse, shop, sofia, now.plusMinutes(40), now.plusMinutes(100), BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        waiting.predecessor = truck;
        supply(store, waiting, late[1].product, late[1], late[0], new BigDecimal("10"));

        var overdue = order(store, "SUP-RAY-RETARD", warehouse, shop, sofia, now.minusMinutes(90), now.minusMinutes(30), BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        supply(store, overdue, plenty[1].product, plenty[1], plenty[0], new BigDecimal("6"));
        var unassigned = order(store, "SUP-RAY-SANS", warehouse, shop, null, now.plusMinutes(120), now.plusMinutes(150), BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        supply(store, unassigned, plenty[1].product, plenty[1], plenty[0], new BigDecimal("8"));
        var absent = order(store, "SUP-RAY-ABSENT", warehouse, shop, hugo, now.plusMinutes(112), now.plusMinutes(127), BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        supply(store, absent, plenty[1].product, plenty[1], plenty[0], new BigDecimal("4"));
        var noHours = order(store, "SUP-RAY-NOHOR", warehouse, shop, lea, now.plusMinutes(180), now.plusMinutes(210), BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        supply(store, noHours, plenty[1].product, plenty[1], plenty[0], new BigDecimal("5"));
        var transit = order(store, "SUP-RAY-TRANSIT", warehouse, shop, hugo, now.plusMinutes(5), now.plusMinutes(35), BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        supply(store, transit, plenty[1].product, plenty[1], plenty[0], new BigDecimal("3"));

        var inProgress = order(store, "SUP-RAY-MANQUE", warehouse, shop, sofia, now.minusMinutes(20), now.plusMinutes(25), BaseHandlingOrder.HandlingOrderStatus.PROCESSING);
        supply(store, inProgress, shortage[1].product, shortage[1], shortage[0], new BigDecimal("15"));
        var source = (ReplenishmentExecution) inProgress.createExecution(sofia);
        source.phase = "SOURCE";
        source.persist();
        var destination = (ReplenishmentExecution) inProgress.createExecution(sofia);
        destination.phase = "DESTINATION";
        destination.status = HandlingExecution.ExecutionStatus.PENDING;
        destination.persist();

        var planned = order(store, "SUP-RAY-PREVU", warehouse, shop, sofia, now.plusMinutes(240), now.plusMinutes(270), BaseHandlingOrder.HandlingOrderStatus.SCHEDULED);
        supply(store, planned, forecast[1].product, forecast[1], forecast[0], new BigDecimal("40"));
        supply(store, planned, unknown[1].product, unknown[1], unknown[0], new BigDecimal("5"));

        grouping(store, shop, sofia, now);
        em.flush();
    }

    /**
     * Returns pairs (shop shelf, warehouse address) of unit products whose warehouse address has no ledger history,
     * so that seeding stock there cannot alter an existing scenario.
     *
     * @param shop the shop
     * @param warehouse the main warehouse
     * @return pairs ordered by EAN, from the end of the catalogue
     */
    private List<ShelfLocation[]> freshPairs(StoreBuilding shop, WarehouseBuilding warehouse) {
        var result = new ArrayList<ShelfLocation[]>();
        var shelves = shop.locations.stream().map(l -> Hibernate.unproxy(l, ShelfLocation.class))
                .filter(l -> l instanceof StoreShelfLocation && l.product != null).toList();
        var reserves = warehouse.locations.stream().map(l -> Hibernate.unproxy(l, ShelfLocation.class))
                .filter(l -> l instanceof WarehouseShelfLocation && l.product != null)
                .sorted(Comparator.comparing((ShelfLocation l) -> l.product.ean).reversed()).toList();
        var used = new HashSet<Long>();
        for (var reserve : reserves) {
            Product product = Hibernate.unproxy(reserve.product, Product.class);
            if (product.productType != ProductType.UNIT || "3017620422003".equals(product.ean) || !used.add(product.id)) continue;
            if (StockMovement.count("location", reserve) > 0 || com.intermarche.backoffice.domain.stock.ExpectedSupply.count("source = ?1 or destination = ?1", reserve) > 0) continue;
            var shelf = shelves.stream().filter(l -> Hibernate.unproxy(l.product, Product.class).id.equals(product.id)).findFirst().orElse(null);
            if (shelf == null) continue;
            result.add(new ShelfLocation[]{shelf, reserve});
            if (result.size() == 5) break;
        }
        return result;
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
     * @param kind SHIFT or ABSENCE
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
     * Records the initial physical stock of a warehouse address without history.
     *
     * @param store store
     * @param location address
     * @param key idempotency key
     * @param quantity quantity
     * @param at instant
     */
    private void stock(Store store, ShelfLocation location, String key, BigDecimal quantity, LocalDateTime at) {
        var account = stocks.account(store.id, Hibernate.unproxy(location.product, Product.class).id);
        stocks.adjust(account.id, location.id, key, quantity, at);
    }

    /**
     * Creates a replenishment order.
     *
     * @param store store
     * @param code unique code
     * @param from source building, null for a truck
     * @param to destination building
     * @param assignee employee or null
     * @param start start
     * @param end end
     * @param status status
     * @return the order
     */
    private ReplenishmentOrder order(Store store, String code, Building from, Building to, Employee assignee, LocalDateTime start, LocalDateTime end,
                                     BaseHandlingOrder.HandlingOrderStatus status) {
        var o = new ReplenishmentOrder();
        o.store = store;
        o.code = code;
        o.sourceBuilding = from;
        o.destinationBuilding = to;
        o.operation = from == null ? ReplenishmentOrder.Operation.TRUCK_UNLOADING : ReplenishmentOrder.Operation.SHELF_REPLENISHMENT;
        o.truckReference = from == null ? "CAMION-DEMO-SUPERVISION" : null;
        o.assignee = assignee;
        o.status = status;
        o.requestedStart = start;
        o.requestedEnd = end;
        o.persistAndFlush();
        return o;
    }

    /**
     * Adds one product movement to an order.
     *
     * @param store store
     * @param order order
     * @param product product
     * @param source source address, null for a truck
     * @param destination destination address
     * @param quantity quantity
     */
    private void supply(Store store, ReplenishmentOrder order, Product product, ShelfLocation source, ShelfLocation destination, BigDecimal quantity) {
        var account = stocks.account(store.id, product.id);
        order.supplies.add(stocks.planSupply(account.id, order.code + ":" + product.ean, source == null ? null : source.id, destination.id, quantity, order.requestedEnd));
    }

    /**
     * Creates a grouping towards a deactivated buffer, reusing an existing demo lot without modifying it.
     *
     * @param store store
     * @param shop shop
     * @param assignee employee
     * @param now reference time
     */
    private void grouping(Store store, StoreBuilding shop, Employee assignee, LocalDateTime now) {
        Lot lot = Lot.<Lot>list("store", store).stream()
                .filter(l -> l.fulfillment != null && l.fulfillment.order != null && "CMD-2026-0452".equals(l.fulfillment.order.orderId))
                .findFirst().orElse(Lot.<Lot>find("store", store).firstResult());
        if (lot == null) return;
        Buffer off = Buffer.findByCode("SUP-BUF-HS");
        if (off == null) {
            off = new Buffer();
            off.building = shop;
            off.bufferCode = "SUP-BUF-HS";
            off.locationDescription = "Quai drive en travaux";
            off.active = false;
            off.ambientCapacity = 10;
            off.chilledCapacity = 5;
            off.frozenCapacity = 2;
            off.persist();
        }
        var g = new GroupingOrder();
        g.store = store;
        g.code = "SUP-GRP-BUFFER";
        g.assignee = assignee;
        g.customer = lot.customer;
        g.buffer = off;
        g.lots.add(lot);
        g.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        g.requestedStart = now.plusMinutes(195);
        g.requestedEnd = now.plusMinutes(225);
        g.persist();
    }
}
