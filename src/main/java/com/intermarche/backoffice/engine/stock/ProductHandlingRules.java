package com.intermarche.backoffice.engine.stock;
import com.intermarche.backoffice.domain.stock.TraceabilityPolicy;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import java.util.*;
public final class ProductHandlingRules {
 private ProductHandlingRules(){}
 public static String incompatibility(String hazardA,String groupA,String blockedA,String hazardB,String groupB,String blockedB){
  if(groupA!=null&&!groupA.isBlank()||groupB!=null&&!groupB.isBlank())if(!Objects.equals(groupA,groupB))return "Groupes de séparation différents";
  if(blocks(blockedA,hazardB)||blocks(blockedB,hazardA))return "Classes de danger incompatibles";return null;
 }
 private static boolean blocks(String list,String hazard){return list!=null&&hazard!=null&&Arrays.stream(list.split(",")).map(String::trim).anyMatch(hazard::equals);}
 public static void requireCompatible(Store store,Product a,Product b){
  if(a.id.equals(b.id))return;
  var x=TraceabilityPolicy.<TraceabilityPolicy>find("store=?1 and product=?2 and context='PICKING'",store,a).firstResult();var y=TraceabilityPolicy.<TraceabilityPolicy>find("store=?1 and product=?2 and context='PICKING'",store,b).firstResult();
  String reason=incompatibility(x==null?null:x.hazardClass,x==null?null:x.separationGroup,x==null?null:x.incompatibleHazards,y==null?null:y.hazardClass,y==null?null:y.separationGroup,y==null?null:y.incompatibleHazards);
  if(reason!=null)throw new OperatorFailure(409,"INCOMPATIBLE_PRODUCT_HANDLING",reason+" : utilisez un autre bac.");
 }
}
