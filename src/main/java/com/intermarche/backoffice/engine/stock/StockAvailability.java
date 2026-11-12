package com.intermarche.backoffice.engine.stock;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Pure, deterministic availability-to-promise calculation. All quantities use the product's base unit. */
public final class StockAvailability {
    private StockAvailability() {}
    public record Change(LocalDateTime at, BigDecimal supply, BigDecimal demand) {}
    public static class Period {
        public LocalDateTime from;
        public LocalDateTime until;
        public BigDecimal physicalAndExpected;
        public BigDecimal committed;
        public BigDecimal balance;
        /** Additional demand at this instant must also fit every later deadline. */
        public BigDecimal availableToPromise;
    }
    public static class Timeline {
        public LocalDateTime asOf;
        public BigDecimal physical;
        public BigDecimal eligiblePhysical;
        public BigDecimal excludedPhysical=BigDecimal.ZERO;
        public boolean eligibilityApplied;
        public BigDecimal inTransit=BigDecimal.ZERO;
        public BigDecimal safetyMargin;
        public List<Period> periods;
        public LocalDateTime nextAvailableAt;
        public boolean protectionRequired;
        /** Physical units to move out of customer-accessible shelves when protection is required. */
        public BigDecimal quantityToProtect;
        /** Planned internal transfers excluded because their source cannot cover them. */
        public BigDecimal unsupportedTransferQuantity=BigDecimal.ZERO;
        public LocalDateTime constrainedAt;
    }
    public static Timeline calculate(LocalDateTime now, BigDecimal physical, BigDecimal margin, List<Change> changes) {
        Objects.requireNonNull(now); Objects.requireNonNull(physical); Objects.requireNonNull(margin);
        if (margin.signum()<0) throw new IllegalArgumentException("Negative safety margin");
        TreeMap<LocalDateTime,BigDecimal[]> events=new TreeMap<>();
        events.put(now,new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});
        for (Change change:changes) {
            // A late, unreceived supply is not usable until its ETA is revised or it is received.
            if (change.at().isBefore(now) && change.supply().signum()>0) continue;
            LocalDateTime at=change.at().isBefore(now)?now:change.at();
            BigDecimal[] sums=events.computeIfAbsent(at,k -> new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});
            sums[0]=sums[0].add(change.supply()); sums[1]=sums[1].add(change.demand());
        }
        List<Period> periods=new ArrayList<>();
        BigDecimal supply=physical,demand=BigDecimal.ZERO;
        for (var event:events.entrySet()) {
            supply=supply.add(event.getValue()[0]); demand=demand.add(event.getValue()[1]);
            Period p=new Period();p.from=event.getKey();p.physicalAndExpected=supply;p.committed=demand;
            p.balance=supply.subtract(demand).subtract(margin); periods.add(p);
        }
        for(int i=0;i+1<periods.size();i++) periods.get(i).until=periods.get(i+1).from;
        BigDecimal minimum=null;
        for (int i=periods.size()-1;i>=0;i--) {
            Period p=periods.get(i); minimum=minimum==null?p.balance:minimum.min(p.balance);
            p.availableToPromise=minimum.max(BigDecimal.ZERO);
        }
        Timeline result=new Timeline();result.asOf=now;result.physical=physical;result.eligiblePhysical=physical;result.safetyMargin=margin;result.periods=periods;
        for (Period p:periods) {
            if (result.constrainedAt==null && p.committed.signum()>0 && p.balance.signum()<=0) result.constrainedAt=p.from;
            if (result.nextAvailableAt==null && p.availableToPromise.signum()>0) result.nextAvailableAt=p.from;
        }
        result.protectionRequired=result.constrainedAt!=null && physical.compareTo(margin)>0;
        result.quantityToProtect=result.protectionRequired ? physical.subtract(margin).max(BigDecimal.ZERO).min(demand) : BigDecimal.ZERO;
        return result;
    }
}
