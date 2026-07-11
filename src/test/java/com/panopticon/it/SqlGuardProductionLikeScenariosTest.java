package com.panopticon.it;

import com.panopticon.data.jdbc.SqlGuard;
import com.panopticon.data.jdbc.SqlGuardException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SqlGuard.assertReadOnly runs at config-validation time (see ConfigValidator)
 * as well as at execution time, so a data definition attempting one of these
 * against the production-like schema can never even reach the database -
 * that's exactly why this suite tests SqlGuard directly instead of shipping a
 * real mutating data-definition fixture, which would simply fail application
 * startup (a good thing - proven by these cases). No Spring context needed:
 * SqlGuard is a pure static utility.
 */
class SqlGuardProductionLikeScenariosTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM users",
            "DELETE FROM tickets WHERE status = 'CLOSED'",
            "UPDATE tickets SET status = 'CLOSED' WHERE id = 1",
            "UPDATE users SET status = 'DELETED'",
            "INSERT INTO reports (reporter_user_id, target_type, reason, severity, status, created_at) VALUES (1, 'USER', 'x', 'LOW', 'NEW', '2026-01-01')",
            "DROP TABLE event_outbox",
            "ALTER TABLE users ADD COLUMN backdoor TEXT",
            "TRUNCATE TABLE tickets",
            "GRANT ALL ON users TO public",
            "CREATE TABLE evil (id INTEGER)",
            "SELECT * FROM users; DROP TABLE users;",
            "SELECT * FROM users /* sneaky */; DELETE FROM users WHERE 1=1",
            "  -- comment\nDELETE FROM tickets",
            "EXEC sp_do_something",
            "CALL some_procedure()",
    })
    void rejectsMutatingOrDangerousStatements(String sql) {
        assertThatThrownBy(() -> SqlGuard.assertReadOnly(sql)).isInstanceOf(SqlGuardException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM tickets",
            "SELECT id, subject FROM tickets WHERE status = 'OPEN'",
            "WITH open_tickets AS (SELECT * FROM tickets WHERE status <> 'CLOSED') SELECT COUNT(*) FROM open_tickets",
            // The words "update"/"delete" appearing inside a string literal must not trip the guard -
            // a real production query filtering on free-text user input would otherwise be unusable.
            "SELECT * FROM tickets WHERE subject = 'please update my address, then delete the old one'",
            "SELECT * FROM tickets -- trailing comment mentioning delete/update, not code\n",
    })
    void allowsGenuineReadOnlyStatements(String sql) {
        SqlGuard.assertReadOnly(sql); // must not throw
    }

    @Test
    void rejectsBlankSql() {
        assertThatThrownBy(() -> SqlGuard.assertReadOnly("")).isInstanceOf(SqlGuardException.class);
        assertThatThrownBy(() -> SqlGuard.assertReadOnly("   ")).isInstanceOf(SqlGuardException.class);
        assertThatThrownBy(() -> SqlGuard.assertReadOnly(null)).isInstanceOf(SqlGuardException.class);
    }

    @Test
    void allowsTrailingSemicolonOnASingleStatement() {
        SqlGuard.assertReadOnly("SELECT * FROM tickets;");
    }

    @Test
    void messageIdentifiesTheOffendingKeyword() {
        // Must start with SELECT/WITH and be a single statement to reach the keyword-specific
        // check at all (an outright "DELETE FROM users" is rejected earlier, by the "must start
        // with SELECT/WITH" check - see rejectsMutatingOrDangerousStatements).
        assertThatThrownBy(() -> SqlGuard.assertReadOnly("WITH x AS (DELETE FROM users WHERE 1=1) SELECT 1"))
                .hasMessageContaining("delete");
    }
}
