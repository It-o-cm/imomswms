package com.intermarche.backoffice.engine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.*;
import jakarta.inject.Inject;
@ApplicationScoped
public class ConfigurationCacheInvalidator {
    @Inject StoreActivityEngine activities;
    @Inject FulfillmentEngine fulfillment;
    void changed(@Observes(during=TransactionPhase.AFTER_SUCCESS) ConfigurationChanged event) {
        activities.evictAll();fulfillment.evictAll();
    }
}
