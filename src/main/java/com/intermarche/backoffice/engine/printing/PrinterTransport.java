package com.intermarche.backoffice.engine.printing;
import jakarta.enterprise.context.ApplicationScoped;import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;
/** Socket success proves transmission only. Ambiguous writes must never be retried automatically. */
@ApplicationScoped public class PrinterTransport {
 public record Destination(String transport,String host,int port,String simulation){}
 public static class DeliveryFailure extends Exception {public final boolean uncertain;public DeliveryFailure(String message,boolean uncertain,Throwable cause){super(message,cause);this.uncertain=uncertain;}}
 /**
  * Network rules for real printers: a private address of the store network and a printer port. Loopback is refused
  * outside tests, so that a printer cannot be used to reach the services of the server itself.
  *
  * @param loopbackAllowed whether a loopback address is accepted, on any port (local simulators in tests)
  * @param ports the ports accepted for a private address
  */
 public record Policy(boolean loopbackAllowed,java.util.Set<Integer> ports){
  /** Printer ports: raw printing (9100 to 9103), LPD and IPP. */
  public static final java.util.Set<Integer> PRINTER_PORTS=java.util.Set.of(9100,9101,9102,9103,515,631);
  /** Rules of a production store: private addresses and printer ports only. */
  public static final Policy STRICT=new Policy(false,PRINTER_PORTS);

  /**
   * Reads the rules from the configuration ({@code imomswms.printing.allow-loopback}).
   *
   * @return the configured rules, strict by default
   */
  public static Policy configured(){
   try{return org.eclipse.microprofile.config.ConfigProvider.getConfig().getOptionalValue("imomswms.printing.allow-loopback",Boolean.class).orElse(false)?new Policy(true,PRINTER_PORTS):STRICT;}catch(RuntimeException e){return STRICT;}
  }

  /**
   * Tells whether an address and a port may be reached.
   *
   * @param address the resolved address
   * @param port the port
   * @return true when allowed
   */
  public boolean allows(InetAddress address,int port){
   if(address.isLoopbackAddress())return loopbackAllowed;
   return address.isSiteLocalAddress()&&ports.contains(port);
  }
 }

 /** Rules applied by this transport. */
 private final Policy policy;

 /**
  * Creates the transport with the configured rules.
  */
 public PrinterTransport(){this(null);}

 /**
  * Creates the transport with given rules.
  *
  * @param policy the rules, or null for the configured ones
  */
 public PrinterTransport(Policy policy){this.policy=policy;}

 /**
  * Checks a destination with the configured rules.
  *
  * @param d the destination
  * @throws IllegalArgumentException when the destination is invalid or not allowed
  */
 public static void validate(Destination d){validate(d,Policy.configured());}

 /**
  * Checks a destination and resolves its address once, so that the connection goes to the very address checked.
  *
  * @param d the destination
  * @param policy the network rules
  * @return the address to connect to, or null for the simulator
  * @throws IllegalArgumentException when the destination is invalid or not allowed
  */
 public static InetAddress validate(Destination d,Policy policy){if("SIMULATOR".equals(d.transport)){if(!java.util.Set.of("SUCCESS","FAIL_CONNECT","UNKNOWN_WRITE").contains(d.simulation))throw new IllegalArgumentException("Mode du simulateur invalide");return null;}if(!"TCP_ZPL".equals(d.transport)||d.host==null||d.host.isBlank()||d.host.length()>253||d.port<1||d.port>65535)throw new IllegalArgumentException("Adresse d’imprimante invalide");InetAddress[] all;try{all=InetAddress.getAllByName(d.host);}catch(UnknownHostException e){throw new IllegalArgumentException("Imprimante introuvable",e);}for(InetAddress a:all)if(!policy.allows(a,d.port))throw new IllegalArgumentException("Une imprimante du réseau local du magasin est requise, sur un port d’impression ("+new java.util.TreeSet<>(policy.ports())+")");return all[0];}

 /**
  * Sends a payload to a printer.
  *
  * @param d the destination
  * @param payload the ZPL payload
  * @throws DeliveryFailure when the printer cannot be reached or the transmission may be partial
  * @throws IllegalArgumentException when the destination is invalid or not allowed
  */
 public void send(Destination d,String payload)throws DeliveryFailure{InetAddress address=validate(d,policy==null?Policy.configured():policy);if("SIMULATOR".equals(d.transport)){if("FAIL_CONNECT".equals(d.simulation))throw new DeliveryFailure("SIMULATED_CONNECT_FAILURE",false,null);if("UNKNOWN_WRITE".equals(d.simulation))throw new DeliveryFailure("SIMULATED_UNCERTAIN_WRITE",true,null);return;}
  // Connect to the address validated above, without resolving the name again.
  boolean started=false;try(var channel=java.nio.channels.SocketChannel.open();var selector=java.nio.channels.Selector.open()){channel.configureBlocking(false);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2500);channel.connect(new InetSocketAddress(address,d.port));channel.register(selector,java.nio.channels.SelectionKey.OP_CONNECT);while(!channel.finishConnect())await(selector,deadline);var data=java.nio.ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8));channel.keyFor(selector).interestOps(java.nio.channels.SelectionKey.OP_WRITE);while(data.hasRemaining()){if(System.nanoTime()>=deadline)throw new java.net.SocketTimeoutException();started=true;if(channel.write(data)==0)await(selector,deadline);}channel.shutdownOutput();}catch(IOException e){throw new DeliveryFailure(started?"TRANSMISSION_UNCERTAIN":"PRINTER_UNREACHABLE",started,e);}
 }
 private static void await(java.nio.channels.Selector selector,long deadline)throws IOException{long ms=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(ms<=0)throw new java.net.SocketTimeoutException();selector.select(Math.max(1,ms));selector.selectedKeys().clear();}
}
