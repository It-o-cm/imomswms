package com.intermarche.backoffice.domain.store;
import jakarta.persistence.*;
/** Forecast address only. Never a physical put-away target. */
@Entity @DiscriminatorValue("PENDING_SUPPLY")
public class PendingSupplyLocation extends ShelfLocation {}
