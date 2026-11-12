package com.intermarche.backoffice.engine.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.commerce.*;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.operator.OperatorSupportService;
import com.intermarche.backoffice.engine.supervision.ActivitySupervisionService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import org.hibernate.Hibernate;

/**
 * Drive arrival contract, version 1: commerce reads the authoritative pickup state of an order and announces the
 * customer's arrival; the store team takes it in charge and hands the order over. imomswms is the authority on
 * both; every observable change raises the order revision, which never decreases.
 */
@ApplicationScoped @Transactional
public class PickupContract {
 public static final String VERSION="1",SOURCE="IMMCOMM";
 static final Pattern UUID_V4=Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),LABEL=Pattern.compile("[A-Z0-9 -]{1,12}");
 static final Set<String> PICKUP_FULFILMENTS=Set.of("DRIVE","CLICK_AND_COLLECT");
 static final SecureRandom RANDOM=new SecureRandom();
 @Inject EntityManager em;@Inject SecurityIdentity identity;@Inject ActivitySupervisionService activity;@Inject ObjectMapper json;@Inject OperatorSupportService notices;

 /** Parking announced by the customer: a numbered space or on foot. */
 public static class Parking {public String kind,label;}
 /** Arrival announcement sent by commerce. */
 public static class ArrivalRequest {public String contractVersion,sourceSystem,requestId;public Long expectedRevision;public Parking parking;}
 /**
  * Result of an announcement.
  *
  * @param created true for a new arrival, false for a replay of the same request
  * @param snapshot the current pickup state
  */
 public record Registered(boolean created,Map<String,Object> snapshot){}

 // ----- Commerce side -----

 /**
  * Reads the pickup state of an order of a store granted to the caller.
  *
  * @param storeCode the store code
  * @param orderReference the order reference sent at submission
  * @return the snapshot
  */
 public Map<String,Object> pickup(String storeCode,String orderReference){Store s=grantedStore(storeCode);Order o=order(s,orderReference);lock(o);return snapshot(o,latest(o));}

 /**
  * Records the arrival of a customer, once per request id: a replay returns the original arrival and the current
  * state; another content under the same id is a conflict.
  *
  * @param storeCode the store code
  * @param orderReference the order reference
  * @param r the announcement
  * @return the arrival and whether it was created
  */
 public Registered arrive(String storeCode,String orderReference,ArrivalRequest r){
  if(r==null||!VERSION.equals(r.contractVersion)||!SOURCE.equals(r.sourceSystem)||r.requestId==null||!UUID_V4.matcher(r.requestId).matches()||r.expectedRevision==null||r.expectedRevision<0)throw CommerceFailure.of(400,"INVALID_REQUEST",false);
  var parking=parking(r.parking);Store s=grantedStore(storeCode);
  String fingerprint=sha256(String.join("|",s.code,orderReference==null?"":orderReference,r.requestId,parking.kind,Objects.toString(parking.label,""),String.valueOf(r.expectedRevision)));
  var replay=replay(r.requestId,fingerprint);if(replay!=null)return replay;
  Order o=order(s,orderReference);lock(o);
  replay=replay(r.requestId,fingerprint);if(replay!=null)return replay;
  var state=state(o);var latest=latest(o);
  if("CANCELLED".equals(state.orderState))throw CommerceFailure.of(409,"ORDER_CANCELLED",false);
  if("COMPLETED".equals(state.orderState)||latest!=null&&DriveArrival.COMPLETED.equals(latest.state))throw CommerceFailure.of(409,"PICKUP_COMPLETED",false);
  if(latest!=null&&latest.active())throw CommerceFailure.of(409,"ARRIVAL_ALREADY_EXISTS",false);
  if(state.revision!=r.expectedRevision)throw CommerceFailure.of(409,"REVISION_CONFLICT",false);
  JsonNode fulfilment=fulfilment(o);if(!PICKUP_FULFILMENTS.contains(fulfilment.path("type").asText("")))throw CommerceFailure.of(409,"UNSUPPORTED_FULFILLMENT",false);
  if(!"READY".equals(state.orderState))throw CommerceFailure.of(409,"ORDER_NOT_READY",false);
  if(!inWindow(s,fulfilment.path("slot")))throw CommerceFailure.of(409,"ARRIVAL_WINDOW_CLOSED",false);
  var a=new DriveArrival();a.store=s;a.order=o;a.sourceSystem=SOURCE;a.principal=principal();a.requestId=r.requestId;a.arrivalId=arrivalId();a.fingerprint=fingerprint;a.expectedRevision=r.expectedRevision;a.parkingKind=parking.kind;a.parkingLabel=parking.label;a.receivedAt=DateTimeProvider.now();
  try{a.persistAndFlush();}catch(PersistenceException e){throw CommerceFailure.of(409,"IDEMPOTENCY_CONFLICT",false);}
  bump(o,a.receivedAt);
  String where="SPACE".equals(a.parkingKind)?"place "+a.parkingLabel:"client à pied";
  notices.systemNotice(s,"DRIVE_ARRIVAL","drive-arrival:"+a.arrivalId,SOURCE,"Arrivée Drive · "+o.orderId,"Le client de la commande "+o.orderId+" est arrivé ("+where+"). Prenez en charge son arrivée dans Expéditions › Arrivées Drive.",o.id,audience(s));
  return new Registered(true,snapshot(o,a));}

 /**
  * Finds an announcement by its request id after a lost response; nothing is disclosed about another caller or an
  * ungranted store.
  *
  * @param requestId the request id
  * @return the current state and the original identifiers
  */
 public Map<String,Object> request(String requestId){if(requestId==null||!UUID_V4.matcher(requestId).matches())throw CommerceFailure.of(404,"REQUEST_NOT_FOUND",false);
  DriveArrival a=DriveArrival.find("principal=?1 and sourceSystem=?2 and requestId=?3",principal(),SOURCE,requestId).firstResult();
  if(a==null||!granted(Hibernate.unproxy(a.store,Store.class)))throw CommerceFailure.of(404,"REQUEST_NOT_FOUND",false);
  lock(a.order);return snapshot(a.order,a);}

 /**
  * Returns the replay of a known request, or fails when its key is reused for another content.
  *
  * @param requestId the request id
  * @param fingerprint the fingerprint of the new request
  * @return the replay, or null for an unknown request
  */
 Registered replay(String requestId,String fingerprint){DriveArrival a=DriveArrival.find("principal=?1 and sourceSystem=?2 and requestId=?3",principal(),SOURCE,requestId).firstResult();if(a==null)return null;
  if(!a.fingerprint.equals(fingerprint))throw CommerceFailure.of(409,"IDEMPOTENCY_CONFLICT",false);lock(a.order);return new Registered(false,snapshot(a.order,a));}

 /**
  * Validates and normalises the parking.
  *
  * @param p the parking sent
  * @return a SPACE with an upper-case label, or PEDESTRIAN without label
  */
 static Parking parking(Parking p){if(p==null||p.kind==null)throw CommerceFailure.of(400,"INVALID_PARKING",false);var v=new Parking();v.kind=p.kind;
  if("PEDESTRIAN".equals(p.kind)){if(p.label!=null&&!p.label.isBlank())throw CommerceFailure.of(400,"INVALID_PARKING",false);return v;}
  if(!"SPACE".equals(p.kind)||p.label==null)throw CommerceFailure.of(400,"INVALID_PARKING",false);
  v.label=p.label.trim().toUpperCase(Locale.ROOT);if(!LABEL.matcher(v.label).matches())throw CommerceFailure.of(400,"INVALID_PARKING",false);return v;}

 /**
  * Resolves a store that the caller may read and change.
  *
  * @param storeCode the store code
  * @return the store
  */
 Store grantedStore(String storeCode){Store s=storeCode==null?null:Store.findByCode(storeCode);if(s==null||!granted(s))throw CommerceFailure.of(403,"STORE_FORBIDDEN",false);return s;}
 /**
  * Tells whether the caller holds an active grant on a store.
  *
  * @param s the store
  * @return true when granted
  */
 boolean granted(Store s){return CommerceStoreGrant.count("principal=?1 and sourceSystem=?2 and store=?3 and active=true",principal(),SOURCE,s)>0;}
 /**
  * Returns the technical identity of the caller.
  *
  * @return the principal name
  */
 String principal(){return identity.getPrincipal().getName();}
 /**
  * Finds an order of a store by its exact reference.
  *
  * @param s the store
  * @param reference the reference
  * @return the order
  */
 static Order order(Store s,String reference){Order o=reference==null?null:Order.find("store=?1 and orderId=?2",s,reference).firstResult();if(o==null)throw CommerceFailure.of(404,"ORDER_NOT_FOUND",false);return o;}
 /**
  * Serialises every pickup change of an order.
  *
  * @param o the order
  */
 void lock(Order o){em.createNativeQuery("select id from orders where id=?1 for update").setParameter(1,com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(o)).getSingleResult();}
 /**
  * Returns the latest arrival of an order, an active one first.
  *
  * @param o the order
  * @return the arrival or null
  */
 static DriveArrival latest(Order o){var active=DriveArrival.<DriveArrival>find("order=?1 and state in ?2 order by receivedAt desc",o,Set.of(DriveArrival.RECEIVED,DriveArrival.ACKNOWLEDGED)).firstResult();return active!=null?active:DriveArrival.<DriveArrival>find("order=?1 order by receivedAt desc,id desc",o).firstResult();}

 /**
  * Maps the internal state of an order to the contract: cancelled, handed over, ready (prepared and every tote in
  * a buffer or loaded), preparing, or received.
  *
  * @param o the order
  * @return RECEIVED, PREPARING, READY, COMPLETED or CANCELLED
  */
 String orderState(Order o){if("CANCELLED".equals(o.status))return "CANCELLED";if("SHIPPED".equals(o.status))return "COMPLETED";
  String stage=activity.orderStage(Hibernate.unproxy(o.store,Store.class),o.id).orElse("NOT_ROUTED");
  return switch(stage){case "READY"->"READY";case "IN_PROGRESS","PREPARED","PREPARED_UNTRACKED","PARTIALLY_SHIPPED"->"PREPARING";default->"RECEIVED";};}

 /**
  * Returns the published state of an order, raising its revision when the order state changed since the last read.
  *
  * @param o the locked order
  * @return the state
  */
 CommercePickupState state(Order o){String current=orderState(o);LocalDateTime now=DateTimeProvider.now();
  CommercePickupState p=CommercePickupState.find("order",o).firstResult();
  if(p==null){p=new CommercePickupState();p.store=Hibernate.unproxy(o.store,Store.class);p.order=o;p.orderState=current;p.changedAt=now;p.persist();return p;}
  if(!p.orderState.equals(current)){p.orderState=current;p.revision++;p.changedAt=now;}return p;}

 /**
  * Raises the revision of an order after a pickup change.
  *
  * @param o the locked order
  * @param at when it changed
  */
 public void bump(Order o,LocalDateTime at){var p=state(o);p.revision++;p.changedAt=at;}

 /**
  * Builds the pickup snapshot of the contract.
  *
  * @param o the locked order
  * @param a the arrival to describe, or null
  * @return the snapshot
  */
 Map<String,Object> snapshot(Order o,DriveArrival a){var p=state(o);var v=new LinkedHashMap<String,Object>();
  v.put("contractVersion",VERSION);v.put("sourceSystem",SOURCE);v.put("storeCode",Hibernate.unproxy(o.store,Store.class).code);v.put("orderReference",o.orderId);
  v.put("revision",p.revision);v.put("orderState",p.orderState);v.put("pickupState",a==null?"NONE":a.state);v.put("updatedAt",instant(p.changedAt));
  JsonNode slot=fulfilment(o).path("slot");var s=new LinkedHashMap<String,Object>();s.put("start",utc(slot.path("start").asText(null)));s.put("end",utc(slot.path("end").asText(null)));v.put("slot",s.get("start")==null&&s.get("end")==null?null:s);
  if(a==null){v.put("arrival",null);return v;}
  var arrival=new LinkedHashMap<String,Object>();arrival.put("arrivalId",a.arrivalId);arrival.put("requestId",a.requestId);arrival.put("receivedAt",instant(a.receivedAt));
  var parking=new LinkedHashMap<String,Object>();parking.put("kind",a.parkingKind);parking.put("label",a.parkingLabel);arrival.put("parking",parking);
  arrival.put("acknowledgedAt",instant(a.acknowledgedAt));arrival.put("completedAt",instant(a.completedAt));v.put("arrival",arrival);return v;}

 /**
  * Reads the fulfilment part of the order specification.
  *
  * @param o the order
  * @return the fulfilment node, missing when unknown
  */
 JsonNode fulfilment(Order o){try{return o.specification==null?json.missingNode():json.readTree(o.specification).path("fulfillment");}catch(Exception e){return json.missingNode();}}

 /**
  * Tells whether now falls in the arrival window of the store around the slot.
  *
  * @param s the store
  * @param slot the slot node
  * @return true when arrivals are accepted now
  */
 boolean inWindow(Store s,JsonNode slot){LocalDateTime start=local(slot.path("start").asText(null)),end=local(slot.path("end").asText(null));if(start==null||end==null)return false;
  DriveArrivalPolicy policy=DriveArrivalPolicy.find("store",s).firstResult();int before=policy==null?DriveArrivalPolicy.DEFAULT_BEFORE:policy.minutesBefore,after=policy==null?DriveArrivalPolicy.DEFAULT_AFTER:policy.minutesAfter;
  LocalDateTime now=DateTimeProvider.now();return !now.isBefore(start.minusMinutes(before))&&!now.isAfter(end.plusMinutes(after));}

 /**
  * Converts a commerce date to the server's local clock.
  *
  * @param value an RFC 3339 date, possibly null
  * @return the local date, or null when absent or unreadable
  */
 static LocalDateTime local(String value){try{return value==null?null:OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();}catch(DateTimeException e){return null;}}
 /**
  * Normalises a commerce date to UTC.
  *
  * @param value an RFC 3339 date, possibly null
  * @return the UTC date, or null
  */
 static String utc(String value){try{return value==null?null:OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);}catch(DateTimeException e){return null;}}
 /**
  * Formats a local date of the server as a UTC instant.
  *
  * @param value the local date, possibly null
  * @return the RFC 3339 UTC date, or null
  */
 public static String instant(LocalDateTime value){return value==null?null:value.atZone(ZoneId.systemDefault()).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);}
 /**
  * Creates an opaque arrival identifier.
  *
  * @return the identifier
  */
 static String arrivalId(){var bytes=new byte[12];RANDOM.nextBytes(bytes);return "arr-"+HexFormat.of().formatHex(bytes);}
 /**
  * Hashes a canonical text.
  *
  * @param value the text
  * @return the SHA-256
  */
 static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 /**
  * Lists the store team notified of an arrival.
  *
  * @param s the store
  * @return the badges of the active employees
  */
 static List<String> audience(Store s){return Employee.<Employee>list("store=?1 and active=true",s).stream().map(e->e.badgeId).filter(Objects::nonNull).toList();}

 // ----- Store side -----

 /**
  * Takes an arrival in charge.
  *
  * @param a the arrival
  * @param actor the operator
  */
 public void acknowledge(DriveArrival a,String actor){lock(a.order);if(!DriveArrival.RECEIVED.equals(a.state))throw new IllegalStateException("Cette arrivée n’est plus à prendre en charge");var now=DateTimeProvider.now();a.state=DriveArrival.ACKNOWLEDGED;a.acknowledgedAt=now;a.acknowledgedBy=actor;a.revisionNumber++;bump(a.order,now);}

 /**
  * Closes an arrival once the order is handed over; an arrival never acknowledged is acknowledged by the same act.
  *
  * @param a the arrival
  * @param actor the operator
  */
 public void complete(DriveArrival a,String actor){lock(a.order);if(!a.active())throw new IllegalStateException("Cette arrivée est déjà close");if(!"SHIPPED".equals(a.order.status))throw new IllegalStateException("Confirmez d’abord la remise des produits au client depuis son départ");
  var now=DateTimeProvider.now();if(a.acknowledgedAt==null){a.acknowledgedAt=now;a.acknowledgedBy=actor;}a.state=DriveArrival.COMPLETED;a.completedAt=now;a.completedBy=actor;a.revisionNumber++;bump(a.order,now);}

 /**
  * Cancels an arrival with its reason.
  *
  * @param a the arrival
  * @param actor the operator
  * @param reason the reason
  */
 public void cancel(DriveArrival a,String actor,String reason){lock(a.order);if(!a.active())throw new IllegalStateException("Cette arrivée est déjà close");var now=DateTimeProvider.now();a.state=DriveArrival.CANCELLED;a.cancelledAt=now;a.cancelledBy=actor;a.cancelReason=reason;a.revisionNumber++;bump(a.order,now);}

 /**
  * Completes the active arrival of an order handed over to its customer, in the same transaction as the handover.
  *
  * @param o the order
  * @param actor the operator who handed it over
  */
 public void handedOver(Order o,String actor){if(!"SHIPPED".equals(o.status))return;for(var a:DriveArrival.<DriveArrival>list("order=?1 and state in ?2",o,Set.of(DriveArrival.RECEIVED,DriveArrival.ACKNOWLEDGED)))complete(a,actor);}

 /**
  * Cancels the active arrival of a cancelled order.
  *
  * @param o the order
  * @param actor who cancelled the order
  */
 public void orderCancelled(Order o,String actor){for(var a:DriveArrival.<DriveArrival>list("order=?1 and state in ?2",o,Set.of(DriveArrival.RECEIVED,DriveArrival.ACKNOWLEDGED)))cancel(a,actor,"Commande annulée");}

 /**
  * Returns the published order state, for the store team.
  *
  * @param o the order
  * @return the contract order state
  */
 public String published(Order o){return orderState(o);}
}
