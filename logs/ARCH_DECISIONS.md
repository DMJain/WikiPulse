# WikiPulse — Architectural Decision Record (ADR)

> This document records every significant architectural decision made during  
> the WikiPulse project, including the context, rationale, and trade-offs.

---

## ADR-001: Java 21 LTS & Spring Boot 3.4.x

**Date:** 2026-03-19  
**Status:** Accepted

### Context
WikiPulse must sustain high-throughput ingestion from Wikimedia's SSE stream,
transform events in-flight, and publish them to Kafka — all with zero data loss.
The technology stack must be supportable in a production environment for at least
3–5 years, making LTS alignment non-negotiable.

### Decision
- **Java 21 (LTS)** as the language runtime.
- **Spring Boot 3.4.3** as the application framework.

### Rationale
| Capability | Java 21 Feature | WikiPulse Benefit |
|---|---|---|
| High-concurrency SSE | **Virtual Threads (JEP 444)** | 1 virtual thread per SSE reconnection — no thread-pool exhaustion |
| Immutable DTOs | **Record Types (JEP 395)** | `WikiEditEvent` as a record = zero boilerplate, structural equality |
| Type-safe branching | **Pattern Matching (JEP 441)** | Clean `switch` over event types (`edit`, `log`, `categorize`) |
| Future-proofing | **Sealed Classes (JEP 409)** | Exhaustive hierarchies for event processors |

Spring Boot 3.4.x ships with Jakarta EE 10, native GraalVM support, and
first-class virtual thread integration via `spring.threads.virtual.enabled`.

### Trade-offs
- Java 21 virtual threads are **not suitable** for CPU-bound workloads (our
  workload is I/O-bound SSE + Kafka sends → ideal fit).
- Spring Boot 3.x drops `javax.*` namespace — all dependencies must be
  Jakarta-compatible.

---

## ADR-002: Virtual Threads for SSE Stream Handling

**Date:** 2026-03-19  
**Status:** Accepted

### Context
The Wikimedia SSE endpoint (`stream.wikimedia.org`) is a long-lived HTTP
connection that emits events at variable rates — from a trickle during off-peak
hours to hundreds of events/second during vandalism sprees or bot runs. The
ingestion layer must handle this without blocking platform threads.

### Decision
Enable virtual threads globally via `spring.threads.virtual.enabled: true`.

### Rationale
- **Platform threads are scarce.** A 4-core machine typically caps at ~200
  platform threads before context-switching overhead dominates. Virtual threads
  are cheap (< 1 KB stack) and managed by the JVM's continuation scheduler.
- **WebFlux + Virtual Threads.** While WebFlux is already non-blocking, virtual
  threads give us a safety net for any blocking call that slips into the
  reactive pipeline (e.g., a synchronous Redis fallback, a JDBC call in a
  callback). Rather than deadlocking the event loop, the virtual thread simply
  parks.
- **Operational simplicity.** Thread dumps remain readable because virtual
  threads carry descriptive names, and the JVM can manage millions of them
  without OS-level tuning.

### Trade-offs
- Virtual threads use `synchronized` monitors (not `ReentrantLock`) which can
  **pin** the carrier thread. We will audit for pinning in Phase 2 via
  `-Djdk.tracePinnedThreads=short`.
- Debugger tooling for virtual threads is still maturing, though IntelliJ 2024+
  handles them well.

---

## ADR-003: Observability Before Business Logic (Actuator + Prometheus)

**Date:** 2026-03-19  
**Status:** Accepted

### Context
In distributed systems, "it works on my machine" is meaningless. Before we
write a single Kafka producer or SSE consumer, we need the infrastructure to
**prove** things work — or prove they don't.

### Decision
Include `spring-boot-starter-actuator` and `micrometer-registry-prometheus`
in the initial `pom.xml`, before any business logic is written.

### Rationale
1. **Health probes from day one.** `/actuator/health` gives us liveness and
   readiness checks that Docker Compose, Kubernetes, and CI pipelines can use
   immediately.
2. **Prometheus metrics from day one.** When we add Kafka producers (Task 3),
   Micrometer will automatically export `kafka.producer.*` metrics. When we add
   the SSE consumer (Task 4), we'll instrument custom counters
   (`wikipulse.events.received`, `wikipulse.events.published`). The metrics
   pipeline is already hot.
3. **Cost of retrofitting is high.** Adding observability after the fact
   requires touching every component. Adding it first means every new component
   is automatically observed.

### Trade-offs
- Slight increase in startup time (~200 ms) for Actuator autoconfiguration.
- Prometheus endpoint must be secured in production (Phase 3, behind ingress).

---

## ADR-004: Testcontainers over Embedded/Mocked Infrastructure

**Date:** 2026-03-19  
**Status:** Accepted

### Context
Integration tests are only as good as their fidelity to production. Embedded
Kafka (`spring-kafka-test`'s `@EmbeddedKafka`) uses an in-process broker with
different threading, networking, and failure semantics than a real Kafka cluster.

### Decision
Use **Testcontainers** for all integration tests: Kafka, PostgreSQL, Redis.
No embedded or mocked infrastructure.

### Rationale
- **Production parity.** Testcontainers spin up real Docker containers with the
  same images we use in `docker-compose.yml` and, eventually, in Kubernetes.
  If a test passes against a Testcontainers Kafka broker, it will pass against
  the production broker.
- **Failure-mode testing.** We can simulate network partitions, slow consumers,
  and broker restarts — none of which are possible with `@EmbeddedKafka`.
- **Deterministic cleanup.** Testcontainers automatically destroys containers
  after the test suite, preventing port conflicts and stale state.

### Trade-offs
- Tests are slower (~5–10 s startup per container). Mitigated by using
  `@Testcontainers` with `@Container` static fields so containers are shared
  across test methods within a class.
- Requires Docker daemon running on the CI agent. All modern CI platforms
  (GitHub Actions, GitLab CI, Jenkins) support this.

---

## ADR-005: Naming Conventions

**Date:** 2026-03-19  
**Status:** Accepted

### Conventions Table

| Element | Convention | Example |
|---|---|---|
| Maven Group ID | `com.wikipulse` | `com.wikipulse` |
| Maven Artifact ID | lowercase-hyphenated | `wikipulse` |
| Java Package | `com.wikipulse.<module>` | `com.wikipulse.producer` |
| Spring App Name | lowercase-hyphenated | `wikipulse-ingestor` |
| Kafka Topics | lowercase-hyphenated | `wiki-edits` |
| Docker Services | lowercase-hyphenated | `kafka-broker`, `redis` |
| Config Classes | `*Config` suffix | `KafkaProducerConfig` |
| DTOs / Events | Java `record` types | `WikiEditEvent` |
| Test Classes | `*Tests` suffix | `WikiPulseApplicationTests` |
| Log files | `UPPER_SNAKE_CASE` | `SYSTEM_STATUS.log` |

### Code Style
Enforced via **Spotless Maven Plugin** using **Google Java Style**. All code
must pass `mvn spotless:check` before merge.

---

## ADR-006: Maven Wrapper Generation Environment Tweak

**Date:** 2026-03-19  
**Status:** Accepted

### Context
Generating the Maven Wrapper via PowerShell on Windows can fail with `LifecyclePhaseNotFoundException` or heredoc parsing errors because of PowerShell's parameter parsing intercepting the `wrapper:wrapper` notation.

### Decision
Generate the Maven wrapper explicitly under `cmd.exe` (`cmd /c "mvn wrapper:wrapper -Dmaven=3.9.6"`) rather than PowerShell to guarantee string boundaries.

### Rationale
This circumvents shell-specific quoting issues completely and ensures the wrapper is correctly generated, keeping our environments portable and deterministic.

### Trade-offs
None, as wrapper generation is a one-time project bootstrapping step.

---

## ADR-007: Mandatory 3-Partition Kafka Strategy

**Date:** 2026-03-19  
**Status:** Accepted

### Context
WikiPulse must process a high volume of events. A single consumer reading from a single partition cannot keep up with peak traffic spikes, creating a bottleneck.

### Decision
The `wiki-edits` Kafka topic must be initialized with **3 partitions** (and a replication factor of 1 for local development).

### Rationale
- **Parallel Processing:** 3 partitions enable up to 3 consumer instances in the same consumer group to process messages in parallel. 
- **Kubernetes Readiness:** This correlates directly with the planned 3-replica K8s worker deployment in Phase 3. Each worker pod will be assigned exactly one partition, maximizing throughput without idle consumers.
- **Ordered Processing:** Kafka guarantees order within a partition. By keying messages appropriately (e.g., by wiki page or user), we ensure causal consistency per entity while scaling horizontally.

### Trade-offs
- Slight increase in ZooKeeper/KRaft metadata overhead, which is negligible for 3 partitions.
- Requires careful partition key selection to avoid data skew.

---

## ADR-008: Hardened Idempotent Kafka Producer

**Date:** 2026-03-19  
**Status:** Accepted

### Context
WikiPulse cannot afford data loss or duplicate events generated by network timeouts between the producer and the Kafka broker. Retries are necessary for resilience but can introduce duplicates if a broker successfully commits a message but the producer's acknowledgment times out.

### Decision
The Kafka producer will be configured for **Idempotence** (`enable.idempotence=true`) along with `acks=all`, `retries=2147483647`, and `compression.type=snappy`.

### Rationale
- **Idempotent Producers:** Kafka issues a unique Producer ID (PID) to the producer upon initialization. Each message sent by the producer carries this PID and an incrementing sequence number. The broker tracks the highest sequence number for each PID. If it receives a message with a sequence number it has already processed, it correctly identifies it as a duplicate retry and discards it, preventing duplicates while allowing infinite retries.
- **acks=all:** Ensures all in-sync replicas acknowledge the message before it's considered successfully sent, preventing data loss if the leader broker fails.
- **Snappy Compression:** Balances CPU overhead with network bandwidth optimization for JSON payloads, essential for high-throughput streams.

### Trade-offs
- Slight producer initialization overhead to establish the PID.
- Increased CPU usage on the producer and broker side due to Snappy compression, though network I/O savings typically outweigh this cost.

---

## ADR-009: Reactive WebFlux SSE Client & Backpressure Strategy

**Date:** 2026-03-21  
**Status:** Accepted

### Context
WikiPulse consumes the Wikipedia `recentchange` Server-Sent Events (SSE) stream. This is a long-lived, high-throughput HTTP connection. Traditional thread-per-request blocking HTTP clients (like `RestTemplate`) are not suitable because they can exhaust thread pools and handle bursts poorly.

### Decision
We will use **Spring WebFlux `WebClient`** to consume the SSE stream reactively. We will also implement a strict backpressure strategy using `.onBackpressureBuffer(...)` and robust reconnection logic using `.retryWhen(Retry.backoff(...))`.

### Rationale
- **WebClient over RestTemplate:** WebClient uses non-blocking I/O (via Netty), allowing a single carrier thread to manage the long-lived HTTP connection without blocking.
- **Backpressure Strategy:** If the internal processing lags behind the stream's emission rate (e.g., 500 events/second bursts), `onBackpressureBuffer(10000)` will buffer up to 10,000 events. If the buffer overflows, reactive streams default to dropping or signaling errors (enforcing backpressure upstream). This prevents OutOfMemoryErrors and ensures JVM stability at the cost of acceptable data shedding during extreme degradation.
- **Exponential Backoff:** The Wikipedia stream drops connections routinely. `Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30))` ensures continuous, polite reconnection attempts without overwhelming the upstream API or crashing our process.

### Trade-offs
- Reactive pipelines can be harder to debug mechanically mechanically, mitigated by our decision to use Virtual Threads (ADR-002).
- Dropping events on buffer full violates strict "zero data loss," but is an intentional circuit-breaker to prioritize system survival over 100% ingestion during unrecoverable stalls.

---

## ADR-010: Kafka Serialization & Partition Key Strategy

**Date:** 2026-03-21  
**Status:** Accepted

### Context
With the SSE client successfully consuming Wikipedia edits, these events must be published to the `wiki-edits` Kafka topic. We must decide on the wire format and the partitioning strategy to ensure reliable, ordered scaling downstream.

### Decision
1. **JSON Serialization:** We will use `org.springframework.kafka.support.serializer.JsonSerializer` for Phase 1.
2. **Partitioning by Title:** The `title` of the Wikipedia page will act as the Kafka message key.

### Rationale
- **JSON over Avro (For Now):** JSON Serialization allows rapid prototyping and immediate human-readable debugging in the Kafka topic. While Confluent Schema Registry and Avro provide superior type-safety and schema evolution, standing up the registry is deferred to Phase 2 to accelerate the initial pipeline link.
- **Title as Partition Key:** Kafka guarantees ordering only within a single partition. By keying messages by page `title`, all edits to a specific page (e.g., "Main_Page") are routed to the same partition. Any downstream consumer processes the edits for a given page sequentially, ensuring causal consistency (e.g., an "undo" event correctly follows the original edit).

### Trade-offs
- Partitioning by title may cause minor data skew if a "hot page" (e.g., a breaking news article) receives a disproportionate number of edits, but this is acceptable for the current scale compared to losing page-level event ordering.

---

## ADR-011: Consumer Group Scaling & Deserialization Safety

**Date:** 2026-03-29  
**Status:** Accepted

### Context
Moving into Phase 2, WikiPulse must transition from ingestion to processing. We need to reliably consume events from the `wiki-edits` topic across a distributed fleet of worker nodes while avoiding poison pill deadlocks and guaranteeing "Exactly-Once" semantics. 

### Decision
1.  **Consumer Group Framework:** All worker nodes will share the `wikipulse-worker-group` ID.
2.  **Error Handling Deserializer:** We will wrap our `StringDeserializer` and `JsonDeserializer` inside Spring Kafka's `ErrorHandlingDeserializer`.
3.  **Manual Offset Management:** Auto-commit will be disabled (`enable-auto-commit=false`). We will implement `AckMode.MANUAL_IMMEDIATE`.

### Rationale
- **Horizontal Scaling via Consumer Group:** A single `group-id` allows Kafka to load-balance the 3 partitions of the `wiki-edits` topic across up to 3 individual consumer instances. This unlocks true parallel processing as per Phase 1 designs.
- **Poison Pill Immunity:** Typical JSON deserializers crash and throw exceptions on malformed data, causing an infinite retry loop on the same offset (a "Poison Pill"). The `ErrorHandlingDeserializer` catches these exceptions, logs the error, and returns null/skips the message, allowing the consumer to advance the offset and survive data corruption.
- **Exactly-Once Semantics:** Auto-commit risks acknowledging a message before the worker finishes processing it (resulting in data loss if the pod crashes). `MANUAL_IMMEDIATE` ensures the offset is strictly acknowledged *only if* the business logic successfully executes.

### Trade-offs
- Manual acks increase code complexity (requiring `Acknowledgment` method parameters) but provide deterministic crash resilience.
- Skipping bad messages via `ErrorHandlingDeserializer` trades zero data loss for system liveliness.

---

## ADR-012: Redis Deduplication Strategy

**Date:** 2026-03-29  
**Status:** Accepted

### Context
WikiPulse consumes events from Kafka across a distributed fleet of worker nodes. Since Kafka guarantees at-least-once delivery by default, and network timeouts can cause the producer to resend events, duplicate events can be processed. We require global idempotency to ensure Wikipedia edits are processed exactly once.

### Decision
1. **Redis for Distributed State Management:** We will use Redis to store whether an edit has already been processed by any worker. 
2. **Atomic `setIfAbsent` Operation:** The idempotency check will utilize Redis's `SETNX` (via `opsForValue().setIfAbsent()`).
3. **24-Hour TTL Strategy:** Every deduplication key stored in Redis will have a 24-hour Time-To-Live (TTL).

### Rationale
- **Redis vs local cache:** Using an external, centralized data store like Redis provides a single source of truth across all consumer nodes.
- **Atomic Operations:** Utilizing `setIfAbsent()` executes the deduplication check and the setting of the processed status in a single, atomic operation within Redis. This prevents Time-Of-Check to Time-Of-Use (TOCTOU) race conditions which would occur if a separate `GET` followed by a `SET` was used, particularly when multiple parallel consumers receive duplicate messages simultaneously.
- **Memory Management:** Without a TTL (Time-To-Live), the unbounded Wikipedia firehose would quickly trigger Redis out-of-memory (OOM) errors. We observe a 24-hour retention period is sufficient to catch delayed retry and duplicate messages from Kafka, balancing memory constraint and idempotency accuracy.

### Trade-offs
- Setting up an external mechanism demands deploying, scaling, and maintaining Redis.
- If an edit retry arrives after 24 hours, it will not bypass our deduplication service and be processed again. This is extremely unlikely in practice given typical Kafka offset-retry lifecycles.

---

## ADR-013: Persistence Strategy & Write-Ahead Offset Commitment

**Date:** 2026-03-29  
**Status:** Accepted

### Context
In Phase 2, deduplicated events must be persisted for downstream analytics. We need to guarantee that no event is lost if the database is temporarily unavailable during processing, and we must optimize our schema for Phase 3's real-time queries.

### Decision
1. **Database engine:** PostgreSQL via Spring Data JPA.
2. **Primary Key mapping:** We will map the Wikipedia-provided `id` directly to the `@Id` column of the `ProcessedEdit` entity instead of generating surrogate keys.
3. **Commit Ordering:** We will enforce a strict "Write-Ahead" offset logic: Step A (Redis Check) -> Step B (Database `save()`) -> Step C (Kafka `acknowledge()`).
4. **Analytical Indexing:** We mandate specialized composite indexes: `(user_name, edit_timestamp)` and `(page_title)`.

### Rationale
- **Primary Key Immunity:** Using Wikipedia's `id` as the natural primary key guarantees uniqueness at the lowest isolation layer. Even if the Redis TTL expires or fails, saving a duplicate id triggers a `ConstraintViolationException`, neutralizing the poison pill unconditionally.
- **Write-Ahead Offset Commitment:** By saving to PostgreSQL *before* calling `acknowledge()`, we inherently treat Kafka as a Write-Ahead Log. If the JVM crashes synchronously after the DB write but before the ACK, Kafka will redeliver. The subsequent retry gracefully halts either at Redis (Step A) or Primary Key constraint check (Step B). If the DB write crashes, the omitted ACK correctly preserves the message for future retry.
- **Analytical Indexes (Phase 3 readiness):** 
  - `idx_user_timestamp` enables O(log n) performance for bounding box queries ("edits by User X over the last 15 minutes"). 
  - `idx_page_title` allows high-speed aggregations for trending pages. 
  Without these explicitly defined in DDL, Phase 3 analytics over millions of rows would degrade to full-table scans.

### Trade-offs
- Setting column types explicitly (e.g. `TEXT`) loosely binds the entity definition to PostgreSQL.
- Heavy indexing incurs a minor write-penalty, which is an acceptable cost (as write throughput is buffered by Kafka) to safeguard Phase 3 fast read latency.

---

## ADR-014: Scoring & Bot Detection

**Date:** 2026-03-30
**Status:** Accepted

### Context
Phase 2 mandates real-time analytical enrichment of streaming events before persistence. Specifically, we need to calculate an edit's complexity and detect rapid-fire bot behavior from users executing >5 edits per 60 seconds. 

### Decision
1. **Complexity Formula:** A stateless heuristic `comment.length() + (title.length() * 2)`.
2. **Bot Detection:** Stateful velocity tracking utilizing Redis atomic `INCR` commands bound by a 60-second TTL.
3. **Execution Placement:** Analytics will execute *after* deduplication but *before* database persistence.

### Rationale
- **Redis for Velocity vs PostgreSQL:** Validating user velocity chronologically against PostgreSQL would require expensive `COUNT()` aggregations over live indexed columns, risking a collapse under firehose RPS. Redis provides atomic time-complexity `O(1)` counters (`INCR`), perfectly suited for a distributed, transient 60-second window.
- **Consumer Injection:** Running analytics sequentially inside the Kafka Consumer thread keeps the analytical side-effects strictly atomic with the offset commit.

---

## ADR-015: Observability Strategy (Micrometer/Prometheus)

**Date:** 2026-03-30
**Status:** Accepted

### Context
Phase 2 mandates integrating observability to track internal metrics. We must monitor consumer lag and in-flight processing latency. Without these metrics, we cannot easily detect memory leaks or pipeline bottlenecks.

### Decision
1. **Metrics Collection:** Spring Boot Actuator with Micrometer bindings for Prometheus.
2. **Key Application Metrics:**
   - `wikipulse_processing_latency` (Timer)
   - `wikipulse_edits_processed_total` (Counter)
   - `wikipulse_bots_detected_total` (Counter)
3. **Consumer Lag:** Export Kafka standard metrics, capturing consumer lag to monitor throughput against stream velocity.

### Rationale
- **Consumer Lag as a Scaling Signal:** If our pipeline processing latency exceeds the influx rate, consumer lag will grow uncontrollably. Monitoring this provides an early warning signal indicating a need to horizontally scale the worker fleet.

---

## ADR-016: Reliable Asynchronous Kafka Publishing (Phase 3 Prep)
**Date**: 2026-03-31
**Context**: The original Kafka producer implementation utilized a "fire-and-forget" pattern, ignoring the `SendResult` of `kafkaTemplate.send()`. In high-throughput environments, this masks network failures or broker rejections, risking silent data loss which violates the "Zero Data Loss" mandate.
**Decision**: 
1. We refactored `WikipediaSseClient` to capture the `CompletableFuture<SendResult<String, Object>>` returned by the KafkaTemplate.
2. We attached a non-blocking `.whenComplete()` callback to log success metadata (partition, offset) directly or emit a `CRITICAL ERROR` log if the publish fails. This avoids blocking the reactive WebFlux pipeline with `.get()`.
3. We configured `max.in.flight.requests.per.connection: 5` in `application.yml`, which pairs perfectly with `enable.idempotence: true` to ensure strictly ordered, duplicate-free publishing locally before acknowledgment.
**Consequences**: The system is now fully aware of ingestion bottlenecks or broker disconnections. Failures will trigger alerts, and in-flight request limits will maintain strict ordering of events on the wire.

---

## ADR-017: Consumer Resilience & Dead Letter Topic (Phase 3 Prep)
**Date**: 2026-04-01
**Context**: The error handling in the `WikiEditConsumer` previously swallowed exceptions to prevent blocking the offset, but doing so meant failing messages would be infinitely retried without yielding the thread, creating poison-pill deadlocks.
**Decision**: 
1. We transitioned away from an infinite retry loop to a `DefaultErrorHandler` configured with a `DeadLetterPublishingRecoverer`.
2. Messages failing sequentially for 3 attempts (with a 2s fixed backoff) will be routed to a quarantine topic: `wiki-edits-dlt`.
3. Added `wikipulse_errors_total` metric inside the consumer catch block, ensuring active visibility into processing failure rates.
4. Refactored the `WorkerMetrics.stopTimer()` into a `finally` block to eradicate latency record survivorship-bias (where only successful saves were timed).
**Consequences**: This guarantees worker partitions never freeze due to app-level poison pills. Failed events are safely quarantined in the DLT for future reprocessing, enforcing the "Zero Data Loss" architecture while keeping the pipeline flowing.

---

## ADR-018: Infrastructure Health & Determinism
**Date**: 2026-04-01
**Context**: Our `docker-compose.yml` relied on standard `depends_on` clauses which merely verify container start, not application readiness. This led to race conditions where Kafka attempted to register with Zookeeper before leader election completed, or the init-containers/app attempted to connect to an unready Kafka broker.
**Decision**: We transitioned to healthcheck-driven startup sequencing using `condition: service_healthy`. Zookeeper now interrogates itself via the `ruok` netcat command.
**Consequences**: Complete elimination of infrastructure race conditions. The data pipeline boots strictly sequentially, guaranteeing all cluster metadata nodes and message brokers are unconditionally healthy before ingress or worker operations commence. This fulfills Audit Item #5 and cements our Phase 3 Kubernetes readiness.

---

## ADR-019: Multi-Stage Containerization Strategy
**Date**: 2026-04-01
**Context**: Fat JARs running under default Docker base images bloat to over 500MB, expanding the CVE attack surface and slowing orchestration pull times. Additionally, running containers as `root` violates modern Kubernetes pod security standards.
**Decision**: We transitioned to a two-stage Alpine-based Dockerfile. The build phase caches Maven layers aggressively (`dependency:go-offline`). The runtime phase uses a stripped `jre-alpine` image and strictly enforces a non-root `wikipulse` user. We leverage array-form entrypoints to guarantee signal propagation (`SIGTERM`) and set `JAVA_OPTS` to optimize for container heuristics and JEP 444 virtual thread parallelism.
**Consequences**: The application container footprint shrinks drastically (target < 200MB). Container escape vulnerabilities are inherently mitigated by the minimal Alpine surface and dropped root capabilities. The JVM gracefully scales heuristics to available container limits, fortifying our Phase 3 Kubernetes transition.

---

## ADR-020: Kubernetes Orchestration & Target Scaling
**Date**: 2026-04-02
**Context**: Local Docker Compose artificially restricts horizontal scaling parameters due to its monolithic engine design. To ingest Wikipedia streams robustly during surge loads without partition idling or bottlenecking, the active application pods must trace the layout of the deployed broker strategy autonomously.
**Decision**: We transitioned into a declarative Kubernetes `Deployment` topology mandating precisely 3 replicas (`replicas: 3`). This yields a 1-to-1 processing ratio aligned flawlessly across our 3-partition `wiki-edits` cluster. Resource bounds and strict memory tracking were instantiated to maintain precise scheduler hygiene. Liveness and Readiness native actuator probes establish rolling update immunity.
**Consequences**: Throughput scalability is totally maximized without spawning idle consumers. Zero-downtime rolling distributions are strictly buffered because the readiness probes independently ensure downstream stability before authorizing network load-balancing. Liveness probes concurrently resolve container freeze occurrences programmatically by restarting unresponsive Virtual Threads natively.

---

## ADR-021: Live Observability & Telemetry Topography
**Date**: 2026-04-02
**Context**: We need real-time visualization over system behavior without deploying heavyweight commercial APM agents. The Phase 2 instrumentation generated native Prometheus endpoints, but we lack an Executive UI and alerting structure to measure Pulse operations cleanly.
**Decision**: We established a self-hosted `prometheus` and `grafana` observability stack deployed identically onto the internal Kubernetes perimeter. We programmed strict Alerting Thresholds configured at Consumer Lag > 5000 units and p99 Processing Latency > 100ms. Throughput visualizations operate on native `1m` moving-average rates `sum(rate(wikipulse_edits_processed_total[1m]))`. 
**Consequences**: We possess complete, real-time control metrics distinguishing explicitly across partition instances via Kubernetes Service mapping. Operations can react seamlessly before consumer starvation impacts business logic. End-to-end transparency is accomplished matching tier-1 modern SRE patterns.

---

## ADR-022: Dynamic Scaling Strategy
**Date**: 2026-04-02
**Context**: To adapt to the real-time intensity of the Wikipedia stream, the WikiPulse worker fleet must transition from static replicas to an elastic cloud-native engine. We must scale autonomously based on load without risking data loss, over-provisioning, or violating non-root security.
**Decision**: 
1. **Horizontal Pod Autoscaling (HPA)** is enabled targeting the `wikipulse-worker` deployment.
2. **Replica Range Logic (1-6)**: Minimum 1 guarantees all 3 partitions are covered by consumer group rebalancing during idle periods. Maximum 6 avoids partition starvation, scaling to up to 2 pods per partition (3 active, 3 hot-standby).
3. **Metric Trigger**: Target 70% CPU Utilization. Since Virtual Threads excel at I/O-bound concurrency, CPU is a highly reliable proxy indicating saturation from high-intensity JSON parsing and analytics routines. 70% allows safe overhead limits avoiding sudden JVM crashing.
4. **Cooldown Period**: A 5-minute (300s) default scale-down stabilization window is strictly retained to prevent "thrashing" triggered by erratic Wikipedia stream bursts.
**Consequences**: The architecture enforces an elastic resiliency model. Under sudden load, pods expand up to our maximum 6 threshold preventing stream backpressure. During inactivity, the system gracefully reduces to 1 pod effectively covering all partitions sequentially without incurring redundant K8s resource costs.

---

## ADR-023: Worker Bootstrap and Kubernetes Configuration Bridging

**Date**: 2026-04-04  
**Status**: Accepted

### Context
Phase 4 Task 1 requires a strict initialization contract for the worker runtime:
all stateful dependencies must resolve through Kubernetes DNS and service ports.
The required targets are PostgreSQL (`postgres:5432`, database `wikipulsedb`,
user `wikipulse`), Redis (`redis:6379`, AOF disabled in infrastructure), and
Kafka (`kafka:29092`, PLAINTEXT internal listener).

The deployment already injects environment variables from
`k8s/configmap.yaml` and `k8s/secret.yaml` via `envFrom`, but runtime property
resolution must be explicitly wired in Spring configuration to prevent
localhost drift.

### Decision
1. Keep the Spring Boot worker on the existing baseline: Spring Boot 3.x on
   Java 17+ (current project baseline remains Spring Boot 3.4.3, Java 21).
2. Standardize worker bootstrap dependencies as the required core stack:
   - Spring Web (reactive stack currently provided by `spring-boot-starter-webflux`)
   - Spring Data JPA
   - Spring Data Redis
   - Spring Kafka
   - Spring Boot Actuator
   - PostgreSQL JDBC Driver
3. Enforce environment-first property mapping in `application.yml`:
   - `SPRING_DATASOURCE_URL` -> fallback `jdbc:postgresql://postgres:5432/wikipulsedb`
   - `SPRING_DATASOURCE_USERNAME` -> fallback `wikipulse`
   - `SPRING_DATASOURCE_PASSWORD` -> fallback to `POSTGRES_PASSWORD`
   - `SPRING_DATA_REDIS_HOST` -> fallback `redis`
   - `SPRING_DATA_REDIS_PORT` -> fallback `6379`
   - `KAFKA_BOOTSTRAP_SERVERS` -> fallback `kafka:29092`
4. Continue using Kubernetes `envFrom` (`ConfigMap` + `Secret`) as the source
   of truth for runtime configuration injection.

### Rationale
- Aligns application bootstrapping with the concrete cluster topology rather
  than machine-local defaults.
- Uses Spring relaxed binding conventions so injected environment variables map
  directly to the Spring context with no custom binding layer.
- Preserves existing worker behavior while making connectivity deterministic in
  Kubernetes.

### Trade-offs
- K8s-first defaults are less convenient for local non-container runs unless
  values are overridden.
- Password fallback compatibility (`SPRING_DATASOURCE_PASSWORD` then
  `POSTGRES_PASSWORD`) adds flexibility but introduces dual-key operational
  support.

---

## ADR-024: In-Cluster Worker Smoke Test Protocol

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 4 Task 2 requires hard proof that the worker can resolve and connect to
all stateful in-cluster dependencies using Kubernetes-injected configuration:
PostgreSQL (`postgres:5432`), Redis (`redis:6379`), and Kafka (`kafka:29092`).
This proof must execute automatically at pod startup and fail immediately on
DNS, networking, credentials, or broker metadata errors.

### Decision
1. Implement a temporary startup smoke check using Spring `ApplicationRunner`
   so validation runs automatically during application bootstrap.
2. Execute deterministic checks in sequence:
   - PostgreSQL: run `SELECT 1` and read connection metadata from the active
     datasource.
   - Redis: perform `SETNX` (`setIfAbsent`) on a namespaced key with short TTL,
     then read the value back and assert round-trip integrity.
   - Kafka: use `AdminClient` metadata APIs to list topics and assert presence
     of required topic `wiki-edits`.
3. Enforce fail-fast startup behavior: any failed assertion throws an
   application-startup exception (`IllegalStateException`), preventing startup
   completion and readiness transition.
4. Emit explicit structured log markers for each dependency check so operators
   can confirm pass/fail from pod logs without attaching debuggers.

### Rationale
- `ApplicationRunner` was selected over a manual REST trigger because startup
  execution is automatic per pod lifecycle, removes operator timing variance,
  and validates connectivity before the pod can ever receive traffic.
- Fail-fast semantics guarantee DNS and service-discovery issues surface as
  immediate startup failures rather than latent runtime defects.
- Sequential assertions establish a deterministic validation contract and make
  troubleshooting straightforward from first failure point.

### Trade-offs
- Startup time increases slightly due to one-time dependency probes.
- Redis smoke check writes a temporary key; TTL-bound namespacing limits any
  operational footprint.
- Strict fail-fast can cause CrashLoopBackOff during infra incidents, which is
  intentional because it prevents false-positive readiness.

---

## ADR-025: Domain Modeling and SSE Ingestion Pipeline Architecture

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 4 Task 3 introduces the production ingestion contract from Wikimedia
`recentchange` into Kafka topic `wiki-edits`. We must formalize immutable
domain modeling with Java records, enforce partition ordering by page title,
and protect JVM memory during burst traffic using bounded backpressure.

### Decision
1. Model ingestion payloads with Java 21 records under the producer domain
   package, including a canonical `WikiEditEvent` and nested value objects
   (`Meta`, optional `User`) for normalized schema representation.
2. Keep the SSE ingestion path reactive with Spring WebFlux `WebClient` and a
   bounded `onBackpressureBuffer(...)` strategy.
3. Publish to Kafka asynchronously with `kafkaTemplate.send(...).whenComplete(...)`
   and key records by page `title` to preserve per-page ordering.
4. Enforce producer reliability defaults: `StringSerializer` key,
   `JsonSerializer` value, and idempotence enabled.

### Wikipedia `recentchange` to Java DTO Mapping

| Wikimedia JSON Field | Source Type | Java Record Field | Target Type | Mapping Rule |
|---|---|---|---|---|
| `id` | number/string | `id` | `Long` | Parse numeric directly; parse string fallback; null if invalid |
| `title` | string | `title` | `String` | Required partition key for Kafka |
| `user` | string | `user` or `user.name` | `String` | Preserve actor username exactly as emitted |
| `timestamp` | epoch seconds | `timestamp` | `Instant` | Convert using `Instant.ofEpochSecond(...)` |
| `type` | string | `type` | `String` | Persist raw event type; ingest pipeline filters `edit` |
| `bot` | boolean | `bot` | `Boolean` | Default to `false` if absent |
| `comment` | string | `comment` | `String` | Preserve text; default empty string when null |
| `meta.domain` | string | `meta.domain` | `String` | Optional metadata passthrough |
| `meta.uri` | string | `meta.uri` | `String` | Optional metadata passthrough |
| `meta.stream` | string | `meta.stream` | `String` | Optional metadata passthrough |
| `meta.dt` | ISO-8601 string | `meta.dt` | `Instant` | Parse ISO-8601 timestamp when present |

### Backpressure and Async Publish Interaction
- `WebClient` emits a reactive `Flux` from the live SSE stream. This ingress is
  intentionally decoupled from broker acknowledgement latency.
- `onBackpressureBuffer(N)` creates a bounded buffer to absorb short spikes
  while downstream Kafka I/O catches up.
- Kafka publishing remains non-blocking by attaching `.whenComplete(...)`
  callbacks instead of using `.get()` or `.join()`. Reactor threads therefore
  never block on broker round trips.
- If throughput exceeds sustained capacity and the buffer reaches its cap, the
  stream fails fast, then reconnects using exponential backoff. This protects
  the process from unbounded queue growth and OutOfMemoryError conditions.
- Combined with idempotent producer semantics, retries preserve delivery
  correctness while maintaining pipeline liveness under unstable networks.

### Rationale
- Aligns with ADR-001 record-first immutable DTO design.
- Operationalizes ADR-009 reactive backpressure and reconnection guarantees.
- Reinforces ADR-010 partition key policy (`title`) for per-page ordering.
- Complies with ADR-016 asynchronous Kafka publishing and explicit success/fail
  callback telemetry.

### Trade-offs
- Richer record modeling increases mapping code complexity versus raw map usage.
- Bounded buffers can still shed load under extreme sustained spikes, but this
  is preferred over process instability.
- Async callbacks require careful logging discipline to avoid noisy logs at
  peak event rates.

---

## ADR-026: Consumer Pipeline and Write-Ahead Offset Logic

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 4 Task 4 requires a stateful worker pipeline that consumes `wiki-edits`,
deduplicates globally, enriches events with analytics, persists results, and
only then acknowledges Kafka offsets. The pipeline must tolerate poison pills,
duplicate deliveries, and JVM crashes without silent data loss.

### Decision
1. Standardize consumer execution order to:
  **Read (Kafka) -> Deduplicate (Redis SETNX 24h) -> Analyze (complexity +
  bot velocity) -> Save (PostgreSQL) -> ACK (Kafka MANUAL_IMMEDIATE)**.
2. Use `ErrorHandlingDeserializer` to isolate deserialization failures from
  partition progress and route irrecoverable failures through error handling.
3. Keep offset commits manual and immediate so acknowledgments happen only
  after successful processing and persistence.
4. Preserve Wikipedia event `id` as the PostgreSQL primary key to provide a
  hard idempotency boundary even if Redis state is unavailable or expired.

### Execution Flow Contract
1. **Read**: Worker receives `WikiEditEvent` from `wiki-edits` under
  `wikipulse-worker-group`.
2. **Deduplicate**: `SETNX edit:processed:<id> true EX 24h`.
  - If key already exists: event is duplicate and can be acknowledged safely.
  - If key is new: continue processing.
3. **Analyze**:
  - Complexity score computed from event content.
  - Bot velocity tracked with `INCR bot:velocity:<user>` and 60-second TTL.
4. **Save**: Persist `ProcessedEdit` to PostgreSQL using event `id` as `@Id`.
5. **ACK**: Call `Acknowledgment.acknowledge()` only after successful save.

### Crash and Recovery Semantics
- **Crash before Redis SETNX**: Kafka redelivers; no state mutation occurred.
- **Crash after SETNX but before Save**: Kafka redelivers; Redis marks seen,
  so duplicate short-circuits safely (no double-write).
- **Crash after Save but before ACK**: Kafka redelivers; either Redis key or
  DB primary key prevents duplicate persistence. ACK then advances offset.
- **Crash after ACK**: Processing already completed and offset committed; no
  replay required.

This ordering treats Kafka as a write-ahead log and guarantees at-least-once
delivery with idempotent side effects.

### Rationale
- Aligns with ADR-011 (`ErrorHandlingDeserializer`, manual acknowledgment).
- Implements ADR-012 distributed deduplication via Redis SETNX + 24h TTL.
- Enforces ADR-013 save-before-ack write-ahead safety model.
- Preserves ADR-014 stateful analytics (velocity window via Redis INCR).

### Trade-offs
- Manual acknowledgment increases code path complexity versus auto-commit.
- Redis TTL-based dedup is probabilistic beyond 24 hours by design.
- Save-before-ack can increase replay frequency during hard crashes, but replay
  is safe due to dedup + primary-key constraints.

---

## ADR-027: API and Real-Time Communication Strategy

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 5 Task 1 introduces a dashboard consumption layer with two distinct read
paths:
1. A historical bootstrap path for initial page load (latest processed edits).
2. A low-latency push path for newly processed edits.

The worker already guarantees save-before-ack semantics (ADR-026). We must add
real-time broadcasting without coupling messaging concerns directly into Kafka
listener flow or weakening reliability boundaries.

### Decision
1. Expose REST endpoint `GET /api/edits/recent?limit=N` for historical reads
   from PostgreSQL using `ProcessedEditRepository` ordered by
   `editTimestamp DESC`.
2. Use Spring WebSocket messaging with **STOMP over SockJS**:
   - WebSocket endpoint: `/ws-wikipulse`
   - Broker destination prefix: `/topic`
   - Live dashboard channel: `/topic/edits`
   - App destination prefix (reserved): `/app`
3. Decouple live push from Kafka consumer by publishing from the persistence
   boundary (`ProcessedEditService`) after a successful DB save. The service
   emits a lightweight payload to a dedicated broadcaster service rather than
   embedding broker code in `WikiEditConsumer`.

### Dispatch Model
- **Trigger point**: Immediately after `repository.save(entity)` succeeds.
- **Payload contract**: Lightweight DTO carrying only dashboard-facing fields
  (id, userName, pageTitle, eventType, editTimestamp, isBot, complexityScore).
- **Broadcast transport**: `SimpMessagingTemplate.convertAndSend("/topic/edits", dto)`.
- **Failure posture**: WebSocket publish errors are logged and metered but do
  not roll back Kafka acknowledgment logic unless future requirements demand
  transactional coupling.

### Rationale
- **Client compatibility**: SockJS offers graceful fallback for environments
  where native WebSockets are blocked, while STOMP provides standardized
  topic/subscription semantics for React dashboards.
- **Separation of concerns**: Kafka consumer remains focused on ingestion,
  deduplication, analytics, and persistence. API push mechanics are isolated in
  dedicated services.
- **Operational safety**: Save-first then push ensures dashboards only receive
  events that have already crossed the system's durability boundary.

### Trade-offs
- STOMP frames add protocol overhead versus raw WebSocket payloads.
- SockJS fallback transports can increase latency under constrained networks.
- Post-save broadcast may temporarily diverge from UI expectations during
  broker outages (data remains available via REST backfill endpoint).

---

## ADR-028: Frontend Real-Time State Strategy

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 5 Task 2 adds a browser dashboard that must:
1. Hydrate quickly with recent history from the backend REST API.
2. Continue updating in real time as new edits arrive via WebSocket.

Wikipedia edit throughput can spike significantly. An unbounded client-side
array would eventually degrade rendering, increase garbage-collection pressure,
and risk tab instability.

### Decision
1. Use **Vite + React + TypeScript** for frontend build/runtime ergonomics and
   strict typing of edit payloads.
2. Use **STOMP over SockJS** (`@stomp/stompjs` + `sockjs-client`) against
   backend endpoint `/ws-wikipulse`, subscribing to `/topic/edits`.
3. Maintain feed state as a bounded **rolling window** with a hard cap of
   100 items.
   - Initial load fetches `GET /api/edits/recent?limit=100`.
   - Each live event is prepended to the array.
   - State is truncated immediately to the newest 100 items.

### State Contract
- Ordering: newest-first for immediate dashboard readability.
- Capacity: constant upper bound (100) to prevent DOM/memory bloat.
- Lifecycle: explicit WebSocket connect on mount and disconnect on unmount.
- Presentation: `isBot` drives badge/background differentiation for fast visual
  scan of automated versus human edits.

### Rationale
- Bounded state guarantees O(1) memory growth relative to stream duration.
- Newest-first display aligns with operational monitoring workflows.
- TypeScript contracts reduce runtime mismatches between REST and STOMP payloads.
- Vite shortens feedback loop and keeps frontend independent from Spring build.

### Trade-offs
- Older entries beyond 100 are dropped from in-memory UI state by design.
- Occasional duplicates can appear during reconnect races unless optional ID
  deduplication is layered in the reducer path.
- SockJS fallback improves compatibility but can add latency versus native
  WebSocket transport.

---

## ADR-029: Elastic Scaling Ceiling by Kafka Partition Cardinality

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 6 Task 1 introduces Horizontal Pod Autoscaling for
`deployment/wikipulse-worker`. The worker consumes from Kafka topic
`wiki-edits`, which is fixed at 3 partitions (ADR-007). Kafka consumer-group
semantics allow only one active consumer per partition within the same group at
any instant.

The previous scaling ceiling (`maxReplicas: 6`, ADR-022) intentionally allowed
hot-standby pods. For this phase, the requirement is to optimize for effective
throughput per resource unit and avoid paying for replicas that cannot actively
consume partitions.

### Decision
1. Set worker HPA bounds to `minReplicas: 1` and `maxReplicas: 3`.
2. Keep the worker in a single consumer group (`wikipulse-worker-group`) so
   Kafka can rebalance ownership of the 3 partitions across available pods.
3. Treat `replicas > partitions` as non-beneficial for steady-state throughput
   because excess pods will remain idle with zero assigned partitions.

### Rationale
- With 3 partitions, the maximum parallel consumer throughput in one consumer
  group is capped at 3 active consumers. A 4th+ pod does not increase
  concurrent partition processing.
- Extra replicas above partition count still incur scheduler, memory, and
  health-probe overhead while often sitting idle, which worsens cost-efficiency.
- Keeping `maxReplicas` equal to partition count creates a clean one-to-one
  ceiling: one pod can own one partition under full scale-out.
- `minReplicas: 1` is sufficient during low traffic because one consumer can
  process all three partitions sequentially while preserving consumer-group
  correctness.

### Consequences
- Peak scale-out is intentionally capped at 3 pods unless partition count is
  increased in a future ADR.
- If sustained load exceeds what 3 partitions can absorb, the correct next step
  is repartitioning/topic redesign rather than adding idle replicas.
- This decision supersedes ADR-022 only for replica ceiling policy
  (`maxReplicas: 6` -> `maxReplicas: 3`).

### Trade-offs
- We lose hot-standby pods that could reduce takeover latency after a pod
  failure.
- Recovery speed now depends more directly on Kubernetes scheduling and Kafka
  rebalance time when replacing failed consumers.

---

## ADR-031: Deep Data Enrichment Strategy for Stream-Native Analytics

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 7 introduces dashboard analytics that need stronger segmentation than the
current event core (`user`, `title`, `timestamp`, `bot`). Specifically, Phase 8
queries must group and filter edits by wiki origin and content class without
calling external services during ingestion.

The Wikimedia `recentchange` payload contains many optional or high-entropy
fields (including long text fragments and nested metadata) that can inflate
memory pressure if ingested wholesale. Our producer pipeline is intentionally
bounded by reactive backpressure and asynchronous Kafka publishing; adding large
payload extraction would widen object graphs, increase serialization cost, and
raise GC churn during burst traffic.

### Decision
1. Expand the canonical edit schema with two lightweight enrichment fields:
   - `server_url` (String), for example `https://en.wikipedia.org`
   - `namespace` (Integer), for example `0` (article), `2` (user)
2. Parse both values directly from the incoming Wikimedia event map inside the
   SSE mapper, using safe fallback parsing to avoid stream interruption on
   malformed data.
3. Persist both fields in `ProcessedEdit`, and add a database index on
   `server_url` to optimize Phase 8 aggregation/read paths.
4. Explicitly defer heavy enrichment candidates (raw text bodies, remote
   category lookups, external API joins) to later offline pipelines.

### Rationale
- `server_url` provides a stable language/region grouping key at low payload
  cost, enabling fast analytics like per-wiki traffic and bot concentration.
- `namespace` introduces high-value content classification (article vs user vs
  template/system spaces) while staying computationally trivial to parse.
- Both fields are scalar and compact, preserving ingestion throughput and
  backpressure boundaries under firehose spikes.
- Indexing `server_url` now prevents expensive table scans when Phase 8 UI
  adds grouped timelines and leaderboard-style breakdowns.
- This strategy maximizes analytical leverage per byte stored, which is the
  desired trade-off for real-time stream systems.

### Trade-offs
- Additional columns slightly increase row width and write amplification.
- `server_url` may contain host variants that require normalization in future
  reporting layers.
- Deferring richer enrichment means some advanced semantic analytics remain
  out-of-scope for this phase by design.

---

## ADR-032: Database-Side Aggregation and Query Strategy for Analytics API

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 7 Task 2 introduces analytics endpoints for chart-ready aggregate metrics:
top languages (`server_url`), namespace distribution (`namespace`), and bot
ratio (`is_bot`). The `processed_edits` table is expected to grow continuously
under stream ingestion, so aggregation strategy must scale with row count.

A client-side or middle-tier approach (fetching many rows and aggregating with
Java Streams) would increase memory footprint, network transfer, and request
latency, especially when dashboards refresh frequently.

### Decision
1. Perform all core aggregations in PostgreSQL via JPQL `@Query` methods using
   `GROUP BY` and `COUNT`.
2. Return compact projection DTOs/interfaces from repository methods rather
   than loading full `ProcessedEdit` entities.
3. Apply descending count ordering in the query for ranking use-cases and
   enforce top-N for language charts using pageable limits.
4. Expose dedicated REST analytics endpoints under `/api/analytics/*` that
   forward repository aggregate results without in-memory re-aggregation.

### Rationale
- Database engines are optimized for aggregation pushdown; executing grouping
  where data resides avoids unnecessary row materialization in the JVM.
- Projection-based reads reduce payload size and serialization overhead for
  dashboard endpoints.
- JPQL keeps the implementation database-portable while still allowing index-
  aware execution plans in PostgreSQL.
- Top-N limiting at query time keeps latency predictable and prevents over-
  rendering in frontend chart libraries.

### Trade-offs
- Aggregation query complexity increases repository surface area and test scope.
- JPQL alias/projection contracts require stricter naming discipline.
- Near-real-time dashboards reflect committed database state, not transient
  in-flight Kafka messages.

---

## ADR-033: Frontend Analytics Dashboard Layout and Charting Strategy

**Date**: 2026-04-05
**Status**: Accepted

### Context
Phase 8 introduces a richer dashboard UX that must present two different
interaction models without conflating transport concerns:
1. **Live Firehose** for high-frequency event streaming (existing STOMP/SockJS).
2. **Analytics Overview** for aggregate snapshots (top languages, namespace
   distribution, bot-vs-human ratio).

The existing frontend is feed-centric and mounts live-stream behavior directly
in the primary page. At the same time, Phase 7 introduced lightweight,
chart-ready REST aggregations under `/api/analytics/*`. We need a UI structure
that exposes both capabilities while keeping operational load predictable.

### Decision
1. Introduce a top-level **tabbed dashboard layout** with two tabs:
   - `Analytics Overview`
   - `Live Firehose`
2. Adopt **Recharts** as the frontend charting library for declarative,
   responsive visualizations in React.
3. Implement Analytics Overview charts from Phase 7 endpoints:
   - `GET /api/analytics/languages?limit=5` -> bar chart (labels derived from
     `serverUrl`)
   - `GET /api/analytics/namespaces` -> donut/pie chart
   - `GET /api/analytics/bots` -> donut/pie chart
4. Fetch analytics data on tab/component mount and allow optional 10-second
   polling for near-real-time refresh.
5. Keep the heavy STOMP/SockJS live stream connection exclusive to the
   `Live Firehose` tab; Analytics Overview must not require a WebSocket session
   to render.

### Rationale
- **Separation of responsibilities**: live event transport and aggregate
  analytics have different latency, payload, and lifecycle requirements.
- **Operational efficiency**: aggregation endpoints return compact grouped
  datasets and are safe to poll at modest intervals without incurring stream
  fan-out overhead.
- **User experience clarity**: explicit tabs reduce cognitive noise by
  separating strategic insight (charts) from tactical monitoring (firehose).
- **Frontend maintainability**: Recharts provides composable primitives
  (`ResponsiveContainer`, `BarChart`, `PieChart`, `Tooltip`, `Legend`) that fit
  TypeScript React patterns and keep rendering logic declarative.

### Trade-offs
- Polling introduces bounded staleness (up to polling interval) versus true
  push-based updates.
- Additional HTTP reads are generated when polling is enabled, though payloads
  remain small and aggregation is database-side.
- Mapping `serverUrl` to a language label can require normalization heuristics
  for non-standard host patterns.

---

## ADR-034: Unified Local Docker Orchestration and Nginx Reverse Proxy

**Date**: 2026-04-06
**Status**: Accepted

### Context
Phase 9 requires a one-command local deployment path so reviewers can launch the
full WikiPulse stack with minimal setup overhead. The backend depends on
PostgreSQL, Redis, Kafka, and ZooKeeper. The frontend is a Vite React app that
consumes Spring Boot REST and SockJS/STOMP WebSocket endpoints.

Running frontend and backend on different browser origins introduces avoidable
CORS complexity in containerized local deployments. A single-origin entrypoint
is required for deterministic demos and portfolio evaluation.

### Decision
1. Standardize local startup on one root `docker-compose.yml` that includes
   PostgreSQL, Redis, ZooKeeper, Kafka, the Spring Boot worker backend, and
   the frontend UI.
2. Containerize the frontend with a multi-stage Docker build:
   - Stage 1: Node-based Vite build pipeline.
   - Stage 2: Nginx Alpine static file serving.
3. Use Nginx as the frontend runtime reverse proxy:
   - `/api` forwards to the backend REST API.
   - `/ws-wikipulse` forwards to the backend WebSocket/SockJS endpoint with
     HTTP upgrade headers.
4. Enforce strict dependency sequencing with health-gated startup:
   - Kafka starts only after ZooKeeper is healthy.
   - Worker starts only after Kafka, PostgreSQL, and Redis are healthy.
5. Publish the frontend at `localhost:3000` as the single local user entrypoint.

### Rationale
- One-click orchestration lowers reviewer friction and ensures repeatable
  startup behavior.
- Fronting the React app with Nginx and proxying backend routes is the standard
  deployment pattern for eliminating browser-side CORS constraints via
  same-origin routing.
- Health-gated sequencing prevents race conditions observed in stateful service
  initialization chains, especially ZooKeeper and Kafka.
- A single ingress surface keeps local topology intuitive while preserving
  backend service isolation inside the Docker network.

### Trade-offs
- Adds Nginx as an additional runtime component to maintain.
- Reverse-proxy behavior must stay aligned with backend route contracts.
- Local compose settings prioritize demo reliability over production ingress
  parity (TLS, WAF policies, and external load balancing remain out of scope).

---

## ADR-035: Read-Path Data Normalization for Analytics Presentation

**Date**: 2026-04-06
**Status**: Accepted

### Context
Phase 10 Task 1 requires analytics responses to be presentation-ready for the
React dashboard while preserving the raw fidelity of ingested Wikimedia data.
Current aggregation queries return raw `serverUrl` and numeric `namespace`
values from PostgreSQL, which are accurate but not consistently readable in UI
charts.

The ingestion and persistence pipeline was intentionally designed to store raw
stream attributes (`server_url`, `namespace`) for correctness and future
analytics flexibility (ADR-031). Changing the write path would mix persistence
concerns with presentation concerns and risk losing source-level detail.

### Decision
1. Keep the write path unchanged: producer mapping, Kafka transport, consumer
  processing, and PostgreSQL storage remain raw and lossless.
2. Introduce a dedicated API-layer normalization utility
  (`WikiMetadataNormalizer`) on the read path to transform aggregate labels.
3. Normalize language source labels in `/api/analytics/languages` with the
  following mapping:
  - `https://en.wikipedia.org` -> `English Wikipedia`
  - `https://www.wikidata.org` -> `Wikidata`
  - `https://commons.wikimedia.org` -> `Wikimedia Commons`
  - Fallback: strip `https://` and return hostname.
4. Normalize namespace labels in `/api/analytics/namespaces` with grouping to
  avoid chart over-fragmentation:
  - `0` -> `Article`
  - `1` -> `Article Talk`
  - `2` -> `User`
  - `3` -> `User Talk`
  - `4` -> `Project`
  - Any other value -> `Other`
5. After normalization, aggregate duplicate labels in the controller response
  path (for example, all non-primary namespaces merged into `Other`).

### Rationale
- Preserves raw data fidelity in storage while still delivering clean labels to
  consumers.
- Concentrates presentation mapping in one backend utility instead of
  duplicating rules in multiple frontend views.
- Reduces namespace category noise so chart legends remain legible and stable.
- Keeps architecture boundaries explicit: write path for durable truth, read
  path for representation.

### Trade-offs
- Adds a lightweight transformation layer to analytics endpoints.
- Mapping tables must be maintained when new canonical wiki hosts are required.
- Grouping minor namespaces into `Other` intentionally reduces label
  granularity in this endpoint.

---

## ADR-036: Database-Parameterized Dynamic Analytics and KPI Snapshot Queries

**Date**: 2026-04-06
**Status**: Accepted

### Context
Phase 10 Tasks 2 and 3 require analytics endpoints to support optional
time-window and bot filters while introducing dashboard KPI headline metrics.
Current aggregation methods return unfiltered snapshots, which cannot answer
queries such as "last 24h" or "bot-only" without additional processing.

Filtering after loading large result sets into Java memory would increase query
payload size, JVM memory pressure, and endpoint latency as data volume grows.
This would duplicate aggregation logic across controller code paths and weaken
the database-centric aggregation strategy established in ADR-032.

### Decision
1. Keep aggregation and filtering in PostgreSQL using parameterized JPQL
   methods in `ProcessedEditRepository`.
2. Extend analytics repository methods to accept optional filter parameters:
   - `Instant since`
   - `Boolean isBot`
3. Apply null-tolerant JPQL predicates for reusable dynamic filtering:
   - `(:since IS NULL OR p.editTimestamp >= :since)`
   - `(:isBot IS NULL OR p.isBot = :isBot)`
4. Introduce a new KPI snapshot read path exposed through
   `GET /api/analytics/kpis` returning:
   - `totalEdits`
   - `botPercentage`
   - `averageComplexity`
5. Compute KPI values from aggregate repository results, with explicit
   zero-safe handling when total edits are zero.

### Rationale
- Preserves database pushdown for filtering and grouping, minimizing row
  transfer and JVM-side post-processing.
- Scales better for high-volume event tables where timeframe filters are
  expected to be a primary query dimension.
- Keeps controller responsibilities focused on request parsing and response
  composition rather than in-memory dataset filtering.
- Enables consistent filter semantics across language, namespace, bot, and KPI
  analytics endpoints.

### Trade-offs
- Repository query signatures become more complex due to optional parameters.
- KPI calculations require explicit guardrails for divide-by-zero and null
  aggregate handling.
- Additional endpoint and DTO surface area must stay synchronized with frontend
  contracts.

---

## ADR-037: Frontend Global State and Chart Polish

**Date**: 2026-04-06
**Status**: Accepted

### Context
Phase 11 requires a synchronized analytics experience where KPI cards and all
charts respond to the same user-selected filters. If each visualization owns
its own local filter state, data refreshes can drift and show mixed time or
bot scopes in the same view.

Namespace distribution can also contain many categories. Rendering a persistent
PieChart legend for dense categorical datasets consumes horizontal space,
causes wrapping in constrained layouts, and has previously destabilized card
heights on smaller viewports.

### Decision
1. Lift analytics filter state to the top-level Analytics Overview tab using
   shared `timeframe` and `isBot` state variables.
2. Treat filter changes as a global rehydration trigger so KPI and chart
   datasets are fetched with identical query parameters in the same cycle.
3. Keep the filter controls colocated with the analytics header area so users
   can clearly see and adjust active query scope.
4. Remove the Namespace PieChart `<Legend>` and rely on a customized
   `<Tooltip>` for value and label discovery on hover.

### Rationale
- A single source of truth for filters guarantees contract consistency across
  KPI, language, namespace, and bot endpoints.
- Coordinated fetch cycles avoid contradictory snapshots such as KPI values from
  one timeframe while charts display another.
- A tooltip-first strategy improves layout resilience for dense categories and
  preserves chart area for data rather than chrome.
- Keeping legend-free namespace visuals reduces UI breakage risk on responsive
  widths while still preserving full value discoverability.

### Trade-offs
- Removing a static legend slightly reduces at-a-glance label visibility until
  users hover slices.
- Top-level filter state increases parent component responsibilities and should
  remain narrowly scoped to analytics concerns.
- Coordinated refetch on every filter change can increase request burstiness,
  though payload sizes remain small and endpoint filters are database-side.
