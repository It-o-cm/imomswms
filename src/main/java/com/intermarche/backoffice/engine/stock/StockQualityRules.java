package com.intermarche.backoffice.engine.stock;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import java.util.*;
public final class StockQualityRules {
 private StockQualityRules(){}
 public static boolean matches(QualityCase hold,Long product,Long lot,Long serial){
  if(!id(hold.product).equals(product))return false;
  if(hold.lot!=null&&!Objects.equals(id(hold.lot),lot)&&!(lot==null&&hold.includeUnknownLots))return false;
  return hold.serial==null||Objects.equals(id(hold.serial),serial)||serial==null&&hold.includeUnknownLots;
 }
 public static void requireAllowed(Store store,Product product,ManufacturerLot lot,ProductSerial serial){
  for(var hold:QualityCase.<QualityCase>list("store=?1 and product=?2 and state='ACTIVE'",store,product))if(matches(hold,id(product),lot==null?null:id(lot),serial==null?null:id(serial)))throw new OperatorFailure(409,"QUALITY_HOLD","Produit sous "+("RECALL".equals(hold.kind)?"rappel":"blocage")+" qualité : "+hold.reason+". Isolez-le ou faites traiter le dossier qualité.");
 }
 public static void requireSegment(StockSegment segment,String context){
  var policy=TraceabilityPolicy.<TraceabilityPolicy>find("store=?1 and product=?2 and context=?3",segment.account.store,segment.account.product,context).firstResult();
  var holds=QualityCase.<QualityCase>list("store=?1 and product=?2 and state='ACTIVE'",segment.account.store,segment.account.product);requireSegment(segment,policy,holds);
 }
 public static void requireSegment(StockSegment segment,TraceabilityPolicy policy,Collection<QualityCase> holds){
  if(!StockEligibility.qualityAllowed(segment.quality))throw new OperatorFailure(409,"QUALITY_HOLD","Ce segment est "+segment.quality+". Choisissez un lot autorisé.");
  int days=policy==null?0:policy.minimumRemainingDays;boolean strict=policy!=null&&policy.bestBeforeStrict;
  if(policy!=null&&(policy.lotRequired&&segment.lot==null||policy.serialRequired&&segment.serial==null||policy.dateRequired&&(segment.lot==null||segment.lot.useBy==null&&segment.lot.bestBefore==null)))throw new OperatorFailure(409,"TRACE_REQUIRED","Lot, série ou date requis pour ce produit");
  if(!StockEligibility.dateAllowed(com.intermarche.backoffice.domain.util.DateTimeProvider.now(),segment.lot==null?null:segment.lot.useBy,segment.lot==null?null:segment.lot.bestBefore,days,strict))throw new OperatorFailure(409,"DATE_NOT_ALLOWED","La date de ce lot ne couvre pas la durée minimale requise");
  for(var hold:holds)if(matches(hold,id(segment.account.product),segment.lot==null?null:id(segment.lot),segment.serial==null?null:id(segment.serial)))throw new OperatorFailure(409,"QUALITY_HOLD","Produit sous "+("RECALL".equals(hold.kind)?"rappel":"blocage")+" qualité : "+hold.reason);
 }
}
