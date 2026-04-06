# WikiPulse V3: Real-Time Event Streaming, Analytics, and SRE Observability

![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=0A0A0A)
![Kafka](https://img.shields.io/badge/Kafka-7.5.0-231F20?logo=apachekafka&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?logo=redis&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?logo=postgresql&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-Enabled-E6522C?logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-Dashboarded-F46800?logo=grafana&logoColor=white)

WikiPulse V3 is a production-style streaming analytics platform that ingests live Wikipedia edits, processes them through a resilient Kafka pipeline, deduplicates and persists enriched events, and exposes both real-time and aggregate intelligence through a React dashboard.

## Architecture (V3)

```mermaid
flowchart LR
    A[Wikipedia SSE Firehose] --> B[WebFlux Backpressure Buffer]
    B --> C[Spring Boot Worker (Virtual Threads)]
    C --> D[Kafka Topic: wiki-edits]
    D --> E[Worker Consumer]

    E --> F[Redis 24h SETNX Dedup]
    F --> G[PostgreSQL Analytics Store]

    E --> H[Prometheus Metrics Scraping]
    H --> I[Grafana Dashboards]

    E --> J[API Layer (REST and STOMP)]
    J --> K[Nginx Reverse Proxy]
    K --> L[React UI]
```

## Core Platform Features

### 1. Reactive Firehose Ingestion With Backpressure Control
- **What**: Wikipedia SSE events are consumed via Spring WebFlux with an explicit bounded backpressure buffer.
- **Why it matters**: Burst traffic is absorbed safely instead of overwhelming downstream services.
- **Outcome**: Stable ingestion under spikes, without dropping the entire pipeline.

### 2. Kafka-Centered Streaming Backbone
- **What**: The worker publishes incoming events into the `wiki-edits` topic and consumes through a managed consumer group.
- **Why it matters**: Kafka decouples ingestion rate from processing rate and preserves partition-level ordering.
- **Outcome**: Reliable asynchronous processing and scalable parallel consumers.

### 3. Distributed Dedup and Durable Persistence
- **What**: Consumer-side dedup uses Redis `SETNX` semantics with a 24-hour TTL window before writing to PostgreSQL.
- **Why it matters**: Retries, rebalances, and at-least-once delivery can otherwise produce duplicates.
- **Outcome**: Idempotent event processing with durable analytics history.

### 4. Interactive Dashboard With Dynamic Filtering
- **What**: The Analytics Overview tab supports **Dynamic Filtering** by Timeframe (`1h`, `24h`, `7d`) and Bot Status (`All`, `Bots`, `Humans`).
- **Why it matters**: Operators can rapidly pivot across recent windows and traffic classes without redeploying queries.
- **Outcome**: Faster incident triage and better data-driven insight during burst conditions.

### 5. Database-Side JPQL Aggregations
- **What**: KPI, language, namespace, and bot/human analytics are computed with **database-side JPQL aggregations**.
- **Why it matters**: Aggregating in PostgreSQL reduces API-side compute overhead and improves response consistency.
- **Outcome**: Efficient REST analytics endpoints for high-fidelity charts and KPI cards.

### 6. Unified API and Live Streaming UX
- **What**: REST endpoints (`/api/...`) and STOMP-over-SockJS (`/ws-wikipulse` -> `/topic/edits`) power the two-tab dashboard experience.
- **Why it matters**: Users need both historical aggregates and real-time event-level observability.
- **Outcome**: One UI surface for analytics and live firehose monitoring.

## Observability and SRE

WikiPulse exports native Micrometer metrics through Spring Actuator at `/actuator/prometheus`. Prometheus scrapes these metrics, and Grafana visualizes them for operational decision-making.

### KPIs Tracked
- **Processing latency**: `wikipulse_processing_latency`
- **Throughput**: `wikipulse_edits_processed_total`
- **Error rate / quarantine signal**: `wikipulse_errors_total` (with failed messages routed to `wiki-edits-dlt`)
- **Consumer lag**: `kafka_consumer_fetch_manager_records_lag`

### SRE Runbook (Kubernetes)

Apply platform manifests:

```bash
kubectl apply -f k8s/
```

Expose worker metrics locally:

```bash
kubectl port-forward svc/wikipulse-metrics-service 8080:8080
```

Validate critical metrics are present:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "wikipulse_processing_latency|wikipulse_errors_total|kafka_consumer_fetch_manager_records_lag"
```

Expose Grafana:

```bash
kubectl port-forward svc/grafana-service 3000:3000
```

Open Grafana:
- http://localhost:3000

### Operational Notes
- Prometheus data source wiring is provisioned via `k8s/grafana-datasource.yaml`.
- Dashboard JSON is versioned in `grafana/dashboard.json`.
- DLT (`wiki-edits-dlt`) is used for poison-pill quarantine after retry exhaustion.

## Quick Start (Docker)

### Prerequisites
- Docker Desktop (or Docker Engine with Compose v2)
- Git

### Start

```bash
git clone https://github.com/DMJain/WikiPulse.git
cd WikiPulse
docker compose up -d --build
```

Grafana is auto-provisioned and instantly available at http://localhost:3001 after startup.

### Validate

```bash
docker compose ps
```

### Access Points
- UI: http://localhost:3000
- Grafana: http://localhost:3001
- REST example: http://localhost:3000/api/edits/recent?limit=5
- STOMP/SockJS probe: http://localhost:3000/ws-wikipulse/info?t=1

### Stop

```bash
docker compose down
```

## Repository Highlights

- `src/main/java`: Spring Boot ingestion, worker, API, and websocket services.
- `frontend`: React analytics and live firehose dashboard.
- `k8s`: Kubernetes manifests for app and infrastructure orchestration.
- `grafana`: Dashboard assets for observability visualization.
- `logs`: ADRs and system execution status records.

## License

This repository is maintained as an engineering portfolio and learning artifact. Review repository policy before external redistribution.
