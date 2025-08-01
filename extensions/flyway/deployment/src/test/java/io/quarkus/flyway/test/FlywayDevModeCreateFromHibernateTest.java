package io.quarkus.flyway.test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.hamcrest.CoreMatchers;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.devui.tests.DevUIJsonRPCTest;
import io.quarkus.runtime.Startup;
import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;

public class FlywayDevModeCreateFromHibernateTest extends DevUIJsonRPCTest {

    public FlywayDevModeCreateFromHibernateTest() {
        super("quarkus-flyway");
    }

    @RegisterExtension
    static final QuarkusDevModeTest config = new QuarkusDevModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(FlywayDevModeCreateFromHibernateTest.class, Endpoint.class, Fruit.class)
                    .addAsResource(new StringAsset(
                            "quarkus.flyway.locations=db/create"), "application.properties"));

    @Test
    public void testGenerateMigrationFromHibernate() throws Exception {
        RestAssured.get("fruit").then().statusCode(200)
                .body("[0].name", CoreMatchers.is("Orange"));

        Map<String, Object> params = Map.of("ds", "<default>");
        JsonNode devuiresponse = super.executeJsonRPCMethod("create", params);

        Assertions.assertNotNull(devuiresponse);
        String type = devuiresponse.get("type").asText();
        Assertions.assertNotNull(type);
        Assertions.assertEquals("success", type);

        config.modifySourceFile(Fruit.class, s -> s.replace("Fruit {", "Fruit {\n" +
                "    \n" +
                "    private String color;\n" +
                "\n" +
                "    public String getColor() {\n" +
                "        return color;\n" +
                "    }\n" +
                "\n" +
                "    public Fruit setColor(String color) {\n" +
                "        this.color = color;\n" +
                "        return this;\n" +
                "    }"));
        //added a field, should now fail (if hibernate were still in charge this would work)
        RestAssured.get("fruit").then().statusCode(500);
        //now update out sql
        config.modifyResourceFile("db/create/V1.0.0__quarkus-flyway-deployment.sql", new Function<String, String>() {
            @Override
            public String apply(String s) {
                return s + "\nalter table FRUIT add column color VARCHAR;";
            }
        });
        // TODO: This still fails.
        //        RestAssured.get("fruit").then().statusCode(200)
        //                .body("[0].name", CoreMatchers.is("Orange"));
    }

    @Path("/fruit")
    @Startup
    public static class Endpoint {

        @Inject
        EntityManager entityManager;

        @Inject
        UserTransaction tx;

        @GET
        public List<Fruit> list() {
            return entityManager.createQuery("from Fruit", Fruit.class).getResultList();
        }

        @PostConstruct
        @Transactional
        public void add() throws Exception {
            tx.begin();
            try {
                Fruit f = new Fruit();
                f.setName("Orange");
                entityManager.persist(f);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                throw e;
            }
        }

    }
}
