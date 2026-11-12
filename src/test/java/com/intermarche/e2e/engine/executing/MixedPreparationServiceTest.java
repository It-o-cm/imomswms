package com.intermarche.e2e.engine.executing;
import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.domain.wms.handling.*;
import com.intermarche.backoffice.engine.executing.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(MixedPreparationServiceTest.Isolated.class)
@QuarkusTest @TestTransaction
class MixedPreparationServiceTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public java.util.Map<String,String> getConfigOverrides(){return java.util.Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:isolated_MixedPreparationServiceTest;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}

 @Inject DemoDataInitializer seed;
 @Inject MixedPreparationService service;
 @Inject PickingPlanService plans;
 @Inject HandlingOrderService handling;
 @io.quarkus.test.InjectMock com.intermarche.backoffice.auth.AuthenticationService authentication;
 record Fixture(PreparingOrder order,LotLine line,PickedItem existing,Employee employee,Buffer buffer){}
 Fixture fixture(boolean grouping){
  seed.seedDemoData();
  ProductCollectingOrder original=ProductCollectingOrder.find("code","PRO-DEMO-OPT").firstResult();
  List<Lot> lots=original.lots.stream().limit(2).toList();
  PreparingOrder order=grouping?new GroupingOrder():new PackingOrder();order.code="MIX-TEST";order.store=original.store;order.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;order.lots.addAll(lots);
  Buffer existing=Buffer.find("bufferCode","LYO-042-BUF-01").firstResult();Buffer buffer=new Buffer();buffer.bufferCode="MIX-BUFFER";buffer.building=existing.building;buffer.active=true;buffer.locationDescription="Dépôt mixte";buffer.ambientCapacity=100;buffer.chilledCapacity=100;buffer.frozenCapacity=100;buffer.persist();
  if(grouping)((GroupingOrder)order).buffer=buffer;
  if(order instanceof PackingOrder packing)packing.building=lots.get(0).chunk.building;
  order.persist();
  LotLine line=lots.get(0).lines.iterator().next();line.quantity=new BigDecimal("5");
  Tote physical=new Tote();physical.code="MIX-SOURCE";physical.store=original.store;physical.volume=new BigDecimal("60");physical.toteType=line.product.temperatureZone==TemperatureZone.AMBIENT || line.product.temperatureZone==TemperatureZone.HOUSEHOLD?Tote.ToteType.STANDARD:Tote.ToteType.INSULATED;physical.persist();
  LoadedTote tote=new LoadedTote();tote.tote=physical;tote.storageType=line.product.temperatureZone;tote.buffer=buffer;tote.persist();
  PickedItem item=new PickedItem();item.lotLine=line;item.product=line.product;item.quantity=new BigDecimal("2");item.status=PickedItem.PickStatus.PICKED;item.loadedTote=tote;item.persist();tote.items.add(item);
  Employee employee=Employee.find("store",original.store).firstResult();
  return new Fixture(order,line,item,employee,buffer);
 }
 HandlingOrderService.OrderReportDTO report(Fixture f){
  var report=new HandlingOrderService.OrderReportDTO();report.orderId=f.order.id;report.totes=new ArrayList<>();int n=0;
  for(var source:service.sources(f.order)){
   Tote physical=new Tote();physical.code="MIX-TARGET-"+(n++);physical.store=f.order.store;physical.volume=new BigDecimal("500");physical.toteType=source.line().product.temperatureZone==TemperatureZone.AMBIENT || source.line().product.temperatureZone==TemperatureZone.HOUSEHOLD?Tote.ToteType.STANDARD:Tote.ToteType.INSULATED;physical.persist();
   var target=new HandlingOrderService.ToteReportDTO();target.code=physical.code;target.zone=source.line().product.temperatureZone;target.bufferId=f.buffer.id;
   var item=new HandlingOrderService.ToteItemReportDTO();item.sourceSnapshot=source.snapshot();item.pickedItemId=source.key();item.qty=source.quantity();target.items=List.of(item);report.totes.add(target);
  }
  // The test buffer must have enough space in every temperature zone.
  f.buffer.ambientCapacity=100;f.buffer.chilledCapacity=100;f.buffer.frozenCapacity=100;

  return report;
 }
 @Inject OperatorExecutionService operator;
 @Test @io.quarkus.test.security.TestSecurity(user="100235",roles="PICKER") void completedMixedMissionRemainsReadableAfterConfirmation(){
  var f=fixture(false);org.mockito.Mockito.when(authentication.getConnectedAccount()).thenReturn(f.employee);
  service.conclude(f.order,f.employee,report(f),null);
  var view=(Map<?,?>)operator.order(f.order.store.id,f.order.id);
  assertEquals(BaseHandlingOrder.HandlingOrderStatus.DONE,f.order.status);
  assertEquals(Map.of(),view.get("directiveSourceLines"));
  assertFalse(((List<?>)view.get("directiveLines")).isEmpty());
  assertThrows(IllegalStateException.class,()->service.sources(f.order));
 }
 @Test @io.quarkus.test.security.TestSecurity(user="100235",roles="PICKER") void cancelledMixedMissionRemainsReadableButCannotBePrepared(){
  var f=fixture(true);org.mockito.Mockito.when(authentication.getConnectedAccount()).thenReturn(f.employee);
  f.order.status=BaseHandlingOrder.HandlingOrderStatus.CANCELLED;
  var view=(Map<?,?>)operator.order(f.order.store.id,f.order.id);
  assertEquals(Map.of(),view.get("directiveSourceLines"));
  assertThrows(IllegalStateException.class,()->service.sources(f.order));
 }
 @Test @io.quarkus.test.security.TestSecurity(user="100235",roles="PICKER") void activeMixedMissionStillExposesItsSourceAliases(){
  var f=fixture(false);org.mockito.Mockito.when(authentication.getConnectedAccount()).thenReturn(f.employee);
  var view=(Map<?,?>)operator.order(f.order.store.id,f.order.id);
  assertFalse(((Map<?,?>)view.get("directiveSourceLines")).isEmpty());
 }
 @Test void partialLineHasLocatedToteAndShelfRemainderAcrossChunks(){
  var f=fixture(false);var sources=service.sources(f.order);
  var parts=sources.stream().filter(s->s.line().id.equals(f.line.id)).toList();assertEquals(2,parts.size());
  assertEquals(0,new BigDecimal("3").compareTo(parts.stream().filter(s->s.item()==null).findFirst().orElseThrow().quantity()));
  assertTrue(parts.stream().anyMatch(s->s.label().contains("MIX-SOURCE") && s.label().contains(f.buffer.locationDescription)));
  var plan=plans.generatePickingPlan(List.of(f.order.id),null);var steps=plan.buildingPlans.values().stream().flatMap(p->p.route.stream()).flatMap(g->g.steps.stream()).toList();
  assertTrue(steps.stream().anyMatch(s->"TOTE".equals(s.sourceKind)&&s.pickedItemId.equals(-f.existing.id)));
  assertTrue(steps.stream().anyMatch(s->"SHELF".equals(s.sourceKind)&&s.pickedItemId.equals(f.line.id)));
 }
 @Test void incompleteReportIsRejectedBeforeMovingProducts(){var f=fixture(false);var report=report(f);report.totes.get(0).items.get(0).qty=BigDecimal.ZERO;assertThrows(IllegalArgumentException.class,()->service.conclude(f.order,f.employee,report,null));assertEquals(PickedItem.PickStatus.PICKED,f.existing.status);assertEquals(BaseHandlingOrder.HandlingOrderStatus.SCHEDULED,f.order.status);}
 @Test void mixedPackingReconcilesEveryLineExactly(){var f=fixture(false);service.conclude(f.order,f.employee,report(f),null);verify(f);}
 @Test void mixedGroupingReconcilesEveryLineAndDepositsAtItsDestination(){var f=fixture(true);service.conclude(f.order,f.employee,report(f),null);verify(f);}
 void verify(Fixture f){
  assertEquals(BaseHandlingOrder.HandlingOrderStatus.DONE,f.order.status);
  assertEquals(PickedItem.PickStatus.CANCELED,f.existing.status);
  for(Lot lot:f.order.lots)for(LotLine line:lot.lines){List<PickedItem> active=PickedItem.list("lotLine=?1 and status=?2",line,PickedItem.PickStatus.PICKED);assertEquals(0,line.quantity.compareTo(active.stream().map(i->i.quantity).reduce(BigDecimal.ZERO,BigDecimal::add)));assertTrue(active.stream().allMatch(i->i.loadedTote.buffer.id.equals(f.buffer.id)));}
 }
 @Test void staleSourceQuantityAndOverAllocationAreRejected(){var f=fixture(false);var report=report(f);f.existing.quantity=BigDecimal.ONE;assertThrows(IllegalArgumentException.class,()->service.conclude(f.order,f.employee,report,null));}
 @Test void excessQuantitiesAreRejected(){var f=fixture(false);var report=report(f);report.totes.get(0).items.get(0).qty=report.totes.get(0).items.get(0).qty.add(BigDecimal.ONE);assertThrows(IllegalArgumentException.class,()->service.conclude(f.order,f.employee,report,null));}
 @Test void oneSourceCanBeSplitAcrossTwoDestinationTotes(){
  var f=fixture(false);var report=report(f);var first=report.totes.stream().filter(t->t.items.get(0).pickedItemId.equals(-f.existing.id)).findFirst().orElseThrow();
  var extraPhysical=new Tote();extraPhysical.code="MIX-SPLIT";extraPhysical.store=f.order.store;extraPhysical.toteType=Tote.find("code",first.code).<Tote>firstResult().toteType;extraPhysical.volume=new BigDecimal("500");extraPhysical.persist();
  var extra=new HandlingOrderService.ToteReportDTO();extra.code=extraPhysical.code;extra.zone=first.zone;extra.bufferId=f.buffer.id;
  var item=new HandlingOrderService.ToteItemReportDTO();item.sourceSnapshot=first.items.get(0).sourceSnapshot;item.pickedItemId=first.items.get(0).pickedItemId;item.qty=BigDecimal.ONE;extra.items=List.of(item);first.items.get(0).qty=BigDecimal.ONE;report.totes.add(extra);
  service.conclude(f.order,f.employee,report,null);verify(f);
 }
 @Test void weighedRemainderKeepsDecimalPrecision(){
  var f=fixture(false);f.line.product.productType=com.intermarche.backoffice.domain.order.ProductType.WEIGHT;f.line.quantity=new BigDecimal("2.375");f.existing.quantity=new BigDecimal("0.750");
  assertEquals(0,new BigDecimal("1.625").compareTo(service.sources(f.order).stream().filter(s->s.line().id.equals(f.line.id)&&s.item()==null).findFirst().orElseThrow().quantity()));
  service.conclude(f.order,f.employee,report(f),null);verify(f);
 }
 @Test void packingCanResumeAnAlreadyReservedExecution(){
  var f=fixture(false);var execution=new com.intermarche.backoffice.client.PickingExecution();execution.handlingOrder=f.order;execution.employee=f.employee;execution.persist();
  service.conclude(f.order,f.employee,report(f),null);verify(f);assertEquals(1,com.intermarche.backoffice.domain.handling.HandlingExecution.count("handlingOrder",f.order));assertEquals(com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE,execution.status);
 }

 @Test void sourceMovedAfterPreviewRequiresRefresh(){var f=fixture(false);var report=report(f);f.existing.loadedTote.buffer=null;assertThrows(IllegalArgumentException.class,()->service.conclude(f.order,f.employee,report,null));}

 @Test void groupingCanKeepAnExistingSourceToteWithoutRepacking(){var f=fixture(true);var report=report(f);report.totes.stream().filter(t->t.items.get(0).pickedItemId.equals(-f.existing.id)).findFirst().orElseThrow().code=f.existing.loadedTote.tote.code;service.conclude(f.order,f.employee,report,null);verify(f);assertTrue(f.existing.loadedTote.items.stream().anyMatch(i->i.status==PickedItem.PickStatus.PICKED && i.quantity.signum()>0));}

 @Test void batchApiServiceUsesTheSameMixedReconciliation(){
  var f=fixture(false);org.mockito.Mockito.when(authentication.getConnectedAccount()).thenReturn(f.employee);
  var position=new com.intermarche.backoffice.domain.workforce.EmployeePosition();position.employee=f.employee;position.building=((PackingOrder)f.order).building;position.availableAt=com.intermarche.backoffice.domain.util.DateTimeProvider.now();position.persist();
  var request=new HandlingOrderService.BatchValidationRequest();request.orderIds=List.of(f.order.id);request.reports=List.of(report(f));handling.createAndConcludeBatch(request);verify(f);
 }

 @Test void reservedGroupingCanUseTheExecutionReportEndpoint(){
  var f=fixture(true);org.mockito.Mockito.when(authentication.getConnectedAccount()).thenReturn(f.employee);
  var position=new com.intermarche.backoffice.domain.workforce.EmployeePosition();position.employee=f.employee;position.building=f.buffer.building;position.availableAt=com.intermarche.backoffice.domain.util.DateTimeProvider.now();position.persist();
  var execution=new GroupingExecution();execution.handlingOrder=f.order;execution.employee=f.employee;execution.persist();
  var request=new HandlingOrderService.ExecutionReportRequest();request.totes=report(f).totes;handling.saveAndConcludeExecution(execution.id,request);verify(f);assertEquals(com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE,execution.status);
 }

 @Test void legacyPackingConclusionCannotHideAShelfRemainder(){var f=fixture(false);var execution=new com.intermarche.backoffice.client.PickingExecution();execution.handlingOrder=f.order;execution.employee=f.employee;execution.persist();assertThrows(IllegalStateException.class,()->handling.concludePickingExecution(execution.id,f.buffer.id));assertNotEquals(BaseHandlingOrder.HandlingOrderStatus.DONE,f.order.status);}
 @Test void legacyGroupingConclusionCannotHideAShelfRemainder(){var f=fixture(true);var execution=new GroupingExecution();execution.handlingOrder=f.order;execution.employee=f.employee;execution.loadedTotes.add(f.existing.loadedTote);execution.persist();assertThrows(IllegalStateException.class,()->handling.concludeGroupingExecution(execution.id));assertNotEquals(BaseHandlingOrder.HandlingOrderStatus.DONE,f.order.status);}


 @Inject PickingResolutionService resolutions;
 com.intermarche.backoffice.domain.order.Product allowed(Fixture f){
  var original=f.line.product;
  var replacement=new com.intermarche.backoffice.domain.order.Product();replacement.ean="9900000000777";replacement.name="Remplacement autorisé";replacement.active=true;replacement.productType=original.productType;replacement.temperatureZone=original.temperatureZone;replacement.referenceVolume=original.referenceVolume;replacement.referenceWeight=original.referenceWeight;replacement.persist();
  var rule=new ProductSubstitution();rule.store=f.order.store;rule.requested=original;rule.replacement=replacement;rule.persist();return replacement;
 }
 @Test void allowedReplacementKeepsRequestedProductAndRecordsActualInTote(){
  var f=fixture(false);var requested=f.line.product;var actual=allowed(f);var report=report(f);
  var item=report.totes.stream().flatMap(t->t.items.stream()).filter(i->i.pickedItemId.equals(f.line.id)).findFirst().orElseThrow();item.productId=actual.id;item.missingReason="Rayon vide";
  service.conclude(f.order,f.employee,report,null);
  assertEquals(requested.id,f.line.product.id);assertEquals(1,PickingShortage.count("lotLine=?1 and replacementProduct=?2",f.line,actual));
  assertTrue(PickedItem.<PickedItem>list("lotLine=?1 and status=?2",f.line,PickedItem.PickStatus.PICKED).stream().anyMatch(i->i.product.id.equals(actual.id)&&i.quantity.compareTo(new BigDecimal("3"))==0));
 }
 @Test void unservedQuantityIsNotRecollectedByNextPreparation(){
  var f=fixture(false);var report=report(f);report.totes.removeIf(t->t.items.get(0).pickedItemId.equals(f.line.id));
  var missing=new HandlingOrderService.MissingReportDTO();missing.pickedItemId=f.line.id;missing.qty=new BigDecimal("3");missing.reason="Introuvable";report.missing=List.of(missing);
  service.conclude(f.order,f.employee,report,null);
  assertEquals(0,new BigDecimal("3").compareTo(resolutions.unfulfilled(f.line)));
  var next=new PackingOrder();next.store=f.order.store;next.code="NEXT-MISSING";next.building=((PackingOrder)f.order).building;next.lots.addAll(f.order.lots);next.persist();assertTrue(service.sources(next).stream().filter(source->source.line().id.equals(f.line.id)).allMatch(source->source.item()!=null));
 }
 @Test void unauthorizedOrRevokedSubstitutionIsRejected(){
  var f=fixture(false);var actual=allowed(f);ProductSubstitution.<ProductSubstitution>find("replacement",actual).firstResult().active=false;
  assertThrows(IllegalArgumentException.class,()->resolutions.replacement(f.order.store,f.line.product,actual.id));
 }
 @Test void incompatibleTemperatureCannotBecomeAnAllowedReplacement(){
  var f=fixture(false);var actual=allowed(f);actual.temperatureZone=f.line.product.temperatureZone==TemperatureZone.CHILLED?TemperatureZone.AMBIENT:TemperatureZone.CHILLED;
  assertThrows(IllegalArgumentException.class,()->resolutions.replacement(f.order.store,f.line.product,actual.id));
 }

 @Test void substitutionAuthorizationIsDirectionalAndScopedToStore(){
  var f=fixture(false);var actual=allowed(f);var other=new Store();other.code="OTHER-SUB-STORE";other.name="Autre magasin";other.persist();
  assertThrows(IllegalArgumentException.class,()->resolutions.replacement(other,f.line.product,actual.id));
  assertThrows(IllegalArgumentException.class,()->resolutions.replacement(f.order.store,actual,f.line.product.id));
 }
 @Test void packingQuantityReportIncludesExistingTotesAndShelfRemainderWithoutCountingBothAsNewPicks(){verifyQuantityReport(false,com.intermarche.backoffice.domain.order.ProductType.WEIGHT,"KG");}
 @Test void groupingQuantityReportKeepsLitresAndMixedSources(){verifyQuantityReport(true,com.intermarche.backoffice.domain.order.ProductType.VOLUME,"L");}
 void verifyQuantityReport(boolean grouping,com.intermarche.backoffice.domain.order.ProductType type,String unit){
  var f=fixture(grouping);f.line.product.productType=type;f.line.quantity=new BigDecimal("2.375");f.existing.quantity=new BigDecimal("0.750");
  service.conclude(f.order,f.employee,report(f),null);verify(f);
  var rows=com.intermarche.backoffice.domain.operator.ActivityQuantity.<com.intermarche.backoffice.domain.operator.ActivityQuantity>list("store=?1 and actor=?2 and source='MIXED_REPORT' and lineId=?3",f.order.store,f.employee.badgeId,f.line.id);
  for(var entry:Map.of(grouping?"GROUPED":"PACKED","2.375","PICKED","1.625","RETRIEVED_FROM_TOTE","0.750").entrySet()){
   var matching=rows.stream().filter(q->entry.getKey().equals(q.metric)).toList();assertFalse(matching.isEmpty());assertTrue(matching.stream().allMatch(q->unit.equals(q.unit)));assertEquals(0,new BigDecimal(entry.getValue()).compareTo(matching.stream().map(q->q.quantity).reduce(BigDecimal.ZERO,BigDecimal::add)));
  }
 }

}
