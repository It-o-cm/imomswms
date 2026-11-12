package com.intermarche.backoffice.engine.stock;
import java.math.BigDecimal;
/** Counted quantity is an observation at its own instant; later physical movements remain separate. */
public final class InventoryCountMath {
 private InventoryCountMath(){}
 public record Comparison(BigDecimal theoreticalAtCount,BigDecimal variance,BigDecimal expectedNow,boolean recountRequired){}
 public static Comparison compare(BigDecimal reference,BigDecimal counted,BigDecimal movementsBeforeCount,BigDecimal movementsAfterCount,BigDecimal absoluteTolerance,BigDecimal relativeTolerancePercent){
  if(counted==null)return null;if(counted.signum()<0||absoluteTolerance.signum()<0||relativeTolerancePercent!=null&&relativeTolerancePercent.signum()<0)throw new IllegalArgumentException("Invalid inventory count or tolerance");
  BigDecimal theoretical=reference.add(movementsBeforeCount),variance=counted.subtract(theoretical),absolute=variance.abs();boolean exceedsAbsolute=absolute.compareTo(absoluteTolerance)>0;
  boolean exceedsRelative=relativeTolerancePercent!=null&&absolute.multiply(BigDecimal.valueOf(100)).compareTo(theoretical.abs().multiply(relativeTolerancePercent))>0;
  return new Comparison(theoretical,variance,counted.add(movementsAfterCount),exceedsAbsolute||exceedsRelative);
 }
 public static boolean conflictingObservations(BigDecimal first,BigDecimal second,BigDecimal physicalMovementBetween,BigDecimal tolerance){return first.add(physicalMovementBetween).subtract(second).abs().compareTo(tolerance)>0;}
 public static boolean conflicting(BigDecimal previousVariance,BigDecimal currentVariance,BigDecimal tolerance){return previousVariance.subtract(currentVariance).abs().compareTo(tolerance)>0;}
}
