package com.intermarche.backoffice.engine.fieldops;
import com.intermarche.backoffice.domain.executing.PickedItem;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MissionClosureTraceFailuresTest {
 PickedItem item(long id){var i=new PickedItem();i.id=id;i.product=new Product();i.product.id=10L;i.product.name="Lait";return i;}
 MissionClosureService service(Set<Long> invalid,Map<Long,Long> serials){var s=new MissionClosureService();s.parcels=new ParcelService(){@Override public List<Map<String,Object>> finalTrace(Collection<PickedItem> items){var i=items.iterator().next();if(invalid.contains(i.id))throw new OperatorFailure(409,"FINAL_QUANTITY_CHANGED","Quantité modifiée");return List.of(Map.of("trace",serials.containsKey(i.id)?List.of(Map.of("serialId",serials.get(i.id))):List.of()));}};return s;}
 @Test void reportsOnlyTheInvalidLineEvenWhenProductsAreIdentical(){var rows=service(Set.of(2L),Map.of()).traceFailures(List.of(item(1),item(2),item(3)));assertEquals(1,rows.size());assertEquals(2L,rows.get(0).get("itemId"));assertEquals(10L,rows.get(0).get("productId"));}
 @Test void marksBothOwnersOfADuplicateSerialAndLeavesOtherLinesAlone(){var rows=service(Set.of(),Map.of(1L,50L,2L,50L,3L,51L)).traceFailures(List.of(item(1),item(2),item(3)));assertEquals(Set.of(1L,2L),new HashSet<>(rows.stream().map(r->r.get("itemId")).toList()));}
 @Test void validLinesHaveNoError(){assertTrue(service(Set.of(),Map.of()).traceFailures(List.of(item(1),item(2))).isEmpty());}
}
