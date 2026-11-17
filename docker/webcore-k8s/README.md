# WebCore Kubernetes manifests

Target: kubeadm Kubernetes 1.35.9, MetalLB VIP `172.68.69.100`, Traefik Ingress, two worker nodes with `ingress-node=true` labels.

## Prerequisites

- Both worker nodes must have images `docker.io/library/webcore-client-new:local` and `docker.io/library/webcore-spring-api:local` imported into containerd namespace `k8s.io`.
- Verify `kubectl get nodes --show-labels` shows `ingress-node=true` on both workers.
- Confirm DB, Redis, LDAP, ClamAV, Loki at `172.68.69.173` are reachable from Pods. Database server IP is taken from the supplied environment variables (not the earlier `.68.94`).
- Ensure passwords are rotated if previously shared and validate PostgreSQL connection string is parsed by this Java application.

## Deploy on master

```bash
cd /root/webcore-k8s
kubectl apply -f namespace.yaml
bash scripts/create-secret.sh
kubectl apply -f backend-configmap.yaml
kubectl apply -f frontend-deployment.yaml -f frontend-service.yaml
kubectl apply -f backend-deployment.yaml -f backend-service.yaml
kubectl rollout status -n webcore deploy/webcore-frontend --timeout=300s
kubectl rollout status -n webcore deploy/webcore-backend --timeout=300s
kubectl get pods,svc -n webcore -o wide
kubectl apply -f webcore-ingress.yaml
kubectl get ingress -n webcore
```

Do **not** apply `backend-secret.yaml` as-is: it contains placeholders. Use the interactive script. Do not commit real secrets to source control.

## Check and debug

```bash
kubectl get pods -n webcore -o wide
kubectl logs -n webcore deploy/webcore-backend --tail=100
kubectl logs -n webcore deploy/webcore-frontend --tail=100
kubectl describe pods -n webcore
kubectl get endpointslices -n webcore
curl -i http://172.68.69.100/
curl -i http://172.68.69.100/api
```

## Important validation

- The backend image declares EXPOSE 8080, but verify the entrypoint actually binds port 8080. The frontend image declares port 3001.
- `/api` is an assumed backend route. If the backend uses another context path, update the Ingress. Traefik does not strip `/api` in this manifest.
- The frontend may have a baked-in API URL; check its actual runtime config. Frontend browser requests must not point at `localhost:51130` unless that is truly intended.
- `WEBCORE_OAUTH_ISSUER=http://localhost:51130` is preserved from the provided configuration but is likely wrong for externally accessed authentication; verify expected issuer, redirect URLs and proxy headers before real use.
- `WEBCORE_CLUSTER_ENABLED=false` is preserved, despite two backend replicas. Verify session/state, scheduled tasks, websocket and distributed coordination. Consider scaling backend to one replica until confirmed safe.
- TCP readiness checks only establish that ports are open, not application health. Replace with HTTP health probes after confirming the actual endpoints.
- `WEBCORE_CORS_ALLOWED_ORIGINS` includes the VIP HTTP origin in addition to provided origins. Remove unneeded development origins before production.
- `WEBCORE_AUTH_COOKIE_SECURE=false`, HTTP ingress, and TRACE security logging are development settings. Use HTTPS, secure cookies, appropriate auth issuer, and less verbose logging in production.
- Passwords in Kubernetes Secrets are not encrypted at rest unless Kubernetes encryption-at-rest is configured. Restrict RBAC and access to the master.
- `imagePullPolicy: Never` requires local images on each eligible worker. For long-term operations use a private registry with immutable image tags/digests.
