package com.intermarche.backoffice.engine.replenishment;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Fill rates shown on unloading carts: an estimate that knows nothing must not look like an empty cart.
 */
class UnloadingCartFillRateTest {
 /**
  * Builds a cart of the given capacity.
  *
  * @param liters the volume capacity
  * @param kg the weight capacity, or null when unknown
  * @return the cart
  */
 static UnloadingCartService.Cart cart(String liters,String kg){return new UnloadingCartService.Cart(null,Set.of("AMBIENT"),new BigDecimal(liters),kg==null?null:new BigDecimal(kg),4);}
 /**
  * Computes the fill rates of a load.
  *
  * @param cart the cart
  * @param liters the estimated volume
  * @param kg the estimated weight
  * @param complete whether every dimension was known
  * @return the view holding the rates
  */
 static LinkedHashMap<String,Object> rates(UnloadingCartService.Cart cart,String liters,String kg,boolean complete){var v=new LinkedHashMap<String,Object>();UnloadingCartService.fillRates(v,cart,new BigDecimal(liters),new BigDecimal(kg),complete,false);return v;}
 /** Nothing known: no gauge rather than a misleading 0 %. */
 @Test void incompleteEstimateWithoutAnyKnownDimensionHasNoRate(){var v=rates(cart("10","20"),"0","0",false);assertNull(v.get("volumePercent"));assertNull(v.get("weightPercent"));assertEquals(false,v.get("estimateComplete"));}
 /** Partially known: the known part is still shown as a lower bound. */
 @Test void incompleteEstimateKeepsWhatIsKnown(){var v=rates(cart("10","20"),"5","0",false);assertEquals(50,v.get("volumePercent"));assertNull(v.get("weightPercent"));}
 /** Complete and empty: a real 0 %. */
 @Test void completeEmptyEstimateIsZero(){var v=rates(cart("10","20"),"0","0",true);assertEquals(0,v.get("volumePercent"));assertEquals(0,v.get("weightPercent"));}
 /** Unknown weight capacity: no weight gauge even with a known weight. */
 @Test void unknownWeightCapacityHasNoWeightRate(){var v=rates(cart("10",null),"5","3",true);assertEquals(50,v.get("volumePercent"));assertNull(v.get("weightPercent"));}
}
