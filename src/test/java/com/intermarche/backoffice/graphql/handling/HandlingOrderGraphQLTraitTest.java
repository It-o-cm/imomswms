package com.intermarche.backoffice.graphql.handling;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import io.smallrye.graphql.api.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HandlingOrderGraphQLTrait#optimizeHandlingOrderQuery}.
 * Verifies the generated JPQL join fragments based on the requested selection set.
 */
class HandlingOrderGraphQLTraitTest {

    private Context contextWithSelection(boolean store, boolean assignee) {
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains("store")).thenReturn(store);
        when(selectionSet.contains("assignee")).thenReturn(assignee);

        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(selectionSet);

        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
        return context;
    }

    private HandlingOrderGraphQLTrait newTrait() {
        return mock(HandlingOrderGraphQLTrait.class, CALLS_REAL_METHODS);
    }

    @Test
    void optimize_noSelection_returnsEmptyString() {
        String query = newTrait().optimizeHandlingOrderQuery(contextWithSelection(false, false));
        assertEquals("", query);
    }

    @Test
    void optimize_storeOnly_appendsStoreJoin() {
        String query = newTrait().optimizeHandlingOrderQuery(contextWithSelection(true, false));
        assertEquals("left join fetch o.store s ", query);
    }

    @Test
    void optimize_assigneeOnly_appendsAssigneeJoin() {
        String query = newTrait().optimizeHandlingOrderQuery(contextWithSelection(false, true));
        assertEquals("left join fetch o.assignee a ", query);
    }

    @Test
    void optimize_bothSelections_appendsBothJoinsInOrder() {
        String query = newTrait().optimizeHandlingOrderQuery(contextWithSelection(true, true));
        assertEquals("left join fetch o.store s left join fetch o.assignee a ", query);
    }
}
