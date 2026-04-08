package com.wikipulse.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.wikipulse.worker.domain.AnalyticsRollup;
import com.wikipulse.worker.domain.AnalyticsRollupRepository;
import com.wikipulse.worker.domain.ProcessedEdit;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import com.wikipulse.worker.service.AnalyticsAggregationService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AnalyticsAggregationIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    registry.add("spring.kafka.listener.auto-startup", () -> "false");
    registry.add("wikipulse.smoke-test.enabled", () -> "false");
    registry.add("wikipulse.sse.enabled", () -> "false");
  }

  @Autowired private ProcessedEditRepository processedEditRepository;

  @Autowired private AnalyticsRollupRepository analyticsRollupRepository;

  @Autowired private AnalyticsAggregationService analyticsAggregationService;

  @BeforeEach
  void setUp() {
    analyticsRollupRepository.deleteAllInBatch();
    analyticsRollupRepository.flush();
    processedEditRepository.deleteAllInBatch();
    processedEditRepository.flush();
  }

  @Test
  void shouldAggregateEditsIntoRollupsWithNormalizedLabels() {
    Instant windowEnd = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant windowStart = windowEnd.minus(Duration.ofMinutes(10));

    processedEditRepository.saveAllAndFlush(
        List.of(
            buildEdit(2001L, "fr.wikipedia.org", 0, false, windowEnd.minusSeconds(30)),
            buildEdit(2002L, "fr.wikipedia.org", 0, true, windowEnd.minusSeconds(90)),
            buildEdit(2003L, "ru.wikinews.org", 1, true, windowEnd.minusSeconds(120)),
            buildEdit(2004L, "unknown.org", 1, false, windowEnd.minusSeconds(180)),
            buildEdit(2005L, "unknown.org", 1, false, windowEnd.minusSeconds(240))));

    analyticsAggregationService.aggregateWindow(windowStart, windowEnd);

    List<AnalyticsRollup> rollups = analyticsRollupRepository.findByTimeBucket(windowStart);
    assertThat(rollups).hasSize(3);

    Map<String, AnalyticsRollup> byLanguageAndNamespace =
        rollups.stream()
            .collect(
                Collectors.toMap(
                    rollup -> rollup.getLanguage() + "|" + rollup.getNamespace(),
                    rollup -> rollup));

    assertThat(byLanguageAndNamespace).containsKeys(
        "French|Article", "Russian|Article Talk", "Unknown|Article Talk");

    AnalyticsRollup frenchArticle = byLanguageAndNamespace.get("French|Article");
    assertThat(frenchArticle.getTotalEdits()).isEqualTo(2L);
    assertThat(frenchArticle.getBotEdits()).isEqualTo(1L);

    AnalyticsRollup russianTalk = byLanguageAndNamespace.get("Russian|Article Talk");
    assertThat(russianTalk.getTotalEdits()).isEqualTo(1L);
    assertThat(russianTalk.getBotEdits()).isEqualTo(1L);

    AnalyticsRollup unknownTalk = byLanguageAndNamespace.get("Unknown|Article Talk");
    assertThat(unknownTalk.getTotalEdits()).isEqualTo(2L);
    assertThat(unknownTalk.getBotEdits()).isEqualTo(0L);
  }

  private static ProcessedEdit buildEdit(
      Long id, String serverUrl, Integer namespace, Boolean isBot, Instant editTimestamp) {
    ProcessedEdit edit = new ProcessedEdit();
    edit.setId(id);
    edit.setUserName("IntegrationUser" + id);
    edit.setPageTitle("Page_" + id);
    edit.setEventType("edit");
    edit.setEditComment("aggregation-test");
    edit.setServerUrl(serverUrl);
    edit.setNamespace(namespace);
    edit.setCountry("Unknown");
    edit.setCity("Unknown");
    edit.setByteDiff(0);
    edit.setIsRevert(false);
    edit.setIsAnonymous(false);
    edit.setIsBot(isBot);
    edit.setComplexityScore(10);
    edit.setEditTimestamp(editTimestamp);
    return edit;
  }
}