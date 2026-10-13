package com.intermarche.backoffice.graphql.handling;

import graphql.schema.DataFetchingEnvironment;
import io.smallrye.graphql.api.Context;

public interface CollectingOrderGraphQLTrait extends HandlingOrderGraphQLTrait {

    default String optimizeCollectingQuery(Context context) {
        DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
        boolean wantsLots = env.getSelectionSet().contains("lots");
        boolean wantsChunk = env.getSelectionSet().contains("chunk");
        boolean wantsFulfillment = env.getSelectionSet().contains("fulfillment");
        boolean wantsOrder = env.getSelectionSet().contains("order");
        StringBuilder query = new StringBuilder();
        query.append(this.optimizeHandlingOrderQuery(context));
        if (wantsLots) {
            query.append("left join fetch o.lots l ");
            if (wantsChunk) {
                query.append("left join fetch l.chunk fc ");
            }
            if (wantsFulfillment) {
                query.append("left join fetch l.fulfillment fl ");
                if (wantsOrder) {
                    query.append("left join fetch l.order o ");
                }
            }
        }
        return query.toString();
    }

}
