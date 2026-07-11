package com.panopticon.it;

import com.panopticon.registry.DashboardRegistry;
import com.panopticon.registry.DataRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact that the Spring context loads at all already proves ConfigValidator
 * accepted every it-config/ fixture (dashboards, panel grid positions, chart
 * option requirements, data definition SQL guard compliance) - see
 * RegistryConfig, which fails startup on any validation error. This class
 * just adds a couple of cheap counts as a fast, specific anchor.
 */
class ContextAndConfigSmokeIT extends AbstractProductionLikeIT {

    @Autowired
    private DashboardRegistry dashboardRegistry;

    @Autowired
    private DataRegistry dataRegistry;

    @Test
    void loadsAllFixtureDashboards() {
        assertThat(dashboardRegistry.all()).extracting(d -> d.id()).containsExactlyInAnyOrder(
                "user-operations", "support-ticketing", "transactions-and-events", "fault-injection-lab");
    }

    @Test
    void loadsAllFixtureDataDefinitions() {
        assertThat(dataRegistry.all()).hasSize(26);
    }

    @Test
    void trafficSimulatorIsActivelyWritingByTheTimeTestsRun() {
        assertThat(simulator.usersCreated()).isPositive();
        assertThat(simulator.ticketsCreated()).isPositive();
        assertThat(simulator.transactionsCreated()).isPositive();
    }
}
