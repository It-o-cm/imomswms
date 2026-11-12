package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.wms.*;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import java.util.*;
public final class DepositTargetGuard {
 private DepositTargetGuard(){}
 public record Target(LoadedTote load,CartCompartment slot){}
 public static Target resolve(Store store,BaseHandlingOrder order,String code){
  FieldOperationsService.text(code,150);CartCompartment slot=null;
  if(code.startsWith("COMPARTMENT:")){try{slot=CartCompartment.findById(Long.valueOf(code.substring(12)));}catch(NumberFormatException e){throw new jakarta.ws.rs.BadRequestException("Code de compartiment invalide");}if(slot==null||!id(slot.cart.store).equals(id(store))||slot.handlingOrder==null||!id(slot.handlingOrder).equals(id(order))||slot.occupant==null)throw new OperatorFailure(409,"WRONG_DEPOSIT_TARGET","Compartiment vide ou affecté à une autre mission");}
  else slot=CartCompartment.find("select c from CartCompartment c join c.cart cart where cart.store=?1 and concat(concat(cart.code,'/'),c.label)=?2",store,code).firstResult();
  if(slot!=null&&(slot.handlingOrder==null||!id(slot.handlingOrder).equals(id(order))||slot.occupant==null))throw new OperatorFailure(409,"WRONG_DEPOSIT_TARGET","Ce compartiment n’est pas affecté à cette mission");
  if(slot==null)slot=CartCompartment.find("occupant.code=?1 and cart.store=?2",code,store).firstResult();
  String tote=slot==null?code:slot.occupant.code;
  LoadedTote load=null;for(String entity:List.of("PickingExecution","GroupingExecution","DispositionExecution")){load=LoadedTote.find("select t from "+entity+" p join p.loadedTotes t where p.handlingOrder=?1 and t.tote.store=?2 and t.tote.code=?3 and t.status=?4",order,store,tote,LoadedTote.Status.IN_USE).firstResult();if(load!=null)break;}
  if(load==null){if(slot==null||slot.handlingOrder==null||!id(slot.handlingOrder).equals(id(order))||LoadedTote.count("tote=?1 and status=?2",slot.occupant.tote,LoadedTote.Status.IN_USE)>0)throw new OperatorFailure(409,"WRONG_DEPOSIT_TARGET","Choisissez un support actif ou un compartiment affecté à cette mission");PhysicalSupport.requireAvailable(slot.occupant.tote);return new Target(null,slot);}
  if(load.buffer!=null)throw new OperatorFailure(409,"WRONG_DEPOSIT_TARGET","Ce support est déjà déposé");
  SupportLayoutService.requireWritable(load);
  if(slot==null)slot=CartCompartment.find("occupant.tote",load.tote).firstResult();
  if(slot!=null&&(slot.handlingOrder==null||!id(slot.handlingOrder).equals(id(order))))throw new OperatorFailure(409,"WRONG_DEPOSIT_TARGET","Support affecté à un autre compartiment ou une autre mission");return new Target(load,slot);
 }
 public static void require(Store store,BaseHandlingOrder order,String tote,String code,LotLine line){
  if(code==null)return;Target target=resolve(store,order,code);if(!toteCode(target).equals(tote))throw new OperatorFailure(409,"DEPOSIT_TARGET_CHANGED","Le compartiment a changé : rescannez sa destination");
  if(target.slot!=null&&target.slot.customerOrder!=null&&Lot.getEntityManager().createQuery("select count(l.id) from Lot l join l.lines x where x=?1 and l.fulfillment.order=?2",Long.class).setParameter(1,line).setParameter(2,target.slot.customerOrder).getSingleResult()==0)throw new OperatorFailure(409,"WRONG_CUSTOMER_COMPARTMENT","Ce compartiment est réservé à une autre commande");
 }
 public static String toteCode(Target t){return t.load==null?t.slot.occupant.code:t.load.tote.code;}
 public static void requirePrepared(Store store,BaseHandlingOrder order,String tote,String code,LotLine line){if(code==null)return;if(code.equals(tote)&&CartCompartment.count("occupant.code=?1 and cart.store=?2",tote,store)==0)return;require(store,order,tote,code,line);}
 public static Object view(Target t){var v=new LinkedHashMap<String,Object>();v.put("toteCode",toteCode(t));v.put("loadedToteId",t.load==null?null:id(t.load));v.put("compartmentId",t.slot==null?null:t.slot.id);v.put("label",t.slot==null?toteCode(t):t.slot.cart.code+" / "+t.slot.label);return v;}
}
