# WebCore ClientNew: domain-session handling and runtime-list keys

This document describes ClientNew (`Runtime/WebCore.ClientNew/cbs`) behavior
that works with the Spring API. It does not replace the Spring LDAPS setup; see
[`WebCore.SpringApi/docs/domain-ldaps-login.md`](../../../WebCore.SpringApi/docs/domain-ldaps-login.md)
for Active Directory configuration and server-side validation.

## Domain password-change session handling

The client does not connect to Active Directory and does not read
`SYSTEM$DOMAINLOGIN`, `SYSTEM$TIMECACHELDAP`, or `pwdLastSet`. Those decisions
belong solely to Spring.

The login and lock-screen identifier fields accept a UPN, a short account name,
or a Windows domain name. With `DOMAINLOGIN = Y`, `minhld`,
`pbb\\minhld`, and `minhld@ad.pbb.local` are sent unchanged to Spring, which
normalizes them to `minhld@ad.pbb.local`. ClientNew must not attempt this
normalization itself because `SYSTEM$DOMAINNAME` is server configuration.

When Spring has `SYSTEM$DOMAINLOGIN = Y`, it can detect a changed AD password
and respond with `401`, expire `access_token` and `refresh_token`, and include
the response header:

```text
X-WebCore-Session-Cleared: true
```

ClientNew forwards that header and **all** upstream `Set-Cookie` values through
both Next proxies:

- `/api/webcore/auth/[action]` proxies Spring `/api/auth/[action]` for login,
  refresh, logout, and current-user requests.
- `/api/webcore-api/[...path]` proxies Spring module endpoints.

Passing every `Set-Cookie` header is required so the browser receives both
expired cookies when Spring invalidates a domain session. `webcore-auth.ts`
recognizes `X-WebCore-Session-Cleared: true` and clears its browser-side session
markers. `AppAuthGate` then routes the user to `/login` when the authenticated
user can no longer be obtained.

When `SYSTEM$DOMAINLOGIN` is not `Y`, Spring leaves its original local
authentication, session, and cookie flow in place. ClientNew has no extra LDAP
request, cache, or conditional behavior in that mode.

## Post-login MAINN metadata bootstrap

After the user has authenticated and the dashboard shell mounts,
`LanguageSelector` loads the MAINN screen metadata through the authenticated
Next proxy:

```text
GET /api/webcore-api/module/MAINN/form-info/MMN
```

This is the ClientNew equivalent of Spring's upstream endpoint
`GET /api/module/MAINN/form-info/MMN`. It must run only after login because
the module endpoint requires the authenticated `access_token` cookie, which the
proxy promotes to the upstream bearer token.

The language selector deliberately has no hard-coded set of language codes.
It finds the `DEFMODFLD` record whose `FieldName` is `LANGID` in the form-info
field collections (normally `CommonFields` for MAINN). Its
`ListSourceItems` supplies the configured language values, for example `VN`,
`EN`, and `MY`. The value determines the flag asset: `EN` uses `gb.svg`; all
other values use their lowercase code, such as `VN` → `vn.svg`.

`ListSourceItems.Text` is only metadata fallback text and may be a key such as
`LANGUAGE_VN`. For the display label, the selector then requests localization
for the same screen using the currently selected language:

```text
GET /api/webcore-api/module/MAINN/MMN/localization
Accept-Language: <selected LANGID>
```

The UI reads `Lookups.LANGID[<language value>]` from that response. Spring
resolves these strings from the `CODE$SYSTEM$LANGUAGE.<CodeValueName>` language
keys, so labels such as `Việt Nam`, `English`, and `Malaysia` follow the active
application language.

Changing the selector still uses the existing `preferred_language` localStorage
and `webcore-language-changed` event flow. This bootstrap only replaces the
source of available language options and their labels; it does not change other
module, session, or localization behavior.

When porting more post-login shell data, use the same pattern: request the
authenticated `MAINN/MMN` form-info once the shell is mounted, derive values
from metadata rather than client constants, and call the matching localization
endpoint only for text that must be translated.

The Spring endpoint exposes this metadata through the typed
`ModuleFormInfoResponse` record. Its public contract has explicit module
identity fields, typed field collections (`ModuleFieldResponse`), lookup items,
buttons, validations, summaries, and module-type-specific collections. The
metadata cache may still use raw database rows internally during the migration,
but controllers must not expose those `Map<String, Object>` values directly.

## Runtime-list composite keys

`RuntimeListModule` is metadata-driven. It reads
`configUi.GridConfig.keyExpr` from the module UI response; the value may be a
single field name or an array of fields.

For an array, the full array is passed to the DevExtreme grid and a key object
is sent to the generic update/delete endpoints. This preserves row identity for
composite database keys. For example, L006 is configured in Core metadata as:

```json
"keyExpr": ["group_name", "var_name"]
```

It is not an L006 hard-code in ClientNew. Using only `group_name` would cause
all variables in the same group to share one grid identity, making multiple
rows appear selected together. Metadata may provide a different single or
composite key for each module without a client code change.

## Client responsibilities

- Keep browser authentication cookies HttpOnly; do not store passwords,
  `pwdLastSet`, or the domain service-account credentials in the client.
- Preserve upstream `Set-Cookie` and `X-WebCore-Session-Cleared` responses in
  proxy routes.
- Treat the server as the authority for domain-session validity; do not attempt
  a client-side password check.
