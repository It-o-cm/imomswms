package com.intermarche.backoffice.engine.fieldops;
import com.intermarche.backoffice.domain.stock.StockOperationReceipt;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
/** Receipt utility for stock decisions, separate from physical operator-command receipts. */
@ApplicationScoped
public class StockCommands {
 @Inject FieldOperationsService field;
 @Inject com.intermarche.backoffice.engine.operator.OperationQuantities quantities;
 public StockOperationReceipt previous(Store store,String key,String fingerprint){FieldOperationsService.key(key);StockOperationReceipt previous=StockOperationReceipt.find("store=?1 and actor=?2 and requestKey=?3",store,field.actor(),key).firstResult();if(previous!=null&&!previous.fingerprint.equals(fingerprint))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne une autre décision de stock");return previous;}
 public Object value(StockOperationReceipt receipt){return field.decode(receipt.result,Map.class);}
 public Object record(Store store,String key,String fingerprint,String operation,Object value){return record(store,key,fingerprint,operation,value,List.of());}
 public Object record(Store store,String key,String fingerprint,String operation,Object value,List<com.intermarche.backoffice.engine.operator.OperationQuantities.Amount> amounts){var prior=previous(store,key,fingerprint);if(prior!=null){if(!prior.operation.equals(operation))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence appartient à une autre action");return value(prior);}var receipt=new StockOperationReceipt();receipt.store=store;receipt.actor=field.actor();receipt.requestKey=key;receipt.fingerprint=fingerprint;receipt.operation=operation;receipt.result=field.encode(value);if("MISSION_STATE".equals(operation)&&value instanceof Map<?,?> state){receipt.activityState=Objects.toString(state.get("state"),null);receipt.activityReason=Objects.toString(state.get("reason"),null);}receipt.quantityProjectionVersion=1;receipt.persist();quantities.save(store,field.actor(),"OPERATION",receipt.id,operation,amounts);return value;}
}
