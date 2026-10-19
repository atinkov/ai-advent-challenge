package ru.lemanapro.perf.simulations;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import ru.lemanapro.perf.endpoints.AccountEndpoint;
import ru.lemanapro.perf.endpoints.AuthEndpoint;
import ru.lemanapro.perf.endpoints.DashboardEndpoint;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class OperationsSimulation extends Simulation {
    private final AuthEndpoint auth = new AuthEndpoint();
    private final DashboardEndpoint dashboard = new DashboardEndpoint();
    private final AccountEndpoint accounts = new AccountEndpoint();

    private final HttpProtocolBuilder HTTP_PROTOCOL = http.baseUrl("http://localhost:8080")
            .contentTypeHeader("application/json");

    ScenarioBuilder operations = scenario("Operations")
            .feed(csv("testdata/users.csv").random())
            .exec(auth.login())
            .exitHereIfFailed()
            .exec(dashboard.getAccounts())
            .exitHereIfFailed()
            .exec(accounts.deposit(), accounts.transfer(), accounts.withdraw());

    {
        setUp(operations.injectOpen(rampUsers(100).during(60)))
                .protocols(HTTP_PROTOCOL)
                // 99%: стенд локальный, сетевых сбоев быть не должно — допускаем единичные таймауты под нагрузкой.
                // 95-й перцентиль 1500 мс: SLA операций из урока 05 модуля 01 — ответ быстрее 2 с с запасом.
                // Авторизация: любой KO — техническая проблема стенда, поэтому строго ноль.
                .assertions(
                        global().successfulRequests().percent().gte(99.0),
                        global().responseTime().percentile3().lt(1500),
                        details("Login").failedRequests().count().is(0L)
                );
    }
}
