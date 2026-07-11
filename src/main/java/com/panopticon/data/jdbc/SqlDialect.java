package com.panopticon.data.jdbc;

import java.util.Locale;

/**
 * Which SQL dialect a jdbc datasource speaks, from {@code dialect} in its
 * config. Deliberately minimal for now: row limiting is already portable
 * via {@code Statement.setMaxRows()} (works the same across H2/Oracle/SQLite),
 * so there's no dialect-specific behavior to implement yet. The enum exists
 * as the extension point — e.g. a future dialect-specific pagination clause
 * or identifier-quoting rule would switch on this — without needing to
 * change every call site once one is actually needed.
 */
public enum SqlDialect {
    GENERIC, H2, ORACLE, SQLITE;

    public static SqlDialect fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }
        try {
            return SqlDialect.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GENERIC;
        }
    }
}
