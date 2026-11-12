package com.intermarche.backoffice.engine.operator;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.core.Response;
import java.io.*;
import java.util.Map;
@Provider @PreMatching
public class OperatorPayloadLimit implements ContainerRequestFilter {
 public void filter(ContainerRequestContext request)throws IOException {
  if(!request.getMethod().equals("POST") || !request.getUriInfo().getPath().endsWith("operator/support/diagnostics"))return;
  byte[] bytes=request.getEntityStream().readNBytes(16385);
  if(bytes.length>16384){request.abortWith(Response.status(413).entity(Map.of("code","DIAGNOSTIC_TOO_LARGE","message","Diagnostic limité à 16 Ko.")).build());return;}
  request.setEntityStream(new ByteArrayInputStream(bytes));
 }
}
