package com.intermarche.backoffice.testutil;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import org.hibernate.Session;
import org.mockito.MockedStatic;

import java.lang.annotation.Annotation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test support for Panache active-record calls outside of a running Quarkus application.
 * <p>
 * In Quarkus 3.x, instance methods such as {@code entity.persist()} / {@code entity.delete()} and
 * static helpers such as {@code Panache.flush()} / {@code Panache.getEntityManager()} all resolve
 * the Hibernate {@link Session} through {@code Arc.container().instance(Session.class).get()}.
 * Outside Quarkus, {@code Arc.container()} is null and those calls fail with an NPE.
 * <p>
 * {@link #mockArcSession(MockedStatic)} stubs {@link Arc#container()} so that every one of those
 * calls is routed to a single mock {@link Session}. Persistence calls then become no-ops and can
 * be verified with {@code verify(session).persist(entity)} etc.
 * <p>
 * Note: {@code PanacheEntityBase.getEntityManager()} is a build-time stub that always throws
 * outside Quarkus; it must be stubbed separately via {@code mockStatic(PanacheEntityBase.class)}
 * when the code under test calls it directly.
 */
public final class PanacheTestSupport {

    private PanacheTestSupport() {
    }

    /**
     * Stubs {@link Arc#container()} so that all Panache persistence calls resolve to a mock session.
     *
     * @param arcMock the open {@link MockedStatic} for {@link Arc}
     * @return the mock {@link Session} backing all Panache persistence calls
     */
    @SuppressWarnings("unchecked")
    public static Session mockArcSession(MockedStatic<Arc> arcMock) {
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<Session> handle = mock(InstanceHandle.class);
        Session session = mock(Session.class);
        arcMock.when(Arc::container).thenReturn(container);
        when(container.instance(eq(Session.class), any(Annotation[].class))).thenReturn(handle);
        when(handle.get()).thenReturn(session);
        return session;
    }
}
