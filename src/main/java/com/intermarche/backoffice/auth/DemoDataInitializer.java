package com.intermarche.backoffice.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.GPSPosition;
import com.intermarche.backoffice.domain.ReservationRecord;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.OrderLine;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductFamily;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.backoffice.domain.oms.FulfillmentChunk;
import com.intermarche.backoffice.domain.oms.FulfillmentLine;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.AisleWaypoint;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanNodeType;
import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.PlanZoneVertex;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreBuilding;
import com.intermarche.backoffice.domain.store.StoreGroup;
import com.intermarche.backoffice.domain.store.StorePlanImageGenerator;
import com.intermarche.backoffice.domain.store.StoreShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.HandlingOrderSequence;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.GroupingOrder;
import com.intermarche.backoffice.domain.wms.handling.PackingOrder;
import com.intermarche.backoffice.domain.wms.handling.ProductCollectingOrder;
import com.intermarche.backoffice.domain.wms.handling.ZoneCollectingOrder;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Initialization class responsible for creating demo data.
 * <p>
 * This bean listens for the application startup event and creates a coherent set of
 * demo data (store, building, aisles, shelf locations, buffer, employees, totes,
 * products, product families, customer, orders, order requests, configurations and
 * events) so that the application is not empty on first launch.
 * <p>
 * All created objects are dated with the current day: audit fields ({@code createdAt},
 * {@code updatedAt}) are set automatically by {@code BaseEntity} via {@link DateTimeProvider},
 * and business dates (e.g. {@link Order#orderDate}) are explicitly set to {@link DateTimeProvider#now()}.
 * <p>
 * Seeding only runs when the database does not contain any {@link Store} yet (idempotent),
 * and can be disabled with the configuration property {@code imomswms.demo-data.enabled=false}.
 */
@ApplicationScoped
public class DemoDataInitializer {

    /**
     * Logger instance for this class.
     */
    private static final Logger LOGGER = Logger.getLogger(DemoDataInitializer.class);

    /**
     * Jackson mapper used to build the JSON specifications of the demo order requests.
     */
    @Inject
    ObjectMapper objectMapper;

    @Inject
    DemoStockInitializer demoStocks;

    /**
     * The demo product catalog: 50 realistic products (EAN-13, brand, temperature
     * zone, unit, reference weight/volume and TTC unit price).
     */
    private static final List<ProductSpec> DEMO_PRODUCTS = List.of(
            new ProductSpec("3017620422003", "Nutella 400g", "Ferrero", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.400"), null, new BigDecimal("4.50")),
            new ProductSpec("3274080005003", "Lait demi-écrémé 1L", "Lactel", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", null, new BigDecimal("1.000"), new BigDecimal("1.20")),
            new ProductSpec("3560070057003", "Pâtes Penne 500g", "Barilla", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.500"), null, new BigDecimal("1.50")),
            new ProductSpec("3245412630003", "Pommes Golden 1kg", "Vergers de France", ProductType.WEIGHT, TemperatureZone.CHILLED, "kg", new BigDecimal("1.000"), null, new BigDecimal("2.80")),
            new ProductSpec("8712100325003", "Lessive liquide 2L", "Skip", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", null, new BigDecimal("2.000"), new BigDecimal("6.50")),
            new ProductSpec("3017640123454", "Café moulu 250g", "Lavazza", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.250"), null, new BigDecimal("4.20")),
            new ProductSpec("3274080012346", "Riz basmati 1kg", "Uncle Ben's", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("1.000"), null, new BigDecimal("3.10")),
            new ProductSpec("3017650987657", "Huile d'olive 1L", "Puget", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", null, new BigDecimal("1.000"), new BigDecimal("7.90")),
            new ProductSpec("3012345678902", "Sucre en poudre 1kg", "Daddy", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("1.000"), null, new BigDecimal("1.80")),
            new ProductSpec("3012345098762", "Farine T55 1kg", "Francine", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("1.000"), null, new BigDecimal("1.40")),
            new ProductSpec("3274080076546", "Jus d'orange 1L", "Tropicana", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", null, new BigDecimal("1.000"), new BigDecimal("2.60")),
            new ProductSpec("3274080087658", "Yaourts nature x8", "Danone", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("1.000"), null, new BigDecimal("2.90")),
            new ProductSpec("3274080098760", "Beurre demi-sel 250g", "Président", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("0.250"), null, new BigDecimal("2.30")),
            new ProductSpec("3274080010984", "Œufs frais x12", "Fermiers du Sud", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", null, null, new BigDecimal("3.40")),
            new ProductSpec("3245412631009", "Tomates rondes 500g", "Vergers de France", ProductType.WEIGHT, TemperatureZone.CHILLED, "kg", new BigDecimal("0.500"), null, new BigDecimal("2.20")),
            new ProductSpec("3245412632006", "Bananes 1kg", "Vergers de France", ProductType.WEIGHT, TemperatureZone.CHILLED, "kg", new BigDecimal("1.000"), null, new BigDecimal("1.90")),
            new ProductSpec("3274080021096", "Saumon fumé 200g", "Fleury Michon", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("0.200"), null, new BigDecimal("5.50")),
            new ProductSpec("3012345556668", "Pain de mie complet", "Harrys", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.500"), null, new BigDecimal("2.10")),
            new ProductSpec("8712100326004", "Shampoing 300ml", "L'Oréal", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", new BigDecimal("0.300"), null, new BigDecimal("4.80")),
            new ProductSpec("8712100327001", "Papier toilette x12", "Lotus", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", null, null, new BigDecimal("5.90")),
            new ProductSpec("3017620421006", "Confiture de fraises 370g", "Bonne Maman", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.370"), null, new BigDecimal("3.20")),
            new ProductSpec("3017620421013", "Miel d'acacia 250g", "Lune de Miel", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.250"), null, new BigDecimal("6.90")),
            new ProductSpec("3017620421020", "Chocolat noir 70% 100g", "Lindt", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.100"), null, new BigDecimal("2.40")),
            new ProductSpec("3017620421037", "Thé vert 20 sachets", "Lipton", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.040"), null, new BigDecimal("3.50")),
            new ProductSpec("3017620421044", "Café en grains 1kg", "Lavazza", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("1.000"), null, new BigDecimal("12.90")),
            new ProductSpec("3017620421051", "Semoule fine 1kg", "Panzani", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("1.000"), null, new BigDecimal("2.20")),
            new ProductSpec("3017620421068", "Lentilles vertes 500g", "Lustucru", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.500"), null, new BigDecimal("2.10")),
            new ProductSpec("3017620421075", "Haricots rouges 400g", "Bonduelle", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.400"), null, new BigDecimal("1.60")),
            new ProductSpec("3017620421082", "Maïs doux 285g", "Bonduelle", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.285"), null, new BigDecimal("1.30")),
            new ProductSpec("3017620421099", "Thon au naturel 160g", "Petit Navire", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.160"), null, new BigDecimal("2.30")),
            new ProductSpec("3017620421105", "Sardines à l'huile 125g", "Connétable", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.125"), null, new BigDecimal("2.80")),
            new ProductSpec("3017620421112", "Compote de pommes x4", "Andros", ProductType.UNIT, TemperatureZone.AMBIENT, "pcs", new BigDecimal("0.400"), null, new BigDecimal("2.50")),
            new ProductSpec("3017620421129", "Camembert 250g", "Président", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("0.250"), null, new BigDecimal("3.10")),
            new ProductSpec("3017620421136", "Yaourts fraise x8", "Danone", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("1.000"), null, new BigDecimal("2.90")),
            new ProductSpec("3017620421143", "Lait entier 1L", "Lactel", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", null, new BigDecimal("1.000"), new BigDecimal("1.25")),
            new ProductSpec("3017620421150", "Crème fraîche 20cl", "Elle & Vire", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", null, new BigDecimal("0.200"), new BigDecimal("1.80")),
            new ProductSpec("3017620421167", "Jambon blanc 4 tranches", "Fleury Michon", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("0.200"), null, new BigDecimal("3.60")),
            new ProductSpec("3017620421174", "Poulet rôti 1kg", "Traiteur du Coin", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("1.000"), null, new BigDecimal("7.50")),
            new ProductSpec("3017620421181", "Saumon frais 2 pavés 250g", "Frais du Jour", ProductType.WEIGHT, TemperatureZone.CHILLED, "kg", new BigDecimal("0.250"), null, new BigDecimal("8.90")),
            new ProductSpec("3017620421198", "Mozzarella 125g", "Galbani", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("0.125"), null, new BigDecimal("1.90")),
            new ProductSpec("3017620421204", "Jus de pomme 1L", "Tropicana", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", null, new BigDecimal("1.000"), new BigDecimal("2.70")),
            new ProductSpec("3017620421211", "Fromage râpé 200g", "Président", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("0.200"), null, new BigDecimal("2.20")),
            new ProductSpec("3017620421228", "Tofu nature 200g", "Sojasun", ProductType.UNIT, TemperatureZone.CHILLED, "pcs", new BigDecimal("0.200"), null, new BigDecimal("2.10")),
            new ProductSpec("3017620421235", "Dentifrice 75ml", "Colgate", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", new BigDecimal("0.075"), null, new BigDecimal("3.20")),
            new ProductSpec("3017620421242", "Savon liquide 300ml", "Le Petit Marseillais", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", null, new BigDecimal("0.300"), new BigDecimal("2.80")),
            new ProductSpec("3017620421259", "Gel douche 250ml", "Dove", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", null, new BigDecimal("0.250"), new BigDecimal("3.90")),
            new ProductSpec("3017620421266", "Déodorant spray 200ml", "Nivea", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", null, new BigDecimal("0.200"), new BigDecimal("4.50")),
            new ProductSpec("3017620421273", "Mouchoirs x100", "Kleenex", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", new BigDecimal("0.100"), null, new BigDecimal("2.20")),
            new ProductSpec("3017620421280", "Sacs poubelle x20 50L", "Sopalin", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", new BigDecimal("0.500"), null, new BigDecimal("3.50")),
            new ProductSpec("3017620421297", "Éponges x3", "Scotch-Brite", ProductType.UNIT, TemperatureZone.HOUSEHOLD, "pcs", new BigDecimal("0.050"), null, new BigDecimal("2.60")),
            new ProductSpec("3017620422101", "Frites surgelées 1kg", "Findus", ProductType.UNIT, TemperatureZone.FROZEN, "pcs", new BigDecimal("1.000"), null, new BigDecimal("3.20")),
            new ProductSpec("3017620422118", "Pizza 4 fromages", "Dr. Oetker", ProductType.UNIT, TemperatureZone.FROZEN, "pcs", new BigDecimal("0.400"), null, new BigDecimal("4.50")),
            new ProductSpec("3017620422125", "Petits pois extra-fins 800g", "Bonduelle", ProductType.UNIT, TemperatureZone.FROZEN, "pcs", new BigDecimal("0.800"), null, new BigDecimal("1.80")),
            new ProductSpec("3017620422132", "Glace vanille 500ml", "Häagen-Dazs", ProductType.UNIT, TemperatureZone.FROZEN, "pcs", new BigDecimal("0.500"), null, new BigDecimal("5.90")),
            new ProductSpec("3017620422149", "Poisson pané 400g", "Findus", ProductType.UNIT, TemperatureZone.FROZEN, "pcs", new BigDecimal("0.400"), null, new BigDecimal("4.80")),
            new ProductSpec("3017620422156", "Nuggets de poulet 500g", "Père Dodu", ProductType.UNIT, TemperatureZone.FROZEN, "pcs", new BigDecimal("0.500"), null, new BigDecimal("4.20")),
            new ProductSpec("3017620422163", "Épinards hachés 800g", "Bonduelle", ProductType.UNIT, TemperatureZone.FROZEN, "pcs", new BigDecimal("0.800"), null, new BigDecimal("1.90")),
            new ProductSpec("3017620422170", "Croissants pur beurre x4", "Traiteur du Coin", ProductType.UNIT, TemperatureZone.FROZEN, "pcs", new BigDecimal("0.240"), null, new BigDecimal("3.50"))
    );

    /**
     * The demo customers.
     */
    private static final List<CustomerSpec> DEMO_CUSTOMERS = List.of(
            new CustomerSpec("CLI-452301", "Sophie", "Durand", "sophie.durand@gmail.com", "0612345678", "3606001234567", "15 Avenue des Lilas", "69003", "Lyon"),
            new CustomerSpec("CLI-452302", "Lucas", "Moreau", "lucas.moreau@gmail.com", "0623456789", "3606001234568", "8 Rue de la République", "69002", "Lyon"),
            new CustomerSpec("CLI-452303", "Emma", "Petit", "emma.petit@gmail.com", "0634567890", "3606001234569", "42 Cours Gambetta", "69007", "Lyon"),
            new CustomerSpec("CLI-452304", "Hugo", "Leroy", "hugo.leroy@gmail.com", "0645678901", "3606001234570", "3 Place Bellecour", "69002", "Lyon"),
            new CustomerSpec("CLI-452305", "Chloé", "Rousseau", "chloe.rousseau@gmail.com", "0656789012", "3606001234571", "27 Quai Saint-Vincent", "69001", "Lyon")
    );

    /**
     * Demo unit prices (TTC) per product EAN, used to build realistic order
     * specifications (line pricing and order summary) for the demo order requests.
     */
    private static final Map<String, BigDecimal> DEMO_PRICES = DEMO_PRODUCTS.stream()
            .collect(Collectors.toMap(ProductSpec::ean, ProductSpec::unitPrice));

    /**
     * Labels of the demo order options, keyed by option code.
     */
    private static final Map<String, String> OPTION_LABELS = Map.of(
            "OPT_EXP", "Livraison Express",
            "OPT_GIFT", "Carton Cadeau",
            "OPT_COOL", "Caisse Réfrigérée"
    );

    /**
     * Whether demo data seeding is enabled.
     * Defaults to true; set {@code imomswms.demo-data.enabled=false} to disable.
     */
    @ConfigProperty(name = "imomswms.demo-data.enabled", defaultValue = "true")
    boolean demoDataEnabled;

    /**
     * Sets whether demo data seeding is enabled.
     * <p>
     * Exposed mainly for testing purposes.
     *
     * @param enabled true to enable seeding, false to disable.
     */
    public void setDemoDataEnabled(boolean enabled) {
        this.demoDataEnabled = enabled;
    }

    /**
     * Method called automatically when the application starts.
     * <p>
     * It is transactional to ensure the database operations are committed.
     *
     * @param ev the startup event.
     */
    @Transactional
    public void onStartup(@Observes StartupEvent ev) {
        if (!demoDataEnabled) {
            LOGGER.info("Demo data seeding is disabled.");
            return;
        }
        seedDemoData();
    }

    /**
     * Creates the demo data set if the database does not contain any store yet.
     * <p>
     * All business dates are set to the current day via {@link DateTimeProvider#now()}.
     */
    @Inject DemoReplenishmentInitializer demoReplenishments;
    @Inject DemoBufferInitializer demoBuffers;
    @Inject DemoPlanningInitializer demoPlanning;
    @Inject DemoSupervisionInitializer demoSupervision;
    @Inject DemoActivityInitializer demoActivity;
    @Inject DemoSubstitutionInitializer demoSubstitutions;

    /**
     * Creates the demo data once, or completes an existing demo with the examples added since, without resetting it.
     */
    @Transactional
    public void seedDemoData() {
        if (Store.count() > 0) {
            // Add the new example to an existing demo without resetting any data.
            Store demoStore = Store.find("code", "LYO-042").firstResult();
            if (demoStore != null) {
                createTotes(demoStore);
                demoStocks.seed(demoStore, DEMO_PRODUCTS.stream()
                        .map(spec -> Product.findByEan(spec.ean())).filter(java.util.Objects::nonNull).toList());
                demoReplenishments.seed(demoStore);
                demoBuffers.seed(demoStore);
                demoPlanning.seed(demoStore);
                demoSupervision.seed(demoStore);
                demoActivity.seed(demoStore);
                demoSubstitutions.seed(demoStore,DEMO_PRODUCTS.stream().map(spec -> Product.findByEan(spec.ean())).filter(java.util.Objects::nonNull).toList());
            }
            if (demoStore != null && ProductCollectingOrder.count("code", "PRO-DEMO-OPT") == 0) {
                StoreBuilding demoBuilding = StoreBuilding.find("buildingCode", "LYO-042-SHOP").firstResult();
                Employee demoPicker = Employee.find("badgeId", "100235").firstResult();
                List<Lot> demoLots = Lot.<Lot>list("store.id", demoStore.id).stream()
                        .filter(lot -> lot.fulfillment != null && lot.fulfillment.order != null
                                && lot.fulfillment.order.orderId != null
                                && lot.fulfillment.order.orderId.matches("CMD-2026-04(5[1-9]|6[0-2])"))
                        .toList();
                if (demoBuilding != null && demoPicker != null && demoLots.size() == 12) {
                    createComplexPickingDemo(demoStore, demoBuilding, demoPicker, demoLots, DateTimeProvider.now());
                }
            }
            LOGGER.info("Demo data already exists. Skipping base creation.");
            return;
        }

        LOGGER.info("Creating demo data...");
        LocalDateTime now = DateTimeProvider.now();

        Store store = createStore();
        StoreGroup group = createStoreGroup(store);
        StoreBuilding building = createBuilding(store);
        createStorePlan(building);
        List<Product> products = createProducts();
        createShelfLocations(building, products);
        demoStocks.seed(store, products);
        seedPlanImage(building);
        Buffer buffer = createBuffer(building);
        List<Employee> employees = createEmployees(store);
        createTotes(store);
        createProductFamilies(products);
        List<Customer> customers = createCustomers();
        List<DemoOrder> demoOrders = createOrders(store, customers, products, now);
        List<OrderRequest> requests = createOrderRequests(store, demoOrders);
        List<Fulfillment> fulfillments = createFulfillments(building, demoOrders);
        List<Lot> lots = createLots(store, fulfillments);
        createHandlingOrders(store, building, buffer, employees, lots, now);
        createComplexPickingDemo(store, building, employees.get(1), lots, now);
        demoReplenishments.seed(store);
        demoBuffers.seed(store);
        demoPlanning.seed(store);
        demoSupervision.seed(store);
        demoActivity.seed(store);
        demoSubstitutions.seed(store,products);
        createConfigurations(store, group);
        createOrderHandlerConfiguration(store);
        createEvents(store, requests);

        LOGGER.info("Demo data created successfully.");
    }

    // --------------------------------------------------
    // Store & StoreGroup
    // --------------------------------------------------

    /**
     * Creates the demo store.
     *
     * @return the persisted demo store.
     */
    private Store createStore() {
        Store store = new Store();
        store.code = "LYO-042";
        store.name = "Intermarché Lyon Part-Dieu";
        store.address = createAdresse("17 Boulevard Vivier-Merle", "69003", "Lyon");
        store.persistAndFlush();
        LOGGER.infof("Demo Store created: %s", store.code);
        return store;
    }

    /**
     * Creates the demo store group and links it to the given store.
     *
     * @param store the demo store.
     * @return the persisted demo store group.
     */
    private StoreGroup createStoreGroup(Store store) {
        StoreGroup group = new StoreGroup();
        group.code = "LYO";
        group.name = "Région Lyon";
        group.stores.add(store);
        group.persistAndFlush();
        LOGGER.infof("Demo StoreGroup created: %s", group.code);
        return group;
    }

    // --------------------------------------------------
    // Building, Aisles, Shelf Locations, Buffer
    // --------------------------------------------------

    /**
     * Creates the demo store building and links it to the given store.
     *
     * @param store the demo store.
     * @return the persisted demo building.
     */
    private StoreBuilding createBuilding(Store store) {
        StoreBuilding building = new StoreBuilding();
        building.buildingCode = "LYO-042-SHOP";
        building.address = createAdresse("17 Boulevard Vivier-Merle", "69003", "Lyon");
        building.gpsPosition = new GPSPosition(45.7615, 4.8560);
        store.buildings.add(building);
        store.persistAndFlush();
        LOGGER.infof("Demo Building created: %s", building.buildingCode);
        return building;
    }

    // --------------------------------------------------
    // Store Plan (geometric layer)
    // --------------------------------------------------

    /**
     * Creates the demo store plan inside the given building: topological nodes,
     * aisles with their geometric layer (flow/shape, head/tail nodes, waypoints)
     * and polygonal zones.
     * <p>
     * The plan mirrors the storepilot "Plan magasin" demo topology and the
     * generated plan image: a central spine from the entrance to the fridges,
     * crossed by the department aisles (grocery, fridges, freezers, household),
     * whose extremities are chained by a perimeter rocade (ROC_*) so pickers
     * can walk from one aisle end to the next without returning to the spine.
     * The logical graph between aisles ({@code connectedToHead}/{@code
     * connectedToTail}) mirrors the geometry: each spine segment is connected
     * to the cross aisles crossing it, and each cross aisle is connected to
     * the rocade segments meeting at its extremity.
     *
     * @param building the demo building.
     */
    private void createStorePlan(Building building) {
        Map<String, PlanNode> nodes = createPlanNodes(building);
        createPlanAisles(building, nodes);
        createPlanZones(building);
    }

    /**
     * Seeds the store plan background image of the demo building: generates the
     * PNG (a realistic store floor plan; only the department labels are derived
     * from the shelf locations) with {@link StorePlanImageGenerator} and stores
     * it base64-encoded, with its world origin (top-left corner).
     * <p>
     * Must be called after {@code createShelfLocations} so the locations exist.
     *
     * @param building the demo building.
     */
    private void seedPlanImage(Building building) {
        StorePlanImageGenerator.Result result = StorePlanImageGenerator.generate(
                building.locations,
                building.buildingCode);
        building.planImage = Base64.getEncoder().encodeToString(result.png());
        building.planImageX = result.originX();
        building.planImageY = result.originY();
        building.persistAndFlush();
        LOGGER.infof("Demo plan image generated: %d bytes, origin (%.0f, %.0f), size %dx%d",
                result.png().length, result.originX(), result.originY(), result.width(), result.height());
    }

    /**
     * Creates the demo plan nodes (positioned extremities of the aisles).
     *
     * @param building the demo building.
     * @return the created nodes, keyed by code.
     */
    private Map<String, PlanNode> createPlanNodes(Building building) {
        Map<String, PlanNode> nodes = new LinkedHashMap<>();
        // Entrance: bottom center of the plan, in front of the checkouts
        nodes.put("ENTRANCE", createPlanNode(building, "ENTRANCE", 0, 512, PlanNodeType.ENTRANCE, "Entrée"));
        // Central spine intersections (x = 0), from bottom to top
        nodes.put("JC_SUR1", createPlanNode(building, "JC_SUR1", 0, 400, PlanNodeType.INTERSECTION, "Croisement SUR1"));
        nodes.put("JC_DRO1", createPlanNode(building, "JC_DRO1", 0, 384, PlanNodeType.INTERSECTION, "Croisement DRO1"));
        nodes.put("JC_SUR2", createPlanNode(building, "JC_SUR2", 0, 328, PlanNodeType.INTERSECTION, "Croisement SUR2"));
        nodes.put("JC_DRO2", createPlanNode(building, "JC_DRO2", 0, 288, PlanNodeType.INTERSECTION, "Croisement DRO2"));
        nodes.put("JC_SUR3", createPlanNode(building, "JC_SUR3", 0, 248, PlanNodeType.INTERSECTION, "Croisement SUR3"));
        nodes.put("JC_DRO3", createPlanNode(building, "JC_DRO3", 0, 192, PlanNodeType.INTERSECTION, "Croisement DRO3"));
        nodes.put("JC_SUR4", createPlanNode(building, "JC_SUR4", 0, 176, PlanNodeType.INTERSECTION, "Croisement SUR4"));
        nodes.put("JC_EPI1", createPlanNode(building, "JC_EPI1", 0, 112, PlanNodeType.INTERSECTION, "Croisement EPI1"));
        nodes.put("JC_EPI2", createPlanNode(building, "JC_EPI2", 0, 0, PlanNodeType.INTERSECTION, "Croisement EPI2"));
        nodes.put("JC_EPI3", createPlanNode(building, "JC_EPI3", 0, -96, PlanNodeType.INTERSECTION, "Croisement EPI3"));
        nodes.put("JC_EPI4", createPlanNode(building, "JC_EPI4", 0, -192, PlanNodeType.INTERSECTION, "Croisement EPI4"));
        nodes.put("JC_EPI5", createPlanNode(building, "JC_EPI5", 0, -288, PlanNodeType.INTERSECTION, "Croisement EPI5"));
        nodes.put("JC_FRA", createPlanNode(building, "JC_FRA", 0, -368, PlanNodeType.INTERSECTION, "Croisement FRA"));
        // Extremities of the cross aisles: connected to each other by the
        // perimeter rocade (ROC_*), so they are intersections, not dead-ends
        nodes.put("END_SUR1", createPlanNode(building, "END_SUR1", -736, 400, PlanNodeType.INTERSECTION, "Fin SUR1"));
        nodes.put("END_SUR2", createPlanNode(building, "END_SUR2", -736, 328, PlanNodeType.INTERSECTION, "Fin SUR2"));
        nodes.put("END_SUR3", createPlanNode(building, "END_SUR3", -736, 248, PlanNodeType.INTERSECTION, "Fin SUR3"));
        nodes.put("END_SUR4", createPlanNode(building, "END_SUR4", -736, 176, PlanNodeType.INTERSECTION, "Fin SUR4"));
        nodes.put("END_DRO1", createPlanNode(building, "END_DRO1", 736, 384, PlanNodeType.INTERSECTION, "Fin DRO1"));
        nodes.put("END_DRO2", createPlanNode(building, "END_DRO2", 736, 288, PlanNodeType.INTERSECTION, "Fin DRO2"));
        nodes.put("END_DRO3", createPlanNode(building, "END_DRO3", 736, 192, PlanNodeType.INTERSECTION, "Fin DRO3"));
        nodes.put("END_EPI1_W", createPlanNode(building, "END_EPI1_W", -736, 112, PlanNodeType.INTERSECTION, "Fin EPI1 Ouest"));
        nodes.put("END_EPI1_E", createPlanNode(building, "END_EPI1_E", 736, 112, PlanNodeType.INTERSECTION, "Fin EPI1 Est"));
        nodes.put("END_EPI2_W", createPlanNode(building, "END_EPI2_W", -736, 0, PlanNodeType.INTERSECTION, "Fin EPI2 Ouest"));
        nodes.put("END_EPI2_E", createPlanNode(building, "END_EPI2_E", 736, 0, PlanNodeType.INTERSECTION, "Fin EPI2 Est"));
        nodes.put("END_EPI3_W", createPlanNode(building, "END_EPI3_W", -736, -96, PlanNodeType.INTERSECTION, "Fin EPI3 Ouest"));
        nodes.put("END_EPI3_E", createPlanNode(building, "END_EPI3_E", 736, -96, PlanNodeType.INTERSECTION, "Fin EPI3 Est"));
        nodes.put("END_EPI4_W", createPlanNode(building, "END_EPI4_W", -736, -192, PlanNodeType.INTERSECTION, "Fin EPI4 Ouest"));
        nodes.put("END_EPI4_E", createPlanNode(building, "END_EPI4_E", 736, -192, PlanNodeType.INTERSECTION, "Fin EPI4 Est"));
        nodes.put("END_EPI5_W", createPlanNode(building, "END_EPI5_W", -736, -288, PlanNodeType.INTERSECTION, "Fin EPI5 Ouest"));
        nodes.put("END_EPI5_E", createPlanNode(building, "END_EPI5_E", 736, -288, PlanNodeType.INTERSECTION, "Fin EPI5 Est"));
        nodes.put("END_FRA_W", createPlanNode(building, "END_FRA_W", -736, -368, PlanNodeType.INTERSECTION, "Fin FRA Ouest"));
        nodes.put("END_FRA_E", createPlanNode(building, "END_FRA_E", 736, -368, PlanNodeType.INTERSECTION, "Fin FRA Est"));
        LOGGER.infof("Demo PlanNodes created: %d", nodes.size());
        return nodes;
    }

    /**
     * Creates and persists a single plan node.
     *
     * @param building the parent building.
     * @param code     the unique node code.
     * @param x        the X coordinate on the plan, in plan world units (1 unit = 1/8 m).
     * @param y        the Y coordinate on the plan, in plan world units (1 unit = 1/8 m).
     * @param type     the node type.
     * @param label    the display label.
     * @return the persisted node.
     */
    private PlanNode createPlanNode(Building building, String code, double x, double y,
                                    PlanNodeType type, String label) {
        PlanNode node = new PlanNode();
        node.code = code;
        node.label = label;
        node.nodeType = type;
        node.x = x;
        node.y = y;
        node.building = building;
        node.persistAndFlush();
        return node;
    }

    /**
     * Creates the demo aisles inside the given building, with their geometric
     * layer (flow/shape types, head/tail nodes, waypoints).
     *
     * @param building the demo building.
     * @param nodes    the plan nodes, keyed by code.
     */
    private void createPlanAisles(Building building, Map<String, PlanNode> nodes) {
        // Central spine (CEN_01..CEN_13), from the entrance up to the fridges.
        // The logical graph (connectedToHead/connectedToTail) mirrors the geometry:
        // each spine segment is connected to the cross aisles crossing it.
        createPlanAisle(building, nodes, "CEN_01", 14.0, "LINEAR", "STRAIGHT", "ENTRANCE", "JC_SUR1", null, "CEN_02,SUR1");
        createPlanAisle(building, nodes, "CEN_02", 2.0, "LINEAR", "STRAIGHT", "JC_SUR1", "JC_DRO1", "CEN_01,SUR1", "CEN_03,DRO1");
        createPlanAisle(building, nodes, "CEN_03", 7.0, "LINEAR", "STRAIGHT", "JC_DRO1", "JC_SUR2", "CEN_02,DRO1", "CEN_04,SUR2");
        createPlanAisle(building, nodes, "CEN_04", 5.0, "LINEAR", "STRAIGHT", "JC_SUR2", "JC_DRO2", "CEN_03,SUR2", "CEN_05,DRO2");
        createPlanAisle(building, nodes, "CEN_05", 5.0, "LINEAR", "STRAIGHT", "JC_DRO2", "JC_SUR3", "CEN_04,DRO2", "CEN_06,SUR3");
        createPlanAisle(building, nodes, "CEN_06", 7.0, "LINEAR", "STRAIGHT", "JC_SUR3", "JC_DRO3", "CEN_05,SUR3", "CEN_07,DRO3");
        createPlanAisle(building, nodes, "CEN_07", 2.0, "LINEAR", "STRAIGHT", "JC_DRO3", "JC_SUR4", "CEN_06,DRO3", "CEN_08,SUR4");
        createPlanAisle(building, nodes, "CEN_08", 8.0, "LINEAR", "STRAIGHT", "JC_SUR4", "JC_EPI1", "CEN_07,SUR4", "CEN_09,EPI1_W,EPI1_E");
        createPlanAisle(building, nodes, "CEN_09", 14.0, "LINEAR", "STRAIGHT", "JC_EPI1", "JC_EPI2", "CEN_08,EPI1_W,EPI1_E", "CEN_10,EPI2_W,EPI2_E");
        createPlanAisle(building, nodes, "CEN_10", 12.0, "LINEAR", "STRAIGHT", "JC_EPI2", "JC_EPI3", "CEN_09,EPI2_W,EPI2_E", "CEN_11,EPI3_W,EPI3_E");
        createPlanAisle(building, nodes, "CEN_11", 12.0, "LINEAR", "STRAIGHT", "JC_EPI3", "JC_EPI4", "CEN_10,EPI3_W,EPI3_E", "CEN_12,EPI4_W,EPI4_E");
        createPlanAisle(building, nodes, "CEN_12", 12.0, "LINEAR", "STRAIGHT", "JC_EPI4", "JC_EPI5", "CEN_11,EPI4_W,EPI4_E", "CEN_13,EPI5_W,EPI5_E");
        createPlanAisle(building, nodes, "CEN_13", 10.0, "LINEAR", "STRAIGHT", "JC_EPI5", "JC_FRA", "CEN_12,EPI5_W,EPI5_E", "FRA_W,FRA_E");
        // Cross aisles (92 m each). The travel direction is chosen so that the
        // shelf side (+1) faces the fixture (gondola / fridge / freezer) of the
        // one-sided aisles; two-sided aisles alternate sides anyway.
        // At the dead-end extremity, each cross aisle is also connected to the
        // perimeter rocade (ROC_*) so pickers can chain aisles without coming
        // back to the central spine.
        createPlanAisle(building, nodes, "EPI1_W", 92.0, "LINEAR", "STRAIGHT", "JC_EPI1", "END_EPI1_W", "CEN_08,CEN_09,EPI1_E", "ROC_W04,ROC_W05");
        createPlanAisle(building, nodes, "EPI1_E", 92.0, "LINEAR", "STRAIGHT", "END_EPI1_E", "JC_EPI1", "ROC_E03,ROC_E04", "CEN_08,CEN_09,EPI1_W");
        createPlanAisle(building, nodes, "EPI2_W", 92.0, "LINEAR", "STRAIGHT", "END_EPI2_W", "JC_EPI2", "ROC_W05,ROC_W06", "CEN_09,CEN_10,EPI2_E");
        createPlanAisle(building, nodes, "EPI2_E", 92.0, "LINEAR", "STRAIGHT", "JC_EPI2", "END_EPI2_E", "CEN_09,CEN_10,EPI2_W", "ROC_E04,ROC_E05");
        createPlanAisle(building, nodes, "EPI3_W", 92.0, "LINEAR", "STRAIGHT", "END_EPI3_W", "JC_EPI3", "ROC_W06,ROC_W07", "CEN_10,CEN_11,EPI3_E");
        createPlanAisle(building, nodes, "EPI3_E", 92.0, "LINEAR", "STRAIGHT", "JC_EPI3", "END_EPI3_E", "CEN_10,CEN_11,EPI3_W", "ROC_E05,ROC_E06");
        createPlanAisle(building, nodes, "EPI4_W", 92.0, "LINEAR", "STRAIGHT", "END_EPI4_W", "JC_EPI4", "ROC_W07,ROC_W08", "CEN_11,CEN_12,EPI4_E");
        createPlanAisle(building, nodes, "EPI4_E", 92.0, "LINEAR", "STRAIGHT", "JC_EPI4", "END_EPI4_E", "CEN_11,CEN_12,EPI4_W", "ROC_E06,ROC_E07");
        createPlanAisle(building, nodes, "EPI5_W", 92.0, "LINEAR", "STRAIGHT", "END_EPI5_W", "JC_EPI5", "ROC_W08,ROC_W09", "CEN_12,CEN_13,EPI5_E");
        createPlanAisle(building, nodes, "EPI5_E", 92.0, "LINEAR", "STRAIGHT", "JC_EPI5", "END_EPI5_E", "CEN_12,CEN_13,EPI5_W", "ROC_E07,ROC_E08");
        createPlanAisle(building, nodes, "FRA_W", 92.0, "LINEAR", "STRAIGHT", "JC_FRA", "END_FRA_W", "CEN_13,FRA_E", "ROC_W09");
        createPlanAisle(building, nodes, "FRA_E", 92.0, "LINEAR", "STRAIGHT", "END_FRA_E", "JC_FRA", "ROC_E08", "CEN_13,FRA_W");
        createPlanAisle(building, nodes, "SUR1", 92.0, "LINEAR", "STRAIGHT", "END_SUR1", "JC_SUR1", "ROC_W01", "CEN_01,CEN_02");
        createPlanAisle(building, nodes, "SUR2", 92.0, "LINEAR", "STRAIGHT", "END_SUR2", "JC_SUR2", "ROC_W01,ROC_W02", "CEN_03,CEN_04");
        createPlanAisle(building, nodes, "SUR3", 92.0, "LINEAR", "STRAIGHT", "END_SUR3", "JC_SUR3", "ROC_W02,ROC_W03", "CEN_05,CEN_06");
        createPlanAisle(building, nodes, "SUR4", 92.0, "LINEAR", "STRAIGHT", "END_SUR4", "JC_SUR4", "ROC_W03,ROC_W04", "CEN_07,CEN_08");
        createPlanAisle(building, nodes, "DRO1", 92.0, "LINEAR", "STRAIGHT", "END_DRO1", "JC_DRO1", "ROC_E01", "CEN_02,CEN_03");
        createPlanAisle(building, nodes, "DRO2", 92.0, "LINEAR", "STRAIGHT", "JC_DRO2", "END_DRO2", "CEN_04,CEN_05", "ROC_E01,ROC_E02");
        createPlanAisle(building, nodes, "DRO3", 92.0, "LINEAR", "STRAIGHT", "JC_DRO3", "END_DRO3", "CEN_06,CEN_07", "ROC_E02,ROC_E03");
        // Perimeter rocade (ROC_*): connects the extremities of the cross
        // aisles along the west and east walls, so a picker can walk from one
        // aisle end to the next without returning to the central spine.
        createPlanAisle(building, nodes, "ROC_W01", 9.0, "LINEAR", "STRAIGHT", "END_SUR1", "END_SUR2", "SUR1", "SUR2,ROC_W02");
        createPlanAisle(building, nodes, "ROC_W02", 10.0, "LINEAR", "STRAIGHT", "END_SUR2", "END_SUR3", "SUR2,ROC_W01", "SUR3,ROC_W03");
        createPlanAisle(building, nodes, "ROC_W03", 9.0, "LINEAR", "STRAIGHT", "END_SUR3", "END_SUR4", "SUR3,ROC_W02", "SUR4,ROC_W04");
        createPlanAisle(building, nodes, "ROC_W04", 8.0, "LINEAR", "STRAIGHT", "END_SUR4", "END_EPI1_W", "SUR4,ROC_W03", "EPI1_W,ROC_W05");
        createPlanAisle(building, nodes, "ROC_W05", 14.0, "LINEAR", "STRAIGHT", "END_EPI1_W", "END_EPI2_W", "EPI1_W,ROC_W04", "EPI2_W,ROC_W06");
        createPlanAisle(building, nodes, "ROC_W06", 12.0, "LINEAR", "STRAIGHT", "END_EPI2_W", "END_EPI3_W", "EPI2_W,ROC_W05", "EPI3_W,ROC_W07");
        createPlanAisle(building, nodes, "ROC_W07", 12.0, "LINEAR", "STRAIGHT", "END_EPI3_W", "END_EPI4_W", "EPI3_W,ROC_W06", "EPI4_W,ROC_W08");
        createPlanAisle(building, nodes, "ROC_W08", 12.0, "LINEAR", "STRAIGHT", "END_EPI4_W", "END_EPI5_W", "EPI4_W,ROC_W07", "EPI5_W,ROC_W09");
        createPlanAisle(building, nodes, "ROC_W09", 10.0, "LINEAR", "STRAIGHT", "END_EPI5_W", "END_FRA_W", "EPI5_W,ROC_W08", "FRA_W");
        createPlanAisle(building, nodes, "ROC_E01", 12.0, "LINEAR", "STRAIGHT", "END_DRO1", "END_DRO2", "DRO1", "DRO2,ROC_E02");
        createPlanAisle(building, nodes, "ROC_E02", 12.0, "LINEAR", "STRAIGHT", "END_DRO2", "END_DRO3", "DRO2,ROC_E01", "DRO3,ROC_E03");
        createPlanAisle(building, nodes, "ROC_E03", 10.0, "LINEAR", "STRAIGHT", "END_DRO3", "END_EPI1_E", "DRO3,ROC_E02", "EPI1_E,ROC_E04");
        createPlanAisle(building, nodes, "ROC_E04", 14.0, "LINEAR", "STRAIGHT", "END_EPI1_E", "END_EPI2_E", "EPI1_E,ROC_E03", "EPI2_E,ROC_E05");
        createPlanAisle(building, nodes, "ROC_E05", 12.0, "LINEAR", "STRAIGHT", "END_EPI2_E", "END_EPI3_E", "EPI2_E,ROC_E04", "EPI3_E,ROC_E06");
        createPlanAisle(building, nodes, "ROC_E06", 12.0, "LINEAR", "STRAIGHT", "END_EPI3_E", "END_EPI4_E", "EPI3_E,ROC_E05", "EPI4_E,ROC_E07");
        createPlanAisle(building, nodes, "ROC_E07", 12.0, "LINEAR", "STRAIGHT", "END_EPI4_E", "END_EPI5_E", "EPI4_E,ROC_E06", "EPI5_E,ROC_E08");
        createPlanAisle(building, nodes, "ROC_E08", 10.0, "LINEAR", "STRAIGHT", "END_EPI5_E", "END_FRA_E", "EPI5_E,ROC_E07", "FRA_E");
        LOGGER.info("Demo Aisles created: CEN_01..CEN_13, EPI1..EPI5 (W/E), FRA (W/E), SUR1..SUR4, DRO1..DRO3, ROC_W01..W09, ROC_E01..E08");
    }

    /**
     * Creates and persists a single aisle with its geometric layer.
     *
     * @param building        the parent building.
     * @param nodes           the plan nodes, keyed by code.
     * @param code            the aisle code.
     * @param length          the aisle length in meters.
     * @param flowType        the flow type (LINEAR, DIAGONAL, CIRCULAR), also used as aisle type.
     * @param shapeType       the shape type (STRAIGHT, CURVED).
     * @param headNodeCode    the code of the head node (nullable).
     * @param tailNodeCode    the code of the tail node (nullable).
     * @param connectedToHead the code connected to the head extremity (logical graph, nullable).
     * @param connectedToTail the code connected to the tail extremity (logical graph, nullable).
     * @param waypoints       the intermediate points of the aisle ({@code {x, y}} pairs).
     */
    private void createPlanAisle(Building building, Map<String, PlanNode> nodes, String code, double length,
                                 String flowType, String shapeType, String headNodeCode, String tailNodeCode,
                                 String connectedToHead, String connectedToTail, double[]... waypoints) {
        Aisle aisle = new Aisle();
        aisle.code = code;
        aisle.lengthMeters = length;
        aisle.aisleType = flowType;
        aisle.flowType = flowType;
        aisle.shapeType = shapeType;
        aisle.headNode = headNodeCode != null ? nodes.get(headNodeCode) : null;
        aisle.tailNode = tailNodeCode != null ? nodes.get(tailNodeCode) : null;
        aisle.connectedToHead = connectedToHead;
        aisle.connectedToTail = connectedToTail;
        aisle.building = building;
        for (int i = 0; i < waypoints.length; i++) {
            AisleWaypoint waypoint = new AisleWaypoint();
            waypoint.aisle = aisle;
            waypoint.position = i;
            waypoint.x = waypoints[i][0];
            waypoint.y = waypoints[i][1];
            aisle.waypoints.add(waypoint);
        }
        aisle.persistAndFlush();
    }

    /**
     * Creates the demo plan zones (polygonal areas of the building).
     *
     * @param building the demo building.
     */
    private void createPlanZones(Building building) {
        // The zones match the departments drawn on the plan image: the fridges
        // along the back wall, the grocery gondolas on both sides of the central
        // aisle, the freezers in the back-left corner and the household goods in
        // the back-right corner.
        createPlanZone(building, "FRESH_AREA", "Rayon Frais", "#0891b2", TemperatureZone.CHILLED,
                new double[][]{{-800, -448}, {800, -448}, {800, -384}, {-800, -384}});
        createPlanZone(building, "FROZEN_AREA", "Surgelés", "#4f46e5", TemperatureZone.FROZEN,
                new double[][]{{-800, 160}, {-96, 160}, {-96, 416}, {-800, 416}});
        createPlanZone(building, "EPICERIE_WEST", "Épicerie", "#d97706", TemperatureZone.AMBIENT,
                new double[][]{{-800, -352}, {-96, -352}, {-96, 64}, {-800, 64}});
        createPlanZone(building, "EPICERIE_EAST", "Épicerie", "#d97706", TemperatureZone.AMBIENT,
                new double[][]{{96, -352}, {800, -352}, {800, 64}, {96, 64}});
        createPlanZone(building, "DROGUERIE_AREA", "Droguerie", "#65a30d", TemperatureZone.HOUSEHOLD,
                new double[][]{{96, 160}, {800, 160}, {800, 416}, {96, 416}});
        LOGGER.info("Demo PlanZones created: FRESH_AREA, FROZEN_AREA, EPICERIE_WEST, EPICERIE_EAST, DROGUERIE_AREA");
    }

    /**
     * Creates and persists a single plan zone with its polygon.
     *
     * @param building        the parent building.
     * @param code            the unique zone code.
     * @param label           the display label.
     * @param color           the display color (hex format).
     * @param temperatureZone the associated temperature zone (nullable).
     * @param polygon         the ordered polygon vertices ({@code {x, y}} pairs, minimum 3).
     */
    private void createPlanZone(Building building, String code, String label, String color,
                                TemperatureZone temperatureZone, double[][] polygon) {
        PlanZone zone = new PlanZone();
        zone.code = code;
        zone.label = label;
        zone.color = color;
        zone.temperatureZone = temperatureZone;
        zone.labelOffsetX = 0.0;
        zone.labelOffsetY = 0.0;
        zone.building = building;
        for (int i = 0; i < polygon.length; i++) {
            PlanZoneVertex vertex = new PlanZoneVertex();
            vertex.zone = zone;
            vertex.position = i;
            vertex.x = polygon[i][0];
            vertex.y = polygon[i][1];
            zone.vertices.add(vertex);
        }
        zone.persistAndFlush();
    }

    /**
     * Creates the demo products.
     *
     * @return the list of persisted demo products.
     */
    private List<Product> createProducts() {
        List<Product> products = new ArrayList<>();
        for (ProductSpec spec : DEMO_PRODUCTS) {
            products.add(createProduct(spec.ean(), spec.name(), spec.brand(), spec.type(),
                    spec.zone(), spec.unit(), spec.weight(), spec.volume()));
        }
        LOGGER.infof("Demo Products created: %d", products.size());
        return products;
    }

    /**
     * Creates and persists a single product.
     *
     * @param ean     the unique EAN code.
     * @param name    the product name.
     * @param brand   the product brand.
     * @param type    the product type (UNIT, WEIGHT, VOLUME).
     * @param zone    the required temperature zone.
     * @param unit    the display unit.
     * @param weight  the reference weight in kg (nullable).
     * @param volume  the reference volume in L (nullable).
     * @return the persisted product.
     */
    private Product createProduct(String ean, String name, String brand, ProductType type,
                                  TemperatureZone zone, String unit, BigDecimal weight, BigDecimal volume) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.brand = brand;
        product.productType = type;
        product.temperatureZone = zone;
        product.unitName = unit;
        product.referenceWeight = weight;
        product.referenceVolume = volume;
        product.persistAndFlush();
        return product;
    }

    /**
     * Number of bays (units) carrying shelves on a single aisle of the store plan.
     *
     * @param aisle the aisle code.
     * @param units the number of bays on this aisle.
     */
    private record AisleSlot(String aisle, int units) {
    }

    /**
     * Placement of a department on the store plan: the aisles that carry its
     * shelves, the bay (unit) prefix and the number of bays per aisle.
     *
     * @param area       the location area (department code).
     * @param department the department display name.
     * @param slots      the aisles carrying the shelves, with their bay counts.
     * @param unitPrefix the bay (unit) prefix.
     */
    private record ShelfPlacement(String area, String department, List<AisleSlot> slots,
                                  String unitPrefix) {
    }

    /**
     * Creates the demo shelf locations inside the given building: every product
     * gets exactly one shelf slot (one bay, position 1), placed by department on
     * the aisles of the store plan.
     * <p>
     * The placement is coherent with the store plan and the demo collecting orders:
     * <ul>
     *   <li>EPICERIE (ambient) on the grocery cross aisles EPI2..EPI5 (bays B1-B3)
     *       — zone collecting EPICERIE/EPI2_W.</li>
     *   <li>FRAIS (chilled) on the fridge aisles FRA_W/FRA_E (bays C1-C10) —
     *       zone collecting FRAIS/FRA_W, the aisles run along the FRESH_AREA zone.</li>
     *   <li>SURGELES (frozen) on the two-sided freezer aisles SUR2/SUR3 (bays S1-S4)
     *       — inside the FROZEN_AREA zone.</li>
     *   <li>DROGUERIE (household) on DRO1/DRO2/DRO3 (bays D1-D8) — inside the
     *       DROGUERIE_AREA zone.</li>
     * </ul>
     * Only two-sided aisles carry shelves, except the one-sided aisles DRO1 and
     * DRO3 which carry a single bay directed towards their fixture.
     *
     * @param building the demo building.
     * @param products the demo products.
     */
    private void createShelfLocations(Building building, List<Product> products) {
        List<ShelfPlacement> placements = List.of(
                new ShelfPlacement("EPICERIE", "Epicerie", List.of(
                        new AisleSlot("EPI2_W", 2), new AisleSlot("EPI2_E", 2),
                        new AisleSlot("EPI3_W", 2), new AisleSlot("EPI3_E", 2),
                        new AisleSlot("EPI4_W", 3), new AisleSlot("EPI4_E", 3),
                        new AisleSlot("EPI5_W", 3), new AisleSlot("EPI5_E", 3)), "B"),
                new ShelfPlacement("FRAIS", "Frais", List.of(
                        new AisleSlot("FRA_W", 10), new AisleSlot("FRA_E", 10)), "C"),
                new ShelfPlacement("SURGELES", "Surgelés", List.of(
                        new AisleSlot("SUR2", 4), new AisleSlot("SUR3", 4)), "S"),
                new ShelfPlacement("DROGUERIE", "Droguerie", List.of(
                        new AisleSlot("DRO1", 1), new AisleSlot("DRO2", 8), new AisleSlot("DRO3", 1)), "D")
        );
        int created = 0;
        for (ShelfPlacement placement : placements) {
            List<Product> departmentProducts = products.stream()
                    .filter(p -> areaOf(p).equals(placement.area()))
                    .toList();
            int expected = placement.slots().stream().mapToInt(AisleSlot::units).sum();
            if (departmentProducts.size() != expected) {
                throw new IllegalStateException("Shelf placement mismatch for " + placement.area()
                        + ": " + departmentProducts.size() + " products for " + expected + " bays");
            }
            int i = 0;
            for (AisleSlot slot : placement.slots()) {
                // Fraction de chaque baie le long de l'allée : les baies sont
                // réparties par paires face à face (2 par station), comme dans
                // l'éditeur de plan magasin (computeShelfPlacements).
                int stations = (slot.units() + 1) / 2;
                for (int u = 0; u < slot.units(); u++) {
                    Product product = departmentProducts.get(i);
                    String unit = placement.unitPrefix() + (u + 1);
                    String elevation = String.valueOf(1 + (i % 4));
                    int facings = 2 + (i % 4);
                    double aislePosition = (u / 2 + 0.5) / stations;
                    createShelfLocation(building, product, placement.area(), slot.aisle(), unit,
                            elevation, "1", facings, placement.department(), aislePosition);
                    created++;
                    i++;
                }
            }
        }
        building.persistAndFlush();
        LOGGER.infof("Demo ShelfLocations created: %d", created);
    }

    /**
     * Maps a product temperature zone to its location area (department).
     *
     * @param product the product.
     * @return the area code of the department storing this product.
     */
    private String areaOf(Product product) {
        return switch (product.temperatureZone) {
            case CHILLED -> "FRAIS";
            case FROZEN -> "SURGELES";
            case HOUSEHOLD -> "DROGUERIE";
            default -> "EPICERIE";
        };
    }

    /**
     * Creates a single shelf location and links it to the given building.
     *
     * @param building   the parent building.
     * @param product    the product stored at this location.
     * @param area       the area (department).
     * @param aisle      the aisle code.
     * @param unit       the structural unit (bay/rack).
     * @param elevation  the vertical level.
     * @param position   the horizontal position (slot/bin).
     * @param facings       the number of facings.
     * @param department    the department name.
     * @param aislePosition the position of the bay along the aisle (fraction 0..1).
     */
    private void createShelfLocation(Building building, Product product, String area, String aisle,
                                     String unit, String elevation, String position, int facings, String department,
                                     double aislePosition) {
        StoreShelfLocation location = new StoreShelfLocation();
        location.product = product;
        location.area = area;
        location.aisle = aisle;
        location.unit = unit;
        location.elevation = elevation;
        location.position = position;
        location.facings = facings;
        location.department = department;
        location.aislePosition = aislePosition;
        building.locations.add(location);
    }

    /**
     * Creates the demo buffer configuration for the given building.
     *
     * @param building the demo building.
     * @return the persisted demo buffer.
     */
    private Buffer createBuffer(Building building) {
        Buffer buffer = new Buffer();
        buffer.building = building;
        buffer.purpose = Buffer.BufferPurpose.STAGING;
        buffer.bufferCode = "LYO-042-BUF-01";
        buffer.locationDescription = "Quai Drive";
        buffer.active = true;
        buffer.ambientCapacity = 20;
        buffer.chilledCapacity = 10;
        buffer.frozenCapacity = 5;
        buffer.maxWeightKg = 500.0;
        buffer.persistAndFlush();
        LOGGER.infof("Demo Buffer created: %s", buffer.bufferCode);
        return buffer;
    }

    // --------------------------------------------------
    // Employees & Totes
    // --------------------------------------------------

    /**
     * Creates the demo employees attached to the given store.
     * <p>
     * Default PIN for all demo employees: {@code 1234}.
     *
     * @param store the demo store.
     * @return the list of persisted demo employees.
     */
    private List<Employee> createEmployees(Store store) {
        List<Employee> employees = new ArrayList<>();
        employees.add(createEmployee(store, "100234", "Jean", "Dupont", "jean.dupont@intermarche.fr", Employee.EmployeeRole.MANAGER));
        employees.add(createEmployee(store, "100235", "Marie", "Martin", "marie.martin@intermarche.fr", Employee.EmployeeRole.PICKER));
        employees.add(createEmployee(store, "100236", "Pierre", "Bernard", "pierre.bernard@intermarche.fr", Employee.EmployeeRole.CASHIER));
        LOGGER.info("Demo Employees created: 100234, 100235, 100236");
        return employees;
    }

    /**
     * Creates and persists a single employee.
     *
     * @param store     the store the employee belongs to.
     * @param badgeId   the unique badge ID (login credential).
     * @param firstName the first name.
     * @param lastName  the last name.
     * @param email     the unique email address.
     * @param role      the employee role.
     * @return the persisted employee.
     */
    private Employee createEmployee(Store store, String badgeId, String firstName, String lastName,
                                String email, Employee.EmployeeRole role) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        employee.firstName = firstName;
        employee.lastName = lastName;
        employee.email = email;
        employee.role = role;
        employee.store = store;
        employee.password = BCrypt.hashpw("1234", BCrypt.gensalt());
        employee.active = true;
        employee.persistAndFlush();
        return employee;
    }

    /**
     * Creates the demo totes attached to the given store.
     *
     * @param store the demo store.
     */
    private void createTotes(Store store) {
        createTote(store, "TOTE-1042-01", "45.000", Tote.ToteType.STANDARD);
        createTote(store, "TOTE-1042-02", "45.000", Tote.ToteType.STANDARD);
        createTote(store, "TOTE-1042-03", "60.000", Tote.ToteType.INSULATED);
        for (int i=4; i<=12; i++) {
            createTote(store, String.format("TOTE-1042-%02d", i), "60.000",
                    i<=7 ? Tote.ToteType.STANDARD : Tote.ToteType.INSULATED);
        }
        for (int i=13; i<=24; i++) {
            createTote(store, String.format("TOTE-1042-%02d", i), "60.000",
                    i<=18 ? Tote.ToteType.STANDARD : Tote.ToteType.INSULATED);
        }
        LOGGER.info("Demo totes ensured: TOTE-1042-01..24 (12 STANDARD, 12 INSULATED)");
    }

    /**
     * Creates and persists a single tote.
     *
     * @param store  the store owning the tote.
     * @param code   the unique tote code.
     * @param volume the tote volume in liters.
     * @param type   the tote type (STANDARD or INSULATED).
     */
    private void createTote(Store store, String code, String volume, Tote.ToteType type) {
        if (Tote.count("code", code)>0) return;
        Tote tote = new Tote();
        tote.code = code;
        tote.volume = new BigDecimal(volume);
        tote.toteType = type;
        tote.store = store;
        tote.persistAndFlush();
    }

    // --------------------------------------------------
    // Product Families
    // --------------------------------------------------

    /**
     * Creates the demo product families and links them to the given products.
     *
     * @param products all demo products.
     */
    private void createProductFamilies(List<Product> products) {
        createProductFamily("EPICERIE", "Produits d'épicerie", "SEC", products, TemperatureZone.AMBIENT);
        createProductFamily("FRAIS", "Produits frais", "FRAIS,REFRIGERE", products, TemperatureZone.CHILLED);
        createProductFamily("SURGELES", "Produits surgelés", "SURGELE", products, TemperatureZone.FROZEN);
        createProductFamily("DROGUERIE", "Produits d'entretien", "HYGIENE", products, TemperatureZone.HOUSEHOLD);
        LOGGER.info("Demo ProductFamilies created: EPICERIE, FRAIS, SURGELES, DROGUERIE");
    }

    /**
     * Creates a product family and links every product of the given temperature zone.
     *
     * @param code        the family code.
     * @param description the family description.
     * @param flags       the family flags.
     * @param products    all demo products.
     * @param zone        the temperature zone of the products to link.
     */
    private void createProductFamily(String code, String description, String flags,
                                     List<Product> products, TemperatureZone zone) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.description = description;
        family.flags = flags;
        for (Product product : products) {
            if (product.temperatureZone == zone) {
                family.products.add(product);
            }
        }
        family.persistAndFlush();
    }

    // --------------------------------------------------
    // Customer & Orders
    // --------------------------------------------------

    /**
     * Creates the demo customer.
     *
     * @return the persisted demo customer.
     */
    private List<Customer> createCustomers() {
        List<Customer> customers = new ArrayList<>();
        for (CustomerSpec spec : DEMO_CUSTOMERS) {
            Customer customer = new Customer();
            customer.customerId = spec.customerId();
            customer.firstName = spec.firstName();
            customer.lastName = spec.lastName();
            customer.email = spec.email();
            customer.phone = spec.phone();
            customer.loyaltyCardId = spec.loyaltyCardId();
            customer.address = createAdresse(spec.street(), spec.postalCode(), spec.city());
            customer.persistAndFlush();
            customers.add(customer);
        }
        LOGGER.infof("Demo Customers created: %d", customers.size());
        return customers;
    }

    /**
     * Creates the demo orders: 12 realistic orders (2 to 12 lines each, 63 lines
     * in total) spread over 5 customers, with varied channels, statuses, delivery
     * slots and options. Some orders are backdated (yesterday / day before) so the
     * date filters of the frontend (today / yesterday / week) have data to show.
     *
     * @param store     the demo store.
     * @param customers the demo customers.
     * @param products  the demo products.
     * @param now       the reference timestamp (today).
     * @return the created orders with their delivery slot and options.
     */
    private List<DemoOrder> createOrders(Store store, List<Customer> customers, List<Product> products, LocalDateTime now) {
        Map<String, Product> byEan = products.stream()
                .collect(Collectors.toMap(p -> p.ean, p -> p));
        List<DemoOrder> orders = new ArrayList<>();

        orders.add(createOrder(store, customers.get(0), "CMD-2026-0451", "ECOMMERCE_APP", "VALIDATED",
                now, now.plusHours(2), List.of(),
                line(byEan, "3017620422003", "2.000"), // Nutella x2
                line(byEan, "3560070057003", "1.000"))); // Pâtes x1

        orders.add(createOrder(store, customers.get(1), "CMD-2026-0452", "DRIVE", "IN_PROGRESS",
                now, now.plusHours(3), List.of(),
                line(byEan, "3274080005003", "3.000"), // Lait x3
                line(byEan, "3245412630003", "1.500"), // Pommes x1.5
                line(byEan, "3274080098760", "1.000"))); // Beurre x1

        orders.add(createOrder(store, customers.get(2), "CMD-2026-0453", "ECOMMERCE_APP", "SHIPPED",
                now, now.plusHours(1), List.of(),
                line(byEan, "8712100325003", "1.000"))); // Lessive x1

        orders.add(createOrder(store, customers.get(3), "CMD-2026-0454", "DRIVE", "VALIDATED",
                now, now.plusHours(4), List.of("OPT_EXP"),
                line(byEan, "3274080012346", "2.000"), // Riz x2
                line(byEan, "3017650987657", "1.000"), // Huile x1
                line(byEan, "3012345678902", "2.000"), // Sucre x2
                line(byEan, "3012345098762", "1.000"), // Farine x1
                line(byEan, "3017640123454", "1.000"), // Café x1
                line(byEan, "3012345556668", "2.000"))); // Pain x2

        orders.add(createOrder(store, customers.get(4), "CMD-2026-0455", "ECOMMERCE_APP", "IN_PROGRESS",
                now, nextDay(now, 10), List.of("OPT_GIFT"),
                line(byEan, "3274080087658", "2.000"), // Yaourts x2
                line(byEan, "3274080098760", "1.000"), // Beurre x1
                line(byEan, "3274080010984", "1.000"), // Œufs x1
                line(byEan, "3245412631009", "1.000"), // Tomates x1
                line(byEan, "3245412632006", "1.500"), // Bananes x1.5
                line(byEan, "3274080021096", "1.000"), // Saumon x1
                line(byEan, "3274080076546", "2.000"), // Jus x2
                line(byEan, "3274080005003", "2.000"))); // Lait x2

        orders.add(createOrder(store, customers.get(0), "CMD-2026-0456", "DRIVE", "VALIDATED",
                now.minusDays(1), now.minusDays(1).plusHours(5), List.of(),
                line(byEan, "3017620422003", "1.000"), // Nutella x1
                line(byEan, "3560070057003", "2.000"), // Pâtes x2
                line(byEan, "3274080012346", "1.000"), // Riz x1
                line(byEan, "3017650987657", "1.000"))); // Huile x1

        orders.add(createOrder(store, customers.get(1), "CMD-2026-0457", "ECOMMERCE_APP", "VALIDATED",
                now, nextDay(now, 14), List.of("OPT_EXP", "OPT_COOL"),
                line(byEan, "3274080005003", "4.000"), // Lait x4
                line(byEan, "3274080087658", "1.000"), // Yaourts x1
                line(byEan, "3274080098760", "2.000"), // Beurre x2
                line(byEan, "3274080010984", "2.000"), // Œufs x2
                line(byEan, "3245412631009", "2.000"), // Tomates x2
                line(byEan, "3245412632006", "2.000"), // Bananes x2
                line(byEan, "3274080021096", "2.000"), // Saumon x2
                line(byEan, "3274080076546", "1.000"), // Jus x1
                line(byEan, "3245412630003", "2.000"), // Pommes x2
                line(byEan, "3017640123454", "1.000"))); // Café x1

        orders.add(createOrder(store, customers.get(2), "CMD-2026-0458", "DRIVE", "SHIPPED",
                now, now.plusHours(2), List.of(),
                line(byEan, "8712100326004", "1.000"), // Shampoing x1
                line(byEan, "8712100327001", "2.000"), // Papier toilette x2
                line(byEan, "8712100325003", "1.000"))); // Lessive x1

        orders.add(createOrder(store, customers.get(3), "CMD-2026-0459", "ECOMMERCE_APP", "IN_PROGRESS",
                now.minusDays(2), now.minusDays(2).plusHours(6), List.of(),
                line(byEan, "3012345556668", "1.000"), // Pain x1
                line(byEan, "3274080098760", "1.000"), // Beurre x1
                line(byEan, "3274080005003", "2.000"), // Lait x2
                line(byEan, "3274080087658", "1.000"), // Yaourts x1
                line(byEan, "3017640123454", "1.000"))); // Café x1

        orders.add(createOrder(store, customers.get(4), "CMD-2026-0460", "DRIVE", "VALIDATED",
                now, nextDay(now, 9), List.of(),
                line(byEan, "3274080012346", "1.000"), // Riz x1
                line(byEan, "3012345678902", "1.000"), // Sucre x1
                line(byEan, "3012345098762", "2.000"), // Farine x2
                line(byEan, "3017650987657", "1.000"), // Huile x1
                line(byEan, "3560070057003", "2.000"), // Pâtes x2
                line(byEan, "3017620422003", "1.000"), // Nutella x1
                line(byEan, "3274080076546", "1.000"))); // Jus x1

        orders.add(createOrder(store, customers.get(0), "CMD-2026-0461", "ECOMMERCE_APP", "SHIPPED",
                now.minusDays(1), now.minusDays(1).plusHours(1), List.of(),
                line(byEan, "3274080010984", "1.000"), // Œufs x1
                line(byEan, "3245412631009", "1.000"))); // Tomates x1

        orders.add(createOrder(store, customers.get(1), "CMD-2026-0462", "DRIVE", "IN_PROGRESS",
                now, nextDay(now, 11), List.of("OPT_GIFT"),
                line(byEan, "3274080005003", "2.000"), // Lait x2
                line(byEan, "3274080087658", "2.000"), // Yaourts x2
                line(byEan, "3274080098760", "1.000"), // Beurre x1
                line(byEan, "3274080010984", "1.000"), // Œufs x1
                line(byEan, "3245412631009", "1.000"), // Tomates x1
                line(byEan, "3245412632006", "1.000"), // Bananes x1
                line(byEan, "3274080021096", "1.000"), // Saumon x1
                line(byEan, "3274080076546", "2.000"), // Jus x2
                line(byEan, "3245412630003", "1.000"), // Pommes x1
                line(byEan, "3274080012346", "1.000"), // Riz x1
                line(byEan, "3017650987657", "1.000"), // Huile x1
                line(byEan, "3017640123454", "1.000"))); // Café x1

        LOGGER.infof("Demo Orders created: %d", orders.size());
        return orders;
    }

    /**
     * Builds an order line spec for the product identified by its EAN.
     *
     * @param byEan    the demo products indexed by EAN.
     * @param ean      the product EAN.
     * @param quantity the requested quantity.
     * @return the order line spec.
     */
    private OrderLineSpec line(Map<String, Product> byEan, String ean, String quantity) {
        Product product = byEan.get(ean);
        if (product == null) {
            throw new IllegalStateException("Demo product not found for EAN: " + ean);
        }
        return new OrderLineSpec(product, quantity);
    }

    /**
     * Returns the timestamp of the day after the given one, at the given hour.
     *
     * @param now  the reference timestamp.
     * @param hour the hour of the day (0-23).
     * @return the timestamp of the next day at the given hour.
     */
    private LocalDateTime nextDay(LocalDateTime now, int hour) {
        return now.plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    /**
     * Creates and persists a single order with its lines.
     * <p>
     * The business date ({@code orderDate}) is set to the reception timestamp, and the
     * audit fields ({@code createdAt}, {@code updatedAt}) are backdated to the same
     * timestamp: {@code createdAt} is {@code updatable=false}, so it can only be set at
     * persist time, via a temporary override of the {@link DateTimeProvider}.
     *
     * @param store        the demo store.
     * @param customer     the demo customer.
     * @param orderId      the unique order ID.
     * @param channel      the order channel.
     * @param status       the order status.
     * @param receivedAt   the reception timestamp (order date).
     * @param deliverySlot the delivery slot start.
     * @param options      the option codes of the order.
     * @param lineSpecs    the order lines.
     * @return the created order with its delivery slot and options.
     */
    private DemoOrder createOrder(Store store, Customer customer, String orderId, String channel,
                                  String status, LocalDateTime receivedAt, LocalDateTime deliverySlot,
                                  List<String> options, OrderLineSpec... lineSpecs) {
        Order order = new Order();
        order.orderId = orderId;
        order.orderDate = receivedAt;
        order.channel = channel;
        order.status = status;
        order.store = store;
        order.customer = customer;
        int lineNumber = 1;
        for (OrderLineSpec spec : lineSpecs) {
            OrderLine line = new OrderLine();
            line.lineNumber = lineNumber++;
            line.product = spec.product();
            line.requestedQuantity = new BigDecimal(spec.quantity());
            order.lines.add(line);
        }
        persistAt(order, receivedAt);
        return new DemoOrder(order, deliverySlot, options);
    }

    /**
     * Persists the given entity with its audit fields backdated to the given timestamp.
     * <p>
     * {@code createdAt} is {@code updatable=false}, so the only way to backdate it is to
     * override the {@link DateTimeProvider} around the persist. The previously fixed time
     * (if any) is restored afterwards.
     *
     * @param entity    the entity to persist.
     * @param timestamp the timestamp to use for the audit fields.
     */
    private void persistAt(BaseEntity entity, LocalDateTime timestamp) {
        LocalDateTime previousFixed = DateTimeProvider.getFixedDateTime();
        DateTimeProvider.setFixedDateTime(timestamp);
        try {
            entity.persistAndFlush();
        } finally {
            if (previousFixed != null) {
                DateTimeProvider.setFixedDateTime(previousFixed);
            } else {
                DateTimeProvider.clear();
            }
        }
    }

    /**
     * Internal record describing an order line to create.
     *
     * @param product  the ordered product.
     * @param quantity the requested quantity.
     */
    private record OrderLineSpec(Product product, String quantity) {
    }

    /**
     * Internal record describing a demo product to create.
     *
     * @param ean       the unique EAN-13 code.
     * @param name      the product name.
     * @param brand     the product brand.
     * @param type      the product type (UNIT, WEIGHT, VOLUME).
     * @param zone      the required temperature zone.
     * @param unit      the display unit.
     * @param weight    the reference weight in kg (nullable).
     * @param volume    the reference volume in L (nullable).
     * @param unitPrice the TTC unit price.
     */
    private record ProductSpec(String ean, String name, String brand, ProductType type,
                               TemperatureZone zone, String unit, BigDecimal weight,
                               BigDecimal volume, BigDecimal unitPrice) {
    }

    /**
     * Internal record describing a demo customer to create.
     *
     * @param customerId    the unique customer ID.
     * @param firstName     the first name.
     * @param lastName      the last name.
     * @param email         the email address.
     * @param phone         the phone number.
     * @param loyaltyCardId the loyalty card ID.
     * @param street        the street line.
     * @param postalCode    the postal code.
     * @param city          the city.
     */
    private record CustomerSpec(String customerId, String firstName, String lastName,
                                String email, String phone, String loyaltyCardId,
                                String street, String postalCode, String city) {
    }

    /**
     * Internal record describing a created demo order, kept with the data needed
     * to build its order request (delivery slot and options).
     *
     * @param order        the persisted order.
     * @param deliverySlot the delivery slot start.
     * @param options      the option codes of the order.
     */
    private record DemoOrder(Order order, LocalDateTime deliverySlot, List<String> options) {
    }

    // --------------------------------------------------
    // Order Requests
    // --------------------------------------------------

    /**
     * Creates the demo order requests for the given store, one per demo order.
     * <p>
     * Each request carries a valid JSON specification (same format as the payloads
     * processed by {@code OrderRecorder}) and is marked as DONE: the requests are
     * considered already processed, so the polling consumer never picks them up and
     * they never end up in FAILED status.
     *
     * @param store      the demo store.
     * @param demoOrders the demo orders.
     * @return the list of persisted demo order requests.
     */
    private List<OrderRequest> createOrderRequests(Store store, List<DemoOrder> demoOrders) {
        List<OrderRequest> requests = new ArrayList<>();
        for (DemoOrder demo : demoOrders) {
            requests.add(createOrderRequest(demo, store.code));
        }
        LOGGER.infof("Demo OrderRequests created: %d (status DONE)", requests.size());
        return requests;
    }

    /**
     * Creates and persists a single order request marked as DONE, with a valid
     * JSON specification matching the given order.
     * <p>
     * The audit fields are backdated to the order reception date, so the date filters
     * of the frontend (today / yesterday / week) reflect the order timeline.
     *
     * @param demo      the demo order (order, delivery slot, options).
     * @param storeCode the target store code.
     * @return the persisted order request.
     */
    private OrderRequest createOrderRequest(DemoOrder demo, String storeCode) {
        Order order = demo.order();
        OrderRequest request = new OrderRequest();
        request.orderNumber = order.orderId;
        request.storeCode = storeCode;
        request.specification = buildOrderSpecification(order, storeCode, demo.deliverySlot(), demo.options());
        // Already processed: the polling consumer only picks up PENDING requests,
        // so these requests are never retried nor marked as FAILED.
        request.reservationRecord.status = ReservationRecord.ReservationStatus.DONE;
        persistAt(request, order.orderDate);
        return request;
    }

    /**
     * Builds the JSON specification of an order request, using the same format
     * as the payloads processed by {@code OrderRecorder}.
     *
     * @param order        the source order.
     * @param storeCode    the target store code.
     * @param deliverySlot the delivery slot start.
     * @param options      the option codes of the order.
     * @return the JSON specification.
     */
    private String buildOrderSpecification(Order order, String storeCode, LocalDateTime deliverySlot, List<String> options) {
        ZoneId zone = ZoneId.of("Europe/Paris");
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode header = root.putObject("orderHeader");
        header.put("orderId", order.orderId);
        header.put("channel", order.channel);
        header.put("status", order.status);
        header.put("createdAt", order.orderDate.atZone(zone).format(iso));
        header.putObject("store").put("code", storeCode);

        Customer customer = order.customer;
        ObjectNode customerNode = root.putObject("customer");
        customerNode.put("customerId", customer.customerId);
        customerNode.put("firstName", customer.firstName);
        customerNode.put("lastName", customer.lastName);
        customerNode.put("email", customer.email);
        customerNode.put("phone", customer.phone);
        customerNode.put("loyaltyCardId", customer.loyaltyCardId);
        ObjectNode address = customerNode.putObject("address");
        address.put("streetLine1", customer.address.streetLine1);
        address.put("postalCode", customer.address.postalCode);
        address.put("city", customer.address.city);
        address.put("country", customer.address.country);

        ArrayNode lines = root.putArray("lines");
        BigDecimal totalPrice = BigDecimal.ZERO;
        for (OrderLine line : order.lines) {
            Product product = line.product;
            BigDecimal unitPrice = DEMO_PRICES.getOrDefault(product.ean, BigDecimal.ZERO);
            BigDecimal lineTotal = unitPrice.multiply(line.requestedQuantity);
            totalPrice = totalPrice.add(lineTotal);

            ObjectNode lineNode = lines.addObject();
            lineNode.put("lineNumber", line.lineNumber);
            lineNode.put("requestedQuantity", line.requestedQuantity);

            // Full product details, as expected by the frontend (name, unit, step)
            ObjectNode productNode = lineNode.putObject("product");
            productNode.put("ean", product.ean);
            productNode.put("name", product.name);
            productNode.put("unitName", product.unitName);
            productNode.put("step", "kg".equals(product.unitName) ? 0.1 : 1);

            lineNode.putObject("logistics").put("temperatureZone", product.temperatureZone.name());

            ObjectNode pricing = lineNode.putObject("pricing");
            pricing.put("unitPrice", unitPrice);
            pricing.put("totalPrice", lineTotal);
        }

        ObjectNode fulfillment = root.putObject("fulfillment");
        fulfillment.putObject("slot").put("start", deliverySlot.atZone(zone).format(iso));

        if (!options.isEmpty()) {
            ArrayNode optionsNode = root.putArray("options");
            for (String code : options) {
                ObjectNode optionNode = optionsNode.addObject();
                optionNode.put("name", OPTION_LABELS.getOrDefault(code, code));
                optionNode.put("code", code);
            }
        }

        root.putObject("summary").put("totalPriceInclTax", totalPrice);

        return root.toString();
    }

    // --------------------------------------------------
    // Fulfillments, Lots & Handling Orders
    // --------------------------------------------------

    /**
     * Creates one fulfillment per demo order, already fully processed.
     * <p>
     * Each fulfillment is marked as DONE (the polling consumer only reserves PENDING
     * fulfillments, so the engine never reprocesses them) and carries a single READY
     * chunk whose delivery date is the order delivery slot, with one fulfillment line
     * per order line. The line location is resolved from the building shelf locations,
     * exactly as the fulfillers would do.
     *
     * @param building   the demo building.
     * @param demoOrders the demo orders.
     * @return the list of persisted demo fulfillments.
     */
    private List<Fulfillment> createFulfillments(StoreBuilding building, List<DemoOrder> demoOrders) {
        List<Fulfillment> fulfillments = new ArrayList<>();
        for (DemoOrder demo : demoOrders) {
            fulfillments.add(createFulfillment(demo, building));
        }
        LOGGER.infof("Demo Fulfillments created: %d (status DONE, 1 READY chunk each)", fulfillments.size());
        return fulfillments;
    }

    /**
     * Creates and persists a single fulfillment with its READY chunk and lines.
     * <p>
     * The audit fields are backdated to the order reception date, so the fulfillment
     * timeline matches the order timeline.
     *
     * @param demo     the demo order (order, delivery slot, options).
     * @param building the demo building.
     * @return the persisted fulfillment.
     */
    private Fulfillment createFulfillment(DemoOrder demo, StoreBuilding building) {
        Order order = demo.order();
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.order = order;
        // Already processed: the polling consumer only reserves PENDING fulfillments,
        // so the fulfillment engine never picks them up.
        fulfillment.reservationRecord.status = ReservationRecord.ReservationStatus.DONE;

        FulfillmentChunk chunk = new FulfillmentChunk();
        chunk.type = FulfillmentChunk.READY;
        chunk.label = "Default";
        chunk.deliveryDate = demo.deliverySlot();
        chunk.building = building;
        for (OrderLine orderLine : order.lines) {
            FulfillmentLine line = new FulfillmentLine();
            line.lineNumber = orderLine.lineNumber;
            line.product = orderLine.product;
            line.requestedQuantity = orderLine.requestedQuantity;
            line.location = building.findLocationByProductId(orderLine.product.id);
            chunk.addLine(line);
        }
        fulfillment.addChunk(chunk);
        persistAt(fulfillment, order.orderDate);
        return fulfillment;
    }

    /**
     * Creates one lot per demo fulfillment, with one lot line per fulfillment line.
     * <p>
     * The lot status is a final status derived from the order status (COLLECTED,
     * PACKED or DELIVERED): the order handler builders only consume lots in PENDING
     * or PLANNED_TO_BE_COLLECTED status, so these lots are never picked up and no
     * duplicate handling orders are generated.
     *
     * @param store        the demo store.
     * @param fulfillments the demo fulfillments.
     * @return the list of persisted demo lots.
     */
    private List<Lot> createLots(Store store, List<Fulfillment> fulfillments) {
        List<Lot> lots = new ArrayList<>();
        for (Fulfillment fulfillment : fulfillments) {
            lots.add(createLot(store, fulfillment));
        }
        LOGGER.infof("Demo Lots created: %d", lots.size());
        return lots;
    }

    /**
     * Creates and persists a single lot for the given fulfillment.
     *
     * @param store        the demo store.
     * @param fulfillment  the source fulfillment.
     * @return the persisted lot.
     */
    private Lot createLot(Store store, Fulfillment fulfillment) {
        FulfillmentChunk chunk = fulfillment.chunks.iterator().next();
        Lot lot = new Lot();
        lot.status = lotStatusForOrder(fulfillment.order.status);
        lot.store = store;
        lot.chunk = chunk;
        lot.fulfillment = fulfillment;
        lot.customer = fulfillment.order.customer;
        for (FulfillmentLine line : chunk.lines) {
            LotLine lotLine = new LotLine();
            lotLine.setFulfillmentLine(line);
            lotLine.product = line.product;
            lotLine.quantity = line.requestedQuantity;
            lot.addLine(lotLine);
        }
        persistAt(lot, fulfillment.order.orderDate);
        return lot;
    }

    /**
     * Maps an order status to a final lot status.
     * <p>
     * Only final statuses are used (COLLECTED, PACKED, DELIVERED): the order handler
     * builders only consume lots in PENDING or PLANNED_TO_BE_COLLECTED status, so the
     * demo lots are never picked up and no duplicate handling orders are generated.
     *
     * @param orderStatus the order status.
     * @return the matching final lot status.
     */
    private Lot.LotStatus lotStatusForOrder(String orderStatus) {
        return switch (orderStatus) {
            case "SHIPPED" -> Lot.LotStatus.DELIVERED;
            case "IN_PROGRESS" -> Lot.LotStatus.PACKED;
            default -> Lot.LotStatus.COLLECTED;
        };
    }

    /**
     * Creates the demo handling orders: two of each type (Grouping, Packing,
     * ProductCollecting, ZoneCollecting) with varied statuses (DONE, SCHEDULED,
     * PROCESSING), so the handling order tile has data to display.
     * <p>
     * Statuses are never OPEN: the order scheduler only replans OPEN orders, so the
     * demo orders are never rescheduled. PROCESSING orders have a requested end in
     * the future, so no "late order" event is emitted for them.
     *
     * @param store     the demo store.
     * @param building  the demo building.
     * @param buffer    the demo buffer.
     * @param employees the demo employees (used as assignees).
     * @param lots      the demo lots.
     * @param now       the reference timestamp (today).
     */
    private void createHandlingOrders(Store store, StoreBuilding building, Buffer buffer,
                                      List<Employee> employees, List<Lot> lots, LocalDateTime now) {
        Employee picker = employees.get(1);  // 100235 Marie Martin
        Employee cashier = employees.get(2); // 100236 Pierre Bernard

        // Grouping orders (target buffer LYO-042-BUF-01)
        // The first grouping order shares its lots with the packing order below:
        // the Gantt draws a dependency arrow between orders sharing a lot.
        createGroupingOrder(store, buffer, picker,
                List.of(lotForOrder(lots, "CMD-2026-0457"), lotForOrder(lots, "CMD-2026-0462")),
                BaseHandlingOrder.HandlingOrderStatus.DONE, todayAt(now, 11, 0), todayAt(now, 12, 0));
        createGroupingOrder(store, buffer, cashier,
                List.of(lotForOrder(lots, "CMD-2026-0451"), lotForOrder(lots, "CMD-2026-0456")),
                BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, todayAt(now, 14, 0), todayAt(now, 15, 0));

        // Packing orders (building LYO-042-SHOP)
        // The first packing order shares its lots with the product collecting order
        // below (8:00-9:00) and the grouping order above (11:00-12:00): the Gantt
        // draws arrows Collecting -> Packing -> Grouping.
        createPackingOrder(store, building, picker,
                List.of(lotForOrder(lots, "CMD-2026-0457"), lotForOrder(lots, "CMD-2026-0462")),
                BaseHandlingOrder.HandlingOrderStatus.DONE, todayAt(now, 9, 30), todayAt(now, 10, 30));
        createPackingOrder(store, building, cashier,
                List.of(lotForOrder(lots, "CMD-2026-0460")),
                BaseHandlingOrder.HandlingOrderStatus.PROCESSING, now, now.plusHours(2));

        // Product collecting orders (by EAN list)
        // The first one shares its lot with the packing order above (9:30-10:30).
        createProductCollectingOrder(store, building, picker,
                List.of("3274080005003", "3274080087658"), lotForOrder(lots, "CMD-2026-0457"),
                BaseHandlingOrder.HandlingOrderStatus.DONE, todayAt(now, 8, 0), todayAt(now, 9, 0));
        createProductCollectingOrder(store, building, cashier,
                List.of("3274080005003", "3274080098760"), lotForOrder(lots, "CMD-2026-0459"),
                BaseHandlingOrder.HandlingOrderStatus.DONE, todayAt(now, 8, 30), todayAt(now, 9, 30));

        // Zone collecting orders (by area / aisle)
        createZoneCollectingOrder(store, building, "FRAIS", "FRA_W", picker,
                lotForOrder(lots, "CMD-2026-0455"),
                BaseHandlingOrder.HandlingOrderStatus.PROCESSING, now, now.plusHours(2));
        createZoneCollectingOrder(store, building, "EPICERIE", "EPI2_W", cashier,
                lotForOrder(lots, "CMD-2026-0452"),
                BaseHandlingOrder.HandlingOrderStatus.SCHEDULED, todayAt(now, 16, 0), todayAt(now, 17, 0));

        LOGGER.info("Demo HandlingOrders created: 8 (2 Grouping, 2 Packing, 2 ProductCollecting, 2 ZoneCollecting)");
    }

    /**
     * Creates and persists a single grouping order.
     * <p>
     * Lots are added directly to the {@code lots} set (not via {@code addLot}) so
     * their final status is preserved.
     *
     * @param store          the demo store.
     * @param buffer         the target buffer.
     * @param assignee       the assigned employee.
     * @param lots           the lots to group.
     * @param status         the order status.
     * @param requestedStart the requested start time.
     * @param requestedEnd   the requested end time.
     * @return the persisted grouping order.
     */
    private GroupingOrder createGroupingOrder(Store store, Buffer buffer, Employee assignee,
                                              List<Lot> lots, BaseHandlingOrder.HandlingOrderStatus status,
                                              LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        GroupingOrder order = new GroupingOrder();
        order.store = store;
        order.assignee = assignee;
        order.customer = lots.get(0).customer;
        order.buffer = buffer;
        order.lots.addAll(lots);
        order.status = status;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedEnd;
        order.code = HandlingOrderSequence.getNextCode(store, order.getPrefix());
        order.persistAndFlush();
        return order;
    }

    /**
     * Creates and persists a single packing order.
     *
     * @param store          the demo store.
     * @param building       the demo building.
     * @param assignee       the assigned employee.
     * @param lots           the lots to pack.
     * @param status         the order status.
     * @param requestedStart the requested start time.
     * @param requestedEnd   the requested end time (also used as deadline).
     * @return the persisted packing order.
     */
    private PackingOrder createPackingOrder(Store store, Building building, Employee assignee,
                                            List<Lot> lots, BaseHandlingOrder.HandlingOrderStatus status,
                                            LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        PackingOrder order = new PackingOrder();
        order.store = store;
        order.assignee = assignee;
        order.customer = lots.get(0).customer;
        order.building = building;
        order.lots.addAll(lots);
        order.status = status;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedEnd;
        order.deadLine = requestedEnd;
        order.code = HandlingOrderSequence.getNextCode(store, order.getPrefix());
        order.persistAndFlush();
        return order;
    }

    /**
     * Creates and persists a single product collecting order.
     *
     * @param store          the demo store.
     * @param building       the demo building.
     * @param assignee       the assigned employee.
     * @param eans           the target product EANs.
     * @param lot            the lot to collect.
     * @param status         the order status.
     * @param requestedStart the requested start time.
     * @param requestedEnd   the requested end time.
     * @return the persisted product collecting order.
     */
    private void createComplexPickingDemo(Store store, Building building, Employee picker,
                                         List<Lot> lots, LocalDateTime now) {
        // Reuse the 12 demo lots without changing their final statuses or stock.
        // Their 63 lines repeat products across customers: multiple picks share a bay.
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.code = "PRO-DEMO-OPT";
        order.store = store;
        order.building = building;
        order.assignee = picker;
        order.status = BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;
        order.requestedStart = now.plusHours(1);
        order.requestedEnd = now.plusHours(3);
        order.lots.addAll(lots);
        order.eans.addAll(lots.stream().flatMap(lot -> lot.lines.stream())
                .map(line -> line.product.ean).distinct().toList());
        order.persistAndFlush();
        LOGGER.infof("Complex demo picking order created: %s (%d lots, %d products)",
                order.code, lots.size(), order.eans.size());
    }

    private ProductCollectingOrder createProductCollectingOrder(Store store, Building building, Employee assignee,
                                                                List<String> eans, Lot lot,
                                                                BaseHandlingOrder.HandlingOrderStatus status,
                                                                LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        ProductCollectingOrder order = new ProductCollectingOrder();
        order.store = store;
        order.assignee = assignee;
        order.building = building;
        order.eans.addAll(eans);
        order.lots.add(lot);
        order.status = status;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedEnd;
        order.code = HandlingOrderSequence.getNextCode(store, order.getPrefix());
        order.persistAndFlush();
        return order;
    }

    /**
     * Creates and persists a single zone collecting order.
     *
     * @param store          the demo store.
     * @param building       the demo building.
     * @param area           the target area.
     * @param aisle          the target aisle.
     * @param assignee       the assigned employee.
     * @param lot            the lot to collect.
     * @param status         the order status.
     * @param requestedStart the requested start time.
     * @param requestedEnd   the requested end time.
     * @return the persisted zone collecting order.
     */
    private ZoneCollectingOrder createZoneCollectingOrder(Store store, Building building, String area, String aisle,
                                                          Employee assignee, Lot lot,
                                                          BaseHandlingOrder.HandlingOrderStatus status,
                                                          LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        ZoneCollectingOrder order = new ZoneCollectingOrder();
        order.store = store;
        order.assignee = assignee;
        order.building = building;
        order.area = area;
        order.aisle = aisle;
        order.lots.add(lot);
        order.status = status;
        order.requestedStart = requestedStart;
        order.requestedEnd = requestedEnd;
        order.code = HandlingOrderSequence.getNextCode(store, order.getPrefix());
        order.persistAndFlush();
        return order;
    }

    /**
     * Finds the demo lot linked to the given order number.
     *
     * @param lots    the demo lots.
     * @param orderId the order number to look up.
     * @return the matching lot.
     */
    private Lot lotForOrder(List<Lot> lots, String orderId) {
        return lots.stream()
                .filter(l -> l.fulfillment.order.orderId.equals(orderId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Demo lot not found for order: " + orderId));
    }

    /**
     * Returns today's date (relative to the given reference timestamp) at the given time.
     *
     * @param now    the reference timestamp.
     * @param hour   the hour of the day (0-23).
     * @param minute the minute of the hour (0-59).
     * @return today at the given time.
     */
    private LocalDateTime todayAt(LocalDateTime now, int hour, int minute) {
        return now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }

    // --------------------------------------------------
    // Configurations
    // --------------------------------------------------

    /**
     * Creates the demo configurations linked to the given store and store group.
     *
     * @param store the demo store.
     * @param group the demo store group.
     */
    private void createConfigurations(Store store, StoreGroup group) {
        // FULFILLER configuration: the specification is a JSON array of fulfiller
        // definitions, each identified by its "type" field matching a registered
        // factory name (Default, ZoneGrouping, ProductGrouping), as expected by
        // FulfillmentEngine.buildFulfillersList and the storepilot configuration tile.
        Configuration config1 = new Configuration();
        config1.name = "Fulfillment standard";
        config1.type = "FULFILLER";
        config1.priority = 10;
        config1.specification = "["
                + "{\"type\":\"Default\",\"name\":\"Répartition magasin\",\"priority\":30,\"buildingCodes\":[\"LYO-042-SHOP\"]},"
                + "{\"type\":\"ZoneGrouping\",\"name\":\"Zone frais\",\"priority\":20,\"buildingCode\":\"LYO-042-SHOP\",\"area\":\"FRAIS\",\"aisle\":\"FRA_W\"},"
                + "{\"type\":\"ProductGrouping\",\"name\":\"Produits épicerie\",\"priority\":10,\"families\":[\"EPICERIE\"]}"
                + "]";
        config1.stores.add(store);
        config1.persistAndFlush();

        Configuration config2 = new Configuration();
        config2.name = "Pricing groupe régional";
        config2.type = "PRICING";
        config2.priority = 5;
        config2.specification = "{\"mode\":\"group\"}";
        config2.storeGroups.add(group);
        config2.persistAndFlush();

        LOGGER.info("Demo Configurations created: Fulfillment standard, Pricing groupe régional");
    }

    /**
     * Creates the demo ORDER_HANDLER configuration required by the StoreActivityEngine.
     * <p>
     * The specification is a JSON array of handler definitions (Grouping, Packing),
     * each identified by its {@code type} field matching a registered factory name.
     *
     * @param store the demo store.
     */
    private void createOrderHandlerConfiguration(Store store) {
        Configuration config = new Configuration();
        config.name = "Manutention standard";
        config.type = "ORDER_HANDLER";
        config.priority = 20;
        config.specification = "["
                + "{\"type\":\"Grouping\",\"name\":\"Groupement standard\",\"validityDelayMinutes\":30,\"groupingWindowMinutes\":60,\"targetBufferCode\":\"LYO-042-BUF-01\"},"
                + "{\"type\":\"Packing\",\"name\":\"Emballage standard\",\"validityDelayMinutes\":15,\"groupingWindowMinutes\":30,\"deadlineDelayMinutes\":60}"
                + "]";
        config.stores.add(store);
        config.persistAndFlush();
        LOGGER.info("Demo Configuration created: Manutention standard (ORDER_HANDLER)");
    }

    // --------------------------------------------------
    // Events
    // --------------------------------------------------

    /**
     * Creates the demo events targeting the given order requests.
     *
     * @param store    the demo store.
     * @param requests the demo order requests.
     */
    private void createEvents(Store store, List<OrderRequest> requests) {
        OrderRequest r0451 = byOrderNumber(requests, "CMD-2026-0451");
        OrderRequest r0452 = byOrderNumber(requests, "CMD-2026-0452");
        OrderRequest r0455 = byOrderNumber(requests, "CMD-2026-0455");
        OrderRequest r0457 = byOrderNumber(requests, "CMD-2026-0457");
        OrderRequest r0458 = byOrderNumber(requests, "CMD-2026-0458");
        OrderRequest r0462 = byOrderNumber(requests, "CMD-2026-0462");

        createEvent(store, "Commande reçue",
                "La commande " + r0451.orderNumber + " a été reçue.",
                Event.UrgencyLevel.LOW, r0451);
        createEvent(store, "Préparation en cours",
                "La commande " + r0452.orderNumber + " est en préparation.",
                Event.UrgencyLevel.MEDIUM, r0452);
        createEvent(store, "Problème de stock",
                "Rupture de stock sur le saumon fumé pour la commande " + r0455.orderNumber + ".",
                Event.UrgencyLevel.HIGH, r0455);
        createEvent(store, "Commande volumineuse",
                "La commande " + r0457.orderNumber + " comporte 10 lignes, prévoir 2 bacs.",
                Event.UrgencyLevel.MEDIUM, r0457);
        createEvent(store, "Commande expédiée",
                "La commande " + r0458.orderNumber + " a été expédiée au client.",
                Event.UrgencyLevel.LOW, r0458);
        createEvent(store, "Prêt au retrait",
                "La commande " + r0462.orderNumber + " est prête au retrait Drive.",
                Event.UrgencyLevel.LOW, r0462);
        LOGGER.info("Demo Events created: 6");
    }

    /**
     * Finds a demo order request by its order number.
     *
     * @param requests    the demo order requests.
     * @param orderNumber the order number to look up.
     * @return the matching order request.
     */
    private OrderRequest byOrderNumber(List<OrderRequest> requests, String orderNumber) {
        return requests.stream()
                .filter(r -> r.orderNumber.equals(orderNumber))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Demo order request not found: " + orderNumber));
    }

    /**
     * Creates and persists a single event targeting the given order request.
     *
     * @param store    the demo store.
     * @param label    the event label.
     * @param desc     the event description.
     * @param urgency  the urgency level.
     * @param target   the target order request.
     */
    private void createEvent(Store store, String label, String desc, Event.UrgencyLevel urgency, OrderRequest target) {
        Event event = new Event();
        event.label = label;
        event.description = desc;
        event.urgencyLevel = urgency;
        event.store = store;
        event.targetEntityType = OrderRequest.class.getName();
        event.targetEntityId = target.id;
        event.targetEntityCode = target.orderNumber;
        event.persistAndFlush();
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Creates a simple postal address.
     *
     * @param streetLine1 the street line.
     * @param postalCode  the postal code.
     * @param city        the city.
     * @return the address value object.
     */
    private Adresse createAdresse(String streetLine1, String postalCode, String city) {
        Adresse adresse = new Adresse();
        adresse.streetLine1 = streetLine1;
        adresse.postalCode = postalCode;
        adresse.city = city;
        adresse.country = "France";
        return adresse;
    }
}
