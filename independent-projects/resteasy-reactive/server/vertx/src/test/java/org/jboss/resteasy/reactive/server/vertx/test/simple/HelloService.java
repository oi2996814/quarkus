package org.jboss.resteasy.reactive.server.vertx.test.simple;

import java.util.Objects;

import javax.enterprise.context.RequestScoped;
import javax.ws.rs.core.Context;

import io.vertx.ext.web.RoutingContext;

@RequestScoped
public class HelloService {

    @Context
    RoutingContext context;

    public String sayHello() {
        Objects.requireNonNull(context);
        return "Hello";
    }
}
