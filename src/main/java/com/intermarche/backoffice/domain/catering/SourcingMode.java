package com.intermarche.backoffice.domain.catering;

/**
 * How a catering article is obtained by the store.
 * <p>
 * Resolved onto each {@link CateringUnit} at reception (rule F2) and then frozen:
 * a later referential change never moves an already-planned unit.
 */
public enum SourcingMode {

    /** Manufactured by the store at a {@link FabricationStation}. */
    FABRICATED,

    /** Ordered from a supplier for the order, received, staged at a station, then handed over. */
    SUPPLIED,

    /** Picked from the store's own dated stock. */
    STOCKED
}
