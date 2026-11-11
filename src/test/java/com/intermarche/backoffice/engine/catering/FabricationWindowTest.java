package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.FabricationStation;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the fabrication window (F5): each bound, the present-instant clamp, closed days and dates,
 * opening hours, the use-by control with and without a reception date, the pushed start, the empty
 * window, and the two worked examples of the specification.
 */
class FabricationWindowTest {

    private final FabricationWindow windows = new FabricationWindow();

    /**
     * Builds a station open from 06:00 to 14:00, Monday to Saturday.
     *
     * @return the station.
     */
    private FabricationStation lab() {
        FabricationStation station = new FabricationStation();
        station.weekdays = "1,2,3,4,5,6";
        station.opensAt = LocalTime.of(6, 0);
        station.closesAt = LocalTime.of(14, 0);
        return station;
    }

    /** Saturday 21 November 2026, 10:00 — the slot start of the worked examples. */
    private static final LocalDateTime SATURDAY_10 = LocalDateTime.of(2026, 11, 21, 10, 0);
    /** A present instant well before the windows. */
    private static final LocalDateTime EARLY = LocalDateTime.of(2026, 11, 18, 8, 0);

    /**
     * T2.028: the pain-surprise example (advance 24 h, staging 60 min, open 06:00-14:00) yields
     * Friday 10:00-14:00 and Saturday 06:00-09:00.
     */
    @Test
    void painSurpriseExample() {
        var window = windows.compute(SATURDAY_10, null, SATURDAY_10.plusHours(1), 60, 24, 48, 6, lab(), EARLY);
        assertFalse(window.empty());
        assertEquals(2, window.ranges().size());
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), window.ranges().get(0).start());
        assertEquals(LocalDateTime.of(2026, 11, 20, 14, 0), window.ranges().get(0).end());
        assertEquals(LocalDateTime.of(2026, 11, 21, 6, 0), window.ranges().get(1).start());
        assertEquals(LocalDateTime.of(2026, 11, 21, 9, 0), window.ranges().get(1).end());
    }

    /**
     * T2.028: the cake example (advance 24 h, shelf life 72 h, reception date) yields the same window,
     * and the use-by covers the end of the reception day.
     */
    @Test
    void cakeExampleSameWindowAndUseByCovered() {
        LocalDate reception = LocalDate.of(2026, 11, 21);
        var window = windows.compute(SATURDAY_10, reception, SATURDAY_10.plusHours(1), 60, 24, 72, 6, lab(), EARLY);
        assertEquals(2, window.ranges().size());
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), window.ranges().get(0).start());
        // the latest end plus the shelf life reaches the end of the reception day
        assertTrue(window.latestEnd().plusHours(72).isAfter(reception.atTime(23, 59, 0)));
    }

    /**
     * T2.026: the bounds — latest end is S minus staging, earliest start is S minus advance.
     */
    @Test
    void boundsAreStagingAndAdvance() {
        var window = windows.compute(SATURDAY_10, null, SATURDAY_10, 60, 24, 240, 6, lab(), EARLY);
        assertEquals(SATURDAY_10.minusMinutes(60), window.latestEnd());
        assertEquals(SATURDAY_10.minusHours(24), window.earliestStart());
    }

    /**
     * T2.026: the earliest start is clamped to the present instant.
     */
    @Test
    void earliestStartClampedToNow() {
        LocalDateTime now = SATURDAY_10.minusHours(3);
        var window = windows.compute(SATURDAY_10, null, SATURDAY_10, 60, 24, 240, 6, lab(), now);
        assertEquals(now, window.earliestStart());
    }

    /**
     * T2.026: a closed weekday is excluded.
     */
    @Test
    void closedWeekdayExcluded() {
        FabricationStation station = lab();
        station.weekdays = "6"; // Saturday only
        var window = windows.compute(SATURDAY_10, null, SATURDAY_10, 60, 24, 240, 6, station, EARLY);
        assertEquals(1, window.days().size());
        assertEquals(LocalDate.of(2026, 11, 21), window.days().get(0));
    }

    /**
     * T2.026: a closed date is excluded.
     */
    @Test
    void closedDateExcluded() {
        FabricationStation station = lab();
        station.closedDates = "2026-11-20";
        var window = windows.compute(SATURDAY_10, null, SATURDAY_10, 60, 24, 240, 6, station, EARLY);
        assertFalse(window.days().contains(LocalDate.of(2026, 11, 20)));
        assertTrue(window.days().contains(LocalDate.of(2026, 11, 21)));
    }

    /**
     * T2.026: the ranges are clamped to the opening and closing hours.
     */
    @Test
    void rangesClampedToOpeningHours() {
        var window = windows.compute(SATURDAY_10, null, SATURDAY_10, 60, 24, 240, 6, lab(), EARLY);
        assertTrue(window.ranges().stream().allMatch(r ->
                !r.start().toLocalTime().isBefore(LocalTime.of(6, 0)) && !r.end().toLocalTime().isAfter(LocalTime.of(14, 0))));
    }

    /**
     * T2.027: without a reception date the deadline uses the slot end plus the margin; the start is
     * pushed so the use-by stays valid.
     */
    @Test
    void deadlineWithoutReceptionDatePushesStart() {
        // Short shelf life forces the start later than S minus advance.
        var window = windows.compute(SATURDAY_10, null, SATURDAY_10.plusHours(1), 60, 24, 10, 6, lab(), EARLY);
        // deadline = slot end 11:00 + 6 h = 17:00 ; deadlineStart = 17:00 - 10 h = 07:00 (same day)
        assertEquals(LocalDateTime.of(2026, 11, 21, 7, 0), window.earliestStart());
    }

    /**
     * T2.027: an empty window (latest end before earliest start) yields no range.
     */
    @Test
    void emptyWindow() {
        // staging lead larger than the advance leaves no room.
        var window = windows.compute(SATURDAY_10, null, SATURDAY_10, 60, 0, 240, 6, lab(), EARLY);
        assertTrue(window.empty());
    }
}
