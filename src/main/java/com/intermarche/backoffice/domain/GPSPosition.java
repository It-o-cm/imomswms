package com.intermarche.backoffice.domain;

import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Value Object representing a physical Geolocation.
 * <p>
 * Contains GPS coordinates (Latitude, Longitude).
 * This is an {@code @Embeddable} object intended to be used alongside
 * {@link Adresse} when physical positioning is required (e.g., for a Building).
 */
@Embeddable
public class GPSPosition {

    /**
     * GPS Latitude coordinate.
     */
    public Double latitude;

    /**
     * GPS Longitude coordinate.
     */
    public Double longitude;

    /**
     * Default constructor required by JPA.
     */
    public GPSPosition() {
    }

    /**
     * Constructor with coordinates.
     *
     * @param latitude  The latitude.
     * @param longitude The longitude.
     */
    public GPSPosition(Double latitude, Double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /**
     * Checks if this location is equal to another object.
     *
     * @param o The object to compare with.
     * @return true if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GPSPosition position = (GPSPosition) o;
        return Objects.equals(latitude, position.latitude) &&
                Objects.equals(longitude, position.longitude);
    }

    /**
     * Calculates the hash code for this location.
     *
     * @return The hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(latitude, longitude);
    }

    /**
     * Calculates a checksum based on the coordinates.
     *
     * @return Checksum integer value.
     */
    public int getChecksum() {
        return Objects.hash(latitude, longitude);
    }
}