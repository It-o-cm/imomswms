package com.intermarche.backoffice.engine.operator;

import com.intermarche.backoffice.engine.executing.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** Commit before building the HTTP acknowledgement; commit failures use the same safe error contract. */
@ApplicationScoped
public class OperatorCommandTransaction {
    @Inject OperatorExecutionService service;
    @Inject OperatorSupportService support;
    @Transactional
    public String apply(OperatorCommandInput input){String result=service.apply(input);support.confirmed(input,result);return result;}
}
