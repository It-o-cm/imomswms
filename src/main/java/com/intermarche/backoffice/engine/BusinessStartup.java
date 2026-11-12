package com.intermarche.backoffice.engine;
import io.quarkus.runtime.StartupEvent;import io.quarkus.runtime.ShutdownEvent;import jakarta.enterprise.context.ApplicationScoped;import jakarta.enterprise.event.Observes;import jakarta.annotation.Priority;
/** Scheduled business work starts after synchronous transactional startup fixtures have committed. */
@ApplicationScoped public class BusinessStartup {
 private volatile boolean ready;
 public boolean ready(){return ready;}
 void initialized(@Observes @Priority(Integer.MAX_VALUE) StartupEvent event){ready=true;}
 void stopping(@Observes ShutdownEvent event){ready=false;}
}
