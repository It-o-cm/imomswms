package com.intermarche.backoffice.graphql.handling;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import io.smallrye.graphql.api.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CollectingOrderGraphQLTrait#optimizeCollectingQuery}.
 * Verifies the generated JPQL join fragments based on the requested selection set.
 */
class CollectingOrderGraphQLTraitTest {

    private Context contextWithSelection(boolean store, boolean assignee, boolean lots,
                                         boolean chunk, boolean fulfillment, boolean order) {
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        when(selectionSet.contains("store")).thenReturn(store);
        when(selectionSet.contains("assignee")).thenReturn(assignee);
        when(selectionSet.contains("lots")).thenReturn(lots);
        when(selectionSet.contains("chunk")).thenReturn(chunk);
        when(selectionSet.contains("fulfillment")).thenReturn(fulfillment);
        when(selectionSet.contains("order")).thenReturn(order);

        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(selectionSet);

        Context context = mock(Context.class);
        when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
        return context;
    }

    private CollectingOrderGraphQLTrait newTrait() {
        return mock(CollectingOrderGraphQLTrait.class, CALLS_REAL_METHODS);
    }

    @Test
    void optimize_noSelection_returnsEmptyString() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, false, false, false, false, false));
        assertEquals("", query);
    }

    @Test
    void optimize_lotsOnly_appendsLotsJoin() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, false, true, false, false, false));
        assertEquals("left join fetch o.lots l ", query);
    }

    @Test
    void optimize_lotsAndChunk_appendsChunkJoin() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, false, true, true, false, false));
        assertEquals("left join fetch o.lots l left join fetch l.chunk fc ", query);
    }

    @Test
    void optimize_lotsChunkFulfillment_appendsFulfillmentJoin() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, false, true, true, true, false));
        assertEquals("left join fetch o.lots l left join fetch l.chunk fc left join fetch l.fulfillment fl ", query);
    }

    @Test
    void optimize_allSelections_appendsOrderJoin() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, false, true, true, true, true));
        assertEquals("left join fetch o.lots l left join fetch l.chunk fc left join fetch l.fulfillment fl left join fetch fl.order fo ", query);
    }

    @Test
    void optimize_chunkWithoutLots_ignoresChunk() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, false, false, true, false, false));
        assertEquals("", query);
    }

    @Test
    void optimize_fulfillmentWithoutLots_ignoresFulfillment() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, false, false, false, true, false));
        assertEquals("", query);
    }

    @Test
    void optimize_orderWithoutFulfillment_ignoresOrder() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, false, true, true, false, true));
        assertEquals("left join fetch o.lots l left join fetch l.chunk fc ", query);
    }

    @Test
    void optimize_storeSelection_prependsStoreJoin() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(true, false, true, false, false, false));
        assertEquals("left join fetch o.store s left join fetch o.lots l ", query);
    }

    @Test
    void optimize_assigneeSelection_prependsAssigneeJoin() {
        String query = newTrait().optimizeCollectingQuery(contextWithSelection(false, true, false, false, false, false));
        assertEquals("left join fetch o.assignee a ", query);
    }
}
