package com.panopticon.data.jdbc;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Basic (not exhaustive) read-only guard for panel/data SQL. Panopticon
 * queries are meant to be reporting-only, so this rejects the obvious
 * mutating/DDL/session/procedural statements before they ever reach the
 * driver. It is a defense-in-depth check, not a substitute for datasource
 * credentials that are themselves read-only.
 */
public final class SqlGuard {

    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "insert", "update", "delete", "merge", "drop", "alter", "truncate",
            "create", "grant", "revoke", "execute", "exec", "call",
            "begin", "commit", "rollback"
    );

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern LEADING_SELECT_OR_CTE = Pattern.compile("^(select|with)\\b", Pattern.CASE_INSENSITIVE);

    private SqlGuard() {
    }

    public static void assertReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlGuardException("Query SQL is empty");
        }

        String cleaned = stripCommentsAndLiterals(sql).trim();

        if (!LEADING_SELECT_OR_CTE.matcher(cleaned).find()) {
            throw new SqlGuardException("Only SELECT/WITH statements are allowed");
        }

        String withoutTrailingSemicolon = cleaned.endsWith(";")
                ? cleaned.substring(0, cleaned.length() - 1)
                : cleaned;
        if (withoutTrailingSemicolon.contains(";")) {
            throw new SqlGuardException("Multiple statements are not allowed");
        }

        String lower = cleaned.toLowerCase(java.util.Locale.ROOT);
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (containsWord(lower, keyword)) {
                throw new SqlGuardException("Statement contains disallowed keyword: " + keyword);
            }
        }
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            int idx = text.indexOf(word, from);
            if (idx < 0) {
                return false;
            }
            boolean startBoundary = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int endIdx = idx + word.length();
            boolean endBoundary = endIdx == text.length() || !Character.isLetterOrDigit(text.charAt(endIdx));
            if (startBoundary && endBoundary) {
                return true;
            }
            from = idx + word.length();
        }
    }

    /** Blanks out string literals and comments (preserving length/position) so keyword checks don't false-positive/negative on literal content. */
    private static String stripCommentsAndLiterals(String sql) {
        String noBlockComments = BLOCK_COMMENT.matcher(sql).replaceAll(m -> " ".repeat(m.group().length()));
        String noLineComments = LINE_COMMENT.matcher(noBlockComments).replaceAll(m -> " ".repeat(m.group().length()));
        return STRING_LITERAL.matcher(noLineComments).replaceAll(m -> "'" + " ".repeat(Math.max(0, m.group().length() - 2)) + "'");
    }
}
