package com.intermarche.backoffice.engine.operator;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

/** Uniform errors, including authentication and JSON parsing failures before the resource method. */
@Provider
public class OperatorResponseFilter implements ContainerResponseFilter {
    @jakarta.inject.Inject com.fasterxml.jackson.databind.ObjectMapper json;
    private String tracking(Object entity){
        if(entity instanceof Map<?,?> body && body.get("trackingId") instanceof String t)return t;
        if(entity instanceof String text)try{String t=json.readTree(text).path("trackingId").asText("");if(!t.isBlank())return t;}catch(Exception ignored){}
        return UUID.randomUUID().toString();
    }
    @Override public void filter(ContainerRequestContext request,ContainerResponseContext response) {
        String path=request.getUriInfo().getPath().replaceFirst("^/+", "");
        if(!path.startsWith("handling-orders/operator")&&!path.startsWith("product-information")&&!path.startsWith("field-operations")&&!path.startsWith("quality")&&!path.startsWith("inventories"))return;
        String tracking=tracking(response.getEntity());response.getHeaders().putSingle("X-Tracking-Id",tracking);
        int status=response.getStatus();if(status<400)return;
        String code=switch(status){case 400->"INVALID_COMMAND";case 401->"AUTHENTICATION_REQUIRED";case 403->"FORBIDDEN";case 404->"NOT_FOUND";case 409->"RESOURCE_CONFLICT";default->"SERVICE_UNAVAILABLE";};
        String message=switch(status){case 400->"Vérifiez les informations de la commande.";case 401->"Session expirée. Reconnectez-vous.";case 403->"Action ou magasin non autorisé.";case 404->"Ressource introuvable dans votre périmètre.";case 409->"La situation a changé. Actualisez avant de confirmer.";default->"Le service ne peut pas confirmer l’opération. Consultez son reçu avant de réessayer.";};
        // Only deliberately constructed public errors supply messages. Never expose raw framework bodies.
        if(response.getEntity() instanceof Map<?,?> body){
            if(body.get("code") instanceof String c)code=c;
            if(status<500 && body.get("message") instanceof String m)message=m;
        }
        boolean retry=status>=500 || status==409;
        response.setEntity(Map.of("contractVersion",2,"outcome","REJECTED","code",code,"message",message,"trackingId",tracking,"retryable",retry,
            "nextActions",status>=500?List.of("CHECK_RECEIPT","RETRY_SAME_KEY"):status==409?List.of("REFRESH"):List.of("CORRECT_INPUT")),null,MediaType.APPLICATION_JSON_TYPE);
        org.jboss.logging.Logger.getLogger(getClass()).warnf("Operator response %s trackingId=%s code=%s",status,tracking,code);
    }
}
