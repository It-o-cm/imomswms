package com.intermarche.e2e.engine.fieldops;
import io.quarkus.test.junit.QuarkusTest;import io.quarkus.narayana.jta.QuarkusTransaction;import org.junit.jupiter.api.Test;
import com.intermarche.backoffice.domain.store.*;import com.intermarche.backoffice.domain.executing.*;import com.intermarche.backoffice.domain.wms.handling.*;import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import java.util.*;import java.math.BigDecimal;import static org.junit.jupiter.api.Assertions.*;import static io.restassured.RestAssured.given;
/** Regression coverage for historical HTTP boundaries and ordinary report quantities. */
@QuarkusTest @jakarta.enterprise.inject.Typed(LegacyExecutionBoundaryTest.class)
@io.quarkus.test.junit.TestProfile(LegacyExecutionBoundaryTest.Isolated.class)
class LegacyExecutionBoundaryTest extends ParcelWorkflowTest {
 public static class Isolated implements io.quarkus.test.junit.QuarkusTestProfile {public Map<String,String> getConfigOverrides(){return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:legacy_boundary_fixed;DB_CLOSE_DELAY=-1","quarkus.scheduler.enabled","false","imomswms.demo-data.enabled","false","imomswms.embeddings.enabled","false");}}
 Long destination(){return QuarkusTransaction.requiringNew().call(()->{var b=new Buffer();b.bufferCode="AUDIT-"+key();b.building=Building.findById(f.building());b.active=true;b.ambientCapacity=20;b.persist();return b.id;});}
 Map<String,Object> summary(){return given().auth().oauth2(f.picker()).get("/handling-orders/operator/consultation/summary?storeId="+f.store()).then().statusCode(200).extract().jsonPath().getMap("$");}
 Map<String,Object> tote(Long item,Long buffer){return Map.of("code",f.tote(),"zone","AMBIENT","bufferId",buffer,"items",List.of(Map.of("pickedItemId",item,"qty",2)));}
 void missingQuantities(Map<String,Object> s){assertEquals("ALL_CONFIRMED_OPERATIONS",s.get("basis"));assertFalse(((List<?>)s.get("quantities")).isEmpty(),s.toString());assertFalse(((List<?>)s.get("operations")).isEmpty(),s.toString());assertFalse(((List<?>)s.get("physicalQuantities")).isEmpty(),s.toString());}
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"VALIDATE_REPORT","RECOVER_REPORT"}) void bulkCollectionProjectsOnce(String action){Long line=line();stock();Long b=destination();QuarkusTransaction.requiringNew().run(()->{var e=com.intermarche.backoffice.client.PickingExecution.<com.intermarche.backoffice.client.PickingExecution>findById(f.execution());e.itemsToPick.clear();PickedItem.delete("id",f.item());e.delete();var o=ProductCollectingOrder.<ProductCollectingOrder>findById(f.order());o.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;o.building=Building.findById(f.building());});var c=command(action);c.put("reason","Reprise du rapport terrain");c.remove("executionId");c.put("report",Map.of("orderIds",List.of(f.order()),"bufferId",b,"reports",List.of(Map.of("orderId",f.order(),"totes",List.of(tote(line,b))))));command(c).then().statusCode(200);var s=summary();missingQuantities(s);assertEquals(false,s.get("incomplete"));command(c).then().statusCode(200);assertEquals(s.get("operations"),summary().get("operations"));assertEquals(s.get("quantities"),summary().get("quantities"));}
 @Test void foreignPickerCannotReadOrAttach(){String badge="X-"+key().substring(0,8);QuarkusTransaction.requiringNew().run(()->{var e=new Employee();e.badgeId=badge;e.firstName="Foreign";e.lastName="Picker";e.email=badge+"@example.test";e.password=org.mindrot.jbcrypt.BCrypt.hashpw("test",org.mindrot.jbcrypt.BCrypt.gensalt(4));e.role=Employee.EmployeeRole.PICKER;e.store=Store.findById(f.other());e.active=true;e.persist();});String token=login(badge);given().auth().oauth2(token).get("/handling-orders/operator/orders/"+f.order()+"?storeId="+f.store()).then().statusCode(403);given().auth().oauth2(token).queryParam("ids",f.execution()).get("/handling-orders/executions/batch").then().statusCode(404);
 given().auth().oauth2(token).contentType("application/json").body(Map.of("toteCode",f.tote(),"usage","AMBIENT")).post("/handling-orders/"+f.execution()+"/totes").then().statusCode(404);
 QuarkusTransaction.requiringNew().run(()->assertEquals(0,LoadedTote.count("tote.code",f.tote())));}
 @Test void ownExecutionReportProjectsQuantities(){line();stock();Long b=destination();
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("bufferId",b,"totes",List.of(tote(f.item(),b)))).post("/handling-orders/"+f.execution()+"/validate-report").then().statusCode(204);
 missingQuantities(summary());
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("bufferId",b,"totes",List.of(tote(f.item(),b)))).post("/handling-orders/"+f.execution()+"/validate-report").then().statusCode(409);
 }

 @Test void anotherEmployeeInSameStoreCannotMutate(){given().auth().oauth2(f.manager()).contentType("application/json").body(Map.of("toteCode",f.tote(),"usage","AMBIENT")).post("/handling-orders/"+f.execution()+"/totes").then().statusCode(403);}
 @Test void historicalPickReturnAndRepickAreMeasured(){line();stock();
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("toteCode",f.tote(),"usage","AMBIENT")).post("/handling-orders/"+f.execution()+"/totes").then().statusCode(201);
 String base="/handling-orders/"+f.execution()+"/totes/"+f.tote()+"/items";
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("pickedItemId",f.item(),"quantity",2)).post(base).then().statusCode(204);
 given().auth().oauth2(f.picker()).delete(base+"/"+f.item()).then().statusCode(204);
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("pickedItemId",f.item(),"quantity",2)).post(base).then().statusCode(204);
 var s=summary();var ops=(List<Map<String,Object>>)s.get("operations");assertEquals(new BigDecimal("4.0"),new BigDecimal(ops.stream().filter(v->"PICKED".equals(v.get("metric"))).findFirst().orElseThrow().get("quantity").toString()));assertTrue(ops.stream().anyMatch(v->"RETURNED_TO_SHELF".equals(v.get("metric"))));
 }
 @Test void invalidReportRollsBackFacts(){line();stock();Long b=destination();var invalid=Map.of("bufferId",b,"totes",List.of(Map.of("code",f.tote(),"zone","AMBIENT","bufferId",b,"items",List.of(Map.of("pickedItemId",f.item(),"qty",3)))));
 given().auth().oauth2(f.picker()).contentType("application/json").body(invalid).post("/handling-orders/"+f.execution()+"/validate-report").then().statusCode(400);assertTrue(((List<?>)summary().get("operations")).isEmpty());QuarkusTransaction.requiringNew().run(()->assertEquals(0,LoadedTote.count("tote.code",f.tote())));
 }

 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"WEIGHT,KG","VOLUME,L"})
 void decimalReportPreservesUnit(String type,String unit){line();QuarkusTransaction.requiringNew().run(()->{var p=com.intermarche.backoffice.domain.order.Product.<com.intermarche.backoffice.domain.order.Product>findById(f.product());p.productType=com.intermarche.backoffice.domain.order.ProductType.valueOf(type);var item=PickedItem.<PickedItem>findById(f.item());item.quantity=new BigDecimal("1.275");item.lotLine.quantity=item.quantity;item.lotLine.fulfillmentLine.requestedQuantity=item.quantity;});stock();Long b=destination();
 var t=Map.of("code",f.tote(),"zone","AMBIENT","bufferId",b,"items",List.of(Map.of("pickedItemId",f.item(),"qty",new BigDecimal("1.275"))));
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("bufferId",b,"totes",List.of(t))).post("/handling-orders/"+f.execution()+"/validate-report").then().statusCode(204);
 var ops=(List<Map<String,Object>>)summary().get("operations");assertTrue(ops.stream().allMatch(v->unit.equals(v.get("unit"))));for(var row:ops)assertEquals(0,new BigDecimal("1.275").compareTo(new BigDecimal(row.get("quantity").toString())));
 }
 @Test void itemFromAnotherExecutionIsRejectedBeforeAnyMutation(){line();stock();attach(f.tote());Long foreign=QuarkusTransaction.requiringNew().call(()->{var item=new PickedItem();item.product=com.intermarche.backoffice.domain.order.Product.findById(f.product());item.lotLine=PickedItem.<PickedItem>findById(f.item()).lotLine;item.quantity=BigDecimal.ONE;item.status=PickedItem.PickStatus.PENDING;item.persist();return item.id;});
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("pickedItemId",foreign,"quantity",1)).post("/handling-orders/"+f.execution()+"/totes/"+f.tote()+"/items").then().statusCode(400);assertTrue(((List<?>)summary().get("operations")).isEmpty());
 }

 @Test void everyHistoricalMutationChecksCustody(){line();Long b=destination();String e="/handling-orders/"+f.execution();
 Object[][] cases={
 {"POST",e+"/totes",Map.of("toteCode",f.tote(),"usage","AMBIENT")},
 {"DELETE",e+"/totes/"+f.tote(),null},
 {"PUT",e+"/items/"+f.item()+"/product",Map.of("newProductId",f.product())},
 {"POST",e+"/totes/"+f.tote()+"/items",Map.of("pickedItemId",f.item(),"quantity",1)},
 {"POST",e+"/items/move",Map.of("pickedItemId",f.item(),"sourceToteCode",f.tote(),"targetToteCode",f.second())},
 {"DELETE",e+"/totes/"+f.tote()+"/items/"+f.item(),null},
 {"POST",e+"/items/"+f.item()+"/cancel",Map.of()},
 {"POST",e+"/conclude",Map.of("bufferId",b)},
 {"POST",e+"/totes/"+f.tote()+"/move-to-destination",Map.of()},
 {"POST",e+"/conclude-grouping",Map.of()},
 {"POST",e+"/validate-report",Map.of("bufferId",b,"totes",List.of(tote(f.item(),b)))},
 {"POST","/handling-orders/executions/batch",Map.of("orderIds",List.of(f.order()))},
 {"POST","/handling-orders/executions/batch-validate",Map.of("orderIds",List.of(f.order()),"reports",List.of())},
 {"POST","/handling-orders/"+f.order()+"/reserve",Map.of("badgeId","WRONG")},
 {"DELETE","/handling-orders/"+f.order()+"/reserve",null}
 };
 for(var c:cases){var request=given().auth().oauth2(f.manager()).contentType("application/json");if(c[2]!=null)request.body(c[2]);request.request((String)c[0],(String)c[1]).then().statusCode(403);}
 given().auth().oauth2(f.manager()).queryParam("ids",f.execution()).get("/handling-orders/executions/batch").then().statusCode(403);
 QuarkusTransaction.requiringNew().run(()->assertEquals(0,LoadedTote.count("tote.code",f.tote())));
 }

 @Test void ordinaryBulkMissingIsMeasuredWithoutInventingAPick(){Long line=line();stock();QuarkusTransaction.requiringNew().run(()->{var e=com.intermarche.backoffice.client.PickingExecution.<com.intermarche.backoffice.client.PickingExecution>findById(f.execution());e.itemsToPick.clear();PickedItem.delete("id",f.item());e.delete();var o=ProductCollectingOrder.<ProductCollectingOrder>findById(f.order());o.status=BaseHandlingOrder.HandlingOrderStatus.SCHEDULED;o.building=Building.findById(f.building());});
 var report=Map.of("orderIds",List.of(f.order()),"reports",List.of(Map.of("orderId",f.order(),"missing",List.of(Map.of("pickedItemId",line,"qty",2,"reason","Rayon vide")))));
 given().auth().oauth2(f.picker()).contentType("application/json").body(report).post("/handling-orders/executions/batch-validate").then().statusCode(204);
 var s=summary();assertTrue(((List<?>)s.get("quantities")).isEmpty());var ops=(List<Map<String,Object>>)s.get("operations");assertEquals(1,ops.size());assertEquals("MISSING",ops.getFirst().get("metric"));assertEquals(0,new BigDecimal("2").compareTo(new BigDecimal(ops.getFirst().get("quantity").toString())));
 }

 @Test void previouslyIncompleteReportCannotClaimCompleteHistory(){QuarkusTransaction.requiringNew().run(()->{var r=new OperatorCommandReceipt();r.store=Store.findById(f.store());r.performedBy=com.intermarche.backoffice.client.PickingExecution.<com.intermarche.backoffice.client.PickingExecution>findById(f.execution()).employee.badgeId;r.action="VALIDATE_REPORT";r.requestKey=key();r.fingerprint=key();r.result="{}";r.quantityProjectionVersion=1;r.persist();});var s=summary();assertEquals(true,s.get("incomplete"));assertEquals(1,((Number)s.get("historicalCommandsWithoutQuantities")).intValue());}

 @Test void historicalMissingMustRemainMissingForFollowingPreparations(){line();
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of()).post("/handling-orders/"+f.execution()+"/items/"+f.item()+"/cancel").then().statusCode(204);
 QuarkusTransaction.requiringNew().run(()->assertEquals(1,PickingShortage.count("handlingOrder.id=?1 and replacementProduct is null",f.order())));
 }
 @Test void returningAnItemThroughTheWrongToteMustBeRejected(){line();var physical=stock();pick(physical);attach(f.second());
 given().auth().oauth2(f.picker()).delete("/handling-orders/"+f.execution()+"/totes/"+f.second()+"/items/"+f.item()).then().statusCode(400);
 QuarkusTransaction.requiringNew().run(()->assertNotNull(PickedItem.<PickedItem>findById(f.item()).loadedTote));
 }

 @Test void historicalReleaseCannotEraseAStartedInventory(){Long order=QuarkusTransaction.requiringNew().call(()->{var o=new InventoryOrder();o.store=Store.findById(f.store());o.assignee=com.intermarche.backoffice.client.PickingExecution.<com.intermarche.backoffice.client.PickingExecution>findById(f.execution()).employee;o.code="INV-"+key().substring(0,8);o.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;o.persist();var e=(com.intermarche.backoffice.domain.inventory.InventoryExecution)o.createExecution(o.assignee);e.countStarted=true;e.persist();return o.id;});
 given().auth().oauth2(f.picker()).delete("/handling-orders/"+order+"/reserve").then().statusCode(409);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
 void confirmedHistoricalWorkPreventsDestructiveRelease(boolean modern){line();var physical=stock();String base="/handling-orders/"+f.execution()+"/totes/"+f.tote();
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("toteCode",f.tote(),"usage","AMBIENT")).post("/handling-orders/"+f.execution()+"/totes").then().statusCode(201);
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("pickedItemId",f.item(),"quantity",2)).post(base+"/items").then().statusCode(204);
 given().auth().oauth2(f.picker()).delete(base+"/items/"+f.item()).then().statusCode(204);
 given().auth().oauth2(f.picker()).delete(base).then().statusCode(204);
 if(modern){var release=command("RELEASE");release.put("orderId",f.order());command(release).then().statusCode(409);}else given().auth().oauth2(f.picker()).delete("/handling-orders/"+f.order()+"/reserve").then().statusCode(409);
 }

 @Test void detachingAnEmptyTotePreservesTraceAndAllowsReuse(){line();var physical=stock();pick(physical);String base="/handling-orders/"+f.execution()+"/totes/"+f.tote();
 given().auth().oauth2(f.picker()).delete(base+"/items/"+f.item()).then().statusCode(204);
 given().auth().oauth2(f.picker()).delete(base).then().statusCode(204);
 QuarkusTransaction.requiringNew().run(()->assertEquals(1,LoadedTote.count("tote.code=?1 and status=?2",f.tote(),LoadedTote.Status.DELIVERED)));
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("toteCode",f.tote(),"usage","AMBIENT")).post("/handling-orders/"+f.execution()+"/totes").then().statusCode(201);
 QuarkusTransaction.requiringNew().run(()->{assertEquals(2,LoadedTote.count("tote.code",f.tote()));assertEquals(1,LoadedTote.count("tote.code=?1 and status=?2",f.tote(),LoadedTote.Status.IN_USE));});
 }

 @Test void previouslyWrittenHistoricalReceiptsAlsoPreventRelease(){confirmedHistoricalWorkPreventsDestructiveRelease(true);
 QuarkusTransaction.requiringNew().run(()->{for(var receipt:com.intermarche.backoffice.domain.stock.StockOperationReceipt.<com.intermarche.backoffice.domain.stock.StockOperationReceipt>list("store.id",f.store())){receipt.fingerprint=key();receipt.actor="before-handover";}});
 var release=command("RELEASE");release.put("orderId",f.order());command(release).then().statusCode(409);
 }

 @jakarta.inject.Inject com.intermarche.backoffice.engine.executing.MixedPreparationService mixedReports;
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void existingMixedExecutionAcceptsItsIndividualHttpReport(boolean grouping){Long line=line();stock();Long b=destination();var ids=QuarkusTransaction.requiringNew().call(()->{
  var source=ProductCollectingOrder.<ProductCollectingOrder>findById(f.order());PreparingOrder order=grouping?new GroupingOrder():new PackingOrder();order.store=source.store;order.assignee=source.assignee;order.lots.addAll(source.lots);order.code=grouping?"HTTP-MIX-G":"HTTP-MIX-P";order.status=BaseHandlingOrder.HandlingOrderStatus.PROCESSING;
  if(grouping)((GroupingOrder)order).buffer=Buffer.findById(b);else ((PackingOrder)order).building=Building.findById(f.building());order.persist();
  com.intermarche.backoffice.domain.handling.HandlingExecution execution=grouping?new GroupingExecution():new com.intermarche.backoffice.client.PickingExecution();execution.handlingOrder=order;execution.employee=source.assignee;execution.persist();var snapshot=mixedReports.sources(order).stream().filter(v->v.key().equals(line)).findFirst().orElseThrow().snapshot();return Map.of("execution",execution.id,"snapshot",snapshot);
 });
 var tote=Map.of("code",f.tote(),"zone","AMBIENT","bufferId",b,"items",List.of(Map.of("pickedItemId",line,"qty",2,"sourceSnapshot",ids.get("snapshot"))));
 given().auth().oauth2(f.picker()).contentType("application/json").body(Map.of("bufferId",b,"totes",List.of(tote))).post("/handling-orders/"+ids.get("execution")+"/validate-report").then().statusCode(204);
 QuarkusTransaction.requiringNew().run(()->{var e=org.hibernate.Hibernate.unproxy(com.intermarche.backoffice.domain.handling.HandlingExecution.findById((Long)ids.get("execution")),com.intermarche.backoffice.domain.handling.HandlingExecution.class);assertEquals(com.intermarche.backoffice.domain.handling.HandlingExecution.ExecutionStatus.DONE,e.status);assertEquals(1,e instanceof GroupingExecution g?g.loadedTotes.size():((com.intermarche.backoffice.client.PickingExecution)e).loadedTotes.size());});
 }

}
