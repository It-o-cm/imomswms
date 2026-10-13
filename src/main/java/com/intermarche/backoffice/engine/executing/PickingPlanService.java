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

    @Inject
    PickingRouteOptimizer routeOptimizer;

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

            if (exec instanceof PickingExecution pe) {
                // SCENARIO A: Execution exists -> Use remaining PickedItems
                Map<Long, Building> lineToBuildingCache = buildLineToBuildingCache(pe.handlingOrder);
                if (pe.itemsToPick != null) {
                    for (PickedItem item : pe.itemsToPick) {
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
                        if (lot.lines != null) {
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
            List<String> optimizedAisles = getOptimizedAisleSequence(ctx.building, startNode, ctx.uniqueAisleCodes);
            int distance = calculateRouteDistance(ctx.building, startNode, optimizedAisles);
            List<RouteGroupDTO> routeGroups = buildOptimizedRouteGroups(ctx.allSteps, optimizedAisles);

            PickingPlanResponse buildingPlan = buildResponse(routeGroups, ctx, distance);
            buildingPlan.buildingId = ctx.building.id;
            buildingPlan.buildingCode = ctx.building.buildingCode;

            response.buildingPlans.put(ctx.building.id, buildingPlan);

            response.globalSummary.totalItems += buildingPlan.summary.totalItems;
            response.globalSummary.estimatedDistanceMeters += buildingPlan.summary.estimatedDistanceMeters;
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
                if (lot.lines != null) {
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
        BuildingPlanContext ctx = contexts.computeIfAbsent(building.id, k -> new BuildingPlanContext(building));
        RouteStepDTO step = mapToStepDTO(line, order, stepNumber);
        ctx.allSteps.add(step);
        if (step.aisle != null) ctx.uniqueAisleCodes.add(step.aisle);
        if (line.product != null && line.quantity != null) {
            BigDecimal w = line.product.referenceWeight != null ? line.product.referenceWeight : BigDecimal.ZERO;
            BigDecimal v = line.product.referenceVolume != null ? line.product.referenceVolume : BigDecimal.ZERO;
            ctx.totalWeight = ctx.totalWeight.add(w.multiply(line.quantity));
            ctx.totalVolume = ctx.totalVolume.add(v.multiply(line.quantity));
        }
    }

    /**
     * Retrieves the optimized sequence of aisles to visit.
     */
    private List<String> getOptimizedAisleSequence(Building building, String startNode, Set<String> aisles) {
        if (building != null && !aisles.isEmpty()) {
            return routeOptimizer.optimizeRoute(building, startNode, new ArrayList<>(aisles));
        }
        return new ArrayList<>(aisles);
    }

    /**
     * Calculates the total distance of the optimized route.
     */
    private int calculateRouteDistance(Building building, String startNode, List<String> route) {
        if (building != null && !route.isEmpty()) {
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
            if (stepsInAisle != null && !stepsInAisle.isEmpty()) {
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
                group.area = entry.getValue().isEmpty() ? "" : entry.getValue().get(0).area;
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
        step.productName = (line.product != null) ? line.product.name : "Unknown";
        step.quantity = line.quantity;
        step.unitLabel = "pcs";
        step.temperatureZone = (line.product != null) ? line.product.temperatureZone : null;
        step.sourceOrderId = order.id;
        step.sourceOrderCode = order.code;

        if (line.fulfillmentLine != null && line.fulfillmentLine.location instanceof ShelfLocation shelfLoc) {
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
        step.productName = (item.product != null) ? item.product.name : "Unknown";
        step.quantity = item.quantity;
        step.unitLabel = "pcs";
        step.temperatureZone = (item.product != null) ? item.product.temperatureZone : null;
        step.sourceOrderId = order.id;
        step.sourceOrderCode = order.code;

        if (item.lotLine != null && item.lotLine.fulfillmentLine != null && item.lotLine.fulfillmentLine.location instanceof ShelfLocation shelfLoc) {
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
        public String buildingCode;
        public PlanSummaryDTO summary;
        public List<RouteGroupDTO> route;
        public CapacityDTO capacity;
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
        public Long pickedItemId;
        public int stepNumber;
        public String productName;
        public BigDecimal quantity;
        public String unitLabel;
        public TemperatureZone temperatureZone;
        public String aisle;
        public String unit;
        public String position;
        public String area;
        public Long sourceOrderId;
        public String sourceOrderCode;
    }
}