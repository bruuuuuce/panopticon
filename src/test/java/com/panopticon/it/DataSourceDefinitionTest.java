package com.panopticon.it;

import com.panopticon.model.DataSourceDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataSourceDefinition is a plain record; no Spring context needed. Covers
 * the displayName fallback rule that lets every datasource have a
 * human-facing "connection name" (shown on dashboard panels and the query
 * stats page) even when {@code display-name} isn't configured.
 */
class DataSourceDefinitionTest {

    @Test
    void displayName_fallsBackToTheTechnicalKey_whenNotConfigured() {
        DataSourceDefinition ds = jdbc("secondary-sqlite", null);
        assertThat(ds.displayName()).isEqualTo("secondary-sqlite");
    }

    @Test
    void displayName_fallsBackToTheTechnicalKey_whenBlank() {
        DataSourceDefinition ds = jdbc("secondary-sqlite", "   ");
        assertThat(ds.displayName()).isEqualTo("secondary-sqlite");
    }

    @Test
    void displayName_isUsedAsIs_whenConfigured() {
        DataSourceDefinition ds = jdbc("demo-h2", "Demo H2 (Ticketing & Payments)");
        assertThat(ds.displayName()).isEqualTo("Demo H2 (Ticketing & Payments)");
        // The technical key is unaffected and still the one DataDefinition/pools reference.
        assertThat(ds.name()).isEqualTo("demo-h2");
    }

    private DataSourceDefinition jdbc(String name, String displayName) {
        return new DataSourceDefinition(name, displayName, "jdbc", "jdbc:h2:mem:x",
                "sa", "", "org.h2.Driver", "h2", true, 1, null, null, null, null, null);
    }
}
