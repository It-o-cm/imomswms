package com.intermarche.backoffice.domain.catering;

import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.printing.StorePrinter;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.persistence.*;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A fabrication station: the place and crew where catering articles are made
 * (e.g. catering laboratory, pastry), with its calendar and capacity (§ 5, F5, F7).
 * <p>
 * {@code weekdays} is a comma-separated list of ISO weekday numbers (1 = Monday …
 * 7 = Sunday); {@code closedDates} a comma-separated list of ISO dates. A null
 * {@code stagingLeadMinutes} falls back to the policy value.
 */
@Entity
@Table(name = "fabrication_stations",
        uniqueConstraints = @UniqueConstraint(name = "uk_station_store_code", columnNames = {"store_id", "code"}))
public class FabricationStation extends BaseEntity {

    /** The store the station belongs to. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id")
    public Store store;

    /** Business code, unique within the store. */
    @Column(nullable = false, length = 40)
    public String code;

    /** Human-readable name. */
    @Column(nullable = false, length = 200)
    public String name;

    /** Building where the station is located. */
    @ManyToOne
    @JoinColumn(name = "building_id")
    public Building building;

    /** Staging buffer (purpose {@code STAGING}) where the station's finished products wait. */
    @ManyToOne
    @JoinColumn(name = "stagingBuffer_id")
    public Buffer stagingBuffer;

    /** Printer used for the station's labels (optional). */
    @ManyToOne
    @JoinColumn(name = "printer_id")
    public StorePrinter printer;

    /** Open weekdays, comma-separated ISO numbers (1..7); null means every day. */
    @Column(length = 20)
    public String weekdays;

    /** Closed dates, comma-separated ISO dates; null means none. */
    @Column(columnDefinition = "TEXT")
    public String closedDates;

    /** Opening time of day. */
    @Column(name = "opensAt")
    public LocalTime opensAt;

    /** Closing time of day. */
    @Column(name = "closesAt")
    public LocalTime closesAt;

    /** Daily capacity in minutes; 0 means unlimited. */
    public Integer dailyMinutes;

    /** Lead minutes before pickup for staging; null falls back to the policy. */
    public Integer stagingLeadMinutes;

    /** Maximum planned minutes per fabrication order. */
    public Integer maxOrderMinutes;

    /** Whether the station is active. */
    @Column(nullable = false)
    public boolean active = true;

    /**
     * Computes the checksum from the station's business fields.
     *
     * @return an integer checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(
                store != null ? store.id : null,
                code, name,
                building != null ? building.id : null,
                stagingBuffer != null ? stagingBuffer.id : null,
                printer != null ? printer.id : null,
                weekdays, closedDates, opensAt, closesAt,
                dailyMinutes, stagingLeadMinutes, maxOrderMinutes, active);
    }
}
