package com.intermarche.backoffice.engine.stock;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockAvailabilityTest {
    static LocalDateTime at(int h,int m) {return LocalDateTime.of(2026,10,20,h,m);}
    static BigDecimal q(int n) {return BigDecimal.valueOf(n);}
    static StockAvailability.Change supply(int h,int m,int n) {return new StockAvailability.Change(at(h,m),q(n),q(0));}
    static StockAvailability.Change demand(int h,int m,int n) {return new StockAvailability.Change(at(h,m),q(0),q(n));}
    @Test void futureBottleneckAlsoClosesImmediateSales() {
        var result=StockAvailability.calculate(at(8,35),q(33),q(3),List.of(
            supply(11,30,60),supply(16,0,80),demand(10,0,17),demand(14,0,75),demand(17,0,3)));
        assertEquals(q(0),result.periods.get(0).availableToPromise);
        assertEquals(at(16,0),result.nextAvailableAt);
        assertTrue(result.protectionRequired);
        assertEquals(at(14,0),result.constrainedAt);
    }
    @Test void exactThresholdClosesSalesAndDoesNotClipTheUnderlyingShortage() {
        var result=StockAvailability.calculate(at(8,35),q(10),q(3),List.of(demand(9,0,7)));
        assertEquals(q(0),result.periods.get(0).availableToPromise);
        assertTrue(result.protectionRequired);
        var shortage=StockAvailability.calculate(at(8,35),q(10),q(3),List.of(demand(9,0,12)));
        assertEquals(q(-5),shortage.periods.get(1).balance);
    }
    @Test void lateReceiptIsNotPromisedAndOverdueCommitmentRemainsDue() {
        var result=StockAvailability.calculate(at(12,0),q(2),q(0),List.of(supply(11,30,60),demand(10,0,3)));
        assertNull(result.nextAvailableAt);
        assertEquals(q(-1),result.periods.get(0).balance);
    }
    @Test void simultaneousReceiptAndDemandHaveNoTransientShortage() {
        var result=StockAvailability.calculate(at(8,35),q(0),q(0),List.of(supply(9,0,10),demand(9,0,4)));
        assertEquals(q(6),result.periods.get(1).availableToPromise);
        assertFalse(result.protectionRequired);
    }
}
