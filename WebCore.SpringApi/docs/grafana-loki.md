# Grafana, Loki and Prometheus for WebCore Spring API

This guide is specific to `Runtime/WebCore.SpringApi`. It explains how to send
Spring API sends logs to Loki and exports runtime/cache metrics to Prometheus.

## 1. Check the observability stack

From the repository root:

```powershell
docker compose -f Runtime/docker/docker-compose.observability.yml ps
```

Expected local addresses:

- Grafana: `http://localhost:3001`
- Loki: `http://localhost:3100`
- Prometheus: `http://localhost:9090`

Start the stack when it is not running:

```powershell
docker compose -f Runtime/docker/docker-compose.observability.yml up -d
```

## 2. Start Spring with Loki enabled

Set the environment variables in the same PowerShell window that starts Java:

```powershell
cd F:\MayAo\Project\WebCore\50.web\Runtime\WebCore.SpringApi

$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:WEBCORE_LOKI_ENABLED = 'true'
$env:WEBCORE_LOKI_ENDPOINT = 'http://localhost:3100/loki/api/v1/push'
$env:WEBCORE_LOKI_SERVICE = 'webcore-spring-api'
$env:WEBCORE_LOKI_ENVIRONMENT = 'development'
# Required only when the local Docker Prometheus container scrapes this host process.
$env:WEBCORE_PROMETHEUS_PUBLIC = 'true'

mvn spring-boot:run
```

`WEBCORE_LOKI_ENABLED` is process-scoped. Set it again whenever Spring is started
from a new terminal.

## 3. Configure Grafana datasource

Open `http://localhost:3001`, then:

1. Open **Connections** → **Data sources**.
2. Add or select **Loki**.
3. For the Docker Compose Grafana container, use `http://loki:3100` as the URL.
4. Save and test the datasource.

## 4. View Spring request logs

1. Open **Explore**.
2. Select the Loki datasource.
3. Switch to **Code** mode if Grafana displays the visual query builder.
4. Use the query below and select a recent time range, for example **Last 5 minutes**:

```logql
{service="webcore-spring-api"} |= "http_request"
```

5. Click **Run query**.

Spring records an `http_request` event for every `/api/**` request. The message
contains the HTTP method, path, status code, elapsed time, and correlation ID.

Useful alternatives:

```logql
{service="webcore-spring-api", level="error"}
```

```logql
{service="webcore-spring-api"} |= "path=/api/module"
```

## Troubleshooting

### Grafana shows `No data`

1. Restart Spring after setting `WEBCORE_LOKI_ENABLED=true`.
2. Open React or call an API endpoint to create a new request log.
3. Set the Grafana range to **Last 5 minutes**.
4. Confirm the query uses `service="webcore-spring-api"`.

### Loki container is running but no Spring log is present

Check the Java terminal for startup output, then verify the environment value in
the same terminal:

```powershell
$env:WEBCORE_LOKI_ENABLED
```

It must print `true`. Do not rely on a value set in another PowerShell session.

### Do not put secrets in logs

The Loki appender labels logs only with `service`, `environment`, and `level`.
Do not add passwords, access tokens, connection strings, or user data to log
messages or labels.

## 5. Runtime cache dashboard and alerts

The compose stack provisions **WebCore / WebCore Spring Runtime** automatically.
It displays Spring availability, cache hit/miss rate, cache warm-up duration,
HTTP throughput and HTTP p95 latency. Prometheus evaluates these alerts:

- `WebCoreSpringApiDown` after two minutes without a successful scrape.
- `WebCoreCacheMissRateHigh` after a cache miss rate above 20% for ten minutes.
- `WebCoreCacheWarmupSlow` after a warm-up duration above 30 seconds for five minutes.

`WEBCORE_PROMETHEUS_PUBLIC` defaults to `false`. Set it to `true` only when
Prometheus can reach the endpoint through private networking (the local compose
file uses `host.docker.internal:51130`). Do not expose `/actuator/prometheus`
directly to the public internet. For production, point the scrape target to the
private service address and configure Alertmanager or Grafana contact points for
notification delivery.
