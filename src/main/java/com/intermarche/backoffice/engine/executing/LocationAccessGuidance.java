package com.intermarche.backoffice.engine.executing;
import com.intermarche.backoffice.domain.store.*;
import org.hibernate.Hibernate;
import java.util.*;
/** Operational guidance derived from structured address data, never from guessed product names. */
public final class LocationAccessGuidance {
 private LocationAccessGuidance(){}
 public static Map<String,Object> of(BaseLocation raw){
  var location=Hibernate.unproxy(raw,BaseLocation.class);var result=new LinkedHashMap<String,Object>();
  var constraints=new ArrayList<String>();var equipment=new ArrayList<String>();
  if(location instanceof WarehouseShelfLocation shelf){
   if(shelf.requiresForklift()){constraints.add("ELEVATED_ACCESS");equipment.add("FORKLIFT");}
   if(shelf.maxWeightKg!=null)result.put("maxWeightKg",shelf.maxWeightKg);
   if(shelf.maxPallets!=null)result.put("maxPallets",shelf.maxPallets);
   result.put("level",shelf.elevation);
  }
  result.put("constraints",constraints);result.put("equipment",equipment);return result;
 }
}
