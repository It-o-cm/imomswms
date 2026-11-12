package com.intermarche.backoffice.engine.commerce;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Structured refusal of the commerce contract: a reason, whether retrying may help, and an opaque correlation id. */
public class CommerceFailure extends WebApplicationException {
 /**
  * Creates a refusal.
  *
  * @param response the structured response
  */
 private CommerceFailure(Response response){super(response);}
 /**
  * Builds a refusal without client data nor technical trace.
  *
  * @param status the HTTP status
  * @param reason the contract reason
  * @param retryable whether a later retry with the same key may succeed
  * @return the failure
  */
 public static CommerceFailure of(int status,String reason,boolean retryable){var body=new LinkedHashMap<String,Object>();body.put("contractVersion",PickupContract.VERSION);body.put("reason",reason);body.put("retryable",retryable);body.put("correlationId",UUID.randomUUID().toString());
  return new CommerceFailure(Response.status(status).type(MediaType.APPLICATION_JSON).header("Cache-Control","no-store").entity(body).build());}
}
