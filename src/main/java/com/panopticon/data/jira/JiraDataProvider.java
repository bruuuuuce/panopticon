package com.panopticon.data.jira;

import com.panopticon.data.DataExecutionContext;
import com.panopticon.data.DataProvider;
import com.panopticon.model.ColumnDefinition;
import com.panopticon.model.DataDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.model.DataSourceDefinition;
import com.panopticon.registry.DataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes Jira-shaped data definitions. Backed entirely by
 * {@link MockJiraClient} for now — see that class for why. Supports three
 * operations: {@code issue-search} (a table of matching issues),
 * {@code issue-count} (a single total, KPI-shaped), and
 * {@code issue-count-by-field} (grouped counts, bar/donut-shaped).
 */
@Component
public class JiraDataProvider implements DataProvider {

    private static final Logger log = LoggerFactory.getLogger(JiraDataProvider.class);
    public static final String PROVIDER_TYPE = "jira";

    private static final List<String> DEFAULT_SEARCH_FIELDS = List.of("key", "summary", "status", "priority", "assignee", "created");
    private static final List<String> KNOWN_OPERATIONS = List.of("issue-search", "issue-count", "issue-count-by-field");

    private final Map<String, MockJiraClient> clients;

    public JiraDataProvider(DataSourceRegistry dataSourceRegistry) {
        Map<String, MockJiraClient> built = new LinkedHashMap<>();
        for (DataSourceDefinition datasource : dataSourceRegistry.all()) {
            if (!PROVIDER_TYPE.equals(datasource.provider())) {
                continue;
            }
            built.put(datasource.name(), new MockJiraClient(datasource.name()));
            log.info("Registered jira datasource '{}' (mock client, baseUrl={})", datasource.name(), datasource.baseUrl());
        }
        this.clients = Map.copyOf(built);
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }


    @Override
    public DataResult execute(DataExecutionContext context) {
        DataDefinition definition = context.definition();
        if (definition.operation() == null || definition.operation().isBlank()) {
            return DataResult.error("Data definition '%s' is missing 'operation' for the jira provider".formatted(definition.id()));
        }
        if (!KNOWN_OPERATIONS.contains(definition.operation())) {
            return DataResult.error("Unsupported jira operation '%s'. Known operations: %s"
                    .formatted(definition.operation(), KNOWN_OPERATIONS));
        }

        MockJiraClient client = clients.get(context.datasource().name());
        if (client == null) {
            return DataResult.error("Datasource '%s' is not a jira datasource".formatted(context.datasource().name()));
        }

        long start = System.nanoTime();
        List<JiraIssue> issues = client.search(definition.jql());
        DataResult partial = switch (definition.operation()) {
            case "issue-search" -> issueSearch(issues, definition);
            case "issue-count" -> issueCount(issues);
            case "issue-count-by-field" -> issueCountByField(issues, definition);
            default -> throw new IllegalStateException("unreachable: validated above");
        };
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return DataResult.ok(partial.columns(), partial.rows(), Instant.now(), elapsedMs);
    }

    private DataResult issueSearch(List<JiraIssue> issues, DataDefinition definition) {
        List<String> fields = definition.fields().isEmpty() ? DEFAULT_SEARCH_FIELDS : definition.fields();
        List<ColumnDefinition> columns = fields.stream()
                .map(f -> new ColumnDefinition(f.toLowerCase(Locale.ROOT), "string"))
                .toList();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JiraIssue issue : issues) {
            if (rows.size() >= definition.maxRows()) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : fields) {
                row.put(field.toLowerCase(Locale.ROOT), fieldValue(issue, field));
            }
            rows.add(row);
        }
        return DataResult.ok(columns, rows, null, 0);
    }

    private DataResult issueCount(List<JiraIssue> issues) {
        List<ColumnDefinition> columns = List.of(new ColumnDefinition("total", "number"));
        List<Map<String, Object>> rows = List.of(Map.of("total", issues.size()));
        return DataResult.ok(columns, rows, null, 0);
    }

    private DataResult issueCountByField(List<JiraIssue> issues, DataDefinition definition) {
        String groupBy = (definition.groupBy() == null || definition.groupBy().isBlank()) ? "status" : definition.groupBy();
        String columnName = groupBy.toLowerCase(Locale.ROOT);

        Map<Object, Long> counts = issues.stream()
                .collect(Collectors.groupingBy(issue -> fieldValue(issue, groupBy), LinkedHashMap::new, Collectors.counting()));

        List<ColumnDefinition> columns = List.of(new ColumnDefinition(columnName, "string"), new ColumnDefinition("total", "number"));
        List<Map<String, Object>> rows = counts.entrySet().stream()
                .sorted(Map.Entry.<Object, Long>comparingByValue().reversed())
                .limit(definition.maxRows())
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(columnName, entry.getKey());
                    row.put("total", entry.getValue());
                    return row;
                })
                .toList();
        return DataResult.ok(columns, rows, null, 0);
    }

    private Object fieldValue(JiraIssue issue, String field) {
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "key" -> issue.key();
            case "summary" -> issue.summary();
            case "status" -> issue.status();
            case "priority" -> issue.priority();
            case "assignee" -> issue.assignee();
            case "created" -> issue.created();
            default -> null;
        };
    }
}
