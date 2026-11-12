package com.intermarche.backoffice.domain.store;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import jakarta.persistence.*;
/** Stock remains in the same building, but is no longer accessible to shoppers. */
@Entity @DiscriminatorValue("PICKING_TOTE")
public class PickingToteLocation extends ShelfLocation {
    // Null denotes a pending assignment. Physical stock may only enter an assigned tote.
    @ManyToOne public LoadedTote protectedTote;
    /** Commercial picked goods already have PickedItem contents; do not present them again as loose buffer stock. */
    @Column(name="picking_trace_only") public boolean traceOnly;
    @Override @PrePersist @PreUpdate public void onCreate(){String explicit=locationCode;super.onCreate();if(explicit!=null && (explicit.startsWith("BAC-") || explicit.startsWith("SEC-")))locationCode=explicit;}
}
