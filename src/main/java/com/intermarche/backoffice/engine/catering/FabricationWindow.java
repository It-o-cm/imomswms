package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.FabricationStation;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the fabrication window of a FABRICATED unit (F5).
 * <p>
 * Latest end is the availability minus the staging lead; earliest start is the availability minus the
 * maximum advance, never before the present instant, and pushed later so that
 * {@code fabrication end + shelf life} still reaches the use-by deadline (end of the reception day,
 * or slot end plus the consumption margin). The admissible ranges are the station's open hours
 * (weekdays outside the closed dates, between opening and closing) intersected with that interval.
 * An empty window leaves the unit RECEIVED (situation {@code FABRICATION_WINDOW}); the server never
 * moves the availability.
 */
@ApplicationScoped
public class FabricationWindow {

    /**
     * An admissible open range.
     *
     * @param start the range start.
     * @param end   the range end.
     */
    public record Range(LocalDateTime start, LocalDateTime end) {
    }

    /**
     * The computed window.
     *
     * @param earliestStart the earliest fabrication start.
     * @param latestEnd     the latest fabrication end.
     * @param ranges        the admissible open ranges, earliest first.
     */
    public record Window(LocalDateTime earliestStart, LocalDateTime latestEnd, List<Range> ranges) {

        /**
         * Whether the window is empty.
         *
         * @return true if there is no admissible range.
         */
        public boolean empty() {
            return ranges.isEmpty();
        }

        /**
         * The admissible days, earliest first.
         *
         * @return the distinct sorted days.
         */
        public List<LocalDate> days() {
            return ranges.stream().map(r -> r.start().toLocalDate()).distinct().sorted().toList();
        }
    }

    /**
     * Computes the fabrication window (F5).
     *
     * @param availability          the pickup slot start (S).
     * @param receptionDate         the reception date, or null.
     * @param slotEnd               the pickup slot end, or null (falls back to availability).
     * @param stagingLeadMinutes    the staging lead minutes.
     * @param maxAdvanceHours       the maximum advance hours.
     * @param shelfLifeHours        the shelf life hours.
     * @param consumptionMarginHours the consumption margin hours.
     * @param station               the fabrication station.
     * @param now                   the present instant.
     * @return the window.
     */
    public Window compute(LocalDateTime availability, LocalDate receptionDate, LocalDateTime slotEnd,
                          int stagingLeadMinutes, int maxAdvanceHours, int shelfLifeHours, int consumptionMarginHours,
                          FabricationStation station, LocalDateTime now) {
        LocalDateTime latestEnd = availability.minusMinutes(stagingLeadMinutes);
        LocalDateTime deadline = receptionDate != null
                ? receptionDate.atTime(23, 59, 59)
                : (slotEnd != null ? slotEnd : availability).plusHours(consumptionMarginHours);
        LocalDateTime earliestStart = availability.minusHours(maxAdvanceHours);
        if (earliestStart.isBefore(now)) {
            earliestStart = now;
        }
        LocalDateTime deadlineStart = deadline.minusHours(shelfLifeHours);
        if (earliestStart.isBefore(deadlineStart)) {
            earliestStart = deadlineStart;
        }
        List<Range> ranges = new ArrayList<>();
        if (!earliestStart.isAfter(latestEnd)) {
            Set<Integer> weekdays = parseWeekdays(station.weekdays);
            Set<LocalDate> closed = parseClosedDates(station.closedDates);
            LocalTime opensAt = station.opensAt != null ? station.opensAt : LocalTime.MIN;
            LocalTime closesAt = station.closesAt != null ? station.closesAt : LocalTime.of(23, 59, 59);
            LocalDate last = latestEnd.toLocalDate();
            for (LocalDate day = earliestStart.toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
                if (weekdays != null && !weekdays.contains(day.getDayOfWeek().getValue())) {
                    continue;
                }
                if (closed.contains(day)) {
                    continue;
                }
                LocalDateTime open = LocalDateTime.of(day, opensAt);
                LocalDateTime close = LocalDateTime.of(day, closesAt);
                LocalDateTime start = open.isAfter(earliestStart) ? open : earliestStart;
                LocalDateTime end = close.isBefore(latestEnd) ? close : latestEnd;
                if (!start.isAfter(end)) {
                    ranges.add(new Range(start, end));
                }
            }
        }
        return new Window(earliestStart, latestEnd, ranges);
    }

    /**
     * Parses the comma-separated ISO weekday numbers of a station, or null for every day.
     *
     * @param weekdays the comma-separated value.
     * @return the set of weekday numbers, or null.
     */
    private Set<Integer> parseWeekdays(String weekdays) {
        if (weekdays == null || weekdays.isBlank()) {
            return null;
        }
        var result = new HashSet<Integer>();
        for (String token : weekdays.split(",")) {
            if (!token.isBlank()) {
                result.add(Integer.parseInt(token.trim()));
            }
        }
        return result;
    }

    /**
     * Parses the comma-separated ISO dates of a station.
     *
     * @param closedDates the comma-separated value.
     * @return the set of closed dates.
     */
    private Set<LocalDate> parseClosedDates(String closedDates) {
        var result = new HashSet<LocalDate>();
        if (closedDates != null && !closedDates.isBlank()) {
            for (String token : closedDates.split(",")) {
                if (!token.isBlank()) {
                    result.add(LocalDate.parse(token.trim()));
                }
            }
        }
        return result;
    }
}
