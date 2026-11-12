package com.intermarche.backoffice.engine.printing;
import jakarta.enterprise.context.ApplicationScoped;import jakarta.inject.Inject;import io.quarkus.scheduler.Scheduled;
@ApplicationScoped public class PrintWorker {
 @Inject PrintQueue queue;@Inject PrinterTransport transport;
 @Scheduled(every="2s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP) void tick(){var work=queue.claim();if(work==null)return;try{transport.send(work.destination(),work.payload());queue.complete(work,null,false);}catch(PrinterTransport.DeliveryFailure failure){queue.complete(work,failure.getMessage(),failure.uncertain);}catch(RuntimeException invalid){queue.complete(work,"INVALID_PRINTER_CONFIGURATION",false);}}
}
