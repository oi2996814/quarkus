package io.quarkus.devui.testrunner.params;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.vertx.ext.web.Router;

@ApplicationScoped
public class Setup {

    public void route(@Observes Router router) {
        router.route("/hello").handler(new HelloResource());
        router.route("/odd/:num").handler(new OddResource());
    }

}
