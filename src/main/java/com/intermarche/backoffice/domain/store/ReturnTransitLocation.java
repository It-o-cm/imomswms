package com.intermarche.backoffice.domain.store;
import jakarta.persistence.*;
/** Physical custody in transit, always excluded from promise by its segment quality. */
@Entity @DiscriminatorValue("RETURN_TRANSIT") public class ReturnTransitLocation extends ShelfLocation { @Override @PrePersist @PreUpdate public void onCreate(){String explicit=locationCode;super.onCreate();if(explicit!=null&&explicit.startsWith("TRANSIT-RET-"))locationCode=explicit;}
}
