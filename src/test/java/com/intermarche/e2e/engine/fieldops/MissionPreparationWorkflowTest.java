package com.intermarche.e2e.engine.fieldops;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
@QuarkusTest @jakarta.enterprise.inject.Typed(MissionPreparationWorkflowTest.class)
class MissionPreparationWorkflowTest extends MissionCoordinationTest {
 String path(){return "/orders/"+f.order()+"/preparation";}
 Map<String,Object> packet(String mode,String revision){return Map.of("storeId",f.store(),"requestKey",key(),"expectedRevision",revision,"mode",mode,"groupCode","PREP-42");}
 @Test void allModesAreExplicitReadByPickerAndProjectIntoOrder(){String revision="0";
  for(String mode:List.of("SINGLE_ORDER","MULTI_ORDER","PRODUCT","ZONE","BATCH","WAVE","ROUTE")){
   var input=packet(mode,revision);var r=call(f.manager(),"PUT",path(),input).then().statusCode(200).extract().jsonPath().<String,Object>getMap("$");revision=r.get("revision").toString();
   call(f.picker(),"GET",path()+"?storeId="+f.store(),null).then().statusCode(200).body("mode",org.hamcrest.Matchers.is(mode));
   given().auth().oauth2(f.picker()).get("/handling-orders/operator/orders/"+f.order()+"?storeId="+f.store()).then().statusCode(200).body("preparation.mode",org.hamcrest.Matchers.is(mode));
   call(f.manager(),"PUT",path(),input).then().statusCode(200).body("revision",org.hamcrest.Matchers.is(revision));
   given().auth().oauth2(f.picker()).get("/handling-orders/operator/orders/page?storeId="+f.store()+"&limit=30&state=ACTIVE&assignment=MINE").then().statusCode(200).body("items.find { it.id == "+f.order()+" }.preparation.mode",org.hamcrest.Matchers.is(mode)).body("items.find { it.id == "+f.order()+" }.preparation.revision",org.hamcrest.Matchers.is(revision));
  }
 }
 @Test void scopePermissionsRevisionsAndGroupIdentityAreChecked(){
  call(f.picker(),"PUT",path(),packet("WAVE","0")).then().statusCode(403);
  call(f.picker(),"GET",path()+"?storeId="+f.other(),null).then().statusCode(403);
  call(f.manager(),"PUT",path(),Map.of("storeId",f.store(),"requestKey",key(),"expectedRevision","0","mode","WAVE")).then().statusCode(400);
  call(f.manager(),"PUT",path(),packet("INVALID","0")).then().statusCode(400);
  call(f.manager(),"PUT",path(),packet("WAVE","0")).then().statusCode(200);
  call(f.manager(),"PUT",path(),packet("BATCH","0")).then().statusCode(409);
 }
}
