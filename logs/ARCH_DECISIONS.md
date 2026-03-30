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


