package ru.lemanapro.perf.endpoints;

import io.gatling.javaapi.http.HttpRequestActionBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class DashboardEndpoint {
    private static final String REQUEST_MAPPING = "/api/v1/dashboard";

    public HttpRequestActionBuilder getAccounts() {
        return http("Dashboard Page")
                .get(REQUEST_MAPPING + "/accounts")
                .header("Authorization", "Bearer #{accessToken}")
                .check(status().is(200))
                .check(jmesPath("length(accounts)").ofInt().gt(0))
                .check(bodyString().saveAs("dashboardResponse"));
    }
}
