package com.wikipulse.worker.repository;

import com.wikipulse.worker.api.dto.EditBehaviorDto;
import com.wikipulse.worker.api.dto.GeoCountDto;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ClickHouseAnalyticsRepository {

  private static final String PROJECT_ALL = "all";
  private static final String PROJECT_WIKIPEDIA = "wikipedia";
  private static final String PROJECT_WIKIMEDIA_COMMONS = "wikimedia-commons";
  private static final String PROJECT_WIKIDATA = "wikidata";

  private final NamedParameterJdbcTemplate clickHouseJdbcTemplate;

  public ClickHouseAnalyticsRepository(
      @Qualifier("clickHouseJdbcTemplate") NamedParameterJdbcTemplate clickHouseJdbcTemplate) {
    this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
  }

  public List<GeoCountDto> getGeoDistribution(String project, Boolean isBot, Instant since) {
    SqlQuery sqlQuery = buildGeoDistributionQuery(project, isBot, since);
    return clickHouseJdbcTemplate.query(
        sqlQuery.sql(),
        sqlQuery.params(),
        (resultSet, rowNumber) ->
            new GeoCountDto(resultSet.getString("country"), resultSet.getLong("count")));
  }

  public EditBehaviorDto getEditBehavior(String project, Boolean isBot, Instant since) {
    SqlQuery sqlQuery = buildEditBehaviorQuery(project, isBot, since);
    EditBehaviorRow row =
        clickHouseJdbcTemplate.queryForObject(
            sqlQuery.sql(),
            sqlQuery.params(),
            (resultSet, rowNumber) ->
                new EditBehaviorRow(
                    resultSet.getLong("total_edits"),
                    resultSet.getLong("revert_edits"),
                    resultSet.getDouble("avg_abs_byte_diff")));

    if (row == null) {
      return new EditBehaviorDto(0L, 0.0, 0.0);
    }

    long totalEdits = Math.max(0L, row.totalEdits());
    long revertEdits = Math.max(0L, row.revertEdits());
    double avgAbsoluteByteDiff = sanitizeNonNegative(row.avgAbsoluteByteDiff());
    double revertRatePct = totalEdits == 0L ? 0.0 : (revertEdits * 100.0) / totalEdits;

    return new EditBehaviorDto(totalEdits, revertRatePct, avgAbsoluteByteDiff);
  }

  private SqlQuery buildGeoDistributionQuery(String project, Boolean isBot, Instant since) {
    List<String> whereClauses = new ArrayList<>();
    MapSqlParameterSource params = new MapSqlParameterSource();

    whereClauses.add("country IS NOT NULL");
    whereClauses.add("country != ''");
    whereClauses.add("country != 'Unknown'");

    appendProjectFilter(normalizeProject(project), whereClauses);
    appendBotFilter(isBot, whereClauses, params);
    appendSinceFilter(since, whereClauses, params);

    String sql =
        """
        SELECT country, count() AS count
        FROM wiki_edits
        WHERE %s
        GROUP BY country
        ORDER BY count DESC
        LIMIT 10
        """
            .formatted(String.join("\n  AND ", whereClauses));

    return new SqlQuery(sql, params);
  }

  private SqlQuery buildEditBehaviorQuery(String project, Boolean isBot, Instant since) {
    List<String> whereClauses = new ArrayList<>();
    MapSqlParameterSource params = new MapSqlParameterSource();

    whereClauses.add("1 = 1");

    appendProjectFilter(normalizeProject(project), whereClauses);
    appendBotFilter(isBot, whereClauses, params);
    appendSinceFilter(since, whereClauses, params);

    String sql =
        """
        SELECT
          count() AS total_edits,
          coalesce(sum(toInt64(isRevert)), 0) AS revert_edits,
          coalesce(avg(abs(toFloat64(byteDiff))), 0.0) AS avg_abs_byte_diff
        FROM wiki_edits
        WHERE %s
        """
            .formatted(String.join("\n  AND ", whereClauses));

    return new SqlQuery(sql, params);
  }

  private static void appendProjectFilter(String project, List<String> whereClauses) {
    switch (project) {
      case PROJECT_ALL -> {
        // No project filter needed.
      }
      case PROJECT_WIKIMEDIA_COMMONS ->
          whereClauses.add("lower(serverUrl) LIKE '%commons.wikimedia.org%'");
      case PROJECT_WIKIDATA -> whereClauses.add("lower(serverUrl) LIKE '%wikidata.org%'");
      case PROJECT_WIKIPEDIA -> {
        whereClauses.add("lower(serverUrl) NOT LIKE '%commons.wikimedia.org%'");
        whereClauses.add("lower(serverUrl) NOT LIKE '%wikidata.org%'");
        whereClauses.add("lower(serverUrl) != 'unknown'");
        whereClauses.add("serverUrl != ''");
      }
      default -> throw new IllegalArgumentException("Unsupported project filter: " + project);
    }
  }

  private static void appendBotFilter(
      Boolean isBot, List<String> whereClauses, MapSqlParameterSource params) {
    if (isBot == null) {
      return;
    }

    whereClauses.add("bot = :isBot");
    params.addValue("isBot", isBot ? 1 : 0);
  }

  private static void appendSinceFilter(
      Instant since, List<String> whereClauses, MapSqlParameterSource params) {
    if (since == null) {
      return;
    }

    whereClauses.add("timestamp >= :since");
    params.addValue("since", Timestamp.from(since));
  }

  private static String normalizeProject(String project) {
    if (project == null || project.isBlank()) {
      return PROJECT_ALL;
    }

    String normalizedProject = project.trim().toLowerCase(Locale.ROOT);
    return switch (normalizedProject) {
      case "all" -> PROJECT_ALL;
      case "wikipedia" -> PROJECT_WIKIPEDIA;
      case "wikimedia commons", "wikimedia-commons", "wikimedia_commons", "commons" ->
          PROJECT_WIKIMEDIA_COMMONS;
      case "wikidata" -> PROJECT_WIKIDATA;
      default -> throw new IllegalArgumentException("Unsupported project filter: " + project);
    };
  }

  private static double sanitizeNonNegative(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return 0.0;
    }
    return Math.max(0.0, value);
  }

  private record SqlQuery(String sql, MapSqlParameterSource params) {}

  private record EditBehaviorRow(long totalEdits, long revertEdits, double avgAbsoluteByteDiff) {}
}
