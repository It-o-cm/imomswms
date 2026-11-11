package com.intermarche.backoffice.engine.catering;

import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.FabricationStation;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validates the station (A), article (P) and variant (V) catering parameters at
 * referential save time (§ 6, T0.045). Each failure throws an
 * {@link IllegalArgumentException} carrying the parameter label; the referential
 * resources turn it into a 400.
 */
public final class CateringReferentialValidation {

    /** Allowed cancellation dispositions. */
    private static final Set<String> DISPOSITIONS = Set.of("REALLOCATE", "ISOLATE");

    private CateringReferentialValidation() {
    }

    /**
     * Validates an integer parameter against inclusive bounds.
     *
     * @param value the value, or null to skip.
     * @param min   the inclusive minimum.
     * @param max   the inclusive maximum.
     * @param label the parameter label used in the error message.
     */
    public static void intRange(Integer value, int min, int max, String label) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(label + " : valeur hors bornes [" + min + ", " + max + "]");
        }
    }

    /**
     * Validates {@code laborSeconds} (V): 0 to 86 400.
     *
     * @param value the value, or null.
     */
    public static void laborSeconds(Integer value) {
        intRange(value, 0, 86400, "laborSeconds (temps de fabrication)");
    }

    /**
     * Validates {@code shelfLifeHours} (P override): 1 to 720.
     *
     * @param value the value, or null.
     */
    public static void shelfLifeHours(Integer value) {
        intRange(value, 1, 720, "shelfLifeHours (durée de conservation)");
    }

    /**
     * Validates {@code maxAdvanceHours} (P override): 0 to 720 and not greater than the shelf life.
     *
     * @param value         the value, or null.
     * @param shelfLifeHours the resolved shelf life, or null if unknown.
     */
    public static void maxAdvanceHours(Integer value, Integer shelfLifeHours) {
        intRange(value, 0, 720, "maxAdvanceHours (avance maximale)");
        if (value != null && shelfLifeHours != null && value > shelfLifeHours) {
            throw new IllegalArgumentException("maxAdvanceHours (avance maximale) : doit rester inférieur ou égal à shelfLifeHours");
        }
    }

    /**
     * Validates {@code supplierLeadDays} (P override): 0 to 30.
     *
     * @param value the value, or null.
     */
    public static void supplierLeadDays(Integer value) {
        intRange(value, 0, 30, "supplierLeadDays (délai fournisseur)");
    }

    /**
     * Validates {@code stagingLeadMinutes} (A override): 0 to 1 440.
     *
     * @param value the value, or null.
     */
    public static void stagingLeadMinutes(Integer value) {
        intRange(value, 0, 1440, "stagingLeadMinutes (délai de mise en attente)");
    }

    /**
     * Validates {@code dailyMinutes} (A): 0 to 10 000.
     *
     * @param value the value, or null.
     */
    public static void dailyMinutes(Integer value) {
        intRange(value, 0, 10000, "dailyMinutes (capacité journalière)");
    }

    /**
     * Validates {@code maxOrderMinutes} (A): 15 to 600.
     *
     * @param value the value, or null.
     */
    public static void maxOrderMinutes(Integer value) {
        intRange(value, 15, 600, "maxOrderMinutes (durée maximale d’un ordre)");
    }

    /**
     * Validates {@code weekdays} (A): a comma-separated list of ISO weekday numbers 1 to 7.
     *
     * @param value the comma-separated value, or null.
     */
    public static void weekdays(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int day;
            try {
                day = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("weekdays (jours ouvrés) : jour invalide « " + trimmed + " »");
            }
            if (day < 1 || day > 7) {
                throw new IllegalArgumentException("weekdays (jours ouvrés) : jour hors bornes [1, 7]");
            }
        }
    }

    /**
     * Validates {@code closedDates} (A): a comma-separated list of ISO dates.
     *
     * @param value the comma-separated value, or null.
     */
    public static void closedDates(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                LocalDate.parse(trimmed);
            } catch (Exception e) {
                throw new IllegalArgumentException("closedDates (dates de fermeture) : date invalide « " + trimmed + " »");
            }
        }
    }

    /**
     * Validates the opening hours (A): the opening time must be strictly before the closing time.
     *
     * @param opensAt  the opening time, or null.
     * @param closesAt the closing time, or null.
     */
    public static void openingHours(LocalTime opensAt, LocalTime closesAt) {
        if (opensAt != null && closesAt != null && !opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("opensAt/closesAt (horaires) : l’ouverture doit précéder la fermeture");
        }
    }

    /**
     * Validates {@code cancelDispositions} (P override): a non-empty, comma-separated subset
     * of {@code REALLOCATE}, {@code ISOLATE}.
     *
     * @param value the comma-separated value, or null to skip.
     */
    public static void cancelDispositions(String value) {
        if (value == null) {
            return;
        }
        var seen = new LinkedHashSet<String>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!DISPOSITIONS.contains(trimmed)) {
                throw new IllegalArgumentException("cancelDispositions (devenirs d’annulation) : valeur inconnue « " + trimmed + " »");
            }
            seen.add(trimmed);
        }
        if (seen.isEmpty()) {
            throw new IllegalArgumentException("cancelDispositions (devenirs d’annulation) : sous-ensemble non vide requis");
        }
    }

    /**
     * Validates {@code sourcingMode} (P): it must be set.
     *
     * @param mode the obtention mode, or null.
     */
    public static void sourcingMode(SourcingMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("sourcingMode (mode d’obtention) : valeur requise");
        }
    }

    /**
     * Validates all station (A) parameters of a fabrication station.
     *
     * @param station the station to validate.
     */
    public static void station(FabricationStation station) {
        weekdays(station.weekdays);
        closedDates(station.closedDates);
        openingHours(station.opensAt, station.closesAt);
        dailyMinutes(station.dailyMinutes);
        maxOrderMinutes(station.maxOrderMinutes);
        stagingLeadMinutes(station.stagingLeadMinutes);
    }

    /**
     * Validates all article (P) override parameters of a catering datasheet.
     *
     * @param item the datasheet to validate.
     */
    public static void item(CateringItem item) {
        sourcingMode(item.sourcingMode);
        shelfLifeHours(item.shelfLifeHours);
        maxAdvanceHours(item.maxAdvanceHours, item.shelfLifeHours);
        supplierLeadDays(item.supplierLeadDays);
        cancelDispositions(item.cancelDispositions);
    }
}
