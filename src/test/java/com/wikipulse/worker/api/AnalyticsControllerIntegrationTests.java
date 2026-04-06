package com.wikipulse.worker.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wikipulse.worker.domain.ProcessedEdit;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import java.time.Instant;
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
class AnalyticsControllerIntegrationTests {

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

  @Autowired private ProcessedEditRepository processedEditRepository;

  @BeforeEach
  void setUp() {
    processedEditRepository.deleteAllInBatch();
    processedEditRepository.flush();

    processedEditRepository.saveAllAndFlush(
      List.of(
        buildEdit(
          1001L,
          "Alice",
          "Main_Page",
          "Minor cleanup",
          "https://en.wikipedia.org",
          0,
          false,
          42,
          Instant.now().minusSeconds(300)),
        buildEdit(
          1002L,
          "BotUser",
          "Data_Page",
          "Automated sync",
          "https://www.wikidata.org",
          2,
          true,
          90,
          Instant.now().minusSeconds(240)),
        buildEdit(
          1003L,
          "Charlie",
          "Talk:Main_Page",
          "Discussion update",
          "https://commons.wikimedia.org",
          1,
          false,
          60,
          Instant.now().minusSeconds(180))));
  }

  @Test
  void shouldReturnOkForLanguagesEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/analytics/languages"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].serverUrl").exists())
        .andExpect(jsonPath("$[0].count").exists());
  }

  @Test
  void shouldReturnOkForNamespacesEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/analytics/namespaces"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].namespace").exists())
        .andExpect(jsonPath("$[0].count").exists());
  }

  @Test
  void shouldReturnOkForBotsEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/analytics/bots"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].isBot").exists())
        .andExpect(jsonPath("$[0].count").exists());
  }

  @Test
  void shouldReturnOkForKpisEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/analytics/kpis"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.totalEdits").exists())
        .andExpect(jsonPath("$.botPercentage").exists())
        .andExpect(jsonPath("$.averageComplexity").exists());
  }

  private static ProcessedEdit buildEdit(
      Long id,
      String userName,
      String pageTitle,
      String editComment,
      String serverUrl,
      Integer namespace,
      Boolean isBot,
      Integer complexityScore,
      Instant editTimestamp) {
    ProcessedEdit edit = new ProcessedEdit();
    edit.setId(id);
    edit.setUserName(userName);
    edit.setPageTitle(pageTitle);
    edit.setEditComment(editComment);
    edit.setServerUrl(serverUrl);
    edit.setNamespace(namespace);
    edit.setIsBot(isBot);
    edit.setComplexityScore(complexityScore);
    edit.setEditTimestamp(editTimestamp);
    edit.setEventType("edit");
    return edit;
  }
}
