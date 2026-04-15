package com.wikipulse.worker.api;

import com.wikipulse.worker.api.dto.EditBehaviorDto;
import com.wikipulse.worker.api.dto.GeoCountDto;
import com.wikipulse.worker.repository.PostgresAnalyticsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
@RequestMapping("/api/v2/analytics")
public class AnalyticsV2Controller {

  private static final String PROJECT_ALL = "all";
  private static final String PROJECT_WIKIPEDIA = "wikipedia";
  private static final String PROJECT_WIKIMEDIA_COMMONS = "wikimedia-commons";
  private static final String PROJECT_WIKIDATA = "wikidata";

  private final PostgresAnalyticsRepository postgresAnalyticsRepository;

  public AnalyticsV2Controller(PostgresAnalyticsRepository postgresAnalyticsRepository) {
    this.postgresAnalyticsRepository = postgresAnalyticsRepository;
  }

  @GetMapping("/geo")
  public List<GeoCountDto> geoDistribution(
      @RequestParam(required = false) String project,
      @RequestParam(required = false) Boolean isBot,
      @RequestParam(required = false) String timeframe) {
    Instant since = parseSince(timeframe);
    String normalizedProject = normalizeProject(project);
    return postgresAnalyticsRepository.getGeoDistribution(normalizedProject, isBot, since);
  }

  @GetMapping("/behavior")
  public EditBehaviorDto editBehavior(
      @RequestParam(required = false) String project,
      @RequestParam(required = false) Boolean isBot,
      @RequestParam(required = false) String timeframe) {
    Instant since = parseSince(timeframe);
    String normalizedProject = normalizeProject(project);
    return postgresAnalyticsRepository.getEditBehavior(normalizedProject, isBot, since);
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
      default -> throw invalidProject(project);
    };
  }

  private static Instant parseSince(String timeframe) {
    if (timeframe == null || timeframe.isBlank()) {
      return null;
    }

    String normalized = timeframe.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() < 2) {
      throw invalidTimeframe(timeframe);
    }

    String valuePortion = normalized.substring(0, normalized.length() - 1);
    char unit = normalized.charAt(normalized.length() - 1);

    long amount;
    try {
      amount = Long.parseLong(valuePortion);
    } catch (NumberFormatException ex) {
      throw invalidTimeframe(timeframe);
    }

    if (amount <= 0) {
      throw invalidTimeframe(timeframe);
    }

    Duration duration =
        switch (unit) {
          case 'h' -> Duration.ofHours(amount);
          case 'd' -> Duration.ofDays(amount);
          default -> throw invalidTimeframe(timeframe);
        };

    return Instant.now().minus(duration);
  }

  private static ResponseStatusException invalidTimeframe(String timeframe) {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Invalid timeframe '"
            + timeframe
            + "'. Supported format examples: 1h, 24h, 7d.");
  }

  private static ResponseStatusException invalidProject(String project) {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Invalid project '"
            + project
            + "'. Supported values: all, wikipedia, wikimedia-commons, wikidata.");
  }
}
