package com.panopticon.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panopticon.model.PanelDefinition;
import com.panopticon.model.ThresholdDirection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PanelDefinition.fromJson has two backward-compatibility concerns to keep
 * working as the schema grows: the dataRef/queryRef rename fallback, and -
 * as of the fixed-thresholds feature - panels written before "thresholds"
 * existed must still parse with an empty list rather than fail. Plain
 * ObjectMapper, no Spring context needed (same as DataSourceDefinitionTest).
 */
class PanelDefinitionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesThresholdsFromJson() throws Exception {
        String json = """
                {
                  "id": "p1", "title": "Panel", "type": "stat", "dataRef": "d1",
                  "grid": {"row": 1, "col": 1, "rowSpan": 1, "colSpan": 3},
                  "options": {"valueField": "n"},
                  "thresholds": [
                    {"field": "n", "label": "Too high", "warning": 10, "critical": 20, "direction": "above"}
                  ]
                }
                """;
        PanelDefinition panel = mapper.readValue(json, PanelDefinition.class);

        assertThat(panel.thresholds()).hasSize(1);
        assertThat(panel.thresholds().get(0).field()).isEqualTo("n");
        assertThat(panel.thresholds().get(0).label()).isEqualTo("Too high");
        assertThat(panel.thresholds().get(0).warning()).isEqualTo(10.0);
        assertThat(panel.thresholds().get(0).critical()).isEqualTo(20.0);
        assertThat(panel.thresholds().get(0).direction()).isEqualTo(ThresholdDirection.ABOVE);
    }

    @Test
    void defaultsToNoThresholdsWhenFieldIsOmitted() throws Exception {
        String json = """
                {
                  "id": "p1", "title": "Panel", "type": "table", "dataRef": "d1",
                  "grid": {"row": 1, "col": 1, "rowSpan": 1, "colSpan": 3},
                  "options": {}
                }
                """;
        PanelDefinition panel = mapper.readValue(json, PanelDefinition.class);

        assertThat(panel.thresholds()).isEqualTo(List.of());
    }

    @Test
    void directionDefaultsToAboveWhenOmitted() throws Exception {
        String json = """
                {
                  "id": "p1", "title": "Panel", "type": "stat", "dataRef": "d1",
                  "grid": {"row": 1, "col": 1, "rowSpan": 1, "colSpan": 3},
                  "options": {"valueField": "n"},
                  "thresholds": [{"field": "n", "warning": 10}]
                }
                """;
        PanelDefinition panel = mapper.readValue(json, PanelDefinition.class);

        assertThat(panel.thresholds().get(0).direction()).isEqualTo(ThresholdDirection.ABOVE);
        assertThat(panel.thresholds().get(0).critical()).isNull();
    }

    @Test
    void legacyQueryRefFallback_stillWorksAlongsideThresholds() throws Exception {
        String json = """
                {
                  "id": "p1", "title": "Panel", "type": "table", "queryRef": "old-data-id",
                  "grid": {"row": 1, "col": 1, "rowSpan": 1, "colSpan": 3},
                  "options": {}
                }
                """;
        PanelDefinition panel = mapper.readValue(json, PanelDefinition.class);

        assertThat(panel.dataRef()).isEqualTo("old-data-id");
        assertThat(panel.thresholds()).isEmpty();
    }
}
