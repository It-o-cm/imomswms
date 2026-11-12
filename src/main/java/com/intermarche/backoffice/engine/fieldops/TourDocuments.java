package com.intermarche.backoffice.engine.fieldops;

import com.intermarche.backoffice.domain.Adresse;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import com.intermarche.backoffice.domain.shipping.*;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import org.hibernate.Hibernate;

/**
 * Transport documents of a sealed tour: the load manifest, one delivery note per order and the consignment note
 * handed to the driver. Their content is frozen as issued; a broken seal voids them and the next seal issues them again.
 */
@ApplicationScoped @Transactional
public class TourDocuments {
 @Inject FieldOperationsService field;@Inject DepartureService departures;

 /**
  * One unit loaded in the vehicle with what it carries.
  *
  * @param code the tote or parcel code
  * @param kind TOTE or PARCEL
  * @param weightKg the weighed parcel weight, or null
  * @param items the picked products it carries
  */
 record Unit(String code,String kind,BigDecimal weightKg,List<PickedItem> items){}

 /**
  * Issues the documents of a tour being sealed.
  *
  * @param t the tour, already holding its seal
  * @param attached its departures, all loaded
  */
 void issue(DepartureTour t,List<LogisticsDeparture> attached){
  int issue=1+(int)TourDocument.count("tour=?1 and kind=?2 and state=?3",t,TourDocument.MANIFEST,TourDocument.VOIDED);
  String suffix=issue>1?"/"+issue:"";
  var byDeparture=new LinkedHashMap<LogisticsDeparture,List<Unit>>();
  for(var d:attached)byDeparture.put(d,departures.units(d).stream().filter(u->"LOADED".equals(u.state)).map(this::unit).toList());
  var byOrder=new LinkedHashMap<Long,List<LogisticsDeparture>>();for(var d:attached)byOrder.computeIfAbsent(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(d.order),k->new ArrayList<>()).add(d);
  var all=byDeparture.values().stream().flatMap(List::stream).toList();var stops=stops(attached);
  var manifest=header(t,TourDocument.MANIFEST,"Manifeste de chargement",t.code+"-MAN"+suffix);
  manifest.put("orders",byDeparture.entrySet().stream().map(e->{var row=new LinkedHashMap<String,Object>();row.put("stop",stops.get(e.getKey().id));row.put("orderCode",e.getKey().order.orderId);row.put("consignee",consignee(e.getKey()));row.put("departureCode",e.getKey().code);row.put("destinationCode",e.getKey().destinationCode);row.put("units",e.getValue().stream().map(this::unitRow).toList());return row;}).toList());
  manifest.put("totals",totals(byOrder.size(),all));
  save(t,null,manifest);
  var consignments=new ArrayList<Map<String,Object>>();
  for(var group:byOrder.values()){var first=group.getFirst();var units=group.stream().flatMap(d->byDeparture.get(d).stream()).toList();
   var note=header(t,TourDocument.DELIVERY_NOTE,"Bon de livraison",t.code+"-BL-"+first.order.orderId+suffix);
   note.put("orderCode",first.order.orderId);note.put("consignee",consignee(first));note.put("departureCodes",group.stream().map(d->d.code).toList());note.put("units",units.stream().map(this::unitRow).toList());note.put("lines",lines(units));note.put("totals",totals(1,units));
   save(t,first.order,note);
   var row=new LinkedHashMap<String,Object>();row.put("stop",stops.get(first.id));row.put("orderCode",first.order.orderId);row.put("consignee",consignee(first));row.putAll(totals(1,units));row.remove("orders");consignments.add(row);}
  var consignment=header(t,TourDocument.CONSIGNMENT_NOTE,"Lettre de voiture",t.code+"-LV"+suffix);
  consignment.put("consignments",consignments);
  consignment.put("totals",totals(byOrder.size(),all));
  consignment.put("signatures",List.of("Expéditeur — "+t.store.name,"Transporteur — "+t.serviceLevel.carrier.name,"Destinataire"));
  save(t,null,consignment);
 }

 /**
  * Persists a document with the hash of its frozen content.
  *
  * @param t the tour
  * @param order the order of a delivery note, or null
  * @param content the content
  */
 void save(DepartureTour t,Order order,Map<String,Object> content){var d=new TourDocument();d.tour=t;d.order=order;d.kind=(String)content.get("kind");d.number=(String)content.get("number");d.content=field.encode(content);d.sha256=TourService.sha256(d.content);d.persist();}

 /**
  * Builds the part shared by every document.
  *
  * @param t the tour
  * @param kind the document kind
  * @param title the printed title
  * @param number the document number
  * @return the content to complete
  */
 Map<String,Object> header(DepartureTour t,String kind,String title,String number){var v=new LinkedHashMap<String,Object>();v.put("kind",kind);v.put("title",title);v.put("number",number);v.put("issuedAt",t.sealedAt);
  Store s=t.store;v.put("sender",Map.of("code",s.code,"name",s.name,"address",address(s.address)));
  v.put("carrier",Map.of("code",t.serviceLevel.carrier.code,"name",t.serviceLevel.carrier.name));v.put("service",Map.of("code",t.serviceLevel.code,"label",t.serviceLevel.label));
  var tour=new LinkedHashMap<String,Object>();tour.put("code",t.code);tour.put("plannedDeparture",t.plannedDeparture);tour.put("vehicleReference",t.vehicleReference);tour.put("sealNumber",t.sealNumber);tour.put("sealedBy",t.sealedBy);tour.put("dock",Map.of("code",t.dock.code,"label",t.dock.label));v.put("tour",tour);return v;}

 /**
  * Describes a loaded unit with its content.
  *
  * @param u the departure unit
  * @return the unit
  */
 Unit unit(DepartureUnit u){return new Unit(departures.code(u),u.parcel==null?"TOTE":"PARCEL",u.parcel==null?null:u.parcel.weightKg,departures.items(u));}

 /**
  * Describes a unit row of a document.
  *
  * @param u the unit
  * @return the row
  */
 Map<String,Object> unitRow(Unit u){var v=new LinkedHashMap<String,Object>();v.put("code",u.code());v.put("kind",u.kind());v.put("weightKg",u.weightKg());v.put("products",u.items().size());return v;}

 /**
  * Adds the products carried by units, per product.
  *
  * @param units the units of one order
  * @return the product lines
  */
 List<Map<String,Object>> lines(List<Unit> units){var lines=new LinkedHashMap<Long,Map<String,Object>>();
  for(var item:units.stream().flatMap(u->u.items().stream()).toList()){Product p=Hibernate.unproxy(item.product,Product.class);
   var line=lines.computeIfAbsent(p.id,k->{var v=new LinkedHashMap<String,Object>();v.put("ean",p.ean);v.put("name",p.name);v.put("quantity",BigDecimal.ZERO);v.put("unit",unitLabel(p.productType));return v;});
   line.put("quantity",((BigDecimal)line.get("quantity")).add(item.quantity));}
  return new ArrayList<>(lines.values());}

 /**
  * Labels the unit of a product type.
  *
  * @param type the product type
  * @return pcs, kg or l
  */
 static String unitLabel(ProductType type){return type==ProductType.WEIGHT?"kg":type==ProductType.VOLUME?"l":"pcs";}

 /**
  * Counts units and known weights.
  *
  * @param orders the number of orders
  * @param units the units
  * @return the totals
  */
 static Map<String,Object> totals(int orders,List<Unit> units){var v=new LinkedHashMap<String,Object>();v.put("orders",orders);v.put("units",units.size());v.put("totes",units.stream().filter(u->"TOTE".equals(u.kind())).count());v.put("parcels",units.stream().filter(u->"PARCEL".equals(u.kind())).count());
  v.put("weightKg",units.stream().map(Unit::weightKg).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add));v.put("weightComplete",units.stream().allMatch(u->u.weightKg()!=null));return v;}

 /**
  * Describes who receives an order: the customer when known, otherwise the recipient of the departure.
  *
  * @param d the departure
  * @return the consignee
  */
 static Map<String,Object> consignee(LogisticsDeparture d){Customer c=d.order.customer==null?null:Hibernate.unproxy(d.order.customer,Customer.class);
  String name=c==null?null:Stream.of(c.firstName,c.lastName).filter(x->x!=null&&!x.isBlank()).reduce((a,b)->a+" "+b).orElse(null);
  var v=new LinkedHashMap<String,Object>();v.put("name",name==null?d.recipient:name);v.put("address",address(c==null?null:c.address));return v;}

 /**
  * Numbers the delivery stops of departures listed in delivery order: departures delivered at the same address share
  * the number of the first of them.
  *
  * @param attached the departures in delivery order
  * @return the stop number of each departure id
  */
 static Map<Long,Integer> stops(List<LogisticsDeparture> attached){var byAddress=new HashMap<String,Integer>();var result=new HashMap<Long,Integer>();
  for(var d:attached){Customer c=d.order.customer==null?null:Hibernate.unproxy(d.order.customer,Customer.class);result.put(d.id,byAddress.computeIfAbsent(addressKey(c==null?null:c.address,d.id),k->byAddress.size()+1));}
  return result;}

 /**
  * Builds the key under which departures are delivered at the same stop: the normalised printed address.
  *
  * @param a the consignee address, possibly null
  * @param departureId the departure, which is its own stop when the address is unknown
  * @return the key
  */
 static String addressKey(Adresse a,long departureId){var lines=address(a);return lines.isEmpty()?"departure:"+departureId:String.join("|",lines).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," ").trim();}

 /**
  * Formats an address as printed lines.
  *
  * @param a the address, possibly null
  * @return the non-empty lines
  */
 static List<String> address(Adresse a){if(a==null)return List.of();var lines=new ArrayList<String>();for(String part:new String[]{a.streetLine1,a.streetLine2})if(part!=null&&!part.isBlank())lines.add(part);
  String city=Stream.of(a.postalCode,a.city).filter(x->x!=null&&!x.isBlank()).reduce((x,y)->x+" "+y).orElse("");if(!city.isBlank())lines.add(city);if(a.country!=null&&!a.country.isBlank())lines.add(a.country);return lines;}
}
