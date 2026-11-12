package com.intermarche.backoffice.engine.fieldops;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class SupportLoadEstimateTest {
 BigDecimal n(String v){return new BigDecimal(v);}
 @Test void exactKnownLoadsRequireSeveralSupports(){var e=SupportLoadEstimate.estimate(List.of(new SupportLoadEstimate.Item("UNIT",n("20"),n("2"),n("3"))));var fit=SupportLoadEstimate.fit(e,n("40"),n("25"));assertEquals(2,fit.get("minimumSupports"));assertEquals(true,fit.get("estimateComplete"));assertEquals(true,fit.get("compatible"));}
 @Test void impossibleUnitIsExplainedEvenIfProductsCouldOtherwiseBeSplit(){var e=SupportLoadEstimate.estimate(List.of(new SupportLoadEstimate.Item("UNIT",n("1"),n("30"),n("5"))));var fit=SupportLoadEstimate.fit(e,n("60"),n("25"));assertEquals(false,fit.get("compatible"));assertTrue(fit.get("reason").toString().contains("unitaire"));}
 @Test void unknownDimensionsAreNotReportedAsACompleteFit(){var e=SupportLoadEstimate.estimate(List.of(new SupportLoadEstimate.Item("UNIT",n("2"),null,null)));assertFalse(e.volumeComplete());assertFalse(e.weightComplete());assertEquals(false,SupportLoadEstimate.fit(e,n("60"),n("25")).get("estimateComplete"));}
 @Test void bulkWeightUsesReferenceRatioAndNotPieceCount(){var e=SupportLoadEstimate.estimate(List.of(new SupportLoadEstimate.Item("WEIGHT",n("1.5"),n("0.25"),n("0.5"))));assertEquals(0,n("3").compareTo(e.liters()));assertEquals(0,n("1.5").compareTo(e.kg()));}
}
