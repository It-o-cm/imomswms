package com.intermarche.backoffice.domain.store;
import jakarta.persistence.*;
/** Commercial goods physically enclosed in one identified parcel, never available to shoppers. */
@Entity @DiscriminatorValue("PARCEL_STOCK") public class ParcelStockLocation extends ShelfLocation {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public com.intermarche.backoffice.domain.shipping.Parcel parcel;
 @Override @PrePersist @PreUpdate public void onCreate(){String explicit=locationCode;super.onCreate();if(explicit!=null&&explicit.startsWith("COL-"))locationCode=explicit;}
}
