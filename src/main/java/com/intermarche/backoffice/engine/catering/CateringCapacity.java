package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.FabricationStation;
import com.intermarche.backoffice.domain.catering.FabricationStationEmployee;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.workforce.PlanningEntry;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.Hibernate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Computes the effective daily fabrication capacity of a station (rule F7, point T2.038).
 * <p>
 * When a planning exists, the capacity is capped by the planned presence of the station's qualified
 * employees on the day (a presence in another building does not serve the station); otherwise the
 * station's configured daily minutes are used. This keeps the configured capacity as the behaviour
 * when no planning has been published.
 */
@ApplicationScoped
public class CateringCapacity {

    /**
     * Returns the effective daily capacity in minutes of a station on a day.
     *
     * @param station the station.
     * @param day     the day.
     * @return the capacity in minutes (0 means unbounded for the caller's convention).
     */
    public int dayCapacityMinutes(FabricationStation station, LocalDate day) {
        int base = station.dailyMinutes != null ? station.dailyMinutes : 0;
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
        Long stationBuildingId = station.building == null ? null
                : Hibernate.unproxy(station.building, Building.class).id;
        int presence = 0;
        boolean planned = false;
        for (FabricationStationEmployee qualified : FabricationStationEmployee.<FabricationStationEmployee>list("station", station)) {
            Employee employee = Hibernate.unproxy(qualified.employee, Employee.class);
            for (PlanningEntry entry : PlanningEntry.<PlanningEntry>list(
                    "employee = ?1 and cancelled = false and startsAt < ?2 and endsAt > ?3",
                    employee, dayEnd, dayStart)) {
                if (stationBuildingId != null && entry.building != null
                        && !Hibernate.unproxy(entry.building, Building.class).id.equals(stationBuildingId)) {
                    continue; // presence elsewhere does not serve this station
                }
                planned = true;
                LocalDateTime from = entry.startsAt.isBefore(dayStart) ? dayStart : entry.startsAt;
                LocalDateTime to = entry.endsAt.isAfter(dayEnd) ? dayEnd : entry.endsAt;
                if (to.isAfter(from)) {
                    presence += (int) Duration.between(from, to).toMinutes();
                }
            }
        }
        if (!planned) {
            return base;
        }
        return base > 0 ? Math.min(base, presence) : presence;
    }
}
