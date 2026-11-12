package com.intermarche.backoffice.engine.fieldops;
import java.math.*;
import java.util.*;
/** Pure feasibility estimate. Missing dimensions are explicitly unknown, never zero capacity. */
public final class SupportLoadEstimate {
 public record Item(String unit,BigDecimal quantity,BigDecimal referenceWeight,BigDecimal referenceVolume){}
 public record Estimate(BigDecimal liters,BigDecimal kg,boolean volumeComplete,boolean weightComplete,BigDecimal largestUnitLiters,BigDecimal largestUnitKg){}
 public static Estimate estimate(Collection<Item> items){BigDecimal liters=BigDecimal.ZERO,kg=BigDecimal.ZERO,largestL=BigDecimal.ZERO,largestKg=BigDecimal.ZERO;boolean volume=true,weight=true;
  for(Item i:items){if(i.quantity==null||i.quantity.signum()<=0)continue;BigDecimal w=null,v=null;if("WEIGHT".equals(i.unit))w=i.quantity;else if("UNIT".equals(i.unit)&&positive(i.referenceWeight))w=i.quantity.multiply(i.referenceWeight);if(w==null)weight=false;else kg=kg.add(w);
   if("VOLUME".equals(i.unit))v=i.quantity;else if(positive(i.referenceVolume)){BigDecimal units="WEIGHT".equals(i.unit)?positive(i.referenceWeight)?i.quantity.divide(i.referenceWeight,12,RoundingMode.HALF_UP):null:i.quantity;if(units!=null)v=units.multiply(i.referenceVolume);}if(v==null)volume=false;else liters=liters.add(v);
   if("UNIT".equals(i.unit)){if(positive(i.referenceWeight))largestKg=largestKg.max(i.referenceWeight);if(positive(i.referenceVolume))largestL=largestL.max(i.referenceVolume);}
  }return new Estimate(liters,kg,volume,weight,largestL,largestKg);
 }
 private static boolean positive(BigDecimal v){return v!=null&&v.signum()>0;}
 public static Map<String,Object> fit(Estimate estimate,BigDecimal capacityLiters,BigDecimal capacityKg){if(!positive(capacityLiters))throw new IllegalArgumentException("Volume requis");boolean impossible=estimate.largestUnitLiters.compareTo(capacityLiters)>0||capacityKg!=null&&estimate.largestUnitKg.compareTo(capacityKg)>0;
  int min=estimate.liters.divide(capacityLiters,0,RoundingMode.CEILING).intValueExact();if(positive(capacityKg))min=Math.max(min,estimate.kg.divide(capacityKg,0,RoundingMode.CEILING).intValueExact());
  return Map.of("compatible",!impossible,"minimumSupports",Math.max(1,min),"estimateComplete",estimate.volumeComplete&&(capacityKg==null||estimate.weightComplete),"reason",impossible?"Au moins un produit unitaire dépasse la capacité de ce support":!estimate.volumeComplete||capacityKg!=null&&!estimate.weightComplete?"Dimensions incomplètes : contrôle au prélèvement nécessaire":min>1?"Le reliquat nécessite plusieurs supports de ce type":"Le reliquat tient dans ce support");
 }
}
