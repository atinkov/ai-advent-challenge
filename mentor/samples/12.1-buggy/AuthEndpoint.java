package ru.lemanapro.perf.endpoints;

import io.gatling.javaapi.http.HttpRequestActionBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class AuthEndpoint {
    public static final String AUTH_ENDPOINT = "/api/v1/auth";

    public HttpRequestActionBuilder login() {
        return http("Login")
                .post(AUTH_ENDPOINT + "/login")
                .body(StringBody("#{loginRequest}"))
                .check(status().is(200))
                .check(jmesPath("accessToken").saveAs("accessToken"));
    }
}
