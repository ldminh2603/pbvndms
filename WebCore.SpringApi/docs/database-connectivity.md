# Database connection and startup for WebCore Spring API

This guide is specific to `Runtime/WebCore.SpringApi`. It describes the single
Core Base connection, PostgreSQL schema resolution, business connections in
`def_source`, and the Windows development startup command.

## 1. One Core Base connection

Spring has exactly one system/Core Base connection. Configure it only through
these variables:

```powershell
$env:WEBCORE_DATABASE_PROVIDER = 'POSTGRES'
$env:WEBCORE_DATABASE_CONNECTION_STRING = 'Host=127.0.0.1;Port=5432;Database=web_core;Username=app_user;Password=<password>;Search Path=core,public;Timeout=120;Command Timeout=120;Pooling=true;'
```

Do not set `spring.datasource`, and do not add a `LegacyConnectionString`.
The Spring application converts this .NET-style PostgreSQL connection string to
a JDBC URL at startup.

`WEBCORE_DATABASE_PROVIDER` accepts `POSTGRES`, `SQLSERVER`, or `ORACLE`.
For the migration configuration shown above, use `POSTGRES`.

Never place a real password in this document, `application.yml`, Git, Loki, or
browser code. Set it only in the process environment or a secret manager.

## 2. PostgreSQL `Search Path`: do not prefix `core.` in module SQL

Use this in the Core Base connection string:

```text
Search Path=core,public
```

Java maps it to PostgreSQL JDBC `currentSchema=core,public`. Therefore SQL in
module configuration and routines can use unqualified names:

```sql
SELECT * FROM def_mod;
SELECT * FROM def_source;
SELECT * FROM def_menu;
```

Do **not** write `core.def_mod`, `core.def_source`, or `core.def_menu` in new
module/action SQL merely to make Spring find them. PostgreSQL resolves names in
the declared order: `core`, then `public`.

The Java Core Base repository may still use explicit schema names for its own
fixed internal metadata reads. That is implementation code, not a requirement
for SQL configured in `def_mod` or for business module SQL.

Use the same `Search Path` rule in a PostgreSQL entry stored in
`core.def_source.config.data_source_connection` when that source should resolve
tables without a schema prefix:

```text
Host=127.0.0.1;Port=5432;Database=limit;Username=app_user;Password=<password>;Search Path=core,public;Pooling=true;
```

For example, with the source above, a module action may use `SELECT * FROM
customer_limit`, not `core.customer_limit`.

## 3. Core Base and business source responsibility

| Data | Connection used by Java | Where it is configured |
| --- | --- | --- |
| Core metadata: menu, module, component, language, role/policy | the single `WEBCORE_DATABASE_CONNECTION_STRING` | Operating-system environment |
| Authentication metadata and token context | the same single Core Base connection | Operating-system environment |
| Business module data, Store/Function/SQL actions, notification source | a source selected by action metadata | `core.def_source` in Core Base |

At runtime Java loads `def_source` through the Core Base connection, then builds
and caches a pool for the selected source. The source name in the action must
match `def_source.source_name`. There is no second hard-coded or legacy
connection string in Spring.

When a `def_source` configuration changes, use the Java admin reload/cache API
or restart Spring so an already cached datasource is recreated.

## 4. Environment variables

Required for a normal local PostgreSQL run:

| Variable | Example / purpose |
| --- | --- |
| `JAVA_HOME` | `C:\Program Files\Java\jdk-21.0.11` |
| `WEBCORE_SERVER_PORT` | `51130` (use a port outside Windows excluded ranges) |
| `WEBCORE_DATABASE_PROVIDER` | `POSTGRES` |
| `WEBCORE_DATABASE_CONNECTION_STRING` | the single Core Base connection, with `Search Path=core,public` |
| `WEBCORE_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` |
| `WEBCORE_AUTH_COOKIE_SECURE` | `false` for local HTTP; `true` behind HTTPS |
| `WEBCORE_AUTH_COOKIE_SAME_SITE` | `Lax` for local development |

Authentication reads RSA keys and optional issuer/audience settings from the
existing Core Base `core.sys_var` / `AUTH_SYSTEM` data. The current Java
application does not read PEM keys from `WEBCORE_JWT_PUBLIC_KEY` or
`WEBCORE_JWT_PRIVATE_KEY`; the `AUTH_SYSTEM` values must be present before
login can start.

Optional variables:

| Group | Variables |
| --- | --- |
| Loki logging | `WEBCORE_LOKI_ENABLED`, `WEBCORE_LOKI_ENDPOINT`, `WEBCORE_LOKI_SERVICE`, `WEBCORE_LOKI_ENVIRONMENT` |
| Redis notification fallback | `WEBCORE_REDIS_HOST`, `WEBCORE_REDIS_PORT`, `WEBCORE_REDIS_PASSWORD`, `WEBCORE_REDIS_HEALTH_ENABLED` |
| Reverse proxy | `WEBCORE_REVERSE_PROXY_ENABLED` |
| JWT duration | `WEBCORE_JWT_ACCESS_TOKEN_TTL`, `WEBCORE_JWT_REFRESH_TOKEN_TTL` |
| Password reset mail | `WEBCORE_PASSWORD_RESET_*`, `WEBCORE_SMTP_*`, or `WEBCORE_SES_*` |

See [Grafana and Loki logging guide](grafana-loki.md) for the exact Loki
variables and Grafana queries.

## 5. Start locally on Windows

Open a new PowerShell window. Values set with `$env:` apply only to this window,
so set them again after opening a new terminal.

```powershell
cd F:\MayAo\Project\WebCore\50.web\Runtime\WebCore.SpringApi

$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:WEBCORE_SERVER_PORT = '51130'
$env:WEBCORE_DATABASE_PROVIDER = 'POSTGRES'
$env:WEBCORE_DATABASE_CONNECTION_STRING = 'Host=127.0.0.1;Port=5432;Database=web_core;Username=app_user;Password=<password>;Search Path=core,public;Timeout=120;Command Timeout=120;Pooling=true;'
$env:WEBCORE_CORS_ALLOWED_ORIGINS = 'http://localhost:5173,http://localhost:3000'
$env:WEBCORE_AUTH_COOKIE_SECURE = 'false'
$env:WEBCORE_AUTH_COOKIE_SAME_SITE = 'Lax'

mvn spring-boot:run
```

For local Java logs in Grafana/Loki, add these variables before the last line:

```powershell
$env:WEBCORE_LOKI_ENABLED = 'true'
$env:WEBCORE_LOKI_ENDPOINT = 'http://localhost:3100/loki/api/v1/push'
$env:WEBCORE_LOKI_SERVICE = 'webcore-spring-api'
$env:WEBCORE_LOKI_ENVIRONMENT = 'development'
```

## 6. Verify startup

In a second PowerShell window:

```powershell
Invoke-RestMethod http://localhost:51130/actuator/health
Invoke-RestMethod http://localhost:51130/health/ready
```

`/actuator/health` should report `UP`. `/health/ready` evaluates configured Core
and business datasource probes, so it can return HTTP 503 until a required
source is reachable. Inspect its `sources` collection to identify the source.
