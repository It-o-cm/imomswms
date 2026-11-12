package com.intermarche.backoffice.domain.store;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import jakarta.persistence.*;
/** A product's stock address inside a physical tote allocated to a staging buffer. */
@Entity @DiscriminatorValue("BUFFER_TOTE_STOCK")
public class BufferToteStockLocation extends ShelfLocation {
    @ManyToOne(optional=false) public LoadedTote loadedTote;
    @ManyToOne(optional=false) public Buffer buffer;
}
