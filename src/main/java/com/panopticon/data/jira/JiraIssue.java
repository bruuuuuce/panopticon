package com.panopticon.data.jira;

import java.time.Instant;

/** A single synthetic issue from {@link MockJiraClient}. */
public record JiraIssue(String key, String summary, String status, String priority, String assignee, Instant created) {
}
