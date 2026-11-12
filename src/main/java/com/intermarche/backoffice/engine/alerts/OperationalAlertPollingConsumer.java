package com.intermarche.backoffice.engine.alerts;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.StoreActivityEngine;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
@ApplicationScoped
public class OperationalAlertPollingConsumer {
    @Inject StockAvailabilityAlerts stockAlerts;
    @Inject StoreActivityEngine activity;
    @Scheduled(every="1s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void stocks(){
        for(Long id:stockAlerts.dueAccounts())try{stockAlerts.evaluate(id);}catch(Exception e){org.jboss.logging.Logger.getLogger(getClass()).error("Stock alert evaluation failed for account "+id,e);}
    }
    @Transactional public List<Store> stores(){return Store.listAll();}
    @Scheduled(every="15s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void handling(){
        for(Store store:stores())activity.checkAndEmitLateHandlingOrders(store);
    }
}
