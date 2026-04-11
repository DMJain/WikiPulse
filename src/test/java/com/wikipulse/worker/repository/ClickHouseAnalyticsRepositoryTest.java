package com.wikipulse.worker.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@SpringBootTest(classes = {ClickHouseAnalyticsRepository.class, ClickHouseAnalyticsRepositoryTest.MockConfig.class})
class ClickHouseAnalyticsRepositoryTest {

  @TestConfiguration
  static class MockConfig {

    @Bean(name = "clickHouseJdbcTemplate")
    NamedParameterJdbcTemplate clickHouseJdbcTemplate() {
      return mock(NamedParameterJdbcTemplate.class);
    }
  }

  @Autowired private ClickHouseAnalyticsRepository clickHouseAnalyticsRepository;

  @Autowired
  @Qualifier("clickHouseJdbcTemplate")
  private NamedParameterJdbcTemplate clickHouseJdbcTemplate;

  @BeforeEach
  void setUp() {
    reset(clickHouseJdbcTemplate);
    when(clickHouseJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(java.util.List.of());
    when(
            clickHouseJdbcTemplate.queryForObject(
                anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(null);
  }

  @Test
  void shouldAppendDynamicWhereClausesForGeoDistribution() {
    Instant since = Instant.parse("2026-04-11T10:15:30Z");

    clickHouseAnalyticsRepository.getGeoDistribution("wikidata", true, since);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);

    org.mockito.Mockito.verify(clickHouseJdbcTemplate)
        .query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

    String sql = sqlCaptor.getValue();
    MapSqlParameterSource params = paramsCaptor.getValue();

    assertThat(sql).contains("country IS NOT NULL");
    assertThat(sql).contains("country != 'Unknown'");
    assertThat(sql).contains("lower(serverUrl) LIKE '%wikidata.org%'");
    assertThat(sql).contains("bot = :isBot");
    assertThat(sql).contains("timestamp >= :since");
    assertThat(params.getValue("isBot")).isEqualTo(1);
    assertThat(params.getValue("since")).isNotNull();
  }

  @Test
  void shouldSkipOptionalWhereClausesWhenGeoFiltersAreNotProvided() {
    clickHouseAnalyticsRepository.getGeoDistribution("all", null, null);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);

    org.mockito.Mockito.verify(clickHouseJdbcTemplate)
        .query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

    String sql = sqlCaptor.getValue();
    MapSqlParameterSource params = paramsCaptor.getValue();

    assertThat(sql).doesNotContain("bot = :isBot");
    assertThat(sql).doesNotContain("timestamp >= :since");
    assertThat(sql).doesNotContain("LIKE '%wikidata.org%'");
    assertThat(sql).doesNotContain("LIKE '%commons.wikimedia.org%'");
    assertThat(params.getValues()).isEmpty();
  }

  @Test
  void shouldAppendDynamicWhereClausesForBehaviorQuery() {
    Instant since = Instant.parse("2026-04-11T08:00:00Z");

    clickHouseAnalyticsRepository.getEditBehavior("wikipedia", false, since);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<MapSqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);

    org.mockito.Mockito.verify(clickHouseJdbcTemplate)
        .queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

    String sql = sqlCaptor.getValue();
    MapSqlParameterSource params = paramsCaptor.getValue();

    assertThat(sql).contains("count() AS total_edits");
    assertThat(sql).contains("coalesce(sum(toInt64(isRevert)), 0) AS revert_edits");
    assertThat(sql).contains("coalesce(avg(abs(toFloat64(byteDiff))), 0.0) AS avg_abs_byte_diff");
    assertThat(sql).contains("lower(serverUrl) NOT LIKE '%commons.wikimedia.org%'");
    assertThat(sql).contains("lower(serverUrl) NOT LIKE '%wikidata.org%'");
    assertThat(sql).contains("bot = :isBot");
    assertThat(sql).contains("timestamp >= :since");
    assertThat(params.getValue("isBot")).isEqualTo(0);
    assertThat(params.getValue("since")).isNotNull();
  }
}
