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

---

## 🏗️ Architecture Blueprint

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

## Repository Structure

- `src/main/java`: Spring Boot ingestion, worker, API, and websocket services.
- `frontend`: React analytics and live firehose dashboard.
- `k8s`: Kubernetes manifests for app and infrastructure orchestration.
- `grafana`: Dashboard assets for observability visualization.
- `docker-compose.yml`: Fully autonomous infrastructure mapping.
