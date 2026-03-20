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
