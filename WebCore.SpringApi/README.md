# WebCore Spring API

Spring Boot 4.1.0 and Java 21 replacement backend. The React application remains in `Runtime/WebCore.Client`.

## Run

Set a Java 21 `JAVA_HOME`, configure `Database.ConnectionString` and JWT keys outside source control, then run:

```bash
mvn spring-boot:run
```

The API listens on `8080` by default. During migration, set the React environment variable `VITE_API_BASE_URL=http://localhost:8080` only after the required endpoints have been ported.

## System database configuration

Spring uses only one base/core database configuration:

```yaml
Database:
  Provider: POSTGRES
  ConnectionString: ${WEBCORE_DATABASE_CONNECTION_STRING}
```

Set `WEBCORE_DATABASE_PROVIDER` to `POSTGRES`, `SQLSERVER`, or `ORACLE` and
set `WEBCORE_DATABASE_CONNECTION_STRING` to the corresponding connection
string. Do not configure `spring.datasource` or a legacy per-service
connection string. Business connections remain registered in `core.def_source`.

See [database connection and startup guide](docs/database-connectivity.md) for
the PostgreSQL `Search Path` convention, Core Base versus `def_source`, all
runtime environment variables, and the Windows startup commands.

See [Docker run guide](docs/docker-run.md) to build and run the Spring API
image, configure database and LDAPS host mappings, and trust the LDAPS
certificate inside Docker.

## Current compatibility foundation

- `GET /health/live` and `GET /health/ready`. Until the Core metadata snapshot and SQL source probes finish, business endpoints return `503 APPLICATION_STARTING`; use `/health/ready` to wait before sending traffic.
- CORS with cookies and the existing `X-Correlation-Id` header
- JWT validation from the `access_token` cookie or Authorization header
- `POST /api/auth/login`, `POST /api/auth/refresh`, `GET /api/auth/me`, and `POST /api/auth/logout`

To enable login, keep the existing RSA public/private PEM values in
`core.sys_var` under `AUTH_SYSTEM`. The application uses the existing PostgreSQL
functions `auth.get_user_auth_context` and `auth.get_user_auth_context_by_email`;
it does not store passwords itself.

## External DMS document intake

`POST /api/dms-docsvc/external/documents` accepts a multipart file with
`categoryCode`, `typeCode`, `documentCode`, and an `Idempotency-Key`. Send an
existing `caseId` to attach to that case. If it is absent, send `objectType`,
`objectRef`, `branchCode`, `customerNumber`, and `customerName`; the API reuses
or creates the case before attaching the document. `documentCode` is stored in
the existing document `referenceText` field and is returned with `caseId` and
`documentId`.

See [Active Directory domain login (LDAPS)](docs/domain-ldaps-login.md) for
domain certificate setup, JDK trust, and the `type = domain` login flow.

## Password-reset mail configuration

Choose the delivery provider with `WEBCORE_PASSWORD_RESET_MAIL_PROVIDER`:

- `smtp`: set `WEBCORE_SMTP_HOST`, `WEBCORE_SMTP_PORT`,
  `WEBCORE_SMTP_USERNAME`, `WEBCORE_SMTP_PASSWORD`, and `WEBCORE_SMTP_FROM`.
- `ses`: set `WEBCORE_SES_REGION` and `WEBCORE_SES_FROM`. On AWS, use the
  workload IAM role; otherwise set `WEBCORE_SES_ACCESS_KEY_ID` and
  `WEBCORE_SES_SECRET_ACCESS_KEY`.

Set `WEBCORE_PASSWORD_RESET_BASE_URL` to the public React URL. Never commit
mail credentials or AWS keys to `application.yml`.

## Grafana and Loki

Use the shared local observability stack from `Runtime/docker`:

```bash
docker compose -f docker-compose.observability.yml up -d
```

Grafana is available at `http://localhost:3001`, Loki at `http://localhost:3100`,
and Prometheus at `http://localhost:9090`. Set `WEBCORE_PROMETHEUS_PUBLIC=true`
only for a private Prometheus scraper; the provisioned Grafana dashboard includes
Spring availability, HTTP and Core cache warm-up/hit/miss metrics.
Enable Spring log delivery with `WEBCORE_LOKI_ENABLED=true`; override the endpoint,
service, and environment with `WEBCORE_LOKI_ENDPOINT`, `WEBCORE_LOKI_SERVICE`, and
`WEBCORE_LOKI_ENVIRONMENT`. Loki labels are limited to `service`, `environment`, and
`level` to avoid high-cardinality indexes.

See [Grafana and Loki logging guide](docs/grafana-loki.md) for the complete Java startup command,
Grafana query, and troubleshooting steps.

## Migration backlog

The old .NET backend has 90 controllers and approximately 73,000 C# lines. The remaining API groups must be ported with contract tests before traffic is moved:

1. Login/refresh and user configuration
2. Core module runtime: `module`, `list`, `find`, `input`, `upload`
3. Navigation, language, notification, component and admin APIs
4. Static module controllers (`Sxxx` / `SLxx`)
5. Dashboard, Excel/PDF export, AWS integrations, database-source adapters and cluster services

Do not remove `Runtime/WebCore.Api` until the contract tests for each group pass against both implementations.
