# S004 cluster rollout and password reset

## Cluster/S004

React keeps its existing .NET contract at `/api/module/S004/cluster/...`; Java also exposes the same operations at `/api/cluster/...`.

Enable every node with the same control-plane database and service identity, but a different instance id:

```powershell
$env:WEBCORE_CLUSTER_ENABLED='true'
$env:WEBCORE_CLUSTER_ENVIRONMENT='production'
$env:WEBCORE_CLUSTER_SERVICE_NAME='webcore-api'
$env:WEBCORE_CLUSTER_INSTANCE_ID='webcore-java-01'
$env:WEBCORE_CLUSTER_POLL_INTERVAL_MS='2000'
```

`prepare` creates one durable delivery per active endpoint. A node only validates during this phase; its live Core/Role snapshot is untouched. `apply` is rejected until every delivery is prepared. `abort` marks all unfinished deliveries aborted, therefore the pre-apply snapshots remain active. PostgreSQL publishes `pg_notify('runtime_command', ...)`; durable polling remains the lossless fallback. Non-PostgreSQL control planes publish to Redis and also retain polling.

The required ops migrations are under `Runtime/script/db_script/migrations/cluster-ops/` and `cluster-runtime-command-signal-audit/`.

## Password change/reset

Endpoints:

* `POST /api/auth/change-password` (authenticated): `currentPassword`, `newPassword`, `confirmPassword`.
* `POST /api/auth/forgot-password`: `email`. It always returns the same response to avoid account enumeration.
* `POST /api/auth/reset-password`: `token`, `newPassword`, `confirmPassword`.

Apply `Runtime/script/db_script/migrations/spring-auth-password-reset/001_auth_password_reset_token.sql` once to the Core PostgreSQL database. Only a SHA-256 token hash is stored; requesting another token revokes the former token, and successful use makes it single-use.

SMTP and Amazon SES SMTP endpoint both use these environment variables:

```powershell
$env:WEBCORE_PASSWORD_RESET_MAIL_PROVIDER='smtp' # or ses; SES uses its SMTP endpoint
$env:WEBCORE_PASSWORD_RESET_BASE_URL='http://localhost:5173'
$env:WEBCORE_PASSWORD_RESET_TOKEN_TTL='PT15M'
$env:WEBCORE_SMTP_HOST='smtp.example.net'
$env:WEBCORE_SMTP_PORT='587'
$env:WEBCORE_SMTP_USERNAME='...'
$env:WEBCORE_SMTP_PASSWORD='...'
$env:WEBCORE_SMTP_FROM='no-reply@example.net'
$env:WEBCORE_SMTP_STARTTLS_ENABLED='true'
```
