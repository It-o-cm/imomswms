package com.intermarche.backoffice.domain.wms.handling;
import com.intermarche.backoffice.domain.store.Buffer;
import jakarta.persistence.*;
/** Urgent withdrawal from customer-accessible shelves into protected picking totes. */
@Entity @DiscriminatorValue("STOCK_PROTECTION")
public class StockProtectionOrder extends ReplenishmentOrder {
    @Column(length=64) public String protectionRuleKey;
    @ManyToOne public Buffer protectionBuffer;
    public boolean urgent=true;
    public int protectionHorizonMinutes;
    @Override public String getPrefix(){return "SEC";}
}
