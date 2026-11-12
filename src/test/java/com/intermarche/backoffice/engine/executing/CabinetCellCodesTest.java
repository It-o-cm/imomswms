package com.intermarche.backoffice.engine.executing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetCellCodesTest {
 @Test void cellLabelsAreUniqueAndKeepTheirIdentityWhenCabinetGrows(){
  var cells=BufferOccupancyService.cellCodes(42L,2,3);
  assertEquals(6,cells.size());assertEquals(6,cells.stream().map(BufferOccupancyService.CabinetCellCode::code).distinct().count());
  assertEquals("BUFFER-CELL:42:2:3",cells.getLast().code());
  assertTrue(BufferOccupancyService.cellCodes(42L,3,4).containsAll(cells));
  assertFalse(BufferOccupancyService.cellCodes(43L,2,3).contains(cells.getFirst()));
 }
 @Test void invalidCabinetGeometryCannotProduceLabels(){assertThrows(IllegalArgumentException.class,()->BufferOccupancyService.cellCodes(42L,0,3));}
}
