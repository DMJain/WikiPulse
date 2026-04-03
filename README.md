# WikiPulse — Operational Runbook

> **A real-time Wikipedia edit analytics engine.**
> Reactive SSE ingestion → Kafka → distributed Spring Boot workers → PostgreSQL + Grafana.

---

## Table of Contents

1. [The Stack](#the-stack)
2. [Architecture Overview](#architecture-overview)
3. [Cold Start Sequence](#cold-start-sequence)
4. [Observability Access](#observability-access)
5. [Scaling Verification — HPA Stress Test](#scaling-verification--hpa-stress-test)
6. [Port Reference](#port-reference)
7. [Dependency Health Checks](#dependency-health-checks)
8. [Troubleshooting](#troubleshooting)

---

## The Stack

| Layer | Technology | Version |
|---|---|---|
| **Language** | Java | 21 (Virtual Threads — ADR-002) |
| **Framework** | Spring Boot | 3.4 |
| **Messaging** | Apache Kafka (Confluent) | 7.5.0 |
| **Cache / Dedup** | Redis | 7.2-alpine |
| **Persistence** | PostgreSQL | 15-alpine |
| **Orchestration** | Kubernetes / Minikube | ≥1.30 |
| **Autoscaling** | Kubernetes HPA (`autoscaling/v2`) | — |
| **Observability** | Prometheus + Grafana | latest |
| **Build** | Maven Wrapper | 3.9.6 |
| **Container** | Eclipse Temurin JRE | 21-alpine |

---

## Architecture Overview

```
Wikipedia SSE Stream
        │
        ▼
┌─────────────────────┐
│  WikipediaSseClient │  Reactive WebFlux (ADR-009)
│  (Spring Boot App)  │  Async producer callbacks (ADR-014)
└────────┬────────────┘
         │ publish
         ▼
┌─────────────────────┐
│    Kafka Broker     │  3 partitions, 1 replication factor
│  Topic: wiki-edits  │  DLT: wiki-edits-dlt (ADR-015)
└────────┬────────────┘
         │ consume (group: wikipulse-worker-group)
         ▼
┌─────────────────────────────────────────────────┐
│        wikipulse-worker  ×1–6 pods (HPA)        │
│  ┌──────────────────────────────────────────┐   │
│  │  DeduplicationService (Redis, TTL=24h)   │   │  ADR-012
│  │  AnalyticsScoringService (complexity)    │   │
│  │  BotDetectionService (Redis velocity)    │   │
│  │  WikiEditRepository (PostgreSQL)         │   │  ADR-013
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
         │ exposition
         ▼
┌─────────────────────┐     ┌──────────────────┐
│  /actuator/prometheus│────▶│   Prometheus     │
└─────────────────────┘     └────────┬─────────┘
                                      │
                                      ▼
                             ┌──────────────────┐
                             │     Grafana       │  localhost:3000
                             └──────────────────┘
```

---

## Cold Start Sequence

> **Estimated time to fully operational: ~5 minutes.**
> Execute each step in order. Do not skip steps.

### Step 1 — Start Minikube

```bash
minikube start --cpus=4 --memory=6144
```

> Using `--cpus=4 --memory=6144` is the **minimum** recommended for running all
> dependencies (Postgres + Redis + Kafka + ZooKeeper + 3 worker pods + Grafana)
> concurrently. The HPA requires the `metrics-server` addon (Step 2).

---

### Step 2 — Enable Metrics Server

The HPA (`k8s/hpa.yaml`) **cannot function** without the metrics-server. Enable it once:

```bash
minikube addons enable metrics-server
```

Verify it is running (wait ~30 s after enabling):

```bash
kubectl get deployment metrics-server -n kube-system
# Expected: READY 1/1
```

---

### Step 3 — Build & Load the Application Image

The `imagePullPolicy: Never` in `deployment.yaml` requires the image to be
pre-loaded directly into Minikube's Docker daemon.

```bash
# Build the image against the local Docker daemon
docker build -t wikipulse-ingestor:latest .

# Load the image into Minikube's image store
minikube image load wikipulse-ingestor:latest
```

Verify the image is visible inside Minikube:

```bash
minikube image ls | grep wikipulse-ingestor
# Expected: docker.io/library/wikipulse-ingestor:latest
```

---

### Step 4 — Apply All Kubernetes Manifests

Apply in dependency order:

```bash
# 1. Config & secrets first (pods reference these)
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml

# 2. Infrastructure layer (Postgres, Redis, Kafka, ZooKeeper)
kubectl apply -f k8s/infrastructure.yaml

# 3. Wait for all infrastructure pods to reach Running state
kubectl wait --for=condition=ready pod -l tier=infrastructure --timeout=180s

# 4. Application layer + HPA
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/hpa.yaml

# 5. Observability layer
kubectl apply -f k8s/grafana-datasource.yaml
kubectl apply -f k8s/grafana-deployment.yaml
kubectl apply -f k8s/grafana-service.yaml
```

**Alternatively — apply the entire k8s/ directory in one shot (idempotent after infrastructure is healthy):**

```bash
kubectl apply -f k8s/
```

---

### Step 5 — Verify Full Cluster Health

```bash
# All pods should show STATUS=Running
kubectl get pods -o wide

# HPA should show TARGETS and MINPODS/MAXPODS
kubectl get hpa wikipulse-worker-hpa

# Services should show CLUSTER-IP and correct ports
kubectl get svc
```

Expected output snapshot:

```
NAME                    READY   STATUS    RESTARTS   AGE
grafana-xxx             1/1     Running   0          2m
kafka-xxx               1/1     Running   0          3m
postgres-xxx            1/1     Running   0          3m
redis-xxx               1/1     Running   0          3m
wikipulse-worker-xxx    1/1     Running   0          1m   ← ×1–6 managed by HPA
zookeeper-xxx           1/1     Running   0          3m
```

---

## Observability Access

### Grafana Dashboard

Grafana runs inside the cluster on port **3000** behind `grafana-service`. Use
`kubectl port-forward` to tunnel it to your local machine:

```bash
kubectl port-forward svc/grafana-service 3000:3000
```

Then open your browser:

```
http://localhost:3000
```

> **Default credentials:** `admin` / `admin` (change on first login).

The Grafana instance is pre-provisioned via `k8s/grafana-datasource.yaml` with
the internal Prometheus data source. Open the **WikiPulse Executive Dashboard**
to see:

| Panel | PromQL |
|---|---|
| p99 Processing Latency | `histogram_quantile(0.99, sum(rate(wikipulse_processing_duration_seconds_bucket[5m])) by (le))` |
| Consumer Lag (per partition) | `kafka_consumer_fetch_manager_records_lag` |
| Error Rate | `rate(wikipulse_errors_total[1m])` |
| Replica Count | `kube_deployment_status_replicas{deployment="wikipulse-worker"}` |

### Prometheus Raw Metrics

```bash
# Port-forward Prometheus (if deployed separately)
kubectl port-forward svc/prometheus-service 9090:9090
# Then visit: http://localhost:9090

# Or scrape the actuator endpoint from a worker pod directly:
kubectl exec -it <wikipulse-worker-pod-name> -- \
  wget -qO- http://localhost:8080/actuator/prometheus | head -60
```

---

## Scaling Verification — HPA Stress Test

The HPA triggers at **70% CPU utilization** (350m milliCPU) per the policy in
`k8s/hpa.yaml` (ADR-022). Use the `md5sum` stress test to saturate CPU without
any external tooling dependencies.

### Step 1 — Open a Watcher Terminal

Keep this running in a **separate terminal** to observe scaling events in real time:

```bash
watch -n 5 "kubectl get hpa wikipulse-worker-hpa && echo '---' && kubectl get pods -l app=wikipulse-worker"
```

### Step 2 — Inject CPU Load

Pick **one** of the worker pods and run the stress loop:

```bash
# Get any running worker pod name
POD=$(kubectl get pod -l app=wikipulse-worker -o jsonpath='{.items[0].metadata.name}')

# Exec into the pod and start md5sum stress loop
kubectl exec -it $POD -- \
  sh -c 'while true; do head -c 100M /dev/urandom | md5sum; done'
```

> The loop continuously hashes 100MB of random bytes, driving CPU to ~100% on
> the container. Press **Ctrl+C** to stop the load.

### Step 3 — Observe Scaling Events

| Time | Expected Observation |
|---|---|
| **T+0s** | Load starts. HPA polls every 15 s. |
| **T+60s** | `TARGETS` column shows CPU > 70%. Scale-up begins. |
| **T+90s** | New pod(s) reach `ContainerCreating → Running`. |
| **T+120s** | Up to 6 pods running. HPA stabilization window engaged. |
| **T+0s (after Ctrl+C)** | Load stops. 300 s anti-thrash window starts. |
| **T+5min** | Pods gradually scale back down (-1 per 60 s). |

### Step 4 — Describe HPA for Detailed Events

```bash
kubectl describe hpa wikipulse-worker-hpa
```

Look for `ScalingActive: True` and `AbleToScale: True` in the Conditions section,
and scaling events in the `Events:` section at the bottom.

---

## Port Reference

| Service | Cluster-Internal Host | Port | External (port-forward) |
|---|---|---|---|
| PostgreSQL | `postgres` | `5432` | `kubectl port-forward svc/postgres 5432:5432` |
| Redis | `redis` | `6379` | `kubectl port-forward svc/redis 6379:6379` |
| Kafka (internal) | `kafka` | `29092` | — |
| Kafka (external) | `kafka` | `9092` | `kubectl port-forward svc/kafka 9092:9092` |
| Grafana | `grafana-service` | `3000` | `kubectl port-forward svc/grafana-service 3000:3000` |
| App Actuator | pod IP | `8080` | `kubectl port-forward svc/<worker-svc> 8080:8080` |

---

## Dependency Health Checks

Run these after `kubectl apply` to confirm each dependency is operational:

```bash
# PostgreSQL — expect: "wikipulsedb"
kubectl exec -it $(kubectl get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}') \
  -- psql -U wikipulse -d wikipulsedb -c '\l' | grep wikipulsedb

# Redis — expect: PONG
kubectl exec -it $(kubectl get pod -l app=redis -o jsonpath='{.items[0].metadata.name}') \
  -- redis-cli ping

# Kafka — expect: topic list including "wiki-edits"
kubectl exec -it $(kubectl get pod -l app=kafka -o jsonpath='{.items[0].metadata.name}') \
  -- kafka-topics --bootstrap-server localhost:9092 --list

# ZooKeeper — expect: "imok"
kubectl exec -it $(kubectl get pod -l app=zookeeper -o jsonpath='{.items[0].metadata.name}') \
  -- bash -c "echo ruok | nc localhost 2181"
```

---

## Troubleshooting

### `UnknownHostException: postgres`

The worker cannot resolve the `postgres` hostname. This means `infrastructure.yaml`
has not been applied or the PostgreSQL pod is not yet `Running`.

```bash
# Check pod status
kubectl get pods -l app=postgres

# Check service exists (DNS entry)
kubectl get svc postgres

# Check pod logs for readiness issues
kubectl logs -l app=postgres --tail=50
```

**Resolution:** `kubectl apply -f k8s/infrastructure.yaml` and wait for
`kubectl wait --for=condition=ready pod -l app=postgres --timeout=120s`.

---

### Worker in `CrashLoopBackOff`

```bash
kubectl logs -l app=wikipulse-worker --previous --tail=100
```

Common causes:
- `postgres` / `redis` / `kafka` services not yet healthy → apply infrastructure first.
- Secret `wikipulse-secret` not applied → `kubectl apply -f k8s/secret.yaml`.
- Image not loaded → `minikube image load wikipulse-ingestor:latest`.

---

### HPA Shows `<unknown>/70%` for TARGETS

The metrics-server addon is not active or not yet ready.

```bash
minikube addons enable metrics-server
kubectl rollout restart deployment metrics-server -n kube-system
# Wait ~60s, then:
kubectl top pods
```

---

### Grafana Shows "No Data"

The Prometheus scrape target may not be reachable inside the cluster.

```bash
# Verify the worker actuator is serving metrics
kubectl exec -it $(kubectl get pod -l app=wikipulse-worker -o jsonpath='{.items[0].metadata.name}') \
  -- wget -qO- http://localhost:8080/actuator/prometheus | grep wikipulse_processing
```

---

*WikiPulse — Phase 3 Operational Runbook — Last updated: 2026-04-03*
