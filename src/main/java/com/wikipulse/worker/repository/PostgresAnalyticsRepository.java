package com.wikipulse.worker.repository;

import com.wikipulse.worker.api.dto.EditBehaviorDto;
import com.wikipulse.worker.api.dto.GeoCountDto;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAnalyticsRepository {

  private static final String PROJECT_ALL = "all";
  private static final String PROJECT_WIKIPEDIA = "wikipedia";
  private static final String PROJECT_WIKIMEDIA_COMMONS = "wikimedia-commons";
  private static final String PROJECT_WIKIDATA = "wikidata";

  private final JdbcTemplate jdbcTemplate;

  public PostgresAnalyticsRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<GeoCountDto> getGeoDistribution(String project, Boolean isBot, Instant since) {
    List<String> whereClauses = new ArrayList<>();
    List<Object> params = new ArrayList<>();

    whereClauses.add("country IS NOT NULL");
    whereClauses.add("country <> ''");
    whereClauses.add("country <> 'Unknown'");

    appendProjectFilter(normalizeProject(project), whereClauses, params);
    appendBotFilter(isBot, whereClauses, params);
    appendSinceFilter(since, whereClauses, params);

    String sql =
        """
        SELECT country, count(*) AS count
        FROM processed_edits
        WHERE %s
        GROUP BY country
        ORDER BY count DESC
        LIMIT 10
        """
            .formatted(String.join("\n  AND ", whereClauses));

    return jdbcTemplate.query(
        sql,
        (resultSet, rowNumber) ->
            new GeoCountDto(resultSet.getString("country"), resultSet.getLong("count")),
        params.toArray());
  }

  public EditBehaviorDto getEditBehavior(String project, Boolean isBot, Instant since) {
    List<String> whereClauses = new ArrayList<>();
    List<Object> params = new ArrayList<>();

    whereClauses.add("1 = 1");

    appendProjectFilter(normalizeProject(project), whereClauses, params);
    appendBotFilter(isBot, whereClauses, params);
    appendSinceFilter(since, whereClauses, params);

    String sql =
        """
        SELECT
          count(*) AS total_edits,
          COALESCE(SUM(CASE WHEN is_revert = true THEN 1 ELSE 0 END), 0) AS revert_edits,
          COALESCE(AVG(ABS(byte_diff::numeric)), 0.0) AS avg_abs_byte_diff
        FROM processed_edits
        WHERE %s
        """
            .formatted(String.join("\n  AND ", whereClauses));

    List<EditBehaviorRow> rows =
        jdbcTemplate.query(
            sql,
            (resultSet, rowNumber) ->
                new EditBehaviorRow(
                    resultSet.getLong("total_edits"),
                    resultSet.getLong("revert_edits"),
                    resultSet.getDouble("avg_abs_byte_diff")),
            params.toArray());

    if (rows.isEmpty()) {
      return new EditBehaviorDto(0L, 0.0, 0.0);
    }

    EditBehaviorRow row = rows.getFirst();
    long totalEdits = Math.max(0L, row.totalEdits());
    long revertEdits = Math.max(0L, row.revertEdits());
    double avgAbsoluteByteDiff = sanitizeNonNegative(row.avgAbsoluteByteDiff());
    double revertRatePct = totalEdits == 0L ? 0.0 : (revertEdits * 100.0) / totalEdits;

    return new EditBehaviorDto(totalEdits, revertRatePct, avgAbsoluteByteDiff);
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

  private static void appendProjectFilter(
      String project, List<String> whereClauses, List<Object> params) {
    switch (project) {
      case PROJECT_ALL -> {
        // no-op
      }
      case PROJECT_WIKIMEDIA_COMMONS -> {
        whereClauses.add("lower(server_url) LIKE ?");
        params.add("%commons.wikimedia.org%");
      }
      case PROJECT_WIKIDATA -> {
        whereClauses.add("lower(server_url) LIKE ?");
        params.add("%wikidata.org%");
      }
      case PROJECT_WIKIPEDIA -> {
        whereClauses.add("server_url IS NOT NULL");
        whereClauses.add("server_url <> ''");
        whereClauses.add("lower(server_url) <> 'unknown'");
        whereClauses.add("lower(server_url) NOT LIKE ?");
        whereClauses.add("lower(server_url) NOT LIKE ?");
        params.add("%commons.wikimedia.org%");
        params.add("%wikidata.org%");
      }
      default -> throw new IllegalArgumentException("Unsupported project filter: " + project);
    }
  }

  private static void appendBotFilter(Boolean isBot, List<String> whereClauses, List<Object> params) {
    if (isBot == null) {
      return;
    }

    whereClauses.add("is_bot = ?");
    params.add(isBot);
  }

  private static void appendSinceFilter(
      Instant since, List<String> whereClauses, List<Object> params) {
    if (since == null) {
      return;
    }

    whereClauses.add("edit_timestamp >= ?");
    params.add(Timestamp.from(since));
  }

  private static double sanitizeNonNegative(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return 0.0;
    }
    return Math.max(0.0, value);
  }

  private record EditBehaviorRow(long totalEdits, long revertEdits, double avgAbsoluteByteDiff) {}
}
