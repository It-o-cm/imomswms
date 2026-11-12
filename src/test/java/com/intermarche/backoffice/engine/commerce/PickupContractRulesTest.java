package com.intermarche.backoffice.engine.commerce;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

/** Pure rules of the Drive arrival contract: parking normalisation, date conversions and failure bodies. */
class PickupContractRulesTest {
 /**
  * Builds a parking.
  *
  * @param kind the kind
  * @param label the label
  * @return the parking
  */
 static PickupContract.Parking parking(String kind,String label){var p=new PickupContract.Parking();p.kind=kind;p.label=label;return p;}
 /**
  * Returns the contract reason of a refusal.
  *
  * @param e the refusal
  * @return the reason
  */
 @SuppressWarnings("unchecked") static String reason(CommerceFailure e){return ((java.util.Map<String,Object>)e.getResponse().getEntity()).get("reason").toString();}
 /** A space label is trimmed and upper-cased; letters, digits, space and dash only, 12 at most. */
 @Test void spaceLabelIsNormalisedAndBounded(){assertEquals("B 12-A",PickupContract.parking(parking("SPACE","  b 12-a ")).label);assertEquals("ABCDEFGHIJKL",PickupContract.parking(parking("SPACE","abcdefghijkl")).label);
  for(String bad:new String[]{"","   ","ABCDEFGHIJKLM","B12!","É1"})assertEquals("INVALID_PARKING",reason(assertThrows(CommerceFailure.class,()->PickupContract.parking(parking("SPACE",bad)))));
  assertEquals("INVALID_PARKING",reason(assertThrows(CommerceFailure.class,()->PickupContract.parking(parking("SPACE",null)))));}
 /** On foot, no label is accepted; an unknown kind or no parking at all is refused. */
 @Test void pedestrianHasNoLabelAndOtherKindsAreRefused(){assertNull(PickupContract.parking(parking("PEDESTRIAN",null)).label);assertNull(PickupContract.parking(parking("PEDESTRIAN","  ")).label);
  assertThrows(CommerceFailure.class,()->PickupContract.parking(parking("PEDESTRIAN","B1")));assertThrows(CommerceFailure.class,()->PickupContract.parking(parking("CAR","B1")));
  assertThrows(CommerceFailure.class,()->PickupContract.parking(parking(null,"B1")));assertThrows(CommerceFailure.class,()->PickupContract.parking(null));}
 /** Dates are read with their offset and published in UTC; absent or unreadable dates stay absent. */
 @Test void datesKeepTheirInstantAndAreNormalisedToUtc(){assertEquals("2026-10-27T21:00:00Z",PickupContract.utc("2026-10-27T23:00:00+02:00"));assertNull(PickupContract.utc(null));assertNull(PickupContract.utc("hier"));
  assertNull(PickupContract.local(null));assertNull(PickupContract.local("2026-10-27"));
  var local=PickupContract.local("2026-10-27T21:00:00Z");assertEquals("2026-10-27T21:00:00Z",PickupContract.instant(local));assertNull(PickupContract.instant(null));
  assertEquals(LocalDateTime.of(2026,10,27,21,0).atZone(ZoneId.of("UTC")).toInstant(),local.atZone(ZoneId.systemDefault()).toInstant());}
 /** A refusal carries the contract version, its reason, whether to retry and an opaque correlation id. */
 @Test @SuppressWarnings("unchecked") void refusalBodyIsStructured(){var e=CommerceFailure.of(503,"TEMPORARILY_UNAVAILABLE",true);var body=(java.util.Map<String,Object>)e.getResponse().getEntity();
  assertEquals(503,e.getResponse().getStatus());assertEquals("1",body.get("contractVersion"));assertEquals(true,body.get("retryable"));assertEquals(36,body.get("correlationId").toString().length());assertEquals("no-store",e.getResponse().getHeaderString("Cache-Control"));}
 /** Arrival identifiers are opaque and distinct. */
 @Test void arrivalIdentifiersAreOpaque(){var a=PickupContract.arrivalId();assertTrue(a.matches("arr-[0-9a-f]{24}"));assertNotEquals(a,PickupContract.arrivalId());}
}
