package com.intermarche.backoffice.engine.operator;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.persistence.*;
import java.util.*;

/** Public business failure. Details from persistence/framework exceptions are logged, never displayed. */
public class OperatorFailure extends WebApplicationException {
    public OperatorFailure(int status,String code,String message){super(Response.status(status).entity(Map.of("code",code,"message",message,"trackingId",UUID.randomUUID().toString())).build());}
    public static RuntimeException safe(RuntimeException failure) {
        if(failure instanceof OperatorFailure)return failure;
        for(Throwable c=failure;c!=null;c=c.getCause()) {
            if(c instanceof OptimisticLockException || c instanceof PessimisticLockException || c instanceof LockTimeoutException)
                return new OperatorFailure(409,"RESOURCE_CONFLICT","Une autre opération modifie cette ressource. Actualisez avant de réessayer.");
        }
        if(failure instanceof IllegalStateException && failure.getMessage()!=null && failure.getMessage().startsWith("Tote ") && failure.getMessage().contains("is already in use by LoadedTote #"))
            return new OperatorFailure(409,"TOTE_IN_USE","Ce bac est déjà utilisé. Choisissez un autre bac.");
        if(failure instanceof WebApplicationException w && w.getResponse().getStatus()<500)return failure;
        if(failure instanceof IllegalArgumentException)return new OperatorFailure(400,"INVALID_COMMAND",failure.getMessage()==null?"Commande invalide":failure.getMessage());
        var publicFailure=new OperatorFailure(503,"SERVICE_UNAVAILABLE","Le service ne peut pas confirmer l’opération. Consultez son reçu avant de la renvoyer avec la même référence.");
        org.jboss.logging.Logger.getLogger(OperatorFailure.class).errorf(failure,"Operator command failed trackingId=%s",((Map<?,?>)publicFailure.getResponse().getEntity()).get("trackingId"));
        return publicFailure;
    }
}
