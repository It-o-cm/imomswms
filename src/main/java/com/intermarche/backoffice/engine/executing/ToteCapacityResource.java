package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/handling-orders/planning/tote-capacities")
@Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER"})
public class ToteCapacityResource implements GraphQLTrait {
    @Inject SecurityIdentity identity;
    @Inject ToteCapacityService capacities;
    @Override public SecurityIdentity getIdentity() {return identity;}
    @POST public List<ToteCapacityService.Capacity> preview(ToteCapacityService.Request request) {
        if(request==null || request.orderIds==null || request.orderIds.isEmpty()) throw new BadRequestException("Orders are required");
        Store scope=identity.hasRole("ADMIN")?null:getStoreContext();
        for(Long id:request.orderIds) {
            BaseHandlingOrder order=BaseHandlingOrder.findById(id);
            if(order==null) throw new NotFoundException("Order not found");
            if(scope!=null && !scope.id.equals(order.store.id)) throw new ForbiddenException("Order outside your store");
        }
        try {return capacities.preview(request);}
        catch(IllegalArgumentException e) {throw new BadRequestException(e.getMessage());}
    }
}
