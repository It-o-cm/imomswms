package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.printing.StorePrinter;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.hibernate.Hibernate;
import java.time.LocalTime;
import java.util.*;

/**
 * Catering referential resource (§ 8.1): datasheets, recipes, fabrication stations and
 * qualifications. The datasheet and its recipes are common to the enseigne and written by an
 * administrator only; the per-store override, the stations and the qualifications belong to a store
 * (a manager is limited to their own store).
 */
@Path("/catering")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "MANAGER", "PICKER"})
@RunOnVirtualThread
@Transactional
public class CateringReferentialResource implements GraphQLTrait {

    @Inject
    SecurityIdentity identity;
    @Inject
    CateringIntake intake;

    /**
     * Returns the security identity.
     *
     * @return the security identity.
     */
    @Override
    public SecurityIdentity getIdentity() {
        return identity;
    }

    /**
     * Resolves a store the caller is allowed to address (403 otherwise).
     *
     * @param id the requested store id.
     * @return the store.
     */
    private Store scope(Long id) {
        Store own = getStoreContext();
        if (!identity.hasRole("ADMIN")) {
            if (own == null || id != null && !Hibernate.unproxy(own, Store.class).id.equals(id)) {
                throw new ForbiddenException("Magasin hors de votre périmètre");
            }
            return Hibernate.unproxy(own, Store.class);
        }
        Store store = id == null ? null : Store.findById(id);
        if (store == null) {
            throw bad("Magasin requis");
        }
        return store;
    }

    /**
     * Requires the caller to be an administrator (enseigne-wide writes).
     */
    private void requireAdmin() {
        if (!identity.hasRole("ADMIN")) {
            throw new ForbiddenException("Réservé à l’administration");
        }
    }

    /**
     * Builds a 400 response carrying a message.
     *
     * @param message the message.
     * @return the exception.
     */
    private BadRequestException bad(String message) {
        return new BadRequestException(jakarta.ws.rs.core.Response.status(400).entity(Map.of("message", message)).build());
    }

    /**
     * Wraps a referential-validation failure into a 400.
     *
     * @param action the action to run.
     * @param <T>    the result type.
     * @return the action result.
     */
    private <T> T validated(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    // --------------------------------------------------
    // Items
    // --------------------------------------------------

    /** Input for a catering datasheet. */
    public static class ItemInput {
        public Long id;
        public Long productId;
        public String sourcingMode;
        public Long stationId;
        public Integer shelfLifeHours;
        public Integer maxAdvanceHours;
        public Integer supplierLeadDays;
        public String supplierName;
        public String cancelDispositions;
        public boolean fragile;
        public boolean active = true;
    }

    /** Input for a per-store datasheet override. */
    public static class ItemStoreInput {
        public String sourcingMode;
        public Long stationId;
        public boolean active = true;
    }

    /**
     * Lists catering datasheets with their resolved mode and known variants (§ 8.1).
     *
     * @param storeId the store for mode resolution, or null for the enseigne view.
     * @param q       an optional product-name/EAN filter.
     * @param mode    an optional sourcing-mode filter.
     * @return the datasheets.
     */
    @GET
    @Path("/items")
    public List<Map<String, Object>> items(@QueryParam("storeId") Long storeId, @QueryParam("q") String q,
                                           @QueryParam("mode") String mode) {
        Store store = storeId == null ? null : scope(storeId);
        List<CateringItem> all = CateringItem.listAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (CateringItem item : all) {
            Product product = Hibernate.unproxy(item.product, Product.class);
            if (q != null && !q.isBlank()
                    && !product.name.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                    && !product.ean.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
                continue;
            }
            SourcingMode resolved = store != null ? intake.resolveMode(item, store) : item.sourcingMode;
            if (mode != null && !mode.isBlank() && (resolved == null || !resolved.name().equals(mode))) {
                continue;
            }
            result.add(itemView(item, resolved));
        }
        return result;
    }

    /**
     * Builds a datasheet projection.
     *
     * @param item     the datasheet.
     * @param resolved the resolved mode.
     * @return the projection.
     */
    private Map<String, Object> itemView(CateringItem item, SourcingMode resolved) {
        var variants = new TreeSet<String>();
        CateringRecipe.<CateringRecipe>list("item", item).forEach(r -> variants.add(r.variantId));
        CateringUnit.<CateringUnit>list("item", item).forEach(u -> {
            if (u.variantId != null) {
                variants.add(u.variantId);
            }
        });
        var variantViews = new ArrayList<Map<String, Object>>();
        for (String variantId : variants) {
            boolean hasBase = CateringRecipe.count(
                    "item = ?1 and variantId = ?2 and stepId is null and choiceId is null and flavourId is null and active = true",
                    item, variantId) > 0;
            variantViews.add(Map.of("variantId", variantId, "recipePresent", hasBase));
        }
        var view = new LinkedHashMap<String, Object>();
        view.put("id", item.id);
        view.put("ean", Hibernate.unproxy(item.product, Product.class).ean);
        view.put("name", Hibernate.unproxy(item.product, Product.class).name);
        view.put("sourcingMode", item.sourcingMode);
        view.put("resolvedMode", resolved);
        view.put("stationId", item.station == null ? null : Hibernate.unproxy(item.station, FabricationStation.class).id);
        view.put("shelfLifeHours", item.shelfLifeHours);
        view.put("maxAdvanceHours", item.maxAdvanceHours);
        view.put("supplierName", item.supplierName);
        view.put("supplierLeadDays", item.supplierLeadDays);
        view.put("cancelDispositions", item.cancelDispositions);
        view.put("fragile", item.fragile);
        view.put("active", item.active);
        view.put("variants", variantViews);
        return view;
    }

    /**
     * Creates or updates a catering datasheet (ADMIN; § 8.1). A station is required for a FABRICATED
     * or SUPPLIED datasheet.
     *
     * @param input the datasheet input.
     * @return the datasheet projection.
     */
    @PUT
    @Path("/items")
    public Map<String, Object> saveItem(ItemInput input) {
        requireAdmin();
        if (input == null || input.productId == null) {
            throw bad("Produit requis");
        }
        SourcingMode mode;
        try {
            mode = SourcingMode.valueOf(input.sourcingMode);
        } catch (RuntimeException e) {
            throw bad("sourcingMode (mode d’obtention) : valeur requise");
        }
        Product product = Product.findById(input.productId);
        if (product == null) {
            throw bad("Produit introuvable");
        }
        CateringItem item = input.id == null ? new CateringItem() : CateringItem.findById(input.id);
        if (item == null) {
            throw bad("Fiche introuvable");
        }
        if ((mode == SourcingMode.FABRICATED || mode == SourcingMode.SUPPLIED) && input.stationId == null) {
            throw bad("station (poste de fabrication) : valeur requise pour un article FABRICATED ou SUPPLIED");
        }
        FabricationStation station = input.stationId == null ? null : FabricationStation.findById(input.stationId);
        if (input.stationId != null && station == null) {
            throw bad("Poste introuvable");
        }
        item.product = product;
        item.sourcingMode = mode;
        item.station = station;
        item.shelfLifeHours = input.shelfLifeHours;
        item.maxAdvanceHours = input.maxAdvanceHours;
        item.supplierLeadDays = input.supplierLeadDays;
        item.supplierName = input.supplierName;
        item.cancelDispositions = input.cancelDispositions;
        item.fragile = input.fragile;
        item.active = input.active;
        validated(() -> {
            CateringReferentialValidation.item(item);
            return null;
        });
        item.persistAndFlush();
        return itemView(item, item.sourcingMode);
    }

    /**
     * Creates or updates a per-store datasheet override (ADMIN or MANAGER of the store; § 8.1).
     *
     * @param id      the datasheet id.
     * @param storeId the store id.
     * @param input   the override input.
     * @return the override projection.
     */
    @PUT
    @Path("/items/{id}/stores/{storeId}")
    public Map<String, Object> saveItemStore(@PathParam("id") Long id, @PathParam("storeId") Long storeId, ItemStoreInput input) {
        Store store = scope(storeId);
        CateringItem item = CateringItem.findById(id);
        if (item == null) {
            throw new NotFoundException();
        }
        if (input == null) {
            throw bad("Surcharge requise");
        }
        CateringItemStore override = CateringItemStore.find("item = ?1 and store = ?2", item, store).firstResult();
        if (override == null) {
            override = new CateringItemStore();
            override.item = item;
            override.store = store;
        }
        if (input.sourcingMode != null && !input.sourcingMode.isBlank()) {
            try {
                override.sourcingMode = SourcingMode.valueOf(input.sourcingMode);
            } catch (RuntimeException e) {
                throw bad("sourcingMode (mode d’obtention) : valeur inconnue");
            }
        } else {
            override.sourcingMode = null;
        }
        if (input.stationId != null) {
            FabricationStation station = FabricationStation.findById(input.stationId);
            if (station == null || !Hibernate.unproxy(station.store, Store.class).id.equals(store.id)) {
                throw bad("Poste hors du magasin");
            }
            override.station = station;
        } else {
            override.station = null;
        }
        override.active = input.active;
        override.persistAndFlush();
        return Map.of("itemId", item.id, "storeId", store.id,
                "sourcingMode", override.sourcingMode, "stationId",
                override.station == null ? null : override.station.id, "active", override.active);
    }

    // --------------------------------------------------
    // Recipes
    // --------------------------------------------------

    /** Input for a recipe line. */
    public static class RecipeLineInput {
        public String componentEan;
        public java.math.BigDecimal quantity;
        public String kind = "INGREDIENT";
    }

    /** Input for a recipe revision. */
    public static class RecipeInput {
        public String variantId;
        public String stepId;
        public String choiceId;
        public String flavourId;
        public Integer laborSeconds;
        public List<RecipeLineInput> lines = new ArrayList<>();
    }

    /**
     * Returns the recipes of a datasheet (§ 8.1).
     *
     * @param id the datasheet id.
     * @return the recipes and their lines.
     */
    @GET
    @Path("/items/{id}/recipes")
    public Map<String, Object> recipes(@PathParam("id") Long id) {
        CateringItem item = CateringItem.findById(id);
        if (item == null) {
            throw new NotFoundException();
        }
        var views = CateringRecipe.<CateringRecipe>list("item = ?1 order by variantId, revision", item).stream().map(recipe -> {
            var lines = CateringRecipeLine.<CateringRecipeLine>list("recipe", recipe).stream().map(line -> Map.of(
                    "componentEan", Hibernate.unproxy(line.component, Product.class).ean,
                    "quantity", line.quantity, "kind", line.kind)).toList();
            return Map.of("id", recipe.id, "variantId", recipe.variantId, "stepId", recipe.stepId,
                    "choiceId", recipe.choiceId, "flavourId", recipe.flavourId, "laborSeconds", recipe.laborSeconds,
                    "revision", recipe.revision, "active", recipe.active, "lines", lines);
        }).toList();
        return Map.of("itemId", item.id, "recipes", views);
    }

    /**
     * Creates a new recipe revision, base or complement (ADMIN; § 8.1).
     *
     * @param id    the datasheet id.
     * @param input the recipe input.
     * @return the created revision.
     */
    @PUT
    @Path("/items/{id}/recipes")
    public Map<String, Object> saveRecipe(@PathParam("id") Long id, RecipeInput input) {
        requireAdmin();
        CateringItem item = CateringItem.findById(id);
        if (item == null) {
            throw new NotFoundException();
        }
        if (input == null || input.variantId == null || input.variantId.isBlank()) {
            throw bad("variantId requis");
        }
        int nextRevision = 1 + (int) CateringRecipe.count(
                "item = ?1 and variantId = ?2 and ((?3 is null and stepId is null) or stepId = ?3) and ((?4 is null and choiceId is null) or choiceId = ?4) and ((?5 is null and flavourId is null) or flavourId = ?5)",
                item, input.variantId, input.stepId, input.choiceId, input.flavourId);
        CateringRecipe.<CateringRecipe>list(
                "item = ?1 and variantId = ?2 and ((?3 is null and stepId is null) or stepId = ?3) and ((?4 is null and choiceId is null) or choiceId = ?4) and ((?5 is null and flavourId is null) or flavourId = ?5) and active = true",
                item, input.variantId, input.stepId, input.choiceId, input.flavourId).forEach(r -> r.active = false);
        CateringRecipe recipe = new CateringRecipe();
        recipe.item = item;
        recipe.variantId = input.variantId;
        recipe.stepId = input.stepId;
        recipe.choiceId = input.choiceId;
        recipe.flavourId = input.flavourId;
        recipe.laborSeconds = input.laborSeconds;
        recipe.revision = nextRevision;
        recipe.active = true;
        validated(() -> {
            CateringReferentialValidation.laborSeconds(input.laborSeconds);
            return null;
        });
        recipe.persist();
        for (RecipeLineInput lineInput : input.lines) {
            Product component = Product.findByEan(lineInput.componentEan);
            if (component == null) {
                throw bad("Composant inconnu : " + lineInput.componentEan);
            }
            if (lineInput.quantity == null || lineInput.quantity.signum() <= 0) {
                throw bad("Quantité du composant " + lineInput.componentEan + " : strictement positive requise");
            }
            CateringRecipeLine line = new CateringRecipeLine();
            line.recipe = recipe;
            line.component = component;
            line.quantity = lineInput.quantity;
            try {
                line.kind = CateringRecipeLine.RecipeLineKind.valueOf(lineInput.kind);
            } catch (RuntimeException e) {
                throw bad("kind de composant inconnu : " + lineInput.kind);
            }
            line.persist();
        }
        return Map.of("itemId", item.id, "variantId", recipe.variantId, "revision", recipe.revision);
    }

    // --------------------------------------------------
    // Stations
    // --------------------------------------------------

    /** Input for a fabrication station. */
    public static class StationInput {
        public Long id;
        public Long storeId;
        public String code;
        public String name;
        public Long buildingId;
        public Long stagingBufferId;
        public Long printerId;
        public String weekdays;
        public String closedDates;
        public String opensAt;
        public String closesAt;
        public Integer dailyMinutes;
        public Integer stagingLeadMinutes;
        public Integer maxOrderMinutes;
        public boolean active = true;
    }

    /** Input for station qualifications. */
    public static class EmployeesInput {
        public Long storeId;
        public List<Long> employeeIds = new ArrayList<>();
    }

    /**
     * Lists the fabrication stations of a store (§ 8.1).
     *
     * @param storeId the store id.
     * @return the stations with calendar and qualified employees.
     */
    @GET
    @Path("/stations")
    public List<Map<String, Object>> stations(@QueryParam("storeId") Long storeId) {
        Store store = scope(storeId);
        return FabricationStation.<FabricationStation>list("store = ?1 order by code", store).stream().map(this::stationView).toList();
    }

    /**
     * Builds a station projection.
     *
     * @param station the station.
     * @return the projection.
     */
    private Map<String, Object> stationView(FabricationStation station) {
        var employees = FabricationStationEmployee.<FabricationStationEmployee>list("station", station).stream()
                .map(q -> Hibernate.unproxy(q.employee, Employee.class).id).toList();
        var view = new LinkedHashMap<String, Object>();
        view.put("id", station.id);
        view.put("code", station.code);
        view.put("name", station.name);
        view.put("buildingId", station.building == null ? null : Hibernate.unproxy(station.building, Building.class).id);
        view.put("stagingBufferId", station.stagingBuffer == null ? null : station.stagingBuffer.id);
        view.put("printerId", station.printer == null ? null : station.printer.id);
        view.put("weekdays", station.weekdays);
        view.put("closedDates", station.closedDates);
        view.put("opensAt", station.opensAt == null ? null : station.opensAt.toString());
        view.put("closesAt", station.closesAt == null ? null : station.closesAt.toString());
        view.put("dailyMinutes", station.dailyMinutes);
        view.put("stagingLeadMinutes", station.stagingLeadMinutes);
        view.put("maxOrderMinutes", station.maxOrderMinutes);
        view.put("active", station.active);
        view.put("employeeIds", employees);
        return view;
    }

    /**
     * Creates or updates a fabrication station (ADMIN or MANAGER of the store; § 8.1).
     *
     * @param input the station input.
     * @return the station projection.
     */
    @PUT
    @Path("/stations")
    public Map<String, Object> saveStation(StationInput input) {
        if (input == null) {
            throw bad("Poste requis");
        }
        Store store = scope(input.storeId);
        FabricationStation station = input.id == null ? new FabricationStation() : FabricationStation.findById(input.id);
        if (station == null || (input.id != null && !Hibernate.unproxy(station.store, Store.class).id.equals(store.id))) {
            throw bad("Poste hors du magasin");
        }
        if (input.code == null || input.code.isBlank()) {
            throw bad("Code du poste requis");
        }
        if (input.name == null || input.name.isBlank()) {
            throw bad("Nom du poste requis");
        }
        FabricationStation other = FabricationStation.find("store = ?1 and code = ?2", store, input.code).firstResult();
        if (other != null && !Objects.equals(other.id, station.id)) {
            throw bad("Code de poste déjà utilisé");
        }
        station.store = store;
        station.code = input.code.trim();
        station.name = input.name.trim();
        station.building = resolveBuilding(store, input.buildingId);
        station.stagingBuffer = resolveBuffer(store, input.stagingBufferId);
        station.printer = resolvePrinter(store, input.printerId);
        station.weekdays = input.weekdays;
        station.closedDates = input.closedDates;
        station.opensAt = parseTime(input.opensAt);
        station.closesAt = parseTime(input.closesAt);
        station.dailyMinutes = input.dailyMinutes;
        station.stagingLeadMinutes = input.stagingLeadMinutes;
        station.maxOrderMinutes = input.maxOrderMinutes;
        station.active = input.active;
        validated(() -> {
            CateringReferentialValidation.station(station);
            return null;
        });
        station.persistAndFlush();
        return stationView(station);
    }

    /**
     * Sets the qualified employees of a station (ADMIN or MANAGER of the store; § 8.1).
     *
     * @param id    the station id.
     * @param input the employee ids.
     * @return the updated station projection.
     */
    @PUT
    @Path("/stations/{id}/employees")
    public Map<String, Object> saveStationEmployees(@PathParam("id") Long id, EmployeesInput input) {
        if (input == null) {
            throw bad("Habilitations requises");
        }
        Store store = scope(input.storeId);
        FabricationStation station = FabricationStation.findById(id);
        if (station == null || !Hibernate.unproxy(station.store, Store.class).id.equals(store.id)) {
            throw bad("Poste hors du magasin");
        }
        FabricationStationEmployee.delete("station", station);
        for (Long employeeId : input.employeeIds) {
            Employee employee = employeeId == null ? null : Employee.findById(employeeId);
            if (employee == null || !Hibernate.unproxy(employee.store, Store.class).id.equals(store.id)) {
                throw bad("Employé d’un autre magasin : " + employeeId);
            }
            FabricationStationEmployee qualification = new FabricationStationEmployee();
            qualification.station = station;
            qualification.employee = employee;
            qualification.persist();
        }
        return stationView(station);
    }

    /**
     * Resolves a building belonging to a store.
     *
     * @param store the store.
     * @param id    the building id, or null.
     * @return the building, or null.
     */
    private Building resolveBuilding(Store store, Long id) {
        if (id == null) {
            return null;
        }
        return store.buildings.stream().map(b -> Hibernate.unproxy(b, Building.class))
                .filter(b -> b.id.equals(id)).findFirst().orElseThrow(() -> bad("Bâtiment hors du magasin"));
    }

    /**
     * Resolves a STAGING buffer belonging to a store.
     *
     * @param store the store.
     * @param id    the buffer id, or null.
     * @return the buffer, or null.
     */
    private Buffer resolveBuffer(Store store, Long id) {
        if (id == null) {
            return null;
        }
        Buffer buffer = Buffer.findById(id);
        if (buffer == null || !store.buildings.contains(Hibernate.unproxy(buffer.building, Building.class))) {
            throw bad("Buffer hors du magasin");
        }
        return buffer;
    }

    /**
     * Resolves a printer belonging to a store.
     *
     * @param store the store.
     * @param id    the printer id, or null.
     * @return the printer, or null.
     */
    private StorePrinter resolvePrinter(Store store, Long id) {
        if (id == null) {
            return null;
        }
        StorePrinter printer = StorePrinter.findById(id);
        if (printer == null) {
            throw bad("Imprimante introuvable");
        }
        return printer;
    }

    /**
     * Parses an HH:MM time, or null.
     *
     * @param value the value.
     * @return the time, or null.
     */
    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException e) {
            throw bad("Heure invalide : " + value);
        }
    }
}
