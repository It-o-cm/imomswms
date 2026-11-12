package com.intermarche.backoffice.engine.stock;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DatedStockAvailabilityTest {
 final LocalDateTime now=LocalDateTime.of(2026,10,27,10,0);
 BigDecimal n(int n){return BigDecimal.valueOf(n);}void eq(int expected,BigDecimal actual){assertEquals(0,n(expected).compareTo(actual));}
 DatedStockAvailability.Batch batch(String key,int quantity,int availableDay,Integer expiryDay){return new DatedStockAvailability.Batch(key,now.plusDays(availableDay),expiryDay==null?null:now.plusDays(expiryDay),n(quantity));}
 DatedStockAvailability.Demand demand(String key,int quantity,int day){return new DatedStockAvailability.Demand(key,now.plusDays(day),n(quantity),true,null);}
 @Test void futureExpiryDoesNotForbidTodaysPromise(){var v=DatedStockAvailability.calculate(now,n(10),n(0),List.of(batch("A",10,0,10)),List.of());eq(10,v.periods.getFirst().availableToPromise);eq(0,v.periods.getLast().availableToPromise);assertFalse(v.protectionRequired);}
 @Test void anExpiredLotCannotCoverALaterCommitment(){var v=DatedStockAvailability.calculate(now,n(10),n(0),List.of(batch("A",10,0,1)),List.of(demand("C",5,2)));eq(0,v.periods.getFirst().availableToPromise);assertFalse(v.protectionRequired);}
 @Test void laterArrivalCanFreeTodaysStockWhilePreservingFutureOrdersAndMargin(){var lots=List.of(batch("A",20,0,30),batch("B",10,5,40));eq(17,DatedStockAvailability.maximumExtra(lots,List.of(demand("C",10,10)),now,n(3)).quantity());}
 @Test void quantityAlreadyConsumedIsNotExpiredAgain(){var v=DatedStockAvailability.calculate(now,n(10),n(0),List.of(batch("A",10,0,2)),List.of(demand("C",10,1)));eq(0,v.periods.getLast().balance);eq(10,v.periods.getLast().committed);}
 @Test void exactDecimalQuantitiesAndBatchRestrictionsAreRespected(){var b=new DatedStockAvailability.Batch("A",now,now.plusDays(2),new BigDecimal("1.375"));var d=new DatedStockAvailability.Demand("C",now,new BigDecimal("0.125"),true,Set.of("A"));assertEquals(0,new BigDecimal("1.250").compareTo(DatedStockAvailability.maximumExtra(List.of(b),List.of(d),now,BigDecimal.ZERO).quantity()));}
}
