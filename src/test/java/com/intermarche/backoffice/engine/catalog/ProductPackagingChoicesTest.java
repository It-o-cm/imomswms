package com.intermarche.backoffice.engine.catalog;
import com.intermarche.backoffice.domain.catalog.ProductPackaging;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class ProductPackagingChoicesTest {
 @Test void baseUnitRemainsAChoiceWithASingleCarton(){var p=new ProductPackaging();p.code="CASE12";p.label="Carton de 12";p.level="CASE";p.parentCode="BASE";p.factor=new BigDecimal("12");p.unit="PCS";var rows=new ProductInformationService().packagingViews(List.of(p),"PCS");assertEquals(2,rows.size());assertEquals("BASE",rows.get(0).get("code"));assertEquals("1",rows.get(0).get("baseFactor"));assertEquals("12",rows.get(1).get("baseFactor"));}
 @Test void aProductWithoutPackagingsHasOnlyItsBaseUnit(){var rows=new ProductInformationService().packagingViews(List.of(),"KG");assertEquals(1,rows.size());assertEquals("Kilogramme",rows.get(0).get("label"));}
}
