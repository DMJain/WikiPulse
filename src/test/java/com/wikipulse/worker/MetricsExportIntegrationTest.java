package com.wikipulse.worker;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.redis.testcontainers.RedisContainer;
import com.wikipulse.producer.client.WikipediaSseClient;
import com.wikipulse.producer.domain.WikiEditEvent;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MetricsExportIntegrationTest {

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

  @Container
  static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    registry.add("wikipulse.smoke-test.enabled", () -> "false");
    registry.add("wikipulse.sse.enabled", () -> "false");
    registry.add("management.endpoints.web.exposure.include", () -> "health,info,prometheus");
    registry.add("management.endpoint.prometheus.enabled", () -> "true");
    registry.add("management.prometheus.metrics.export.enabled", () -> "true");
  }

  @Autowired private KafkaTemplate<String, WikiEditEvent> kafkaTemplate;

  @Autowired private ProcessedEditRepository processedEditRepository;

  @Autowired private TestRestTemplate testRestTemplate;

  @Autowired private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

  @MockBean private WikipediaSseClient wikipediaSseClient;

  @Test
  void testKafkaConsumerMetricsExposed() {
    long eventId = System.currentTimeMillis();
    Instant timestamp = Instant.now();
    WikiEditEvent event =
        new WikiEditEvent(
            eventId,
            "Phase16_Test_Page",
            "Phase16User",
            timestamp,
            "edit",
            false,
            "Phase 16 telemetry verification",
            "https://en.wikipedia.org",
            0,
            new WikiEditEvent.Meta(
                "en.wikipedia.org",
                "recentchange",
                "https://en.wikipedia.org/wiki/Phase16_Test_Page",
                timestamp));

          processedEditRepository.count();

          await()
            .atMost(5, SECONDS)
            .untilAsserted(
              () -> {
                ConcurrentMessageListenerContainer<?, ?> container =
                  (ConcurrentMessageListenerContainer<?, ?>)
                    kafkaListenerEndpointRegistry.getListenerContainers().iterator().next();
                assertThat(container.getAssignedPartitions()).isNotEmpty();
              });

    kafkaTemplate.send("wiki-edits", event.title(), event).join();

    await()
        .atMost(5, SECONDS)
        .untilAsserted(() -> assertThat(processedEditRepository.findById(eventId)).isPresent());

    String prometheusMetrics = testRestTemplate.getForObject("/actuator/prometheus", String.class);
    assertThat(prometheusMetrics).contains("kafka_consumer_fetch_manager_records_lag");
  }
}
