package com.intermarche.backoffice.engine.stock;

import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.BaseLocation;
import java.time.LocalDateTime;
import java.util.*;

/** Segments qualify the existing append-only ledger; they never maintain a second balance. */
public final class StockSegments {
    private StockSegments() {}
    public static StockSegment legacy(StockAccount account, BaseLocation location) {
        String key=String.valueOf(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(location));
        StockSegment segment=StockSegment.find("account=?1 and segmentKey=?2",account,key).firstResult();
        if(segment==null){segment=new StockSegment();segment.account=account;segment.location=location;
            segment.segmentKey=key;segment.quality="UNASSESSED";segment.persist();}
        return segment;
    }
    public static java.math.BigDecimal balance(StockSegment segment,LocalDateTime at){
        return StockMovement.getEntityManager().createQuery("select coalesce(sum(m.quantity),0) from StockMovement m where m.segment=?1 and m.effectiveAt<=?2",java.math.BigDecimal.class)
            .setParameter(1,segment).setParameter(2,at).getSingleResult();
    }
}
