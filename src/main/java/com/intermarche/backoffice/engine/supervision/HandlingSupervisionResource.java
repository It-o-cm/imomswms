package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import com.intermarche.backoffice.engine.supervision.SupervisionModel.ApplyRequest;
import com.intermarche.backoffice.engine.supervision.SupervisionModel.ApplyResult;
import com.intermarche.backoffice.engine.supervision.SupervisionModel.Report;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Handling supervision API. A manager is restricted to his own store; an administrator chooses one.
 * <ul>
 *   <li>GET /store-operations/supervision?storeId=…&amp;start=…&amp;end=… : anomalies, counters and filter values.</li>
 *   <li>POST /store-operations/supervision/apply : applies one proposal after server-side revalidation; 409 when stale.</li>
 * </ul>
 */
@Path("/store-operations/supervision")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "MANAGER"})
@RunOnVirtualThread
@Transactional
public class HandlingSupervisionResource {

    @Inject StoreOperationsResource operations;
    @Inject HandlingSupervisionService supervision;
    @Inject SupervisionActionService actions;

    /**
     * Returns the supervision of a store for a period, by default the current day.
     *
     * @param storeId store, required for an administrator
     * @param startText ISO local date-time
     * @param endText ISO local date-time
     * @return the report
     */
    @GET
    public Report report(@QueryParam("storeId") Long storeId, @QueryParam("start") String startText, @QueryParam("end") String endText) {
        Store store = operations.scope(storeId);
        LocalDateTime start, end;
        try {
            start = startText == null || startText.isBlank() ? DateTimeProvider.now().toLocalDate().atStartOfDay() : LocalDateTime.parse(startText);
            end = endText == null || endText.isBlank() ? start.plusDays(1) : LocalDateTime.parse(endText);
        } catch (RuntimeException e) {
            throw bad("Dates de supervision invalides.");
        }
        try {
            return supervision.analyze(store, start, end);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    /**
     * Applies a validated proposal.
     *
     * @param request the proposal and the state it was computed on
     * @return 200 with the result, or 409 with the reasons when the situation changed
     */
    @POST
    @Path("/apply")
    public Response apply(ApplyRequest request) {
        if (request == null) throw bad("Proposition requise.");
        Store store = operations.scope(request.storeId());
        ApplyResult result;
        try {
            result = actions.apply(store, request);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        return Response.status("APPLIED".equals(result.status()) ? 200 : 409).entity(result).build();
    }

    /**
     * Builds a 400 answer with a JSON message, like the other store operations.
     *
     * @param message message
     * @return the exception
     */
    private BadRequestException bad(String message) {
        return new BadRequestException(Response.status(400).type(MediaType.APPLICATION_JSON).entity(Map.of("message", message)).build());
    }
}
