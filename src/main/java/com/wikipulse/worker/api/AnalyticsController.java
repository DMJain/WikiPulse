package com.wikipulse.worker.api;

import com.wikipulse.worker.api.dto.BotCountDto;
import com.wikipulse.worker.api.dto.EditUpdateDto;
import com.wikipulse.worker.api.dto.KpiDto;
import com.wikipulse.worker.api.dto.LanguageCountDto;
import com.wikipulse.worker.api.dto.NamespaceCountDto;
import com.wikipulse.worker.api.dto.TrendBucketDto;
import com.wikipulse.worker.domain.AnalyticsRollupRepository;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import com.wikipulse.worker.util.WikiMetadataNormalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
@RequestMapping("/api")
public class AnalyticsController {

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 200;
  private static final int DEFAULT_LANGUAGE_LIMIT = 5;
  private static final int MAX_LANGUAGE_LIMIT = 10;
  private static final String COMMONS_LANGUAGE = "Wikimedia Commons";
  private static final String WIKIDATA_LANGUAGE = "Wikidata";
  private static final String UNKNOWN_LANGUAGE = "Unknown";

  private final ProcessedEditRepository processedEditRepository;
  private final AnalyticsRollupRepository analyticsRollupRepository;
  private final WikiMetadataNormalizer wikiMetadataNormalizer;

  public AnalyticsController(
      ProcessedEditRepository processedEditRepository,
      AnalyticsRollupRepository analyticsRollupRepository,
      WikiMetadataNormalizer wikiMetadataNormalizer) {
    this.processedEditRepository = processedEditRepository;
    this.analyticsRollupRepository = analyticsRollupRepository;
    this.wikiMetadataNormalizer = wikiMetadataNormalizer;
  }

  @GetMapping("/edits/recent")
  public List<EditUpdateDto> recentEdits(
      @RequestParam(name = "limit", defaultValue = "25") int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
    if (limit <= 0) {
      boundedLimit = DEFAULT_LIMIT;
    }

    return processedEditRepository
        .findByOrderByEditTimestampDesc(PageRequest.of(0, boundedLimit))
        .stream()
        .map(EditUpdateDto::from)
        .toList();
  }

  @GetMapping("/analytics/languages")
  public List<LanguageCountDto> topLanguages(
      @RequestParam(name = "limit", defaultValue = "5") int limit,
      @RequestParam(required = false) String timeframe,
      @RequestParam(required = false) Boolean isBot,
      @RequestParam(required = false) String project) {
    int boundedLimit = Math.max(1, Math.min(limit, MAX_LANGUAGE_LIMIT));
    if (limit <= 0) {
      boundedLimit = DEFAULT_LANGUAGE_LIMIT;
    }

    Instant since = parseSince(timeframe);
    ProjectScope projectScope = parseProject(project);
    ProjectQuery projectQuery = toProjectQuery(projectScope);

    return analyticsRollupRepository
        .findLanguageRollups(
            since,
            projectQuery.applyExactLanguage(),
            projectQuery.exactLanguage(),
            projectQuery.applyWikipedia())
        .stream()
        .map(
            result ->
                new LanguageCountDto(
                    result.getLanguage(),
                    editsForBotFilter(result.getTotalEdits(), result.getBotEdits(), isBot)))
        .filter(entry -> safeCount(entry.count()) > 0L)
        .sorted(
            Comparator.comparing(LanguageCountDto::count, Comparator.reverseOrder())
                .thenComparing(LanguageCountDto::serverUrl))
        .limit(boundedLimit)
        .toList();
  }

  @GetMapping("/analytics/namespaces")
  public List<NamespaceCountDto> namespaceBreakdown(
      @RequestParam(required = false) String timeframe,
      @RequestParam(required = false) Boolean isBot,
      @RequestParam(required = false) String project) {
    Instant since = parseSince(timeframe);
    ProjectScope projectScope = parseProject(project);
    ProjectQuery projectQuery = toProjectQuery(projectScope);

    return analyticsRollupRepository
        .findNamespaceRollups(
            since,
            projectQuery.applyExactLanguage(),
            projectQuery.exactLanguage(),
            projectQuery.applyWikipedia())
        .stream()
        .map(
            result ->
                new NamespaceCountDto(
                    result.getNamespace(),
                    editsForBotFilter(result.getTotalEdits(), result.getBotEdits(), isBot)))
        .filter(entry -> safeCount(entry.count()) > 0L)
        .sorted(
            Comparator.comparing(NamespaceCountDto::count, Comparator.reverseOrder())
                .thenComparing(NamespaceCountDto::namespace))
        .toList();
  }

  @GetMapping("/analytics/bots")
  public List<BotCountDto> botVsHumanBreakdown(
      @RequestParam(required = false) String timeframe,
      @RequestParam(required = false) Boolean isBot,
      @RequestParam(required = false) String project) {
    Instant since = parseSince(timeframe);
    ProjectScope projectScope = parseProject(project);
    ProjectQuery projectQuery = toProjectQuery(projectScope);

    AnalyticsRollupRepository.RollupTotals totals =
        analyticsRollupRepository.getRollupTotals(
            since,
            projectQuery.applyExactLanguage(),
            projectQuery.exactLanguage(),
            projectQuery.applyWikipedia());

    long totalEdits = safeCount(totals == null ? null : totals.getTotalEdits());
    long botEdits = safeCount(totals == null ? null : totals.getBotEdits());
    long humanEdits = Math.max(0L, totalEdits - botEdits);

    if (Boolean.TRUE.equals(isBot)) {
      return botEdits > 0L ? List.of(new BotCountDto(true, botEdits)) : List.of();
    }

    if (Boolean.FALSE.equals(isBot)) {
      return humanEdits > 0L ? List.of(new BotCountDto(false, humanEdits)) : List.of();
    }

    return List.of(new BotCountDto(true, botEdits), new BotCountDto(false, humanEdits)).stream()
        .filter(entry -> safeCount(entry.count()) > 0L)
        .sorted(Comparator.comparing(BotCountDto::count, Comparator.reverseOrder()))
        .toList();
  }

  @GetMapping("/analytics/kpis")
  public KpiDto kpiSnapshot(
      @RequestParam(required = false) String timeframe,
      @RequestParam(required = false) Boolean isBot,
      @RequestParam(required = false) String project) {
    Instant since = parseSince(timeframe);
    ProjectScope projectScope = parseProject(project);
    ProjectQuery projectQuery = toProjectQuery(projectScope);

    AnalyticsRollupRepository.RollupTotals totals =
        analyticsRollupRepository.getRollupTotals(
            since,
            projectQuery.applyExactLanguage(),
            projectQuery.exactLanguage(),
            projectQuery.applyWikipedia());

    long totalEdits = safeCount(totals == null ? null : totals.getTotalEdits());
    long botEdits = safeCount(totals == null ? null : totals.getBotEdits());
    long filteredTotalEdits = editsForBotFilter(totalEdits, botEdits, isBot);
    long filteredBotEdits =
        Boolean.TRUE.equals(isBot) ? filteredTotalEdits : Boolean.FALSE.equals(isBot) ? 0L : botEdits;
    double averageComplexity = calculateAverageComplexity(since, isBot, projectScope);
    double botPercentage =
        filteredTotalEdits == 0 ? 0.0 : (filteredBotEdits * 100.0) / filteredTotalEdits;

    return new KpiDto(filteredTotalEdits, botPercentage, averageComplexity);
  }

  @GetMapping("/analytics/trend")
  public List<TrendBucketDto> trend(
      @RequestParam(required = false) String timeframe,
      @RequestParam(required = false) Boolean isBot,
      @RequestParam(required = false) String project) {
    Instant since = parseSince(timeframe);
    ProjectScope projectScope = parseProject(project);
    ProjectQuery projectQuery = toProjectQuery(projectScope);

    return analyticsRollupRepository
        .findTrendRollups(
            since,
            projectQuery.applyExactLanguage(),
            projectQuery.exactLanguage(),
            projectQuery.applyWikipedia())
        .stream()
        .map(
            result -> {
              long totalEdits = editsForBotFilter(result.getTotalEdits(), result.getBotEdits(), isBot);
              long botEdits =
                  Boolean.TRUE.equals(isBot)
                      ? totalEdits
                      : Boolean.FALSE.equals(isBot) ? 0L : safeCount(result.getBotEdits());
              return new TrendBucketDto(result.getTimeBucket(), totalEdits, botEdits);
            })
        .filter(entry -> entry.totalEdits() > 0L)
        .toList();
  }

  private double calculateAverageComplexity(Instant since, Boolean isBot, ProjectScope projectScope) {
    List<ProcessedEditRepository.ComplexityByServerUrl> buckets =
        processedEditRepository.findComplexityByServerUrl(since, isBot);

    double weightedComplexity = 0.0;
    long totalCount = 0L;
    for (ProcessedEditRepository.ComplexityByServerUrl bucket : buckets) {
      String normalizedLanguage = wikiMetadataNormalizer.normalizeServerUrl(bucket.getServerUrl());
      if (!matchesProject(normalizedLanguage, projectScope)) {
        continue;
      }

      long count = safeCount(bucket.getCount());
      if (count <= 0L) {
        continue;
      }

      double averageComplexity =
          bucket.getAverageComplexity() == null ? 0.0 : bucket.getAverageComplexity();
      weightedComplexity += averageComplexity * count;
      totalCount += count;
    }

    return totalCount == 0L ? 0.0 : weightedComplexity / totalCount;
  }

  private static long editsForBotFilter(Long totalEdits, Long botEdits, Boolean isBot) {
    long safeTotalEdits = safeCount(totalEdits);
    long safeBotEdits = safeCount(botEdits);
    long safeHumanEdits = Math.max(0L, safeTotalEdits - safeBotEdits);

    if (Boolean.TRUE.equals(isBot)) {
      return safeBotEdits;
    }

    if (Boolean.FALSE.equals(isBot)) {
      return safeHumanEdits;
    }

    return safeTotalEdits;
  }

  private static ProjectQuery toProjectQuery(ProjectScope projectScope) {
    return switch (projectScope) {
      case ALL -> new ProjectQuery(false, "", false);
      case WIKIMEDIA_COMMONS -> new ProjectQuery(true, COMMONS_LANGUAGE, false);
      case WIKIDATA -> new ProjectQuery(true, WIKIDATA_LANGUAGE, false);
      case WIKIPEDIA -> new ProjectQuery(false, "", true);
    };
  }

  private static boolean matchesProject(String normalizedLanguage, ProjectScope projectScope) {
    return switch (projectScope) {
      case ALL -> true;
      case WIKIMEDIA_COMMONS -> COMMONS_LANGUAGE.equals(normalizedLanguage);
      case WIKIDATA -> WIKIDATA_LANGUAGE.equals(normalizedLanguage);
      case WIKIPEDIA ->
          !WIKIDATA_LANGUAGE.equals(normalizedLanguage)
              && !COMMONS_LANGUAGE.equals(normalizedLanguage)
              && !UNKNOWN_LANGUAGE.equals(normalizedLanguage);
    };
  }

  private static ProjectScope parseProject(String project) {
    if (project == null || project.isBlank()) {
      return ProjectScope.ALL;
    }

    String normalizedProject = project.trim().toLowerCase(Locale.ROOT);
    return switch (normalizedProject) {
      case "all" -> ProjectScope.ALL;
      case "wikipedia" -> ProjectScope.WIKIPEDIA;
      case "wikimedia commons", "wikimedia-commons", "wikimedia_commons", "commons" ->
          ProjectScope.WIKIMEDIA_COMMONS;
      case "wikidata" -> ProjectScope.WIKIDATA;
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

  private static long safeCount(Long count) {
    return count == null ? 0L : count;
  }

  private enum ProjectScope {
    ALL,
    WIKIPEDIA,
    WIKIMEDIA_COMMONS,
    WIKIDATA
  }

  private record ProjectQuery(boolean applyExactLanguage, String exactLanguage, boolean applyWikipedia) {}
}