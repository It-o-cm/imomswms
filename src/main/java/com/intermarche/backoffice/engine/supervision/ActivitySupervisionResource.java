package com.intermarche.backoffice.engine.supervision;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Activity supervision API. A manager is restricted to his own store, including for the detail of an order; an
 * administrator chooses one. Proposals are applied with the handling supervision endpoint, which revalidates them.
 * <ul>
 *   <li>GET /store-operations/activity-supervision?storeId=…&amp;horizonHours=… : indicators, chart series and situations.</li>
 *   <li>GET /store-operations/activity-supervision/orders/{id}?storeId=… : progress of one customer order, on demand.</li>
 * </ul>
 */
@Path("/store-operations/activity-supervision")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "MANAGER"})
@RunOnVirtualThread
@Transactional
public class ActivitySupervisionResource {

    @Inject StoreOperationsResource operations;
    @Inject ActivitySupervisionService supervision;

    /**
     * Returns the activity supervision of a store.
     *
     * @param storeId store, required for an administrator
     * @param horizonHours forecast horizon, by default the store setting
     * @return the report
     */
    @GET
    public ActivityModel.Report report(@QueryParam("storeId") Long storeId, @QueryParam("horizonHours") Integer horizonHours) {
        Store store = operations.scope(storeId);
        try {
            return supervision.analyze(store, horizonHours);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    /**
     * Returns the progress of one customer order of the store.
     *
     * @param id order identifier
     * @param storeId store, required for an administrator
     * @return the detail
     */
    @GET
    @Path("/orders/{id}")
    public ActivityModel.OrderDetail order(@PathParam("id") Long id, @QueryParam("storeId") Long storeId) {
        Store store = operations.scope(storeId);
        try {
            return supervision.order(store, id);
        } catch (NoSuchElementException e) {
            throw new NotFoundException(Response.status(404).type(MediaType.APPLICATION_JSON).entity(Map.of("message", e.getMessage())).build());
        }
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
