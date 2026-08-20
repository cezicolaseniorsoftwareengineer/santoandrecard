# Kubernetes deployment — card-platform

Target: any conformant cluster. Verified layout only; see "Verification status".

## Prerequisites

- A reachable cluster (`kubectl cluster-info` must succeed).
- An ingress-nginx controller, for the hosts in `ingress.yaml`.
- The images. Releases publish them to the GitHub Container Registry and
  `kustomization.yaml` points at that tag, so a cluster with internet access
  needs nothing built locally. Building locally is still supported and is what
  the steps below cover.

The image identity lives in `kustomization.yaml` and nowhere else. It used to be
written into two manifests and two documents, and they drifted — the manifests
named one tag while the runbook taught another, and the only symptom was a pod
stuck in `ImagePullBackOff`.

## 1. Images

### From the registry (nothing to build)

Every release publishes both images. Point the kustomization at the version you
want:

```sh
cd k8s && kustomize edit set image   ghcr.io/cezicolaseniorsoftwareengineer/card-service=*:v0.4.0   ghcr.io/cezicolaseniorsoftwareengineer/web-app=*:v0.4.0
```

### Or build them locally

```sh
mvn -pl card-service -am clean package
docker build -f card-service/src/main/docker/Dockerfile.jvm   -t card-service:local card-service
docker build -t web-app:local web-app

cd k8s && kustomize edit set image card-service=card-service:local   web-app=web-app:local
```

`imagePullPolicy` is `IfNotPresent`, so a local tag is never pulled and must
already be in the cluster's store. Docker Desktop's own Kubernetes shares the
Docker store; every other local cluster — including the kind-based provisioner
Docker Desktop now ships — keeps a separate containerd store and needs the
images side-loaded:

```sh
kind load docker-image card-service:local web-app:local   # kind
minikube image load card-service:local                    # minikube
```

With no `kind` binary, stream them into the node directly:

```sh
docker save card-service:local |   docker exec -i <node-container> ctr -n k8s.io images import -
```

Check what the cluster actually holds, because `docker images` answers for the
wrong store:

```sh
docker exec <node-container> crictl images | grep -E 'card-service|web-app'
```

## 2. Create the Secret and the realm ConfigMap

`card-service-secret.example.yaml` is a template and must not be applied as-is.
The Secret carries **two** keys: the manifests mount `db-password` and
`keycloak-admin-password`, and Keycloak crash-loops without the second.

```sh
kubectl create namespace card-platform
kubectl -n card-platform create secret generic card-service-secrets   --from-literal=db-password="$(openssl rand -base64 24)"   --from-literal=keycloak-admin-password="$(openssl rand -base64 24)"
```

The realm definition lives outside this kustomization root, so its ConfigMap is
created out-of-band as well:

```sh
kubectl -n card-platform create configmap keycloak-realm   --from-file=keycloak/realm-card-platform.json
```

The same key backs both PostgreSQL's `POSTGRES_PASSWORD` and the application's
`DB_PASSWORD`, so rotating it requires re-initialising or `ALTER ROLE` on the
database — the password is only read by `initdb` on first start.

## 3. Apply

```sh
kubectl apply -k k8s/
kubectl -n card-platform rollout status deploy/card-service --timeout=180s
```

## 4. Smoke test

```sh
kubectl -n card-platform port-forward svc/card-service 8080:80
curl -fsS http://localhost:8080/q/health/ready
```

## Notes and limits

- `k8s/postgres.yaml` is a single-replica StatefulSet suitable for local and
  development clusters only. It has no backup, no failover and no connection
  pooler. Production must drop it and point `DB_URL` at a managed instance.
- `quarkus.flyway.migrate-at-start=true` runs migrations from every replica.
  Flyway serialises this with a database lock, so concurrent startup is safe,
  but a failed migration will crash-loop all replicas.
- `ingress.yaml` publishes three hosts under `*.card-platform.localhost`, which
  browsers resolve to the loopback address with no hosts-file edit. It needs an
  ingress-nginx controller. No TLS is configured and the realm runs with
  `sslRequired: none`, so exposing any of this beyond the machine requires TLS
  first.
- No NetworkPolicy is defined; the namespace is open east-west by default.

## Verification status

DEPLOY VERIFICADO. The full stack was applied to a Docker Desktop cluster and
all seven pods reached `1/1 Running` with no restarts. `/q/health/ready`
returned `UP` with the database check `UP`, `/api/v1/cards` without a token
returned `401`, and a complete money journey ran through the Ingress against
the real Keycloak: self-service issuance at the issuer's limit, a top-up, a card
load, a purchase, and the same `Idempotency-Key` replayed returning the original
purchase rather than charging twice.

Four defects were found and fixed by that run. None of them was in the
application.

- **Keycloak restarted 139 times.** It had no startup probe, so the liveness
  probe owned the window in which Keycloak rebuilds its configuration and
  imports the realm: it began at 60s and killed the container three failures
  later, at exactly 105s, every time. Augmentation alone takes 96s on this node.
  The process was never unhealthy, only unfinished — which is the distinction a
  liveness probe cannot make and a startup probe exists for.
- **Kafka restarted 343 times.** Its readiness probe launched a whole JVM every
  ten seconds against a broker limited to one CPU, so the probe competed with
  what it was measuring and won. Startup is now judged by the listener being
  open; the client check runs at a period that observes rather than loads.
- **Kafka never became ready, not once.** A KRaft broker resolves its own pod
  name to reach the controller quorum, and a headless Service publishes a pod's
  DNS record only when that pod is ready — it was waiting on a name that could
  not exist until it had finished starting. `publishNotReadyAddresses` breaks
  the deadlock.
- **Kafka and Redis were wired to nothing.** Both were deployed and neither was
  passed to the application, so both fell back to their localhost defaults
  inside the pod. The failure was silent by design — the outbox holds what it
  cannot publish, the throttle allows, the cache misses — and no event about
  money had ever left the service. `KAFKA_BOOTSTRAP_SERVERS` and `REDIS_URL` are
  now set in the ConfigMap, and the topic carries `wallet.topped-up`,
  `card.loaded` and `purchase.authorised`, keyed by customer.

An earlier run had already made the PostgreSQL and card-service probe timeouts
explicit, after the 1s default proved shorter than an exec on a contended node.

Two replicas saturate a single-node laptop cluster that is also running the
Compose stack. `kubectl -n card-platform scale deploy/card-service --replicas=1`
if the node starts evicting or the API server times out.
