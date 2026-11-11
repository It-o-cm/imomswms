package com.intermarche.backoffice.domain.catering;

/**
 * Lifecycle state of a {@link CateringUnit} (rule F3).
 * <p>
 * Each state change increments the unit {@code revision} and the published
 * order revision. The nine states and their entry events are defined in the
 * specification (§ 7, F3).
 */
public enum CateringUnitState {

    /** Created by order recording (I6). */
    RECEIVED,

    /** Blocked: unknown EAN not auto-referenced (I5), catering disabled, or recipe required and missing (F4). */
    BLOCKED,

    /** A fabrication order (or validated collecting order) covers the whole quantity. */
    PLANNED,

    /** First quantity fabricated, staged or picked. */
    IN_PROGRESS,

    /** Whole quantity fabricated, staged or picked. */
    PRODUCED,

    /** Whole quantity deposited in a staging buffer (F15). */
    STAGED,

    /** Order handed over or shipped. */
    HANDED_OVER,

    /** Whole quantity definitively not served (F12). A partially served unit keeps its state and carries a short quantity. */
    SHORT,

    /** Cancelled (F19). */
    CANCELLED
}
