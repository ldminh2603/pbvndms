package com.webcore.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Password changes and reset tokens are DB-backed so they work across every Spring node. */
@Service
public class PasswordResetService {
    private final CoreDataSourceResolver source; private final PasswordResetProperties properties; private final JavaMailSender mail;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(); private final SecureRandom random = new SecureRandom();
    public PasswordResetService(CoreDataSourceResolver source, PasswordResetProperties properties, JavaMailSender mail) { this.source=source; this.properties=properties; this.mail=mail; }
    private JdbcTemplate db() { return new JdbcTemplate(source.resolve("DBCORE_auth")); }

    public void change(String email, String currentPassword, String newPassword) {
        inAuthTransaction(() -> {
            validate(newPassword); String hash = db().query("SELECT password FROM auth.\"user\" WHERE lower(email)=lower(?) AND lower(type)='local' AND status='A' AND is_deleted='N'", rs -> rs.next() ? rs.getString(1) : null, email);
            if (hash == null || !bcrypt.matches(currentPassword, hash)) throw new AuthRepository.InvalidCredentialsException();
            db().update("UPDATE auth.\"user\" SET password=?,modified_date=CURRENT_TIMESTAMP,modified_by=? WHERE lower(email)=lower(?) AND lower(type)='local'", bcrypt.encode(newPassword), email, email);
            revoke(email);
        });
    }
    public void request(String email, String ip) {
        inAuthTransaction(() -> {
            Integer found = db().queryForObject("SELECT COUNT(*) FROM auth.\"user\" WHERE lower(email)=lower(?) AND lower(type)='local' AND status='A' AND is_deleted='N'", Integer.class, email);
            // Never disclose whether an account exists.
            if (found == null || found == 0) return;
            byte[] raw = new byte[32]; random.nextBytes(raw); String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            db().update("UPDATE auth.password_reset_token SET revoked_at=CURRENT_TIMESTAMP WHERE lower(user_email)=lower(?) AND used_at IS NULL AND revoked_at IS NULL", email);
            db().update("INSERT INTO auth.password_reset_token(user_email,token_hash,expires_at,requested_ip) VALUES(?,?,CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),?)", email.toLowerCase(), sha256(token), Math.max(60, properties.tokenTtl().toSeconds()), ip);
            send(email, token);
        });
    }
    public void reset(String token, String password) {
        inAuthTransaction(() -> {
            validate(password); String hash=sha256(token);
            String email = db().query("SELECT user_email FROM auth.password_reset_token WHERE token_hash=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP FOR UPDATE", rs -> rs.next() ? rs.getString(1) : null, hash);
            if (email == null) throw new IllegalArgumentException("Reset token is invalid, expired, or revoked.");
            db().update("UPDATE auth.\"user\" SET password=?,modified_date=CURRENT_TIMESTAMP,modified_by=? WHERE lower(email)=lower(?) AND lower(type)='local'", bcrypt.encode(password), email, email);
            db().update("UPDATE auth.password_reset_token SET used_at=CURRENT_TIMESTAMP WHERE token_hash=?", hash); revoke(email);
        });
    }
    private void send(String email, String token) {
        if (properties.smtp().host() == null || properties.smtp().host().isBlank()) throw new IllegalStateException("Password reset SMTP is not configured.");
        SimpleMailMessage message=new SimpleMailMessage(); message.setTo(email); message.setFrom(properties.smtp().from()); message.setSubject("WebCore password reset"); message.setText("Open this link to reset your password: " + properties.baseUrl().replaceAll("/$", "") + "/reset-password?token=" + token + "\nThis link expires in " + properties.tokenTtl().toMinutes() + " minutes."); mail.send(message);
    }
    private void revoke(String email) { db().update("UPDATE auth.password_reset_token SET revoked_at=CURRENT_TIMESTAMP WHERE lower(user_email)=lower(?) AND used_at IS NULL AND revoked_at IS NULL", email); }
    private void inAuthTransaction(Runnable work) {
        new TransactionTemplate(new DataSourceTransactionManager(source.resolve("DBCORE_auth"))).executeWithoutResult(status -> work.run());
    }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static void validate(String password) { if(password == null || password.length() < 8) throw new IllegalArgumentException("New password must contain at least 8 characters."); }
}
