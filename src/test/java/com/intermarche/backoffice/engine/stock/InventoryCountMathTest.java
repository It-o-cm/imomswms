package com.intermarche.backoffice.engine.stock;
import org.junit.jupiter.api.Test;import java.math.BigDecimal;import static org.junit.jupiter.api.Assertions.*;
class InventoryCountMathTest {
 BigDecimal n(int n){return BigDecimal.valueOf(n);}
 @Test void intercalaryConsumptionDoesNotInventADifferenceBetweenCounts(){var first=InventoryCountMath.compare(n(10),n(5),n(0),n(-2),n(0),null);var recount=InventoryCountMath.compare(n(8),n(3),n(0),n(0),n(0),null);assertEquals(n(-5),first.variance());assertEquals(n(3),first.expectedNow());assertEquals(first.variance(),recount.variance());assertFalse(InventoryCountMath.conflicting(first.variance(),recount.variance(),n(0)));}
 @Test void conflictingCountsAreKeptSeparate(){var first=InventoryCountMath.compare(n(10),n(5),n(0),n(0),n(0),null);var second=InventoryCountMath.compare(n(10),n(6),n(0),n(0),n(0),null);assertTrue(InventoryCountMath.conflicting(first.variance(),second.variance(),n(0)));}
 @Test void zeroIsAnObservationAndNullIsNotZero(){assertNull(InventoryCountMath.compare(n(10),null,n(0),n(0),n(0),null));var zero=InventoryCountMath.compare(n(10),n(0),n(0),n(0),n(0),null);assertEquals(n(-10),zero.variance());assertTrue(zero.recountRequired());}
 @Test void configuredAbsoluteAndRelativeTolerancesRemainExact(){assertFalse(InventoryCountMath.compare(n(100),n(99),n(0),n(0),n(2),n(5)).recountRequired());assertTrue(InventoryCountMath.compare(n(10),n(9),n(0),n(0),n(2),n(5)).recountRequired());}
}
