package com.intermarche.e2e.engine.catalog;
import com.intermarche.backoffice.engine.catalog.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class Gs1ParserTest {
 @Test void validGtinUpcAndBadCheckDigit(){assertEquals("04006381333931",Gs1Parser.gtin("4006381333931"));assertEquals("00036000291452",Gs1Parser.gtin("036000291452"));assertThrows(IllegalArgumentException.class,()->Gs1Parser.gtin("4006381333932"));}
 @Test void parenthesizedWeightLotDatesAndSerial(){var p=Gs1Parser.parse("(01)04006381333931(3103)001250(17)271231(10)LOT-7(21)SER-9");assertEquals("1.250",p.quantity().toPlainString());assertEquals("KG",p.unit());assertEquals("2027-12-31",p.fields().get("17"));assertEquals("LOT-7",p.fields().get("10"));}
 @Test void scannerSeparatorAndBarePredefinedElements(){var p=Gs1Parser.parse("]d2010400638133393110LOT-7\u001d17271231");assertEquals("LOT-7",p.fields().get("10"));assertEquals("2027-12-31",p.fields().get("17"));assertEquals("04006381333931",Gs1Parser.parse("010400638133393117271231").identifier());}
 @Test void contentCountAndLitres(){assertEquals(new BigDecimal("12"),Gs1Parser.parse("(02)04006381333931(37)12").quantity());assertEquals("L",Gs1Parser.parse("(01)04006381333931(3153)000375").unit());}
 @Test void invalidIncompleteAmbiguousOrUnsupportedNeverResolve(){for(String s:new String[]{"(01)04006381333932","(01)123","(01)04006381333931(17)270230","(01)04006381333931(10)","(01)04006381333931(10)A(10)B","(02)04006381333931","(01)04006381333931(37)12","(01)04006381333931(99)ABC","(01)04006381333931(30)2(3103)001000","]C1010400638133393117251331"})assertThrows(IllegalArgumentException.class,()->Gs1Parser.parse(s),s);}
 @Test void manualAndMeasuredNetAreIdenticalWithoutGuessingCommercialName(){var m=new ProductMeasure.Input();m.gross=new BigDecimal("400");m.tare=new BigDecimal("25");m.unit="G";m.origin="SCALE";m.deviceId="BAL-1";assertEquals(new BigDecimal("0.375"),ProductMeasure.normalize(m,"KG"));m.gross=null;m.tare=null;m.net=new BigDecimal("375");m.origin="MANUAL";assertEquals(new BigDecimal("0.375"),ProductMeasure.normalize(m,"KG"));}
 @Test void invalidTareDimensionPrecisionAndUnitAreRejected(){var m=new ProductMeasure.Input();m.gross=new BigDecimal("1");m.tare=new BigDecimal("2");m.unit="KG";m.origin="MANUAL";assertThrows(IllegalArgumentException.class,()->ProductMeasure.normalize(m,"KG"));m.tare=BigDecimal.ZERO;assertThrows(IllegalArgumentException.class,()->ProductMeasure.normalize(m,"L"));m.gross=new BigDecimal("0.0001");assertThrows(IllegalArgumentException.class,()->ProductMeasure.normalize(m,"KG"));m.gross=new BigDecimal("1E1000");assertThrows(IllegalArgumentException.class,()->ProductMeasure.normalize(m,"KG"));}
 @Test void eanScannerSymbologyIsNotConfusedWithGs1Elements(){assertEquals("4006381333931",Gs1Parser.parse("]E04006381333931").identifier());assertThrows(IllegalArgumentException.class,()->Gs1Parser.parse("]E0BROKEN"));}

}
