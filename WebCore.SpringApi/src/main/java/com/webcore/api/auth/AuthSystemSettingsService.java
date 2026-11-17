package com.webcore.api.auth;

import java.time.Duration;
import java.util.List;

import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Mirrors the .NET AUTH_SYSTEM bootstrap: JWT material is owned by the core
 * database, never by a file shipped with either backend.
 */
@Service
public class AuthSystemSettingsService {
    private static final String AUTH_SYSTEM = "AUTH_SYSTEM";
    private final AuthProperties defaults;

    private final CoreDataSourceResolver coreSource;
    public AuthSystemSettingsService(AuthProperties defaults, CoreDataSourceResolver coreSource) {
        this.defaults = defaults;
        this.coreSource = coreSource;
    }

    public Settings load() {
        List<SystemVariable> values = new JdbcTemplate(coreSource.resolve()).query(
                "SELECT var_name, var_value FROM core.sys_var "
                        + "WHERE group_name = ? AND var_name IN "
                        + "('Private_Key', 'Public_Key', "
                        + "'Access_Token_Timeout', 'Refresh_Token_Timeout', 'Lock_Screen_Timeout_Seconds', "
                        + "'Failed_Login_Limit', 'Lockout_Duration_Seconds')",
                (rs, ignored) -> new SystemVariable(rs.getString("var_name"), rs.getString("var_value")), AUTH_SYSTEM);
        String privateKeyValue = value(values, "Private_Key");
        String publicKeyValue = value(values, "Public_Key");
        String accessTimeout = value(values, "Access_Token_Timeout");
        String refreshTimeout = value(values, "Refresh_Token_Timeout");
        String lockScreenTimeoutSeconds = value(values, "Lock_Screen_Timeout_Seconds");
        String failedLoginLimit = value(values, "Failed_Login_Limit");
        String lockoutDurationSeconds = value(values, "Lockout_Duration_Seconds");

        String privateKey = normalizePem(privateKeyValue);
        String publicKey = normalizePem(publicKeyValue);
        if (privateKey.isBlank() || publicKey.isBlank()) {
            throw new IllegalStateException("AUTH_SYSTEM must contain Private_Key and Public_Key.");
        }

        return new Settings(
                privateKey,
                publicKey,
                defaults.issuer(),
                defaults.audience(),
                timeout(accessTimeout, defaults.accessTokenTtl()),
                timeout(refreshTimeout, defaults.refreshTokenTtl()),
                seconds(lockScreenTimeoutSeconds, 300),
                positiveInt(failedLoginLimit, 5, 100),
                seconds(lockoutDurationSeconds, 14_400));
    }

    private static String value(List<SystemVariable> values, String name) {
        return values.stream().filter(value -> name.equalsIgnoreCase(value.name())).map(SystemVariable::value)
                .filter(value -> value != null).findFirst().orElse("");
    }

    private static String normalizePem(String value) {
        return value == null ? "" : value.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n").trim();
    }

    private static Duration timeout(String minutes, Duration fallback) {
        try {
            int value = Integer.parseInt(minutes == null ? "" : minutes.trim());
            return value > 0 ? Duration.ofMinutes(value) : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Idle timeout is deliberately stored in seconds so short UAT values are precise. */
    private static int seconds(String value, int fallback) {
        try {
            int seconds = Integer.parseInt(value == null ? "" : value.trim());
            return seconds > 0 && seconds <= 86_400 ? seconds : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int positiveInt(String value, int fallback, int maximum) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed > 0 && parsed <= maximum ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public record Settings(String privateKeyPem, String publicKeyPem, String issuer, String audience,
                           Duration accessTokenTtl, Duration refreshTokenTtl, int lockScreenTimeoutSeconds,
                           int failedLoginLimit, int lockoutDurationSeconds) { }
    private record SystemVariable(String name, String value) { }
}
