package com.wikipulse.worker.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wikipulse.worker.domain.AnalyticsRollup;
import com.wikipulse.worker.domain.AnalyticsRollupRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TrendEndpointIntegrationTest {

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
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private AnalyticsRollupRepository analyticsRollupRepository;

  private Instant bucketOne;
  private Instant bucketTwo;

  @BeforeEach
  void setUp() {
    analyticsRollupRepository.deleteAllInBatch();
    analyticsRollupRepository.flush();

    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    bucketOne = now.minusSeconds(1200);
    bucketTwo = now.minusSeconds(600);

    analyticsRollupRepository.saveAllAndFlush(
        List.of(
            buildRollup(bucketOne, "English", "Article", 5L, 2L),
            buildRollup(bucketOne, "Wikidata", "User", 3L, 3L),
            buildRollup(bucketTwo, "English", "Article", 7L, 1L),
            buildRollup(bucketTwo, "Wikimedia Commons", "Article", 4L, 1L)));
  }

  @Test
  void shouldReturnTrendBucketsAsChronologicalArray() throws Exception {
    mockMvc
        .perform(get("/api/analytics/trend"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].timeBucket").value(bucketOne.toString()))
        .andExpect(jsonPath("$[0].totalEdits").value(8))
        .andExpect(jsonPath("$[0].botEdits").value(5))
        .andExpect(jsonPath("$[1].timeBucket").value(bucketTwo.toString()))
        .andExpect(jsonPath("$[1].totalEdits").value(11))
        .andExpect(jsonPath("$[1].botEdits").value(2));
  }

  @Test
  void shouldReturnProjectFilteredTrendArray() throws Exception {
    mockMvc
        .perform(get("/api/analytics/trend").param("project", "wikidata"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].timeBucket").value(bucketOne.toString()))
        .andExpect(jsonPath("$[0].totalEdits").value(3))
        .andExpect(jsonPath("$[0].botEdits").value(3));
  }

  private static AnalyticsRollup buildRollup(
      Instant timeBucket, String language, String namespace, Long totalEdits, Long botEdits) {
    AnalyticsRollup rollup = new AnalyticsRollup();
    rollup.setTimeBucket(timeBucket);
    rollup.setLanguage(language);
    rollup.setNamespace(namespace);
    rollup.setTotalEdits(totalEdits);
    rollup.setBotEdits(botEdits);
    return rollup;
  }
}
