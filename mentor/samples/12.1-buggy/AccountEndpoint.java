package ru.lemanapro.perf.endpoints;

import io.gatling.javaapi.http.HttpRequestActionBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class AccountEndpoint {
    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String TRANSFER = "/api/v1/transfer/internal";

    public HttpRequestActionBuilder deposit() {
        return http("Deposit")
                .post(ACCOUNTS + "/#{accountId}/deposit")
                .header("Authorization", "Bearer #{accessToken}")
                .body(StringBody("#{depositRequest}"))
                .check(status().is(200))
                .check(jmesPath("amount").ofDouble().is(session -> session.getDouble("depositAmount")));
    }

    public HttpRequestActionBuilder transfer() {
        return http("Transfer internal")
                .post(TRANSFER)
                .header("Authorization", "Bearer #{accessToken}")
                .body(StringBody("#{transferRequest}"))
                .check(status().is(200));
    }

    public HttpRequestActionBuilder withdraw() {
        return http("Withdraw")
                .post(ACCOUNTS + "/#{accountId}/withdraw")
                .header("Authorization", "Bearer #{accessToken}")
                .body(StringBody("#{withdrawRequest}"))
                .check(status().is(200));
    }
}
