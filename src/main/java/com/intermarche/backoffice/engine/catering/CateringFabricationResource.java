package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.CateringPurchaseNeed;
import com.intermarche.backoffice.domain.catering.CateringUnit;
import com.intermarche.backoffice.domain.catering.FabricationLineUnit;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catering fabrication reads and actions (§ 8.2). Reads are open to ADMIN, MANAGER and PICKER.
 */
@Path("/catering")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "MANAGER", "PICKER"})
@RunOnVirtualThread
@Transactional
public class CateringFabricationResource implements GraphQLTrait {

    @Inject
    SecurityIdentity identity;
    @Inject
    CateringProposalEngine proposals;
    @Inject
    CateringValidationService validation;
    @Inject
    com.intermarche.backoffice.engine.stock.TemporalStockService stocks;

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
     * Resolves a store the caller is allowed to address.
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
            throw new BadRequestException("Magasin requis");
        }
        return store;
    }

    /**
     * Lists catering units of a store (§ 8.2): order, slot, article, variant, composition, quantity,
     * mode, state and the linked fabrication order or purchase need.
     *
     * @param storeId the store id.
     * @param from    the earliest availability, or null.
     * @param to      the latest availability, or null.
     * @param state   an optional state filter.
     * @return the units.
     */
    @GET
    @Path("/units")
    public List<Map<String, Object>> units(@QueryParam("storeId") Long storeId,
                                           @QueryParam("from") String from, @QueryParam("to") String to,
                                           @QueryParam("state") String state) {
        Store store = scope(storeId);
        StringBuilder query = new StringBuilder("order.store = ?1");
        var params = new java.util.ArrayList<Object>();
        params.add(store);
        if (from != null && !from.isBlank()) {
            query.append(" and availableAt >= ?").append(params.size() + 1);
            params.add(LocalDateTime.parse(from));
        }
        if (to != null && !to.isBlank()) {
            query.append(" and availableAt <= ?").append(params.size() + 1);
            params.add(LocalDateTime.parse(to));
        }
        if (state != null && !state.isBlank()) {
            query.append(" and state = ?").append(params.size() + 1);
            params.add(com.intermarche.backoffice.domain.catering.CateringUnitState.valueOf(state));
        }
        query.append(" order by availableAt");
        return CateringUnit.<CateringUnit>list(query.toString(), params.toArray()).stream().map(this::unitView).toList();
    }

    /**
     * Builds a unit projection.
     *
     * @param unit the unit.
     * @return the projection.
     */
    private Map<String, Object> unitView(CateringUnit unit) {
        var view = new LinkedHashMap<String, Object>();
        view.put("lineId", unit.lineId);
        view.put("orderRef", Hibernate.unproxy(unit.order, Order.class).orderId);
        view.put("availableAt", unit.availableAt);
        view.put("ean", unit.ean);
        view.put("itemId", unit.item == null ? null : unit.item.id);
        view.put("variantId", unit.variantId);
        view.put("variantLabel", unit.variantLabel);
        view.put("configuration", unit.configuration);
        view.put("quantity", unit.quantity);
        view.put("producedQuantity", unit.producedQuantity);
        view.put("shortQuantity", unit.shortQuantity);
        view.put("sourcingMode", unit.sourcingMode);
        view.put("state", unit.state);
        FabricationLineUnit lineUnit = FabricationLineUnit.find("unit", unit).firstResult();
        view.put("fabricationOrderId", lineUnit == null ? null
                : Hibernate.unproxy(lineUnit.line, com.intermarche.backoffice.domain.catering.FabricationLine.class).order.id);
        CateringPurchaseNeed need = CateringPurchaseNeed.find("unit", unit).firstResult();
        view.put("purchaseNeedId", need == null ? null : need.id);
        return view;
    }

    /**
     * Lists the fabrication proposals of a store (§ 8.2, F8).
     *
     * @param storeId the store id.
     * @return the proposed orders.
     */
    @GET
    @Path("/fabrication/proposals")
    public List<Map<String, Object>> proposalsList(@QueryParam("storeId") Long storeId) {
        Store store = scope(storeId);
        return proposalViews(store);
    }

    /**
     * Recomputes and returns the fabrication proposals of a store (§ 8.2, F8).
     *
     * @param storeId the store id.
     * @return the proposed orders.
     */
    @POST
    @Path("/fabrication/proposals/recompute")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public List<Map<String, Object>> recompute(@QueryParam("storeId") Long storeId) {
        Store store = scope(storeId);
        proposals.recompute(store);
        return proposalViews(store);
    }

    /** A batch validation request. */
    public static class ValidateRequest {
        public Long storeId;
        public List<CateringValidationService.OrderValidation> orders = new java.util.ArrayList<>();
    }

    /**
     * Validates a batch of fabrication proposals (§ 8.2, F8).
     *
     * @param request the validation request.
     * @return the validated order ids.
     */
    @POST
    @Path("/fabrication/proposals/validate")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Map<String, Object> validate(ValidateRequest request) {
        if (request == null) {
            throw new BadRequestException("Demande de validation requise");
        }
        Store store = scope(request.storeId);
        List<Long> validated = validation.validate(store, request.orders, true);
        return Map.of("validated", validated);
    }

    /**
     * Serves the component requirements of a store by day and component (§ 8.2, F9).
     *
     * @param storeId the store id.
     * @param from    the earliest day, or null.
     * @param to      the latest day, or null.
     * @return the requirements.
     */
    @GET
    @Path("/requirements")
    public List<Map<String, Object>> requirements(@QueryParam("storeId") Long storeId,
                                                  @QueryParam("from") String from, @QueryParam("to") String to) {
        Store store = scope(storeId);
        var commitments = com.intermarche.backoffice.domain.stock.StockCommitment.<com.intermarche.backoffice.domain.stock.StockCommitment>list(
                "account.store = ?1 and reference like 'FAB:%' and status = ?2", store,
                com.intermarche.backoffice.domain.stock.StockCommitment.CommitmentStatus.ACTIVE);
        Map<String, Map<String, Object>> byDayComponent = new java.util.LinkedHashMap<>();
        for (var commitment : commitments) {
            var account = commitment.account;
            Product component = Hibernate.unproxy(account.product, Product.class);
            java.time.LocalDate day = commitment.preparationAt.toLocalDate();
            String key = day + "|" + component.ean;
            var row = byDayComponent.computeIfAbsent(key, k -> {
                var created = new LinkedHashMap<String, Object>();
                created.put("day", day);
                created.put("componentEan", component.ean);
                created.put("required", java.math.BigDecimal.ZERO);
                created.put("available", stocks.availability(account.id,
                        Hibernate.unproxy(commitment.building, com.intermarche.backoffice.domain.store.Building.class).id,
                        commitment.preparationAt).periods.get(0).availableToPromise);
                return created;
            });
            row.put("required", ((java.math.BigDecimal) row.get("required")).add(commitment.quantity));
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (var row : byDayComponent.values()) {
            row.put("gap", ((java.math.BigDecimal) row.get("available")).subtract((java.math.BigDecimal) row.get("required")));
            result.add(row);
        }
        return result;
    }

    /**
     * Builds the proposal projections of a store.
     *
     * @param store the store.
     * @return the projections.
     */
    private List<Map<String, Object>> proposalViews(Store store) {
        return com.intermarche.backoffice.domain.wms.handling.FabricationOrder.<com.intermarche.backoffice.domain.wms.handling.FabricationOrder>list(
                        "store = ?1 and proposalState = ?2 order by productionDate, id", store,
                        com.intermarche.backoffice.domain.wms.handling.FabricationOrder.FabricationProposalState.PROPOSED)
                .stream().map(this::proposalView).toList();
    }

    /**
     * Builds a proposal projection.
     *
     * @param order the fabrication order.
     * @return the projection.
     */
    private Map<String, Object> proposalView(com.intermarche.backoffice.domain.wms.handling.FabricationOrder order) {
        var lines = com.intermarche.backoffice.domain.catering.FabricationLine.<com.intermarche.backoffice.domain.catering.FabricationLine>list("order", order).stream().map(line -> {
            var units = FabricationLineUnit.<FabricationLineUnit>list("line", line).stream().map(lineUnit -> {
                CateringUnit unit = Hibernate.unproxy(lineUnit.unit, CateringUnit.class);
                return Map.of("lineId", unit.lineId, "orderRef", Hibernate.unproxy(unit.order, Order.class).orderId,
                        "availableAt", unit.availableAt, "quantity", lineUnit.quantity);
            }).toList();
            return Map.of("itemId", Hibernate.unproxy(line.item, com.intermarche.backoffice.domain.catering.CateringItem.class).id,
                    "variantId", line.variantId, "configurationKey", line.configurationKey,
                    "plannedQuantity", line.plannedQuantity, "units", units);
        }).toList();
        var view = new LinkedHashMap<String, Object>();
        view.put("orderId", order.id);
        view.put("revision", order.version);
        view.put("code", order.code);
        view.put("stationId", order.station == null ? null : order.station.id);
        view.put("productionDate", order.productionDate);
        view.put("requestedStart", order.requestedStart);
        view.put("requestedEnd", order.requestedEnd);
        view.put("plannedMinutes", order.plannedMinutes);
        view.put("dailyCapacity", order.station == null ? null : order.station.dailyMinutes);
        view.put("proposedEmployeeId", order.proposedEmployee == null ? null : order.proposedEmployee.id);
        view.put("lines", lines);
        return view;
    }
}

