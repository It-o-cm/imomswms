package com.intermarche.backoffice.graphql.handling;

import com.intermarche.backoffice.graphql.GraphQLTrait;
import graphql.schema.DataFetchingEnvironment;
import io.smallrye.graphql.api.Context;

public interface HandlingOrderGraphQLTrait extends GraphQLTrait {

        default String optimizeHandlingOrderQuery(Context context) {
            DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
            boolean wantsStore = env.getSelectionSet().contains("store");
            boolean wantsAssignee = env.getSelectionSet().contains("assignee");
            StringBuilder query = new StringBuilder();
            if (wantsStore) query.append("left join fetch o.store s ");
            if (wantsAssignee) query.append("left join fetch o.assignee a ");
            return query.toString();
        }

}
