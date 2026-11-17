package com.webcore.api.auth;

import java.time.OffsetDateTime;
import java.util.List;

import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable authentication lockout and audit state shared by every API node. */
@Service
public class AuthSecurityAuditService {
    private final CoreDataSourceResolver source;
    private final AuthSystemSettingsService settings;

    public AuthSecurityAuditService(CoreDataSourceResolver source, AuthSystemSettingsService settings) {
        this.source = source;
        this.settings = settings;
    }

    public void assertNotLocked(String principal, String remoteIp) {
        List<OffsetDateTime> rows = db().query("""
                SELECT locked_until
                FROM auth.login_security_state
                WHERE principal = lower(?) AND locked_until > CURRENT_TIMESTAMP
                """, (rs, row) -> rs.getObject("locked_until", OffsetDateTime.class), principal);
        if (!rows.isEmpty()) {
            audit(principal, "LOGIN", "LOCKED", remoteIp, null, rows.getFirst());
            throw new AccountLockedException(rows.getFirst());
        }
    }

    public void loginSucceeded(String principal, String remoteIp) {
        db().update("DELETE FROM auth.login_security_state WHERE principal = lower(?)", principal);
        audit(principal, "LOGIN", "SUCCESS", remoteIp, null, null);
    }

    public void loginFailed(String principal, String remoteIp) {
        AuthSystemSettingsService.Settings configured = settings.load();
        LockState state = db().queryForObject("""
                INSERT INTO auth.login_security_state (principal, failed_attempts, locked_until, updated_at)
                VALUES (lower(?), 1, NULL, CURRENT_TIMESTAMP)
                ON CONFLICT (principal) DO UPDATE
                SET failed_attempts = CASE
                        WHEN auth.login_security_state.locked_until IS NULL
                             OR auth.login_security_state.locked_until <= CURRENT_TIMESTAMP
                        THEN auth.login_security_state.failed_attempts + 1
                        ELSE auth.login_security_state.failed_attempts
                    END,
                    locked_until = CASE
                        WHEN auth.login_security_state.locked_until > CURRENT_TIMESTAMP
                        THEN auth.login_security_state.locked_until
                        WHEN auth.login_security_state.failed_attempts + 1 >= ?
                        THEN CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                        ELSE NULL
                    END,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING failed_attempts, locked_until
                """, (rs, row) -> new LockState(rs.getInt("failed_attempts"),
                rs.getObject("locked_until", OffsetDateTime.class)), principal,
                configured.failedLoginLimit(), configured.lockoutDurationSeconds());
        boolean locked = state.lockedUntil() != null && state.lockedUntil().isAfter(OffsetDateTime.now());
        audit(principal, "LOGIN", locked ? "LOCKED" : "FAILED", remoteIp, state.failedAttempts(), state.lockedUntil());
        if (locked) throw new AccountLockedException(state.lockedUntil());
    }

    public void logout(String principal, String remoteIp) {
        audit(principal, "LOGOUT", "SUCCESS", remoteIp, null, null);
    }

    private void audit(String principal, String action, String outcome, String remoteIp, Integer failedAttempts, OffsetDateTime lockedUntil) {
        if (failedAttempts == null && lockedUntil == null) {
            db().update("""
                    INSERT INTO auth.security_audit_event (occurred_at, principal, action, outcome, remote_ip, detail)
                    VALUES (CURRENT_TIMESTAMP, lower(?), ?, ?, ?, '{}'::jsonb)
                    """, principal, action, outcome, emptyToNull(remoteIp));
            return;
        }
        if (lockedUntil == null) {
            db().update("""
                    INSERT INTO auth.security_audit_event (occurred_at, principal, action, outcome, remote_ip, detail)
                    VALUES (CURRENT_TIMESTAMP, lower(?), ?, ?, ?, jsonb_build_object('failedAttempts', ?))
                    """, principal, action, outcome, emptyToNull(remoteIp), failedAttempts);
            return;
        }
        if (failedAttempts == null) {
            // A subsequent sign-in while already locked has no new failed-attempt
            // count. Do not bind an untyped NULL into jsonb_build_object: PostgreSQL
            // rejects that polymorphic argument and would turn the intended 423 into
            // a generic 500 response.
            db().update("""
                    INSERT INTO auth.security_audit_event (occurred_at, principal, action, outcome, remote_ip, detail)
                    VALUES (CURRENT_TIMESTAMP, lower(?), ?, ?, ?, jsonb_build_object('lockedUntil', ?))
                    """, principal, action, outcome, emptyToNull(remoteIp), lockedUntil);
            return;
        }
        db().update("""
                INSERT INTO auth.security_audit_event (occurred_at, principal, action, outcome, remote_ip, detail)
                VALUES (CURRENT_TIMESTAMP, lower(?), ?, ?, ?,
                        jsonb_build_object('failedAttempts', ?, 'lockedUntil', ?))
                """, principal, action, outcome, emptyToNull(remoteIp), failedAttempts, lockedUntil);
    }

    private JdbcTemplate db() { return new JdbcTemplate(source.resolve("DBCORE_auth")); }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private record LockState(int failedAttempts, OffsetDateTime lockedUntil) { }

    public static final class AccountLockedException extends RuntimeException {
        private final OffsetDateTime lockedUntil;
        public AccountLockedException(OffsetDateTime lockedUntil) { this.lockedUntil = lockedUntil; }
        public OffsetDateTime lockedUntil() { return lockedUntil; }
    }
}
