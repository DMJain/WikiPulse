# WikiPulse V3: Real-Time Event Streaming, Analytics, and SRE Observability

![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=0A0A0A)
![Kafka](https://img.shields.io/badge/Kafka-7.5.0-231F20?logo=apachekafka&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?logo=redis&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?logo=postgresql&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-Enabled-E6522C?logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-Dashboarded-F46800?logo=grafana&logoColor=white)

WikiPulse V3 is a production-style streaming analytics platform that ingests live Wikipedia edits, processes them through a resilient Kafka pipeline, deduplicates and persists enriched events, and exposes both real-time and aggregate intelligence through a React dashboard. An elite deployment model encompasses an automated Zero-Touch SRE Observability Stack designed to visualize real-time dynamic scaling and cluster elasticity.

## ✨ Core Platform Features

- **Live Wikimedia Stream Ingestion**: Spring WebFlux consumes the public Wikimedia SSE firehose and publishes normalized events into Kafka for elastic downstream processing.
- **Resilient Event Processing Pipeline**: Virtual-thread worker consumers process Kafka partitions concurrently with Redis-backed deduplication to suppress replay noise.
- **Enrichment and Analytics Persistence**: Every processed edit is enriched (including bot-aware signals and complexity metadata) before durable storage in PostgreSQL.
- **Materialized Rollups & Background Aggregation**: A `@Scheduled` background engine pre-aggregates Wikipedia edits into 10-minute buckets to enable lightning-fast React charting without hammering the raw event table.
- **Hybrid KPI Query Strategy**: Dashboard count metrics are optimized through rollup reads, while fidelity-sensitive complexity analytics are derived from raw processed-edit records.
- **Production Observability by Default**: Prometheus and Grafana provide live visibility into ingestion rate, worker saturation, consumer lag, and scaling behavior.

## 🚀 Zero-Touch Quick Start

Spin up the entire distributed system (Frontend, Backend Worker, Kafka cluster, PostgreSQL, Redis, Prometheus, and Grafana) locally with a single command.

### Prerequisites
- Docker Desktop (or Docker Engine with Compose v2)
- Git

### Start the Cluster
```bash
docker compose up -d --build
```
*Wait ~30 seconds for Kafka and the JVM initialization.*

### Access Points
- **React Analytics Dashboard**: [http://localhost:3000](http://localhost:3000)
- **Grafana Command Center**: [http://localhost:3001](http://localhost:3001) *(Credentials: `admin` / `admin`)*

---

## 📊 SRE & Elasticity: How to Read the Dashboard

WikiPulse relies on a true auto-scaling, event-driven pattern. The embedded **WikiPulse Platform** Grafana Dashboard is designed to visually establish the relationship between traffic ingestion, resource saturation, and cluster elasticity. 

When reviewing the system, examine the Grafana dashboard panels collectively:
1. **Ingestion Rate (Edits/sec)**: Observe traffic surges when Wikipedia publishes a high volume of concurrent edits.
2. **CPU Usage per Container**: As ingestion spikes, the CPU pressure on active worker nodes will cross the `70%` horizontal threshold.
3. **Active Worker Containers**: When sustained CPU load crosses 70%, the Kubernetes Horizontal Pod Autoscaler (HPA) physically spawns more virtual container instances. You will see the Active Workers step mathematically from `1` up to `6`.
4. **Kafka Consumer Lag**: During a traffic burst, Consumer Lag may build. As the `Active Worker Containers` scale outward and start reading Kafka partitions, this Consumer Lag curve mathematically collapses back to zero.

This establishes visual proof of our **Elastic Scaling Strategy** maintaining stream-processing integrity!

## 🎛️ Interactive Dashboard With Dynamic Filtering

The React analytics workspace keeps KPI cards and charts synchronized through composable runtime filters:

- **Timeframe Filter**: Slice analytics windows across all time, last 1 hour, last 24 hours, or last 7 days.
- **Bot Status Filter**: Isolate bot-only activity, human-only activity, or blended traffic.
- **Project Filter**: Scope analytics to **Wikipedia**, **Wikidata**, or **Wikimedia Commons** for project-specific trend analysis.

WikiPulse applies a **Hybrid KPI** strategy so the UI remains fast and analytically faithful: edit counts are fetched from materialized rollups, while average complexity is calculated from raw processed-edit tables.

---

## 🏗️ Architecture Blueprint

```mermaid
flowchart LR
    A[Wikipedia SSE Firehose] --> B[WebFlux Backpressure Buffer]
    B --> C[Spring Boot Worker (Virtual Threads)]
    C --> D[Kafka Topic: wiki-edits]
    D --> E[Worker Consumer]

    E --> F[Redis 24h SETNX Dedup]
    F --> G[PostgreSQL Analytics Store (Raw Events)]
    G --> M[Rollup Cron Job (@Scheduled every 10m)]
    M --> N[PostgreSQL Analytics Store (Materialized Rollups)]
    N --> J[API Layer (REST and STOMP)]

    E --> H[Prometheus Metrics Scraping]
    H --> I[Grafana Dashboards]

    E --> J[API Layer (REST and STOMP)]
    J --> K[Nginx Reverse Proxy]
    K --> L[React UI]
```

## Repository Structure

- `src/main/java`: Spring Boot ingestion, worker, API, and websocket services.
- `frontend`: React analytics and live firehose dashboard.
- `k8s`: Kubernetes manifests for app and infrastructure orchestration.
- `grafana`: Dashboard assets for observability visualization.
- `docker-compose.yml`: Fully autonomous infrastructure mapping.
