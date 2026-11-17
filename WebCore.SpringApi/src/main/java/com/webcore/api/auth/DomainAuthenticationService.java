package com.webcore.api.auth;

import java.time.Duration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import com.webcore.api.core.CoreMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Verifies Active Directory credentials through LDAPS when enabled in Core sys_var. */
@Service
public class DomainAuthenticationService {
    private static final int LDAPS_PORT = 636;
    private static final Logger log = LoggerFactory.getLogger(DomainAuthenticationService.class);
    private final CoreMetadataService coreMetadataService;
    private final DomainSessionProperties sessionProperties;
    private final ConcurrentHashMap<String, CachedPasswordLastSet> passwordLastSetCache = new ConcurrentHashMap<>();

    public DomainAuthenticationService(CoreMetadataService coreMetadataService, DomainSessionProperties sessionProperties) {
        this.coreMetadataService = coreMetadataService;
        this.sessionProperties = sessionProperties;
    }

    public boolean isEnabled() {
        return "Y".equalsIgnoreCase(coreMetadataService.systemValue("SYSTEM", "DOMAINLOGIN", ""));
    }

    public void verifyCredentials(String username, String password) {
        if (password == null || password.isBlank()) {
            throw new AuthRepository.InvalidCredentialsException();
        }

        String host = requiredSystemValue("HOSTNAME");
        String principal = canonicalPrincipal(username);

        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        environment.put(Context.PROVIDER_URL, "ldaps://" + host + ":" + LDAPS_PORT);
        environment.put(Context.SECURITY_AUTHENTICATION, "simple");
        environment.put(Context.SECURITY_PRINCIPAL, principal);
        environment.put(Context.SECURITY_CREDENTIALS, password);
        environment.put("com.sun.jndi.ldap.connect.timeout", "10000");
        environment.put("com.sun.jndi.ldap.read.timeout", "10000");

        try {
            InitialDirContext context = new InitialDirContext(environment);
            try {
                // JNDI may defer the network connection until the first LDAP
                // operation. Read the Root DSE to force AD to validate the
                // supplied user credentials before issuing any WebCore token.
                context.getAttributes("", new String[] { "namingContexts" });
            } finally {
                context.close();
            }
        } catch (AuthenticationException exception) {
            log.warn("LDAPS authentication failed for principal '{}' against '{}:{}': {}",
                    principal, host, LDAPS_PORT, exception.getMessage());
            throw new AuthRepository.InvalidCredentialsException();
        } catch (NamingException exception) {
            log.warn("LDAPS connection failed for principal '{}' against '{}:{}': {}",
                    principal, host, LDAPS_PORT, exception.getMessage());
            throw new AuthService.AuthConfigurationException(
                    "AUTH_DOMAIN_UNAVAILABLE", "Cannot connect to the configured domain server.");
        }
    }

    /**
     * Returns AD's password-change version for a domain account. This value is
     * stored in a domain JWT and compared on every authenticated request.
     */
    public String passwordLastSet(String username) {
        return passwordLastSet(canonicalPrincipal(username), false);
    }

    /** A successful interactive login must always issue tokens from AD's current value. */
    public String passwordLastSetFresh(String username) {
        return passwordLastSet(canonicalPrincipal(username), true);
    }

    /**
     * Converts accepted Domain-login names to one AD userPrincipalName.
     * Examples: minhld and pbb\\minhld become minhld@ad.pbb.local.
     */
    public String canonicalPrincipal(String username) {
        String value = username == null ? "" : username.trim();
        if (value.isBlank()) {
            throw new AuthRepository.InvalidCredentialsException();
        }
        if (value.contains("@")) {
            return value.toLowerCase(Locale.ROOT);
        }

        int separator = value.lastIndexOf('\\');
        String accountName = separator >= 0 ? value.substring(separator + 1).trim() : value;
        if (accountName.isBlank()) {
            throw new AuthRepository.InvalidCredentialsException();
        }
        return (accountName + "@" + requiredSystemValue("DOMAINNAME")).toLowerCase(Locale.ROOT);
    }

    private String passwordLastSet(String username, boolean forceRefresh) {
        String cacheKey = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        Duration cacheTtl = passwordLastSetCacheTtl();
        long now = System.nanoTime();

        if (!forceRefresh && !cacheTtl.isZero() && !cacheTtl.isNegative()) {
            CachedPasswordLastSet cached = passwordLastSetCache.get(cacheKey);
            if (cached != null && cached.expiresAtNanos() > now) {
                return cached.value();
            }
        }

        String value = loadPasswordLastSet(username);
        if (!cacheTtl.isZero() && !cacheTtl.isNegative()) {
            passwordLastSetCache.put(cacheKey, new CachedPasswordLastSet(value, now + cacheTtl.toNanos()));
        } else {
            passwordLastSetCache.remove(cacheKey);
        }
        return value;
    }

    private String loadPasswordLastSet(String username) {
        String host = requiredSystemValue("HOSTNAME");
        String baseDn = requiredSessionProperty(sessionProperties.baseDn(), "WEBCORE_DOMAIN_BASE_DN");
        String serviceUsername = requiredSessionProperty(sessionProperties.serviceUsername(), "WEBCORE_DOMAIN_SERVICE_USERNAME");
        String servicePassword = requiredSessionProperty(sessionProperties.servicePassword(), "WEBCORE_DOMAIN_SERVICE_PASSWORD");

        Hashtable<String, String> environment = ldapEnvironment(host, serviceUsername, servicePassword);
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[] { "pwdLastSet" });

        InitialDirContext context = null;
        try {
            context = new InitialDirContext(environment);
            var results = context.search(baseDn, "(&(objectClass=user)(userPrincipalName={0}))", new Object[] { username.trim() }, controls);
            if (!results.hasMore()) {
                throw new AuthRepository.InvalidCredentialsException();
            }

            SearchResult result = results.next();
            Attribute passwordLastSet = result.getAttributes().get("pwdLastSet");
            if (passwordLastSet == null || passwordLastSet.get() == null) {
                throw new AuthRepository.InvalidCredentialsException();
            }
            return passwordLastSet.get().toString();
        } catch (AuthRepository.InvalidCredentialsException exception) {
            throw exception;
        } catch (NamingException exception) {
            log.warn("Cannot read pwdLastSet for domain account '{}' against '{}:{}': {}",
                    username, host, LDAPS_PORT, exception.getMessage());
            throw new AuthService.AuthConfigurationException(
                    "AUTH_DOMAIN_UNAVAILABLE", "Cannot connect to the configured domain server.");
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (NamingException exception) {
                    log.debug("Cannot close LDAPS context for domain account '{}': {}", username, exception.getMessage());
                }
            }
        }
    }

    private Duration passwordLastSetCacheTtl() {
        String configuredSeconds = coreMetadataService.systemValue("SYSTEM", "TIMECACHELDAP", "0");
        try {
            long seconds = Long.parseLong(configuredSeconds);
            return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO;
        } catch (NumberFormatException exception) {
            log.warn("SYSTEM$TIMECACHELDAP must be a whole number of seconds; LDAP session caching is disabled.");
            return Duration.ZERO;
        }
    }

    private static Hashtable<String, String> ldapEnvironment(String host, String username, String password) {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        environment.put(Context.PROVIDER_URL, "ldaps://" + host + ":" + LDAPS_PORT);
        environment.put(Context.SECURITY_AUTHENTICATION, "simple");
        environment.put(Context.SECURITY_PRINCIPAL, username);
        environment.put(Context.SECURITY_CREDENTIALS, password);
        environment.put("com.sun.jndi.ldap.connect.timeout", "10000");
        environment.put("com.sun.jndi.ldap.read.timeout", "10000");
        return environment;
    }

    private String requiredSystemValue(String name) {
        String value = coreMetadataService.systemValue("SYSTEM", name, "");
        if (value.isBlank()) {
            throw new AuthService.AuthConfigurationException(
                    "AUTH_DOMAIN_CONFIGURATION", "SYSTEM$" + name + " is required when SYSTEM$DOMAINLOGIN is Y.");
        }
        return value;
    }

    private static String requiredSessionProperty(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new AuthService.AuthConfigurationException(
                    "AUTH_DOMAIN_CONFIGURATION", environmentName + " is required when SYSTEM$DOMAINLOGIN is Y.");
        }
        return value.trim();
    }

    private record CachedPasswordLastSet(String value, long expiresAtNanos) { }
}
