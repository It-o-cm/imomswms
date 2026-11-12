package com.intermarche.backoffice.engine.catalog;
import java.math.BigDecimal;
import java.util.Set;
/** No approximation or inferred weight. Amounts are expressed in the product's base dimension. */
public final class ProductMeasure {
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="ProductMeasurementInput") public static class Input {public BigDecimal gross,net,tare;public String unit,origin,deviceId;}
 public static BigDecimal normalize(Input m,String baseUnit){
  if(m==null||m.unit==null||!Set.of("MANUAL","SCAN","SCALE").contains(m.origin==null?"":m.origin))throw new IllegalArgumentException("Mesure, unité et origine requises");
  if(m.deviceId!=null && (!m.deviceId.matches("[A-Za-z0-9 _.:/-]{1,80}")))throw new IllegalArgumentException("Identifiant de balance invalide");
  if("SCALE".equals(m.origin)&&(m.deviceId==null||m.deviceId.isBlank()))throw new IllegalArgumentException("Balance requise pour une mesure instrumentée");
  String dimension=switch(m.unit){case "KG","G"->"KG";case "L","ML"->"L";case "PCS"->"PCS";default->throw new IllegalArgumentException("Unité de mesure inconnue");};
  if(!dimension.equals(baseUnit))throw new IllegalArgumentException("Dimension incompatible avec le produit");
  BigDecimal tare=m.tare==null?BigDecimal.ZERO:m.tare;amount(tare,false);BigDecimal net;
  if(m.gross!=null){amount(m.gross,true);if(tare.compareTo(m.gross)>0)throw new IllegalArgumentException("La tare dépasse le brut");net=m.gross.subtract(tare);if(m.net!=null && m.net.compareTo(net)!=0)throw new IllegalArgumentException("Brut, tare et net incohérents");}
  else{if(m.net==null||tare.signum()!=0)throw new IllegalArgumentException("Un brut est requis pour appliquer une tare");net=m.net;}
  amount(net,true);if(Set.of("G","ML").contains(m.unit))net=net.movePointLeft(3);
  if(net.stripTrailingZeros().scale()>(baseUnit.equals("PCS")?0:3))throw new IllegalArgumentException("Précision incompatible : pièces entières ou trois décimales kg/L");return net.stripTrailingZeros();
 }
 private static void amount(BigDecimal value,boolean positive){if(value==null||value.signum()<0||positive&&value.signum()==0||value.precision()>19||value.scale()>6||value.precision()-value.scale()>13)throw new IllegalArgumentException("Mesure positive, 19 chiffres et six décimales maximum");}
 private ProductMeasure(){}
}
