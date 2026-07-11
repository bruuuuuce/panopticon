package com.panopticon.data.jira;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Synthetic Jira client used until a real one exists. Generates a fixed pool
 * of plausible issues once at construction (relative to "now", like the H2
 * demo seed data, so it always looks current) and answers {@link #search}
 * against that in-memory pool — no HTTP call, no real Jira instance
 * required. {@code baseUrl}/{@code auth} on the owning datasource are
 * accepted but unused; they're there for a future real
 * {@code JiraDataProvider} implementation to read.
 *
 * <p><b>JQL is not parsed.</b> Real JQL is a full query language; teaching
 * this mock to parse it would be a project of its own. The one thing it
 * recognizes is the "not done" filter from the sample data definition
 * (a case-insensitive check for {@code != done} anywhere in the string) —
 * enough to make the bundled demo/mock data definition's output look like
 * what it claims to show, without pretending to be a JQL engine.
 */
public class MockJiraClient {

    private static final String[] STATUSES = {"To Do", "In Progress", "Blocked", "Done"};
    private static final String[] PRIORITIES = {"Low", "Medium", "High", "Critical"};
    private static final String[] ASSIGNEES = {"A. Rossi", "B. Chen", "C. Diallo", "D. Kim"};
    private static final String[] SUBJECTS = {
            "Login failure", "Billing discrepancy", "API timeout", "Data export issue", "Feature request"
    };
    private static final int ISSUE_COUNT = 60;

    private final List<JiraIssue> issues;

    public MockJiraClient(String projectKeyHint) {
        this.issues = generate(projectKeyHint);
    }

    public List<JiraIssue> search(String jql) {
        boolean excludeDone = jql != null && jql.toLowerCase(Locale.ROOT).contains("!= done");
        return issues.stream()
                .filter(issue -> !excludeDone || !"Done".equals(issue.status()))
                .toList();
    }

    private static List<JiraIssue> generate(String projectKeyHint) {
        // Just the leading word (e.g. "demo-jira" -> "DEMO"): a real Jira project
        // key is short, and datasource names here tend to be descriptive/hyphenated.
        String prefix = (projectKeyHint == null || projectKeyHint.isBlank())
                ? "DEMO"
                : projectKeyHint.toUpperCase(Locale.ROOT).split("[^A-Z0-9]")[0];
        Instant now = Instant.now();
        List<JiraIssue> generated = new ArrayList<>(ISSUE_COUNT);
        for (int i = 1; i <= ISSUE_COUNT; i++) {
            String status = STATUSES[i % STATUSES.length];
            String priority = PRIORITIES[(i / 3) % PRIORITIES.length];
            String assignee = ASSIGNEES[i % ASSIGNEES.length];
            String subject = SUBJECTS[i % SUBJECTS.length];
            Instant created = now.minus(i * 6L, ChronoUnit.HOURS);
            generated.add(new JiraIssue(prefix + "-" + (100 + i), subject + " (#" + i + ")", status, priority, assignee, created));
        }
        return generated;
    }
}
