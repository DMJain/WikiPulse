package com.wikipulse.worker.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.clickhouse.jdbc.ClickHouseDriver;
import com.wikipulse.worker.api.dto.EditBehaviorDto;
import com.wikipulse.worker.api.dto.GeoCountDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * True Testcontainers integration test for {@link ClickHouseAnalyticsRepository}.
 *
 * <p>Spins up a real ClickHouse server container, creates the {@code wiki_edits} MergeTree table
 * (Kafka engine and materialized view are intentionally excluded — Testcontainers ClickHouse has no
 * Kafka), inserts deterministic fixture rows, and validates the repository methods end-to-end.
 *
 * <p>This is the sole authoritative test for the ClickHouse repository layer.
 */
@SpringBootTest(
    classes = {
      ClickHouseIntegrationTest.ContainerConfig.class,
      ClickHouseAnalyticsRepository.class
    })
@Testcontainers
class ClickHouseIntegrationTest {

  private static final Network TEST_NETWORK = Network.newNetwork();
  private static final Path CLICKHOUSE_INIT_SQL = Path.of("clickhouse", "initdb.d", "01_init_tables.sql");

  @Container
  static final KafkaContainer KAFKA =
    new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
      .withNetwork(TEST_NETWORK)
      .withNetworkAliases("kafka");

  // Pin an LTS image to keep builds reproducible; matches the major version used in docker-compose.
  @Container
  static final ClickHouseContainer CLICKHOUSE =
    new ClickHouseContainer("clickhouse/clickhouse-server:23.8-alpine")
      .withNetwork(TEST_NETWORK);

  // ---------------------------------------------------------------------------
  // Minimal Spring context — just the JdbcTemplate wired to the live container.
  // No Postgres / Redis / Kafka beans needed.
  // ---------------------------------------------------------------------------

  @TestConfiguration
  static class ContainerConfig {

    @Bean("clickHouseJdbcTemplate")
    JdbcTemplate clickHouseJdbcTemplate() {
      SimpleDriverDataSource ds = new SimpleDriverDataSource();
      ds.setDriverClass(ClickHouseDriver.class);
      // getJdbcUrl() returns the host-mapped URL, e.g. jdbc:clickhouse://localhost:XXXXX/default
      ds.setUrl(CLICKHOUSE.getJdbcUrl());
      ds.setUsername(CLICKHOUSE.getUsername());
      ds.setPassword(CLICKHOUSE.getPassword());
      return new JdbcTemplate(ds);
    }
  }

  @Autowired private ClickHouseAnalyticsRepository repository;

  @Autowired
  @Qualifier("clickHouseJdbcTemplate")
  private JdbcTemplate jdbcTemplate;

  // ---------------------------------------------------------------------------
  // Schema + fixture setup
  // ---------------------------------------------------------------------------

  /**
   * Recreates a clean {@code wiki_edits} MergeTree table and inserts 3 deterministic rows before
   * each test. The schema is executed from the real {@code 01_init_tables.sql} script.
   */
  @BeforeEach
  void setUpSchemaAndFixtures() throws IOException {
    jdbcTemplate.execute("DROP TABLE IF EXISTS wiki_edits_mv");
    jdbcTemplate.execute("DROP TABLE IF EXISTS wiki_edits_queue");
    jdbcTemplate.execute("DROP TABLE IF EXISTS wiki_edits");

    executeInitSql();

    // Row 1: human edit, US, en.wikipedia.org, 50 bytes, no revert
    // Row 2: bot edit,   DE, wikidata.org,     200 bytes, IS a revert
    // Row 3: human edit, US, en.wikipedia.org, 30 bytes, no revert
    jdbcTemplate.execute(
        """
        INSERT INTO wiki_edits VALUES
          (1, 'Test Page 1', 'Alice',   '2026-04-11 10:00:00.000', 'edit', 0, 'Minor fix',
           'https://en.wikipedia.org', 0, 'US', 'New York', 50, 0, 0,
           ('en.wikipedia.org', 'recentchange', 'https://en.wikipedia.org/wiki/P1', '2026-04-11T10:00:00Z')),
          (2, 'Test Page 2', 'BotUser', '2026-04-11 10:05:00.000', 'edit', 1, 'Automated',
           'https://www.wikidata.org', 0, 'DE', 'Berlin',   200, 1, 0,
           ('www.wikidata.org', 'recentchange',  'https://www.wikidata.org/wiki/Q1',  '2026-04-11T10:05:00Z')),
          (3, 'Test Page 3', 'Charlie', '2026-04-11 10:10:00.000', 'edit', 0, 'Fix typo',
           'https://en.wikipedia.org', 0, 'US', 'Boston',   30,  0, 0,
           ('en.wikipedia.org', 'recentchange', 'https://en.wikipedia.org/wiki/P3', '2026-04-11T10:10:00Z'))
        """);
  }

  private void executeInitSql() throws IOException {
    // In Testcontainers, the broker is reachable from sibling containers via kafka:9092.
    String initSql = Files.readString(CLICKHOUSE_INIT_SQL).replace("kafka:29092", "kafka:9092");

    for (String statement : initSql.split(";")) {
      String sql = statement.trim();
      if (!sql.isEmpty()) {
        jdbcTemplate.execute(sql);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // getGeoDistribution tests
  // ---------------------------------------------------------------------------

  @Test
  void getGeoDistribution_noFilters_returnsTopCountriesByCount() {
    List<GeoCountDto> result = repository.getGeoDistribution("all", null, null);

    assertThat(result).isNotEmpty();
    // US has 2 edits → ranked first; DE has 1 → ranked second
    assertThat(result.get(0).country()).isEqualTo("US");
    assertThat(result.get(0).count()).isEqualTo(2L);
    assertThat(result).hasSize(2);
  }

  @Test
  void getGeoDistribution_filterBot_returnsOnlyBotCountry() {
    List<GeoCountDto> result = repository.getGeoDistribution("all", true, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).country()).isEqualTo("DE");
    assertThat(result.get(0).count()).isEqualTo(1L);
  }

  @Test
  void getGeoDistribution_filterHuman_returnsOnlyHumanCountry() {
    List<GeoCountDto> result = repository.getGeoDistribution("all", false, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).country()).isEqualTo("US");
    assertThat(result.get(0).count()).isEqualTo(2L);
  }

  @Test
  void getGeoDistribution_filterByProject_wikidataIsolatesToDE() {
    List<GeoCountDto> result = repository.getGeoDistribution("wikidata", null, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).country()).isEqualTo("DE");
  }

  @Test
  void getGeoDistribution_filterByProject_wikipedia_excludesWikidata() {
    List<GeoCountDto> result = repository.getGeoDistribution("wikipedia", null, null);

    // Only en.wikipedia.org rows should remain → US only, count = 2
    assertThat(result).hasSize(1);
    assertThat(result.get(0).country()).isEqualTo("US");
    assertThat(result.get(0).count()).isEqualTo(2L);
  }

  @Test
  void getGeoDistribution_sinceFutureTimestamp_returnsEmpty() {
    // All fixtures are in the past relative to a far-future since
    List<GeoCountDto> result =
        repository.getGeoDistribution("all", null, java.time.Instant.parse("2030-01-01T00:00:00Z"));

    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // getEditBehavior tests
  // ---------------------------------------------------------------------------

  @Test
  void getEditBehavior_noFilters_returnsCombinedStats() {
    EditBehaviorDto result = repository.getEditBehavior("all", null, null);

    // 3 total edits, 1 revert → revert rate = 33.33 %
    assertThat(result.totalEdits()).isEqualTo(3L);
    assertThat(result.revertRatePct()).isGreaterThan(33.0).isLessThan(34.0);
    // avg |byteDiff| = (50 + 200 + 30) / 3 = 93.33
    assertThat(result.avgAbsoluteByteDiff()).isGreaterThan(93.0).isLessThan(94.0);
  }

  @Test
  void getEditBehavior_filterBot_returnsOnlyBotStats() {
    EditBehaviorDto result = repository.getEditBehavior("all", true, null);

    assertThat(result.totalEdits()).isEqualTo(1L);
    // The single bot edit IS a revert → 100 % revert rate
    assertThat(result.revertRatePct()).isEqualTo(100.0);
  }

  @Test
  void getEditBehavior_filterHuman_returnsZeroRevertRate() {
    EditBehaviorDto result = repository.getEditBehavior("all", false, null);

    assertThat(result.totalEdits()).isEqualTo(2L);
    assertThat(result.revertRatePct()).isEqualTo(0.0); // no human reverts in fixtures
  }

  @Test
  void getEditBehavior_sinceFutureTimestamp_returnsZeroDto() {
    EditBehaviorDto result =
        repository.getEditBehavior("all", null, java.time.Instant.parse("2030-01-01T00:00:00Z"));

    assertThat(result.totalEdits()).isEqualTo(0L);
    assertThat(result.revertRatePct()).isEqualTo(0.0);
    assertThat(result.avgAbsoluteByteDiff()).isEqualTo(0.0);
  }
}
