package com.webcore.api.auth;

import java.sql.Array;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import com.webcore.api.database.CoreDataSourceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Repository;

/** Database-backed OAuth clients. This PoC intentionally supports client_credentials only. */
@Repository
public class OAuthClientRepository implements RegisteredClientRepository {
    private final JdbcTemplate jdbc;

    public OAuthClientRepository(CoreDataSourceResolver coreSource) {
        this.jdbc = new JdbcTemplate(coreSource.resolve("DBCORE_auth"));
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Manage OAuth clients through auth.oauth_client.");
    }

    @Override
    public RegisteredClient findById(String id) {
        return find("id = ?", id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return find("client_id = ?", clientId);
    }

    private RegisteredClient find(String predicate, String value) {
        return jdbc.query("""
                SELECT id, client_id, client_secret_hash, client_name, allowed_scopes, access_token_ttl_seconds
                FROM oauth_client
                WHERE %s
                  AND active = TRUE
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """.formatted(predicate), resultSet -> {
            if (!resultSet.next()) return null;
            return toRegisteredClient(
                    resultSet.getString("id"),
                    resultSet.getString("client_id"),
                    resultSet.getString("client_secret_hash"),
                    resultSet.getString("client_name"),
                    scopes(resultSet.getArray("allowed_scopes")),
                    resultSet.getInt("access_token_ttl_seconds"));
        }, value);
    }

    private static RegisteredClient toRegisteredClient(String id, String clientId, String secret, String name,
                                                       Set<String> scopes, int tokenTtlSeconds) {
        return RegisteredClient.withId(id)
                .clientId(clientId)
                .clientSecret(secret)
                .clientName(name)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(configuredScopes -> configuredScopes.addAll(scopes))
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofSeconds(tokenTtlSeconds))
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .build())
                .build();
    }

    private static Set<String> scopes(Array sqlArray) throws java.sql.SQLException {
        if (sqlArray == null || !(sqlArray.getArray() instanceof Object[] values)) return Set.of();
        return Arrays.stream(values)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
