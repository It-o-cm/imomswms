package com.intermarche.backoffice.domain.store;

/**
 * Enumeration representing the type of a topological node in a store plan.
 * <p>
 * The node type drives both the visual rendering of the node on the store plan
 * (color, radius) and the navigation rules (e.g., an entrance is a valid
 * start/end point for a picking route).
 * </p>
 */
public enum PlanNodeType {

    /**
     * Entrance / exit point of the building (e.g., customer entrance, dock door).
     */
    ENTRANCE,

    /**
     * Intersection where several aisles meet (e.g., a central hub).
     */
    INTERSECTION,

    /**
     * Dead-end: extremity of an aisle with no further connection.
     */
    DEADEND
}
