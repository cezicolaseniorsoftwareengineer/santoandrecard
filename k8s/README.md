# Kubernetes deployment — card-platform

Target: any conformant cluster. Verified layout only; see "Verification status".

## Prerequisites

- A reachable cluster (`kubectl cluster-info` must succeed).
- A container image `card-service:0.2.0` present in the cluster's image store
  (`imagePullPolicy: IfNotPresent`, no registry is configured).

## 1. Build the image

```sh
mvn -pl card-service -am clean package
docker build -f card-service/src/main/docker/Dockerfile.jvm \
  -t card-service:0.2.0 card-service
```

On a non-Docker-Desktop cluster the image must be side-loaded, e.g.
`kind load docker-image card-service:0.2.0` or `minikube image load`.

## 2. Create the database Secret

`card-service-secret.example.yaml` is a template and must not be applied as-is.

```sh
kubectl create namespace card-platform
kubectl -n card-platform create secret generic card-service-secrets \
  --from-literal=db-password="$(openssl rand -base64 24)"
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
- No Ingress is defined; the Service is ClusterIP. Expose it deliberately.
- No NetworkPolicy is defined; the namespace is open east-west by default.

## Verification status

BUILD VERIFICADO COM SUCESSO / DEPLOY VERIFICADO. Steps 1-4 were executed on a
Docker Desktop cluster: the image was built as `card-service:0.2.0`, the
kustomization applied, and `postgres-0`, `keycloak` and `card-service` all
reached `1/1 Running`. `/q/health/ready` returned `UP` with the database check
`UP` through `port-forward`, and `/api/v1/cards` without a token returned `401`.

Two defects were found and fixed by that run, both from inheriting the 1s
default probe timeout on a contended node: PostgreSQL was restarted repeatedly
while healthy because `pg_isready` could not complete an exec in 1s, and
`card-service` never finished its startup budget. Probe timeouts are now
explicit in `postgres.yaml` and `card-service.yaml`.

The image tag is the one in `card-service.yaml` (`0.2.0`), not the `0.1.0` this
document used to state in step 1.

Two replicas saturate a single-node laptop cluster that is also running the
Compose stack. `kubectl -n card-platform scale deploy/card-service --replicas=1`
if the node starts evicting or the API server times out.
