# ADR-005: Delivery Pipeline and the Deployment Model

- Status: Accepted for the pipeline, Proposed for the deployment model
- Date: 2026-08-20
- Scope: how a commit becomes a published image, and how a published image becomes a running deployment
- Relates to: ADR-004 (which database a deployment answers against)

## Context

The pipeline verified and published nothing. Every check ran on every push —
tests, the money invariants under concurrency, a browser journey — and then the
commit sat there. Images existed only on whichever machine last ran
`docker build`, and the version they carried was written by hand into two
manifests and two documents.

Four hand-copied versions drift, and these did. The manifests named one tag
while the runbook taught another, and the symptom was a pod that never started:
`imagePullPolicy` is `IfNotPresent` and no registry was configured, so the
cluster silently used whatever it already held, or nothing.

That failure has a shape worth naming, because it recurred three times in one
day and each recurrence looked different:

- Documentation naming a tag the manifests did not.
- Manifests naming a tag the registry did not hold.
- A pipeline publishing `0.4.0` for a repository tag of `v0.4.0`, because the
  semver patterns in the metadata action parse the ref and drop the leading `v`.

Every one of them is the same defect: **a version that exists in more than one
place, with nothing checking that the copies agree**.

## Decision

### The version has one home

`k8s/kustomization.yaml` carries the image identity. The manifests reference a
bare name and resolve through it. One line per image, changed once per release.

### The image tag is the git tag, character for character

`type=ref,event=tag`, not the semver patterns. The semver patterns produce
tidier output and quietly rewrite the version, which is how `v0.4.0` in the
repository became `0.4.0` in the registry and a 404 for anyone who trusted the
manifest. A translation layer between two names for one thing is the defect,
not the fix.

### Nothing is published from a red build

The release workflow runs the full suite before it builds anything. A registry
carrying an image no test ever saw is worse than no registry: it makes an
unverified build look like a release.

### The pipeline asks the registry rather than trusting the manifest

Rendering a kustomization proves the YAML is well formed. It does not prove the
image it names was ever built. The `kubernetes manifests` job renders, validates
every resource against the Kubernetes schemas in strict mode — unknown fields
rejected, because a typo in a probe silently does nothing — and then asks the
registry whether each image reference can be pulled.

This check was written against the broken state and refused it before it was
allowed to pass. A guard that has never failed has not been tested.

### `main` cannot go red unnoticed

Branch protection requires all checks. Force pushes and branch deletion are
refused. Administrators are not forced through it, so a direct commit from the
GitHub interface still works — the protection is there to catch a mistake, not
to add ceremony to a single-maintainer repository.

## What is deliberately not decided

**Automatic deployment to an environment.** Delivery stops at the registry.

The gap is not tooling, it is reachability: the cluster is local, and GitHub
Actions cannot open a connection to it. The two ways out differ in kind rather
than in effort.

### Option A — GitOps, the cluster pulls

An operator in the cluster (Argo CD or Flux) watches this repository and applies
what it finds. Actions never needs credentials to the cluster, and never needs a
route into it, so it works with a laptop cluster and with a private production
one for the same reason.

It also makes the deployed state observable: the operator reports drift between
the repository and the cluster, which is the question "is what is running what we
think is running" — currently answerable only by hand.

Cost: an operator to install and keep upgraded, and a second repository or
directory for environment overlays, because a cluster that applies `main`
directly deploys every commit.

### Option B — push-based, Actions deploys

Actions holds a kubeconfig and runs `kubectl apply`. Simpler to explain and
immediate to set up.

It requires a cluster GitHub can reach and a long-lived credential in repository
secrets that can change anything in the namespace. For a demonstration
environment that is acceptable. For anything holding money it inverts the trust
boundary: the cluster stops being the thing that decides what may run in it.

### Recommendation

Option A, when there is an environment worth deploying to continuously. Not
before — an operator watching a repository that deploys to one laptop adds a
component to maintain and answers no question that `kubectl apply -k k8s/` does
not already answer.

The prerequisite is not the operator. It is a cluster that outlives the machine,
and TLS with rotated credentials before anything is exposed to it, which is
recorded in ADR-004 and in the README's own statement that this must not be
reachable from outside an isolated environment.

## Consequences

Deploying no longer requires a build. `kubectl apply -k k8s/` pulls published
images, so a reader with a cluster and no Java toolchain can run the platform.

A release is now an event with a record: a tag produces images, notes stating
which images carry that version, and a run showing what passed before they were
built.

The class of defect that produced this ADR fails in CI instead of in a cluster.
A manifest naming an image that does not exist is caught in seconds, by a job
that asks, rather than an hour later as an `ImagePullBackOff` with nothing on
screen explaining why.

Publishing on every push to `main` costs registry storage that nothing prunes.
Untagged digests accumulate, and a retention policy is worth adding before that
becomes the reason someone deletes packages by hand.
