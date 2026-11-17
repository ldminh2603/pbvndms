# Active Directory domain login (LDAPS)

This guide configures Spring WebCore login against Active Directory over LDAPS.
It is intended for a lab or internal deployment. Do not expose LDAP directly to
the internet; prefer a VPN or restrict TCP 636 to the Spring API host.

## Login flow

When `SYSTEM$DOMAINLOGIN` is `Y`, Spring performs these steps:

1. Reads the values from the Core metadata snapshot.
2. Connects to `ldaps://SYSTEM$HOSTNAME:636`.
3. Normalizes the supplied Active Directory account, then binds it. The accepted
   forms `minhld`, `pbb\minhld`, and `minhld@ad.pbb.local` all mean
   `minhld@SYSTEM$DOMAINNAME`. A supplied UPN is used as-is (apart from case
   normalization).
4. Loads the WebCore account using the same email and `type = domain`, then
   issues the normal WebCore access and refresh tokens.
5. Stores Active Directory's `pwdLastSet` value in the signed tokens. The AD
   query result is cached per user for `SYSTEM$TIMECACHELDAP` seconds; a changed
   value expires both cookies and requires a new login on the first check after
   that cache window.

`pwdLastSet` is an AD timestamp/sequence value that changes when the account
password is changed. It is **not** the password, its hash, or a reusable secret.
Spring places this value only in the signed JWT payload, then compares it with
the current AD value. A JWT is signed, not encrypted, so do not add other
sensitive AD attributes to it.

### Session validation after login

For every authenticated request of a `type = domain` user, Spring's
`DomainSessionValidationFilter` runs after JWT authentication:

1. It obtains `pwdLastSet` through a service-account LDAPS search (or its
   per-user cache).
2. It compares that value with `domainPasswordLastSet` in the JWT.
3. If the values differ or the claim is absent, it responds `401`, expires both
   `access_token` and `refresh_token`, and sends
   `X-WebCore-Session-Cleared: true`.

This does not re-bind the user's password on every menu click. It performs an
LDAP read at most once per user per cache window (except a fresh interactive
login, which always reads AD). With `TIMECACHELDAP = 30`, a password change can
remain usable for up to 30 seconds after the most recent successful lookup; the
next protected request then clears the session.

The domain password is never compared with, stored in, or changed in
`auth."user"`. Roles, policies, menus, and the privileged-account flag remain
owned by WebCore.

If Active Directory rejects a domain-login password, Spring expires both the
`access_token` and `refresh_token` cookies in that login response. The user must
then authenticate again with the current domain password. The response includes
`X-WebCore-Session-Cleared: true`; the login and lock-screen clients use it to
clear their browser session state and password field.

When `SYSTEM$DOMAINLOGIN` is not exactly `Y`, **none** of this LDAP logic runs:
there is no domain credential bind, no `pwdLastSet` read/cache, and no domain
session invalidation. Login, refresh, JWT validation, session state, and cookies
remain the existing local WebCore behavior (`type = local` and bcrypt password
verification).

## Required `core.sys_var` values

```text
SYSTEM | DOMAINLOGIN | Y
SYSTEM | DOMAINNAME  | ad.pbb.local
SYSTEM | HOSTNAME    | dc01.ad.pbb.local
SYSTEM | TIMECACHELDAP | 30
```

`TIMECACHELDAP` is a whole number of **seconds**. `30` means the first protected
request for each domain user reads AD, then further protected requests for that
user reuse the value for 30 seconds. It is an in-memory cache for each Spring
instance, so clustered instances maintain separate entries. Use `0` (or an
invalid/non-positive value) to disable the cache and check AD on every protected
request.

`HOSTNAME` must be the DNS name covered by the LDAPS certificate. Do not use an
IP address here.

Spring reloads the complete Core metadata snapshot at startup. Restart the
Spring API after changing these values, or use the normal full Core reload.

## Required session-check account

The API needs a read-only AD service account to look up `pwdLastSet` after a
token is issued. Configure these environment variables on the Spring API host:

```text
WEBCORE_DOMAIN_SERVICE_USERNAME=webcore-session-reader@ad.pbb.local
WEBCORE_DOMAIN_SERVICE_PASSWORD=<service-account-password>
WEBCORE_DOMAIN_BASE_DN=DC=ad,DC=pbb,DC=local
```

Grant this account read access to `userPrincipalName` and `pwdLastSet` for the
required users. The password is used only for the LDAPS service bind and is not
stored in WebCore or sent to a browser.

## Verify password-change invalidation

1. Set `SYSTEM$DOMAINLOGIN` to `Y` and set `SYSTEM$TIMECACHELDAP` to the desired
   number of seconds (for example `30`), then restart Spring or perform a full
   Core metadata reload.
2. Log in with a `type = domain` WebCore user and open a protected module.
3. Change that user's password in Active Directory.
4. Wait no longer than the configured cache interval from the last protected
   request, then open or refresh a protected page. Spring must return `401`,
   expire both cookies, and ClientNew must route to login.
5. Log in with the new password. The old password must be rejected by the AD
   bind; the new password must create a new session.

For an immediate test set `TIMECACHELDAP` to `0`, reload the metadata, and
repeat. Restore the normal value after the test to avoid an LDAPS search on
every protected request.

## Create a lab LDAPS certificate on the Domain Controller

Run PowerShell as Administrator on the Domain Controller. Replace the DNS name
with the Domain Controller FQDN.

```powershell
$cert = New-SelfSignedCertificate -Type SSLServerAuthentication -Subject "CN=dc01.ad.pbb.local" -DnsName "dc01.ad.pbb.local" -CertStoreLocation "Cert:\LocalMachine\My" -KeySpec KeyExchange -KeyLength 2048 -HashAlgorithm SHA256 -FriendlyName "WebCore LDAPS DC"

$cert | Format-List Subject,Thumbprint,HasPrivateKey,EnhancedKeyUsageList
```

The certificate must have a private key and `Server Authentication` usage.
For a production domain, use an AD CS-issued `Domain Controller Authentication`
or `Kerberos Authentication` certificate instead of a self-signed certificate.

For a self-signed lab certificate, trust its own root on the Domain Controller:

```powershell
$thumbprint = "<THUMBPRINT>"
$cert = Get-Item "Cert:\LocalMachine\My\$thumbprint"
Export-Certificate -Cert $cert -FilePath "$env:USERPROFILE\Desktop\dc01-ad-pbb-local.cer"
Import-Certificate -FilePath "$env:USERPROFILE\Desktop\dc01-ad-pbb-local.cer" -CertStoreLocation "Cert:\LocalMachine\Root"
Get-Item "Cert:\LocalMachine\Root\$thumbprint"
```

Restart the Domain Controller after issuing or replacing the certificate.

## Verify LDAPS before testing WebCore

On the Domain Controller:

```powershell
Get-Service NTDS
Get-NetTCPConnection -LocalPort 636 -State Listen
```

Both IPv4 and IPv6 listeners are expected. Then run `ldp.exe`:

1. Choose **Connection > Connect**.
2. Server: `dc01.ad.pbb.local`; port: `636`; select **SSL**.
3. Choose **Connection > Bind** and bind with the domain UPN.

Do the same LDP connect and bind test on the Spring API host. `telnet` or
`Test-NetConnection` confirms only TCP connectivity; a successful LDP bind
confirms that LDAPS actually works end-to-end.

If the API host is in another network, ensure DNS resolves the hostname to a
reachable address, and allow only API-host-to-DC TCP 636. A public NAT rule must
forward TCP 636 to the real Domain Controller and permit the forwarding traffic.

## Trust the certificate in the Spring JDK

Copy `dc01-ad-pbb-local.cer` to the Spring API host, for example to `C:\Temp`.
Import it into the exact JDK used to launch Spring:

```powershell
$javaHome = $env:JAVA_HOME
& "$javaHome\bin\keytool.exe" -importcert -alias dc01-ad-pbb-local -file "C:\Temp\dc01-ad-pbb-local.cer" -keystore "$javaHome\lib\security\cacerts"
```

The default `cacerts` password is `changeit`. Verify the import:

```powershell
& "$javaHome\bin\keytool.exe" -list -keystore "$javaHome\lib\security\cacerts" -alias dc01-ad-pbb-local
```

Restart the Spring API after changing the JDK truststore.

## WebCore domain user

Create exactly one active WebCore account for the domain UPN. Its password value
is not used while domain login is enabled.

```sql
SELECT id, email, type, status, is_deleted, is_privileged_account
FROM auth."user"
WHERE lower(email) = lower('user@ad.pbb.local')
  AND lower(type) = 'domain';
```

The user must have `status = 'A'` and not be deleted. Assign `auth.user_role`
records unless `is_privileged_account` is true; WebCore authorization then uses
the normal role, policy, component, and menu mappings.

## Failure codes

| Error code | Meaning |
| --- | --- |
| `AUTH_INVALID_CREDENTIALS` | AD rejected the user or password, or the domain WebCore user was not found. |
| `AUTH_DOMAIN_UNAVAILABLE` | Spring could not establish an LDAPS connection; verify LDP bind, DNS, routing/NAT, firewall, and certificate trust. |
| `AUTH_DOMAIN_CONFIGURATION` | A required domain value is empty: `SYSTEM$HOSTNAME`, `SYSTEM$DOMAINNAME`, or one of the session-check account settings. |
