package com.intermarche.e2e.engine.printing;
import com.intermarche.backoffice.engine.printing.*;import com.google.zxing.*;import com.google.zxing.common.HybridBinarizer;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.util.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.concurrent.*;
class LabelRendererTest {
 /** Checks that a rendered barcode decodes back to the logistics code and that label texts are escaped. */
 @Test void barcodeRoundTripRetainsLogisticsIdentity()throws Exception{String code="COL-1-12345";var matrix=LabelRenderer.barcode(code,732);int[] pixels=new int[matrix.getWidth()*matrix.getHeight()];for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)pixels[y*matrix.getWidth()+x]=matrix.get(x,y)?0xff000000:0xffffffff;var image=new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(matrix.getWidth(),matrix.getHeight(),pixels)));assertEquals(code,new MultiFormatReader().decode(image).getText());var result=LabelRenderer.render(code,"Commande <A>",List.of("Buffer froid","Client ^XZ"),812,600);assertTrue(result.preview().contains("&lt;A&gt;"));assertFalse(result.payload().contains("Client ^XZ"));assertTrue(result.payload().endsWith("^FS^XZ"));assertEquals(1,result.payload().split("\\^XA",-1).length-1);}
 /** Checks that a ZPL-breaking code or too many lines are refused. */
 @Test void unsafeOrOvercrowdedLabelsAreRejected(){assertThrows(IllegalArgumentException.class,()->LabelRenderer.render("x^XZ","Titre",List.of(),812,600));assertThrows(IllegalArgumentException.class,()->LabelRenderer.render("COL-1","Titre",Collections.nCopies(6,"Texte"),400,350));}
 /** Checks that the TCP adapter sends the frozen payload byte for byte to a loopback printer allowed by a lab policy. */
 @Test void tcpAdapterTransmitsFrozenPayloadExactly()throws Exception{try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var executor=Executors.newSingleThreadExecutor()){var read=executor.submit(()->{try(var socket=server.accept()){return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}});String payload=LabelRenderer.render("COL-1","Colis",List.of("Commande A"),812,600).payload();new PrinterTransport(new PrinterTransport.Policy(true,PrinterTransport.Policy.PRINTER_PORTS)).send(new PrinterTransport.Destination("TCP_ZPL","127.0.0.1",server.getLocalPort(),null),payload);assertEquals(payload,read.get(5,TimeUnit.SECONDS));}}
 /** Checks that the simulator tells a safe failure from an uncertain write, and that a public address is refused. */
 @Test void simulatorSeparatesSafeFailureAndUncertainty(){var transport=new PrinterTransport();var failed=assertThrows(PrinterTransport.DeliveryFailure.class,()->transport.send(new PrinterTransport.Destination("SIMULATOR",null,0,"FAIL_CONNECT"),"x"));assertFalse(failed.uncertain);var uncertain=assertThrows(PrinterTransport.DeliveryFailure.class,()->transport.send(new PrinterTransport.Destination("SIMULATOR",null,0,"UNKNOWN_WRITE"),"x"));assertTrue(uncertain.uncertain);assertThrows(IllegalArgumentException.class,()->PrinterTransport.validate(new PrinterTransport.Destination("TCP_ZPL","8.8.8.8",9100,null)));}
 /** Checks that long business references wrap without losing their text. */
 @Test void fullLengthBusinessReferencesWrapWithoutLosingTheirText(){var label=LabelRenderer.render("DEST-1-2","Destination",List.of("Commande "+"A".repeat(64),"Référence "+"B".repeat(160),"Destinataire "+"C".repeat(160)),812,600);assertTrue(label.preview().contains("Commande"));assertTrue(label.payload().endsWith("^FS^XZ"));}
 /** Checks that the strict policy accepts a private address on a printer port only. */
 @Test void strictPolicyAllowsPrivateAddressesOnPrinterPortsOnly()throws Exception{
  var strict=PrinterTransport.Policy.STRICT;
  assertTrue(strict.allows(InetAddress.getByName("192.168.1.20"),9100));
  assertTrue(strict.allows(InetAddress.getByName("10.0.0.5"),631));
  assertFalse(strict.allows(InetAddress.getByName("192.168.1.20"),22));
  assertFalse(strict.allows(InetAddress.getByName("127.0.0.1"),9100));
  assertFalse(strict.allows(InetAddress.getByName("169.254.169.254"),80));
  assertFalse(strict.allows(InetAddress.getByName("169.254.10.1"),9100));
  assertFalse(strict.allows(InetAddress.getByName("8.8.8.8"),9100));
 }
 /** Checks that a lab policy accepts loopback on any port, and still refuses public addresses. */
 @Test void labPolicyAcceptsLoopbackOnAnyPort()throws Exception{
  var lab=new PrinterTransport.Policy(true,PrinterTransport.Policy.PRINTER_PORTS);
  assertTrue(lab.allows(InetAddress.getByName("127.0.0.1"),8040));
  assertTrue(lab.allows(InetAddress.getByName("192.168.1.20"),9100));
  assertFalse(lab.allows(InetAddress.getByName("192.168.1.20"),8040));
  assertFalse(lab.allows(InetAddress.getByName("8.8.8.8"),9100));
 }
 /** Checks that validation returns the address it checked and refuses every invalid destination. */
 @Test void validationReturnsTheCheckedAddressAndRefusesInvalidDestinations()throws Exception{
  var strict=PrinterTransport.Policy.STRICT;
  assertEquals(InetAddress.getByName("192.168.1.20"),PrinterTransport.validate(new PrinterTransport.Destination("TCP_ZPL","192.168.1.20",9100,null),strict));
  assertNull(PrinterTransport.validate(new PrinterTransport.Destination("SIMULATOR",null,0,"SUCCESS"),strict));
  assertThrows(IllegalArgumentException.class,()->PrinterTransport.validate(new PrinterTransport.Destination("SIMULATOR",null,0,"OTHER"),strict));
  assertThrows(IllegalArgumentException.class,()->PrinterTransport.validate(new PrinterTransport.Destination("TCP_ZPL","127.0.0.1",9100,null),strict));
  assertThrows(IllegalArgumentException.class,()->PrinterTransport.validate(new PrinterTransport.Destination("TCP_ZPL","192.168.1.20",3306,null),strict));
  assertThrows(IllegalArgumentException.class,()->PrinterTransport.validate(new PrinterTransport.Destination("RAW","192.168.1.20",9100,null),strict));
  assertThrows(IllegalArgumentException.class,()->PrinterTransport.validate(new PrinterTransport.Destination("TCP_ZPL"," ",9100,null),strict));
  assertThrows(IllegalArgumentException.class,()->PrinterTransport.validate(new PrinterTransport.Destination("TCP_ZPL","printer.invalid",9100,null),strict));
 }
 /** Checks that the strict transport refuses a loopback printer before connecting. */
 @Test void strictTransportRefusesLoopbackBeforeConnecting(){
  var transport=new PrinterTransport(PrinterTransport.Policy.STRICT);
  assertThrows(IllegalArgumentException.class,()->transport.send(new PrinterTransport.Destination("TCP_ZPL","127.0.0.1",9100,null),"x"));
 }
}
