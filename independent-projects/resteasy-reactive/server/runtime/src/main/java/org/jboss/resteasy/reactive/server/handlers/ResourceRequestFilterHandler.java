package org.jboss.resteasy.reactive.server.handlers;

import javax.ws.rs.container.ContainerRequestFilter;

import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.jaxrs.ContainerRequestContextImpl;
import org.jboss.resteasy.reactive.server.spi.ServerRestHandler;

public class ResourceRequestFilterHandler implements ServerRestHandler {

    private final ContainerRequestFilter filter;
    private final boolean preMatch;
    private final boolean nonBlockingRequired;
    private final boolean withFormRead;

    public ResourceRequestFilterHandler(ContainerRequestFilter filter, boolean preMatch, boolean nonBlockingRequired,
            boolean withFormRead) {
        this.filter = filter;
        this.preMatch = preMatch;
        this.nonBlockingRequired = nonBlockingRequired;
        this.withFormRead = withFormRead;
    }

    public ContainerRequestFilter getFilter() {
        return filter;
    }

    public boolean isPreMatch() {
        return preMatch;
    }

    public boolean isNonBlockingRequired() {
        return nonBlockingRequired;
    }

    public boolean isWithFormRead() {
        return withFormRead;
    }

    @Override
    public void handle(ResteasyReactiveRequestContext requestContext) throws Exception {
        ContainerRequestContextImpl filterContext = requestContext.getContainerRequestContext();
        if (filterContext.isAborted()) {
            return;
        }
        requestContext.requireCDIRequestScope();
        filterContext.setPreMatch(preMatch);
        filter.filter(filterContext);
    }
}
