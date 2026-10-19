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
            .exec(dashboard.getAccounts())
            .exec(accounts.deposit(), accounts.transfer(), accounts.withdraw());

    {
        setUp(operations.injectOpen(rampUsers(100).during(60)))
                .protocols(HTTP_PROTOCOL)
                .assertions(
                        global().successfulRequests().percent().gte(99.0),
                        global().responseTime().percentile3().lt(1500)
                );
    }
}
