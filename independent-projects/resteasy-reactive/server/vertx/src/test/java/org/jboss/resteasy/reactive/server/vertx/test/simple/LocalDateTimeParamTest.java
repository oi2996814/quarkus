package org.jboss.resteasy.reactive.server.vertx.test.simple;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.hamcrest.Matchers;
import org.jboss.resteasy.reactive.DateFormat;
import org.jboss.resteasy.reactive.RestCookie;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.Separator;
import org.jboss.resteasy.reactive.server.vertx.test.framework.ResteasyReactiveUnitTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.restassured.RestAssured;

public class LocalDateTimeParamTest {

    @RegisterExtension
    static ResteasyReactiveUnitTest test = new ResteasyReactiveUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(HelloResource.class, CustomDateTimeFormatterProvider.class));

    @Test
    public void localDateTimeAsQueryParam() {
        RestAssured.get("/hello?date=1984-08-08T01:02:03")
                .then().statusCode(200).body(Matchers.equalTo("hello#1984"));

        RestAssured.get("/hello?date=")
                .then().statusCode(200).body(Matchers.equalTo("hello#null"));
    }

    @Test
    public void localDateTimeCollectionAsQueryParam() {
        RestAssured.get("/hello/list?date=1984-08-08T01:02:03,1992-04-25T01:02:03")
                .then().statusCode(200).body(Matchers.equalTo("hello#1984,1992"));

        RestAssured.get("/hello/list?date=&date=1984-08-08T01:02:03")
                .then().statusCode(200).body(Matchers.equalTo("hello#1984"));
    }

    @Test
    public void localDateTimeAsOptionalQueryParam() {
        RestAssured.get("/hello/optional?date=1984-08-08T01:02:03")
                .then().statusCode(200).body(Matchers.equalTo("hello#1984"));

        RestAssured.get("/hello/optional")
                .then().statusCode(200).body(Matchers.equalTo("hello#2022"));
    }

    @Test
    public void localDateTimeAsPathParam() {
        RestAssured.get("/hello/1995-09-21 01:02:03")
                .then().statusCode(200).body(Matchers.equalTo("hello@9"));
    }

    @Test
    public void localDateTimeAsFormParam() {
        RestAssured.given().formParam("date", "1995/09/22 01:02").post("/hello")
                .then().statusCode(200).body(Matchers.equalTo("hello:22"));

        RestAssured.given().formParam("date", "").post("/hello")
                .then().statusCode(200).body(Matchers.equalTo("hello:null"));
    }

    @Test
    public void localDateTimeCollectionAsFormParam() {
        RestAssured.given().formParam("date", "1995/09/22 01:02", "1992/04/25 01:02").post("/hello/list")
                .then().statusCode(200).body(Matchers.equalTo("hello:22,25"));
    }

    @Test
    public void localDateTimeAsHeader() {
        RestAssured.with().header("date", "1984-08-08 01:02:03")
                .get("/hello/header")
                .then().statusCode(200).body(Matchers.equalTo("hello=1984-08-08T01:02:03"));

        RestAssured.with().header("date", "")
                .get("/hello/header")
                .then().statusCode(200).body(Matchers.equalTo("hello=null"));
    }

    @Test
    public void localDateTimeAsHeaderList() {
        RestAssured.with().header("date", "", "1984-08-08 01:02:03", "")
                .get("/hello/header/list")
                .then().statusCode(200).body(Matchers.equalTo("hello=[1984-08-08T01:02:03]"));

        RestAssured.with().header("date", "")
                .get("/hello/header/list")
                .then().statusCode(200).body(Matchers.equalTo("hello=[]"));
    }

    @Test
    public void localDateTimeAsCookie() {
        RestAssured.with().cookie("date", "1984-08-08 01:02:03")
                .get("/hello/cookie")
                .then().statusCode(200).body(Matchers.equalTo("hello/1984-08-08T01:02:03"));

        RestAssured.with().cookie("date", "")
                .get("/hello/cookie")
                .then().statusCode(200).body(Matchers.equalTo("hello/null"));
    }

    @Path("hello")
    public static class HelloResource {

        @GET
        public String helloQuery(@RestQuery LocalDateTime date) {
            if (date == null) {
                return "hello#null";
            }
            return "hello#" + date.getYear();
        }

        @GET
        @Path("list")
        public String helloQuerySet(@RestQuery @Separator(",") Set<LocalDateTime> date) {
            String joinedYears = date.stream()
                    .map(LocalDateTime::getYear)
                    .sorted()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            return "hello#" + joinedYears;
        }

        @Path("optional")
        @GET
        public String helloOptionalQuery(@RestQuery Optional<LocalDateTime> date) {
            return "hello#" + date.orElse(LocalDateTime.of(2022, 1, 1, 0, 0)).getYear();
        }

        @GET
        @Path("{date}")
        public String helloPath(@RestPath @DateFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime date) {
            return "hello@" + date.getMonthValue();
        }

        @POST
        public String helloForm(
                @FormParam("date") @DateFormat(dateTimeFormatterProvider = CustomDateTimeFormatterProvider.class) LocalDateTime date) {
            if (date == null) {
                return "hello:null";
            }
            return "hello:" + date.getDayOfMonth();
        }

        @POST
        @Path("list")
        public String helloFormSet(
                @FormParam("date") @DateFormat(dateTimeFormatterProvider = CustomDateTimeFormatterProvider.class) Set<LocalDateTime> dates) {
            String joinedDays = dates.stream()
                    .map(LocalDateTime::getDayOfMonth)
                    .sorted()
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            return "hello:" + joinedDays;
        }

        @Path("cookie")
        @GET
        public String helloCookie(@RestCookie @DateFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime date) {
            return "hello/" + date;
        }

        @Path("header")
        @GET
        public String helloHeader(@RestHeader @DateFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime date) {
            return "hello=" + date;
        }

        @Path("header/list")
        @GET
        public String helloHeaderList(@RestHeader @DateFormat(pattern = "yyyy-MM-dd HH:mm:ss") List<LocalDateTime> date) {
            return "hello=" + date;
        }
    }

    public static class CustomDateTimeFormatterProvider implements DateFormat.DateTimeFormatterProvider {
        @Override
        public DateTimeFormatter get() {
            return DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
        }
    }

}
