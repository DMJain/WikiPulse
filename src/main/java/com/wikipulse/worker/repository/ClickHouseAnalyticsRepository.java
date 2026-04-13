package com.wikipulse.worker.repository;

import com.wikipulse.worker.api.dto.EditBehaviorDto;
import com.wikipulse.worker.api.dto.GeoCountDto;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse read-model repository for CQRS analytics queries.
 *
 * <p><b>Implementation note on parameter binding:</b> {@code clickhouse-jdbc 0.6.x} uses {@code
 * SqlBasedPreparedStatement} internally which does NOT support JDBC positional {@code ?}
 * placeholders emitted by {@code NamedParameterJdbcTemplate}. All filter parameters are therefore
 * inlined directly into the SQL string using type-safe formatting:
 *
 * <ul>
 *   <li>{@code isBot} → integer literal {@code 0} or {@code 1} (never a user string)
 *   <li>{@code since} → UTC timestamp formatted as {@code 'YYYY-MM-DD HH:mm:ss'} (typed {@link
 *       Instant}, never raw user input)
 * </ul>
 */
@Repository
public class ClickHouseAnalyticsRepository {

  private static final String PROJECT_ALL = "all";
  private static final String PROJECT_WIKIPEDIA = "wikipedia";
  private static final String PROJECT_WIKIMEDIA_COMMONS = "wikimedia-commons";
  private static final String PROJECT_WIKIDATA = "wikidata";

  /** ClickHouse DateTime64 accepts 'YYYY-MM-DD HH:mm:ss' literal strings. */
  private static final DateTimeFormatter CH_TIMESTAMP_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

  private final JdbcTemplate clickHouseJdbcTemplate;

  public ClickHouseAnalyticsRepository(
      @Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouseJdbcTemplate) {
    this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
  }

  public List<GeoCountDto> getGeoDistribution(String project, Boolean isBot, Instant since) {
    String sql = buildGeoDistributionSql(project, isBot, since);
    return clickHouseJdbcTemplate.query(
        sql,
        (resultSet, rowNumber) ->
            new GeoCountDto(resultSet.getString("country"), resultSet.getLong("count")));
  }

  public EditBehaviorDto getEditBehavior(String project, Boolean isBot, Instant since) {
    String sql = buildEditBehaviorSql(project, isBot, since);
    EditBehaviorRow row;
    try {
      row =
          clickHouseJdbcTemplate.queryForObject(
              sql,
              (resultSet, rowNumber) ->
                  new EditBehaviorRow(
                      resultSet.getLong("total_edits"),
                      resultSet.getLong("revert_edits"),
                      resultSet.getDouble("avg_abs_byte_diff")));
    } catch (EmptyResultDataAccessException ex) {
      return new EditBehaviorDto(0L, 0.0, 0.0);
    }

    if (row == null) {
      return new EditBehaviorDto(0L, 0.0, 0.0);
    }

    long totalEdits = Math.max(0L, row.totalEdits());
    long revertEdits = Math.max(0L, row.revertEdits());
    double avgAbsoluteByteDiff = sanitizeNonNegative(row.avgAbsoluteByteDiff());
    double revertRatePct = totalEdits == 0L ? 0.0 : (revertEdits * 100.0) / totalEdits;

    return new EditBehaviorDto(totalEdits, revertRatePct, avgAbsoluteByteDiff);
  }

  // ---------------------------------------------------------------------------
  // SQL builders — parameters inlined as typed literals (no user-controlled strings)
  // ---------------------------------------------------------------------------

  private String buildGeoDistributionSql(String project, Boolean isBot, Instant since) {
    List<String> whereClauses = new ArrayList<>();
    whereClauses.add("country IS NOT NULL");
    whereClauses.add("country != ''");
    whereClauses.add("country != 'Unknown'");

    appendProjectFilter(normalizeProject(project), whereClauses);
    appendBotFilter(isBot, whereClauses);
    appendSinceFilter(since, whereClauses);

    return """
        SELECT country, count() AS count
        FROM wiki_edits
        WHERE %s
        GROUP BY country
        ORDER BY count DESC
        LIMIT 10
        """
        .formatted(String.join("\n  AND ", whereClauses));
  }

  private String buildEditBehaviorSql(String project, Boolean isBot, Instant since) {
    List<String> whereClauses = new ArrayList<>();
    whereClauses.add("1 = 1");

    appendProjectFilter(normalizeProject(project), whereClauses);
    appendBotFilter(isBot, whereClauses);
    appendSinceFilter(since, whereClauses);

    return """
        SELECT
          count() AS total_edits,
          coalesce(sum(toInt64(isRevert)), 0) AS revert_edits,
          coalesce(avg(abs(toFloat64(byteDiff))), 0.0) AS avg_abs_byte_diff
        FROM wiki_edits
        WHERE %s
        """
        .formatted(String.join("\n  AND ", whereClauses));
  }

  // ---------------------------------------------------------------------------
  // Filter helpers
  // ---------------------------------------------------------------------------

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

  /**
   * Inlines {@code bot} as an integer literal (0 or 1). Safe: value is derived from a typed
   * Boolean, never from raw user input.
   */
  private static void appendBotFilter(Boolean isBot, List<String> whereClauses) {
    if (isBot == null) {
      return;
    }
    whereClauses.add("bot = " + (isBot ? 1 : 0));
  }

  /**
   * Inlines {@code since} as a UTC timestamp string literal. Safe: value is derived from a typed
   * {@link Instant}, never from raw user input.
   */
  private static void appendSinceFilter(Instant since, List<String> whereClauses) {
    if (since == null) {
      return;
    }
    whereClauses.add("timestamp >= '" + CH_TIMESTAMP_FMT.format(since) + "'");
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

  private record SqlQuery(String sql) {}

  private record EditBehaviorRow(long totalEdits, long revertEdits, double avgAbsoluteByteDiff) {}
}
