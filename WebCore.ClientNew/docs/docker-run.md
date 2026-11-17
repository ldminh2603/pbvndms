# Run ClientNew with Docker

ClientNew is a Next.js server. It proxies `/api/webcore*` requests to Spring
using the runtime `WEBCORE_API_URL` environment variable. It is not a static
site export in this mode.

## Source machine: build the image

```bash
cd Runtime/docker
docker compose -f docker-compose.client-new.yml build
docker save --output webcore-client-new_local.tar webcore-client-new:local
```

For a local Spring API published on port `51130`, the Compose default is
`http://host.docker.internal:51130`. Override it before `up` only when Spring
is reachable at another URL:

```bash
$env:WEBCORE_API_URL = 'http://host.docker.internal:51130'
docker compose -f docker-compose.client-new.yml up -d
```

## Deployment machine: no source code

Copy `webcore-client-new_local.tar` and
`docker-compose.client-new.deploy.yml` to the deployment machine, then run:

```bash
docker load --input webcore-client-new_local.tar
$env:WEBCORE_API_URL = 'http://host.docker.internal:51130'
docker compose -f docker-compose.client-new.deploy.yml up -d
```

The default public port is `3001`; override it with
`WEBCORE_CLIENT_NEW_PORT`. Use `up -d --force-recreate` after changing
`WEBCORE_API_URL`. A plain `start` reuses the environment captured when the
container was created.
