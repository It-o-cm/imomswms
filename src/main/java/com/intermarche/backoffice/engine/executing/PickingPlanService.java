package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.client.PickingExecution;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.ShelfLocation;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.domain.wms.Lot;
import com.intermarche.backoffice.domain.wms.LotLine;
import com.intermarche.backoffice.domain.wms.handling.PreparingOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service responsible for generating picking plans and finding mergeable orders.
 * <p>
 * This service focuses on read-only optimization logic, calculating routes,
 * aggregating capacities, and determining order compatibility without modifying
 * the persistent state of executions.
 * </p>
 */
@ApplicationScoped
public class PickingPlanService {

    @Inject SheetTotePreparation sheetTotes;
    @Inject MixedPreparationService mixedPreparation;
    @Inject PickingResolutionService resolutions;

    @Inject
    PickingRouteOptimizer routeOptimizer;

    @Inject
    StorePickingRoutePlanner storeRoutePlanner;

    // --------------------------------------------------
    // Public API
    // --------------------------------------------------

    /**
     * Finds orders eligible for merging with a primary order for batch picking.
     *
     * @param primaryOrderId The ID of the primary order.
     * @param search         Search string to filter results.
     * @return A list of mergeable order DTOs.
     */
    @Transactional
    public List<MergedOrderDTO> findMergeableOrders(Long primaryOrderId, String search) {
        BaseHandlingOrder primary = BaseHandlingOrder.findById(primaryOrderId);
        if (primary == null) return Collections.emptyList();

        List<BaseHandlingOrder> candidates = BaseHandlingOrder.list(
                "store = ?1 and status = ?2 and id != ?3",
                primary.store,
                BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,
                primaryOrderId
        );

        return candidates.stream()
                .filter(o -> o instanceof PreparingOrder)
                .filter(o -> MixedPreparationService.supports(primary)==MixedPreparationService.supports(o))
                .filter(o -> filterBySearch(o, search))
                .map(this::mapToMergeDTO)
                .collect(Collectors.toList());
    }

    /**
     * Generates optimized picking plans grouped by Building.
     * <p>
     * Determines the building by inspecting the Lot -> Chunk -> Building chain.
     * Handles both initial planning (from LotLines) and re-planning during execution (from PickedItems).
     * </p>
     *
     * @param orderIds  List of order IDs.
     * @param startNode Start location (optional, can be null).
     * @return A BatchPickingPlanResponse containing plans per building.
     */
    @Transactional
    public BatchPickingPlanResponse generatePickingPlan(List<Long> orderIds, String startNode) {
        List<BaseHandlingOrder> orders = BaseHandlingOrder.list("id in ?1", orderIds);

        Map<Long, BuildingPlanContext> contextsByBuilding = new HashMap<>();
        int globalStepCounter = 1;

        for (BaseHandlingOrder order : orders) {
            HandlingExecution exec = HandlingExecution.find("handlingOrder", order).firstResult();

            if (MixedPreparationService.supports(order) && (exec==null || exec.status!=HandlingExecution.ExecutionStatus.DONE)) {
                for(var source:mixedPreparation.sources((PreparingOrder)order)) {
                    if(source.building()==null)throw new IllegalStateException("Bâtiment source manquant.");
                    var ctx=contextsByBuilding.computeIfAbsent(source.building().id,k->new BuildingPlanContext(source.building()));
                    var step=mapToStepDTO(source.line(),order,globalStepCounter++);
                    step.pickedItemId=source.key();step.quantity=source.quantity();step.sourceAvailable=source.quantity();
                    step.productId=source.product().id;step.productEan=source.product().ean;step.productName=source.product().name;step.temperatureZone=source.product().temperatureZone;step.sourceKind=source.item()==null?"SHELF":"TOTE";step.sourceLocation=source.item()==null?step.sourceLocation:source.label();step.sourceSnapshot=source.snapshot();step.sourceToteCode=source.item()==null?null:source.item().loadedTote.tote.code;
                    if(order instanceof com.intermarche.backoffice.domain.wms.handling.GroupingOrder grouping && grouping.buffer!=null)step.destinationBufferId=grouping.buffer.id;
                    step.chunkId=((PreparingOrder)order).lots.stream().filter(l->l.lines.contains(source.line())).map(l->l.chunk.id).findFirst().orElse(null);
                    if(source.item()!=null){step.aisle="BACS";step.unit=source.item().loadedTote.tote.code;step.position=null;step.area="BACS";step.planNodeCode=source.item().loadedTote.buffer==null?null:source.item().loadedTote.buffer.planNodeCode;}
                    ctx.allSteps.add(step);if(step.aisle!=null && source.item()==null)ctx.uniqueAisleCodes.add(step.aisle);
                    ctx.totalWeight=ctx.totalWeight.add((source.line().product.referenceWeight==null?BigDecimal.ZERO:source.line().product.referenceWeight).multiply(source.quantity()));
                    ctx.totalVolume=ctx.totalVolume.add((source.line().product.referenceVolume==null?BigDecimal.ZERO:source.line().product.referenceVolume).multiply(source.quantity()));
                }
            } else if (exec instanceof PickingExecution pe) {
                // SCENARIO A: Execution exists -> Use remaining PickedItems
                Map<Long, Building> lineToBuildingCache = buildLineToBuildingCache(pe.handlingOrder);
                if (pe.itemsToPick != null) {
                    for (PickedItem item : pe.itemsToPick) {
                        if(item.status!=PickedItem.PickStatus.PENDING || item.quantity.signum()<=0)continue;
                        Building building = null;
                        if (item.lotLine != null) {
                            building = lineToBuildingCache.get(item.lotLine.id);
                        }
                        addStepToContext(contextsByBuilding, item, order, globalStepCounter++, building);
                    }
                }
            } else {
                // SCENARIO B: Initial Planning -> Use LotLines
                if (order instanceof PreparingOrder po && po.lots != null) {
                    for (Lot lot : po.lots) {
                        Building building = resolveBuildingFromLot(lot);
                        if (lot != null && lot.lines != null) {
                            for (LotLine line : lot.lines) {
                                addStepToContext(contextsByBuilding, line, order, globalStepCounter++, building);
                            }
                        }
                    }
                }
            }
        }

        BatchPickingPlanResponse response = new BatchPickingPlanResponse();

        for (BuildingPlanContext ctx : contextsByBuilding.values()) {
            StorePickingRoutePlanner.Result planned = storeRoutePlanner == null ? null
                    : storeRoutePlanner.plan(ctx.building, ctx.allSteps, startNode);
            PickingPlanResponse buildingPlan;
            if (planned != null) {
                int distance = planned.geometry().distanceMeters == null ? 0 : (int)Math.round(planned.geometry().distanceMeters);
                buildingPlan = buildResponse(planned.route(), ctx, distance);
                buildingPlan.mapRoute = planned.geometry();
            } else {
                // Legacy stores without a geometric plan still use server-side routing.
                List<String> optimizedAisles = getOptimizedAisleSequence(ctx.building, startNode, ctx.uniqueAisleCodes);
                int distance = calculateRouteDistance(ctx.building, startNode, optimizedAisles);
                List<RouteGroupDTO> routeGroups = buildOptimizedRouteGroups(ctx.allSteps, optimizedAisles);
                int number = 1;
                for (RouteGroupDTO group : routeGroups) for (RouteStepDTO step : group.steps) step.stepNumber = number++;
                buildingPlan = buildResponse(routeGroups, ctx, distance);
            }
            buildingPlan.buildingId = ctx.building.id;
            buildingPlan.buildingCode = ctx.building.buildingCode;buildingPlan.buildingType=ctx.building.getBuildingType();

            response.buildingPlans.put(ctx.building.id, buildingPlan);

            response.globalSummary.totalItems += buildingPlan.summary.totalItems;
            response.globalSummary.estimatedDistanceMeters += buildingPlan.summary.estimatedDistanceMeters;
        }

        if(sheetTotes!=null)for(BaseHandlingOrder order:orders) {
            var steps=contextsByBuilding.values().stream().flatMap(ctx->ctx.allSteps.stream()).filter(step->Objects.equals(step.sourceOrderId,order.id)).toList();
            response.toteRequirements.put(order.id,sheetTotes.estimate(order.store,steps));
        }
        return response;
    }

    // --------------------------------------------------
    // Private Helpers
    // --------------------------------------------------

    /**
     * Builds a cache map of LotLine ID -> Building for a specific order.
     */
    private Map<Long, Building> buildLineToBuildingCache(BaseHandlingOrder order) {
        Map<Long, Building> cache = new HashMap<>();
        if (order instanceof PreparingOrder po && po.lots != null) {
            for (Lot lot : po.lots) {
                Building b = resolveBuildingFromLot(lot);
                if (lot != null && lot.lines != null) {
                    for (LotLine line : lot.lines) {
                        cache.put(line.id, b);
                    }
                }
            }
        }
        return cache;
    }

    /**
     * Resolves the building associated with a Lot.
     */
    private Building resolveBuildingFromLot(Lot lot) {
        if (lot != null && lot.chunk != null) {
            return lot.chunk.building;
        }
        return null;
    }

    /**
     * Adds a PickedItem step to the building context map.
     */
    private void addStepToContext(Map<Long, BuildingPlanContext> contexts, PickedItem item, BaseHandlingOrder order, int stepNumber, Building building) {
        if (building == null) return;
        BuildingPlanContext ctx = contexts.computeIfAbsent(building.id, k -> new BuildingPlanContext(building));
        RouteStepDTO step = mapToStepDTO(item, order, stepNumber);
        ctx.allSteps.add(step);
        if (step.aisle != null) ctx.uniqueAisleCodes.add(step.aisle);
        if (item.product != null && item.quantity != null) {
            BigDecimal w = item.product.referenceWeight != null ? item.product.referenceWeight : BigDecimal.ZERO;
            BigDecimal v = item.product.referenceVolume != null ? item.product.referenceVolume : BigDecimal.ZERO;
            ctx.totalWeight = ctx.totalWeight.add(w.multiply(item.quantity));
            ctx.totalVolume = ctx.totalVolume.add(v.multiply(item.quantity));
        }
    }

    /**
     * Adds a LotLine step to the building context map.
     */
    private void addStepToContext(Map<Long, BuildingPlanContext> contexts, LotLine line, BaseHandlingOrder order, int stepNumber, Building building) {
        if (building == null) return;
        BigDecimal remaining=PickingExecution.remainingToCollect(line);
        if(remaining.signum()==0)return;
        BuildingPlanContext ctx = contexts.computeIfAbsent(building.id, k -> new BuildingPlanContext(building));
        RouteStepDTO step = mapToStepDTO(line, order, stepNumber);step.quantity=remaining;
        ctx.allSteps.add(step);
        if (step.aisle != null) ctx.uniqueAisleCodes.add(step.aisle);
        if (line.product != null && line.quantity != null) {
            BigDecimal w = line.product.referenceWeight != null ? line.product.referenceWeight : BigDecimal.ZERO;
            BigDecimal v = line.product.referenceVolume != null ? line.product.referenceVolume : BigDecimal.ZERO;
            ctx.totalWeight = ctx.totalWeight.add(w.multiply(remaining));
            ctx.totalVolume = ctx.totalVolume.add(v.multiply(remaining));
        }
    }

    /**
     * Retrieves the optimized sequence of aisles to visit.
     */
    private List<String> getOptimizedAisleSequence(Building building, String startNode, Set<String> aisles) {
        // building is never null: contexts are only created for non-null buildings
        if (!aisles.isEmpty()) {
            return routeOptimizer.optimizeRoute(building, startNode, new ArrayList<>(aisles));
        }
        return new ArrayList<>(aisles);
    }

    /**
     * Calculates the total distance of the optimized route.
     */
    private int calculateRouteDistance(Building building, String startNode, List<String> route) {
        // building is never null: contexts are only created for non-null buildings
        if (!route.isEmpty()) {
            return routeOptimizer.calculateTotalRouteDistance(building, startNode, route);
        }
        return 0;
    }

    /**
     * Groups steps by aisle and sorts the groups according to the optimized sequence.
     */
    private List<RouteGroupDTO> buildOptimizedRouteGroups(List<RouteStepDTO> allSteps, List<String> optimizedAisleOrder) {
        Map<String, List<RouteStepDTO>> stepsByAisle = allSteps.stream()
                .filter(s -> s.aisle != null)
                .collect(Collectors.groupingBy(s -> s.aisle));

        List<RouteGroupDTO> routeGroups = new ArrayList<>();
        Set<String> addedAisles = new HashSet<>();

        for (String aisleCode : optimizedAisleOrder) {
            List<RouteStepDTO> stepsInAisle = stepsByAisle.get(aisleCode);
            // groupingBy never produces empty groups, so a present key implies a non-empty list
            if (stepsInAisle != null) {
                RouteGroupDTO group = new RouteGroupDTO();
                group.aisleCode = aisleCode;
                group.area = stepsInAisle.get(0).area;
                stepsInAisle.sort(Comparator.comparingInt(s -> s.stepNumber));
                group.steps = stepsInAisle;
                routeGroups.add(group);
                addedAisles.add(aisleCode);
            }
        }

        for (Map.Entry<String, List<RouteStepDTO>> entry : stepsByAisle.entrySet()) {
            if (!addedAisles.contains(entry.getKey())) {
                RouteGroupDTO group = new RouteGroupDTO();
                group.aisleCode = entry.getKey();
                group.area = entry.getValue().get(0).area;
                group.steps = entry.getValue();
                routeGroups.add(group);
            }
        }
        return routeGroups;
    }

    /**
     * Constructs the final response DTO for a single building.
     */
    private PickingPlanResponse buildResponse(List<RouteGroupDTO> routeGroups, BuildingPlanContext context, int distanceMeters) {
        PickingPlanResponse response = new PickingPlanResponse();
        response.route = routeGroups;
        response.summary = new PlanSummaryDTO();
        response.summary.totalItems = context.allSteps.size();
        response.summary.estimatedDistanceMeters = distanceMeters;
        response.capacity = new CapacityDTO();
        response.capacity.totalWeightKg = context.totalWeight;
        response.capacity.totalVolumeLiters = context.totalVolume;
        if(sheetTotes!=null)response.totePreparation=sheetTotes.estimate(Building.findStoreByBuildingId(context.building.id),context.allSteps);
        return response;
    }

    /**
     * Filters an order based on a search query string.
     */
    private boolean filterBySearch(BaseHandlingOrder o, String search) {
        if (search == null || search.isBlank()) return true;
        String s = search.toLowerCase();
        if (o.code != null && o.code.toLowerCase().contains(s)) return true;
        return false;
    }

    // --------------------------------------------------
    // Mapping Helpers
    // --------------------------------------------------

    /**
     * Maps a LotLine entity to a RouteStepDTO.
     */
    private RouteStepDTO mapToStepDTO(LotLine line, BaseHandlingOrder order, int stepNumber) {
        RouteStepDTO step = new RouteStepDTO();
        step.pickedItemId = line.id;
        step.stepNumber = stepNumber;
        step.productId=line.product==null?null:line.product.id;step.productEan=line.product==null?null:line.product.ean;
        step.productName = (line.product != null) ? line.product.name : "Unknown";
        step.quantity = line.quantity;
        step.productType=line.product==null || line.product.productType==null?"UNIT":line.product.productType.name();
        step.unitLabel = switch(com.intermarche.backoffice.domain.order.ProductType.valueOf(step.productType)){case WEIGHT -> "kg";case VOLUME -> "L";default -> "pcs";};
        step.temperatureZone = (line.product != null) ? line.product.temperatureZone : null;
        step.sourceOrderId = order.id;
        step.sourceOrderCode = order.code;

        var rawLocation=line.location!=null?line.location:line.fulfillmentLine==null?null:line.fulfillmentLine.location;
        if (rawLocation!=null && org.hibernate.Hibernate.unproxy(rawLocation) instanceof ShelfLocation shelfLoc) {
            step.sourceLocation=shelfLoc.locationCode;
            step.aisle = shelfLoc.aisle;
            step.unit = shelfLoc.unit;
            step.position = shelfLoc.position;
            step.area = shelfLoc.area;
        }
        return step;
    }

    /**
     * Maps a PickedItem entity to a RouteStepDTO.
     */
    private RouteStepDTO mapToStepDTO(PickedItem item, BaseHandlingOrder order, int stepNumber) {
        RouteStepDTO step = new RouteStepDTO();
        step.pickedItemId = item.id;
        step.stepNumber = stepNumber;
        step.productId=item.product==null?null:item.product.id;step.productEan=item.product==null?null:item.product.ean;
        step.productName = (item.product != null) ? item.product.name : "Unknown";
        step.quantity = item.quantity;
        step.productType=item.product==null || item.product.productType==null?null:item.product.productType.name();
        step.unitLabel=item.product.productType==com.intermarche.backoffice.domain.order.ProductType.WEIGHT?"kg":item.product.productType==com.intermarche.backoffice.domain.order.ProductType.VOLUME?"L":item.product.unitName==null?"pcs":item.product.unitName;
        step.temperatureZone = (item.product != null) ? item.product.temperatureZone : null;
        step.sourceOrderId = order.id;
        step.sourceOrderCode = order.code;

        // item.lotLine is never null here: the caller only maps items whose lotLine resolved a building
        var rawLocation=item.lotLine.location!=null?item.lotLine.location:item.lotLine.fulfillmentLine==null?null:item.lotLine.fulfillmentLine.location;
        if (rawLocation!=null && org.hibernate.Hibernate.unproxy(rawLocation) instanceof ShelfLocation shelfLoc) {
            step.sourceLocation=shelfLoc.locationCode;
            step.aisle = shelfLoc.aisle;
            step.unit = shelfLoc.unit;
            step.position = shelfLoc.position;
            step.area = shelfLoc.area;
        }
        return step;
    }

    /**
     * Maps a BaseHandlingOrder to a MergedOrderDTO.
     */
    private MergedOrderDTO mapToMergeDTO(BaseHandlingOrder o) {
        MergedOrderDTO dto = new MergedOrderDTO();
        dto.id = o.id;
        dto.code = o.code;
        dto.customerName = "Customer";
        dto.totalItems = 0;
        dto.deadline = o.requestedEnd;
        return dto;
    }

    // --------------------------------------------------
    // Inner Classes & DTOs
    // --------------------------------------------------

    /** Internal context object to hold state during plan extraction for a single building. */
    private static class BuildingPlanContext {
        Building building;
        List<RouteStepDTO> allSteps = new ArrayList<>();
        Set<String> uniqueAisleCodes = new HashSet<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalVolume = BigDecimal.ZERO;

        public BuildingPlanContext(Building building) {
            this.building = building;
        }
    }

    /** Response container for picking plans, potentially across multiple buildings. */
    public static class BatchPickingPlanResponse {
        public Map<Long, PickingPlanResponse> buildingPlans = new LinkedHashMap<>();
        public PlanSummaryDTO globalSummary = new PlanSummaryDTO();
        public Map<Long,List<SheetTotePreparation.Requirement>> toteRequirements = new LinkedHashMap<>();
    }

    /** Mergeable order DTO. */
    public static class MergedOrderDTO {
        public Long id;
        public String code;
        public String customerName;
        public int totalItems;
        public LocalDateTime deadline;
    }

    /** Picking plan response for a single building. */
    public static class PickingPlanResponse {
        public Long buildingId;
        public String buildingCode,buildingType;
        public PlanSummaryDTO summary;
        public List<RouteGroupDTO> route;
        public CapacityDTO capacity;
        public StorePickingRoutePlanner.Geometry mapRoute;
        public List<SheetTotePreparation.Requirement> totePreparation = List.of();
    }

    /** Plan summary. */
    public static class PlanSummaryDTO {
        public int totalItems;
        public int estimatedDistanceMeters;
    }

    /** Capacity DTO. */
    public static class CapacityDTO {
        public BigDecimal totalWeightKg;
        public BigDecimal totalVolumeLiters;
    }

    /** Route group. */
    public static class RouteGroupDTO {
        public String aisleCode;
        public String area;
        public List<RouteStepDTO> steps;
    }

    /** Route step. */
    public static class RouteStepDTO {
        public StorePickingRoutePlanner.Point mapPoint;
        public StorePickingRoutePlanner.Point shelfPoint;
        public Long pickedItemId,productId;
        public String productEan;
        public int stepNumber;
        public String planNodeCode;
        public String sourceKind,sourceLocation,sourceSnapshot,sourceToteCode;
        public Long chunkId,destinationBufferId;
        public String productName;
        public BigDecimal quantity;
        public String unitLabel;
        public String productType;
        public BigDecimal sourceAvailable;
        public TemperatureZone temperatureZone;
        public String aisle;
        public String unit;
        public String position;
        public String area;
        public Long sourceOrderId;
        public String sourceOrderCode;
    }
}