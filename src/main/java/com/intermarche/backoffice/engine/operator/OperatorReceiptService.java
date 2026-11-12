package com.intermarche.backoffice.engine.operator;
import com.intermarche.backoffice.domain.stock.StockOperationReceipt;
import com.intermarche.backoffice.domain.executing.ToteContentCorrection;
import com.intermarche.backoffice.domain.shipping.Shipment;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import jakarta.enterprise.context.ApplicationScoped;import jakarta.inject.Inject;import jakarta.transaction.Transactional;import jakarta.ws.rs.*;import java.util.*;
/** Reading a receipt never retries its business action, even if no receipt exists yet. */
@ApplicationScoped @Transactional
public class OperatorReceiptService {
 @Inject com.fasterxml.jackson.databind.ObjectMapper json;
 @Inject OperatorSupportService support;@Inject StoreOperationsResource places;
 public Object read(Long storeId,String key,String kind){
  var store=support.store(storeId);String actor=support.actor();if(key==null||!key.matches("[a-zA-Z0-9-]{8,64}")||!Set.of("PARCEL","DEPARTURE","DISPATCH","CONTENTS","FIELD").contains(kind==null?"":kind))throw new BadRequestException("Type et référence de confirmation requis");
  if(Set.of("PARCEL","DEPARTURE","FIELD").contains(kind)){StockOperationReceipt r=StockOperationReceipt.find("store=?1 and actor=?2 and requestKey=?3",store,actor,key).firstResult();if(r!=null&&(kind.equals("FIELD")||r.operation.contains(kind)))try{return Map.of("found",true,"kind",kind,"action",r.operation,"confirmedAt",r.createdAt,"result",json.readTree(r.result),"resultBasis","FROZEN_RECEIPT");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
  else if(kind.equals("DISPATCH")){Shipment r=Shipment.find("store=?1 and performedBy=?2 and requestKey=?3",store,actor,store.id+":"+key).firstResult();if(r!=null)return Map.of("found",true,"kind",kind,"action","DISPATCH","confirmedAt",r.dispatchedAt,"result",Map.of("id",r.id,"code",r.code),"resultBasis","CONFIRMED_REFERENCE");}
  else {ToteContentCorrection r=ToteContentCorrection.find("store=?1 and performedBy=?2 and requestKey=?3",store,actor,key).firstResult();if(r!=null)return Map.of("found",true,"kind",kind,"action","CONTENTS","confirmedAt",r.createdAt,"result",places.toteContents(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(r.loadedTote.tote),store.id),"resultBasis","CURRENT_TOTE_AFTER_CONFIRMED_CORRECTION");}
  return Map.of("found",false,"kind",kind,"requestKey",key,"nextActions",List.of("CHECK_AGAIN","RETRY_SAME_COMMAND"));
 }
}
