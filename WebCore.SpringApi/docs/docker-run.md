# Run WebCore Spring API with Docker

This guide runs `WebCore.SpringApi` as the `webcore-spring-api:local` image.
The Compose file is `Runtime/docker/docker-compose.spring-api.yml`.

Do not put passwords, LDAP service-account credentials, or connection strings
in Git, Dockerfiles, or Compose files. Set them in the deployment terminal or
inject them from the deployment secret store.

## 1. Build versus run

`build` only compiles the JAR and creates the image. It does not start an API
container and does not need database or LDAP connectivity.

```bash
cd Runtime/docker
docker compose -f docker-compose.spring-api.yml build
```

`up -d` creates and starts the container. It passes the current terminal's
`WEBCORE_*` variables into Spring. On Windows, `$env:` values apply only to the
current PowerShell window.

```bash
docker compose -f docker-compose.spring-api.yml up -d
```

Use `--force-recreate` after changing an environment variable, host mapping,
or LDAPS certificate:

```bash
docker compose -f docker-compose.spring-api.yml up -d --force-recreate
```

Do not use `docker run webcore-spring-api:local` for normal operation; it does
not apply the Compose port, host mappings, certificate mount, or runtime
configuration.

## Deploy without source code

The deployment machine needs only a pre-built image, the deployment Compose
file, the public LDAPS certificate, and deployment environment variables. It
does not need the Java, Maven, or application source directories.

On the build machine, export the image after a successful build:

```bash
docker save --output webcore-spring-api_local.tar webcore-spring-api:local
```

Copy `webcore-spring-api_local.tar` and
`Runtime/docker/docker-compose.spring-api.deploy.yml` to the deployment
machine. On that machine, import the image:

```bash
docker load --input webcore-spring-api_local.tar
```

Set the runtime variables in section 2 and the database/LDAP mappings in
sections 3 and 4. Copy the public LDAPS certificate to
`./secrets/dc01-ad-pbb-local.cer` next to the deployment Compose file, or set
an absolute override path:

```bash
$env:WEBCORE_LDAPS_CERT_HOST_PATH = 'D:\WebCore\secrets\dc01-ad-pbb-local.cer'
```

Then run the deployment Compose file from the directory to which it was copied:

```bash
docker compose -f docker-compose.spring-api.deploy.yml up -d
```

The deployment Compose file deliberately has `image:` only and no `build:`
section. To deploy a newer image, repeat `docker save`, copy the new archive,
run `docker load`, then run `up -d --force-recreate`.

## 2. Required runtime configuration

Set these values in the same PowerShell window used for `up -d`. Substitute
real values from the deployment secret source; placeholders below are not
usable credentials.

```bash
$env:WEBCORE_CLUSTER_ENABLED = 'false'
$env:WEBCORE_SERVER_PORT = '51130'
$env:WEBCORE_DATABASE_PROVIDER = 'POSTGRES'
$env:WEBCORE_DATABASE_CONNECTION_STRING = 'Host=corelocalhost;Port=5432;Database=web_core;Username=<user>;Password=<password>;Search Path=core,public;Timeout=120;Command Timeout=120;Pooling=true;'
$env:WEBCORE_CORS_ALLOWED_ORIGINS = 'http://localhost:5173,http://localhost:3005'
$env:WEBCORE_AUTH_COOKIE_SECURE = 'false'
$env:WEBCORE_AUTH_COOKIE_SAME_SITE = 'Lax'

$env:WEBCORE_LOKI_ENABLED = 'true'
$env:WEBCORE_LOKI_ENDPOINT = 'http://corelocalhost:3100/loki/api/v1/push'
$env:WEBCORE_LOKI_SERVICE = 'webcore-spring-api'
$env:WEBCORE_LOKI_ENVIRONMENT = 'development'
$env:WEBCORE_PROMETHEUS_PUBLIC = 'true'
$env:MANAGEMENT_HEALTH_MAIL_ENABLED = 'false'

$env:WEBCORE_DOMAIN_SERVICE_USERNAME = 'webcore-session-reader@ad.example.local'
$env:WEBCORE_DOMAIN_SERVICE_PASSWORD = '<service-account-password>'
$env:WEBCORE_DOMAIN_BASE_DN = 'DC=ad,DC=example,DC=local'
```

The image listens on port `8080`; Compose publishes it as
`WEBCORE_SERVER_PORT` (default `51130`). `JAVA_HOME` is not needed at runtime:
the image contains Java 21.

## 3. Database host mapping

`corelocalhost` is a Docker-only alias used by the database connection string.
Map it to the IP of the PostgreSQL server:

```bash
$env:WEBCORE_CORELOCALHOST_IP = '<postgres-server-ip>'
```

Compose writes the equivalent of the following entry inside the container:

```text
<postgres-server-ip> corelocalhost
```

If PostgreSQL is on another machine, do not use Docker's `host-gateway` for
this value. The database host must allow the Docker/Windows machine to connect
to PostgreSQL TCP port `5432` and `pg_hba.conf` must authorize the API account.

## 4. Active Directory / LDAPS

Spring reads the LDAPS hostname from the Core database, not from an environment
variable. Required `core.sys_var` values are:

```text
SYSTEM | DOMAINLOGIN    | Y
SYSTEM | DOMAINNAME     | ad.example.local
SYSTEM | HOSTNAME       | dc01.ad.example.local
SYSTEM | TIMECACHELDAP  | 30
```

Spring connects to `ldaps://SYSTEM$HOSTNAME:636`. If the container cannot use
the organisation's DNS, set a Docker-only hosts mapping that exactly matches
the database hostname:

```bash
$env:WEBCORE_LDAP_HOSTNAME = 'dc01.ad.example.local'
$env:WEBCORE_LDAP_HOST_IP = '<domain-controller-ip>'
```

Verify the mapping after the container starts:

```bash
docker compose -f docker-compose.spring-api.yml exec webcore-spring-api sh -c "grep dc01.ad.example.local /etc/hosts"
```

The Domain Controller must allow the Docker host to reach TCP port `636`.

## 5. Trust the LDAPS certificate

The Docker image has its own Java truststore. Importing a certificate into the
Windows JDK does not trust it inside this image.

Export the public certificate once from the Domain Controller or, preferably,
the issuing root/intermediate CA. Do not export or distribute a private key.
The same public certificate can be used by every Spring API deployment that
connects to that LDAPS endpoint. It is only replaced when the CA or server
certificate rotates.

For a local development default, copy the certificate to the ignored path:

```text
Runtime/WebCore.SpringApi/certs/dc01-ad-pbb-local.cer
```

For another deployment machine, store it outside the repository and override
the host mount path:

```bash
$env:WEBCORE_LDAPS_CERT_HOST_PATH = 'D:\WebCore\secrets\dc01-ad-pbb-local.cer'
```

At startup the entrypoint mounts that file read-only and imports it into the
container's Java truststore. Rebuild is not needed when only the certificate
file changes; run `up -d --force-recreate` instead.

## 6. Start and verify

```bash
cd Runtime/docker
docker compose -f docker-compose.spring-api.yml up -d --force-recreate
docker compose -f docker-compose.spring-api.yml ps
docker compose -f docker-compose.spring-api.yml logs -f webcore-spring-api
```

From the Windows host:

```bash
Invoke-RestMethod http://localhost:51130/actuator/health
Invoke-RestMethod http://localhost:51130/health/ready
```

`/actuator/health` should be `UP`. `/health/ready` can return `503` if a
required business source registered in `core.def_source` is unavailable.

## 7. Troubleshooting

| Symptom | Meaning and action |
| --- | --- |
| `docker ps` does not show the API | The container exited. Use `docker compose -f docker-compose.spring-api.yml ps -a` and inspect logs. |
| `password authentication failed for user` | The container reached PostgreSQL; correct the database password or verify that `WEBCORE_CORELOCALHOST_IP` points at the intended instance. |
| `AUTH_DOMAIN_UNAVAILABLE` / `LDAPS connection failed` | Check the `/etc/hosts` mapping, TCP `636`, and then the LDAPS certificate mount/trust. |
| `SSLHandshakeException` or `PKIX path building failed` | The LDAPS certificate/CA is missing from the container truststore. Set `WEBCORE_LDAPS_CERT_HOST_PATH`, then force recreate. |
| Compose reports orphan observability/database containers | Those services may be managed by other Compose files. Do not use `--remove-orphans` unless the intent is to stop and remove them. |
