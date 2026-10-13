package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.Building;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to optimize the picking route using the Nearest Insertion heuristic.
 */
@ApplicationScoped
public class PickingRouteOptimizer {

    private final Map<String, Double> distanceCache = new ConcurrentHashMap<>();
    private Map<String, Aisle> aisleMap = new HashMap<>();

    /**
     * Optimizes the visiting order of aisles.
     * <p>
     * If startNode is null, automatically picks the first aisle as a virtual start
     * to ensure relative path optimization still occurs.
     *
     * @param building      The building topology context.
     * @param startNode     The starting node code (can be null).
     * @param targetAisles  The list of unique aisle codes to visit.
     * @return An ordered list of aisle codes representing the optimized route.
     */
    @Transactional
    public List<String> optimizeRoute(Building building, String startNode, List<String> targetAisles) {
        if (targetAisles == null || targetAisles.isEmpty()) {
            return Collections.emptyList();
        }

        loadAisles(building);

        // Work on a copy
        List<String> remainingAisles = new ArrayList<>(targetAisles);

        // ---------------------------------------------------------
        // LOGIQUE DE FALLBACK : Si pas de startNode, on en "crée" un
        // ---------------------------------------------------------
        String effectiveStartNode = startNode;
        boolean isVirtualStart = false;

        if (effectiveStartNode == null || effectiveStartNode.isBlank()) {
            // On prend la première allée disponible comme point de départ arbitraire
            // Cela permet d'optimiser le chemin relatif entre les allées
            effectiveStartNode = remainingAisles.get(0);
            isVirtualStart = true;
        }

        // 1. Initialization: Find the nearest aisle to the start
        String firstNode = findNearest(effectiveStartNode, remainingAisles);
        List<String> route = new ArrayList<>();

        // Si on a démarré virtuellement sur une allée cible, on ne l'ajoute pas deux fois
        // Mais si le 'firstNode' trouvé est différent du 'virtualStart', on l'ajoute normalement.
        // NOTE: Si isVirtualStart est true, effectiveStartNode était dans remainingAisles.
        // findNearest va retourner ce même node (distance 0).
        // On doit donc le retirer de remainingAisles.

        route.add(firstNode);
        remainingAisles.remove(firstNode);

        // 2. Insertion Loop
        while (!remainingAisles.isEmpty()) {
            String bestCandidate = null;
            int bestPosition = -1;
            double minCost = Double.MAX_VALUE;

            for (String candidate : remainingAisles) {
                for (int position = 0; position <= route.size(); position++) {
                    // On utilise effectiveStartNode pour le calcul du coût en position 0
                    double cost = calculateInsertionCost(route, candidate, position, effectiveStartNode);
                    if (cost < minCost) {
                        minCost = cost;
                        bestCandidate = candidate;
                        bestPosition = position;
                    }
                }
            }

            if (bestCandidate != null) {
                route.add(bestPosition, bestCandidate);
                remainingAisles.remove(bestCandidate);
            } else {
                // Fallback
                route.add(remainingAisles.remove(0));
            }
        }
        return route;
    }

    /**
     * Calculates the total physical distance of a specific route path.
     *
     * @param building      The building context.
     * @param startNode     The starting node (can be null).
     * @param orderedRoute  The ordered list of aisle codes.
     * @return The total distance in meters.
     */
    public int calculateTotalRouteDistance(Building building, String startNode, List<String> orderedRoute) {
        if (orderedRoute == null || orderedRoute.isEmpty()) {
            return 0;
        }

        loadAisles(building);

        // ---------------------------------------------------------
        // LOGIQUE DE FALLBACK pour le calcul de distance
        // ---------------------------------------------------------
        String effectiveStartNode = startNode;
        if (effectiveStartNode == null || effectiveStartNode.isBlank()) {
            // Si pas de startNode, on considère que le départ est la première allée du parcours.
            // La distance "avant le début" est donc 0.
            effectiveStartNode = orderedRoute.get(0);
        }

        double totalDistance = 0.0;
        String previousNode = effectiveStartNode;

        for (String currentNode : orderedRoute) {
            totalDistance += getDistance(previousNode, currentNode);
            previousNode = currentNode;
        }

        return (int) Math.round(totalDistance);
    }

    // --------------------------------------------------
    // Internal Calculation Logic
    // --------------------------------------------------

    /**
     * Loads all aisles for a specific building into the internal map.
     */
    private void loadAisles(Building building) {
        this.aisleMap.clear();
        this.distanceCache.clear();

        if (building == null) return;

        List<Aisle> allAisles = Aisle.list("building", building);
        for (Aisle a : allAisles) {
            aisleMap.put(a.code, a);
        }
    }

    /**
     * Finds the aisle closest to a specific starting node.
     */
    private String findNearest(String startNode, List<String> candidates) {
        String nearest = null;
        double minDistance = Double.MAX_VALUE;
        for (String candidate : candidates) {
            double dist = getDistance(startNode, candidate);
            // Strictly less to prefer the candidate itself if startNode == candidate (dist=0)
            if (dist < minDistance) {
                minDistance = dist;
                nearest = candidate;
            }
        }
        // Fallback if calculation failed (e.g. no connectivity)
        return (nearest != null) ? nearest : candidates.get(0);
    }

    /**
     * Calculates the incremental cost of inserting a candidate aisle at a specific position.
     */
    private double calculateInsertionCost(List<String> currentPath, String candidate, int position, String startNode) {
        double existingCost = getRouteCost(startNode, currentPath, position);
        double linkOutCost = 0.0;

        if (position < currentPath.size()) {
            String nextNode = currentPath.get(position);
            String prevNode = (position == 0) ? startNode : currentPath.get(position - 1);
            linkOutCost = getDistance(prevNode, nextNode);
        }

        String prevNode = (position == 0) ? startNode : currentPath.get(position - 1);
        double linkInCost = getDistance(prevNode, candidate);
        double tailCost = 0.0;

        if (position < currentPath.size()) {
            String nextNode = currentPath.get(position);
            tailCost = getDistance(candidate, nextNode);
        }

        return existingCost - linkOutCost + linkInCost + tailCost;
    }

    /**
     * Calculates the total cost of a path up to a certain index.
     */
    private double getRouteCost(String startNode, List<String> path, int limitIndex) {
        double total = 0.0;
        String prev = startNode;
        for (int i = 0; i < limitIndex; i++) {
            total += getDistance(prev, path.get(i));
            prev = path.get(i);
        }
        return total;
    }

    /**
     * Calculates the physical distance between two nodes.
     */
    private double getDistance(String fromCode, String toCode) {
        if (fromCode.equals(toCode)) return 0.0;

        String key = fromCode + "|" + toCode;
        if (distanceCache.containsKey(key)) {
            return distanceCache.get(key);
        }

        List<String> path = runDijkstra(fromCode, toCode);
        double totalDistance = 0.0;

        for (String nodeCode : path) {
            Aisle aisle = aisleMap.get(nodeCode);
            if (aisle != null && aisle.lengthMeters != null) {
                totalDistance += aisle.lengthMeters;
            }
        }

        distanceCache.put(key, totalDistance);
        distanceCache.put(toCode + "|" + fromCode, totalDistance);
        return totalDistance;
    }

    /**
     * Internal implementation of Dijkstra's algorithm.
     */
    private List<String> runDijkstra(String startCode, String endCode) {
        PriorityQueue<PathNode> queue = new PriorityQueue<>(Comparator.comparingDouble(n -> n.distance));
        Map<String, Double> distances = new HashMap<>();
        Map<String, String> predecessors = new HashMap<>();

        queue.add(new PathNode(startCode, 0.0));
        distances.put(startCode, 0.0);

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (current.code.equals(endCode)) {
                return reconstructPath(predecessors, endCode);
            }

            if (current.distance > distances.getOrDefault(current.code, Double.MAX_VALUE)) continue;

            for (String neighbor : getNeighbors(current.code)) {
                double edgeCost = calculateEdgeCost(current.code, neighbor);
                double newDist = distances.get(current.code) + edgeCost;

                if (newDist < distances.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    distances.put(neighbor, newDist);
                    predecessors.put(neighbor, current.code);
                    queue.add(new PathNode(neighbor, newDist));
                }
            }
        }
        return Collections.emptyList();
    }

    /**
     * Retrieves the set of neighbor node codes for a given node.
     */
    private Set<String> getNeighbors(String nodeCode) {
        Set<String> neighbors = new HashSet<>();
        Aisle aisle = aisleMap.get(nodeCode);
        if (aisle != null) {
            if (aisle.connectedToHead != null) neighbors.addAll(Arrays.asList(aisle.connectedToHead.split("\\,")));
            if (aisle.connectedToTail != null) neighbors.addAll(Arrays.asList(aisle.connectedToTail.split("\\,")));
        }
        return neighbors;
    }

    /**
     * Calculates the cost (distance) to move from one node to a direct neighbor.
     */
    private double calculateEdgeCost(String fromCode, String toCode) {
        Aisle from = aisleMap.get(fromCode);
        Aisle to = aisleMap.get(toCode);
        double lenFrom = (from != null && from.lengthMeters != null) ? from.lengthMeters : 0.0;
        double lenTo = (to != null && to.lengthMeters != null) ? to.lengthMeters : 0.0;
        return (lenFrom / 2.0) + (lenTo / 2.0);
    }

    /**
     * Reconstructs the path from the predecessors map.
     */
    private List<String> reconstructPath(Map<String, String> predecessors, String endCode) {
        LinkedList<String> path = new LinkedList<>();
        String current = endCode;
        while (current != null) {
            path.addFirst(current);
            current = predecessors.get(current);
        }
        return path;
    }

    /**
     * Helper class for PriorityQueue entries.
     */
    private static class PathNode {
        String code;
        double distance;
        PathNode(String code, double distance) { this.code = code; this.distance = distance; }
    }
}