package com.wikipulse.worker.api;

import com.wikipulse.worker.api.dto.BotCountDto;
import com.wikipulse.worker.api.dto.EditUpdateDto;
import com.wikipulse.worker.api.dto.KpiDto;
import com.wikipulse.worker.api.dto.LanguageCountDto;
import com.wikipulse.worker.api.dto.NamespaceCountDto;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import com.wikipulse.worker.util.WikiMetadataNormalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
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

  private final ProcessedEditRepository processedEditRepository;
  private final WikiMetadataNormalizer wikiMetadataNormalizer;

  public AnalyticsController(
      ProcessedEditRepository processedEditRepository,
      WikiMetadataNormalizer wikiMetadataNormalizer) {
    this.processedEditRepository = processedEditRepository;
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
      @RequestParam(required = false) Boolean isBot) {
    int boundedLimit = Math.max(1, Math.min(limit, MAX_LANGUAGE_LIMIT));
    if (limit <= 0) {
      boundedLimit = DEFAULT_LANGUAGE_LIMIT;
    }

    Instant since = parseSince(timeframe);

    Map<String, Long> normalizedLanguageCounts =
        processedEditRepository.findTopLanguages(PageRequest.of(0, boundedLimit), since, isBot).stream()
            .collect(
                Collectors.groupingBy(
                    result -> wikiMetadataNormalizer.normalizeServerUrl(result.getServerUrl()),
                    Collectors.summingLong(result -> safeCount(result.getCount()))));

    return normalizedLanguageCounts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(boundedLimit)
        .map(entry -> new LanguageCountDto(entry.getKey(), entry.getValue()))
        .toList();
  }

  @GetMapping("/analytics/namespaces")
  public List<NamespaceCountDto> namespaceBreakdown(
      @RequestParam(required = false) String timeframe,
      @RequestParam(required = false) Boolean isBot) {
    Instant since = parseSince(timeframe);

    Map<String, Long> normalizedNamespaceCounts =
        processedEditRepository.findNamespaceBreakdown(since, isBot).stream()
            .collect(
                Collectors.groupingBy(
                    result -> wikiMetadataNormalizer.normalizeNamespace(result.getNamespace()),
                    Collectors.summingLong(result -> safeCount(result.getCount()))));

    return normalizedNamespaceCounts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .map(entry -> new NamespaceCountDto(entry.getKey(), entry.getValue()))
        .toList();
  }

  @GetMapping("/analytics/bots")
  public List<BotCountDto> botVsHumanBreakdown(
      @RequestParam(required = false) String timeframe,
      @RequestParam(required = false) Boolean isBot) {
    Instant since = parseSince(timeframe);

    return processedEditRepository.findBotVsHumanBreakdown(since, isBot).stream()
        .map(result -> new BotCountDto(result.getIsBot(), result.getCount()))
        .toList();
  }

  @GetMapping("/analytics/kpis")
  public KpiDto kpiSnapshot(
      @RequestParam(required = false) String timeframe,
      @RequestParam(required = false) Boolean isBot) {
    Instant since = parseSince(timeframe);
    ProcessedEditRepository.KpiSnapshot snapshot = processedEditRepository.getKpiSnapshot(since, isBot);

    long totalEdits = safeCount(snapshot == null ? null : snapshot.getTotalEdits());
    long botEdits = safeCount(snapshot == null ? null : snapshot.getBotEdits());
    double averageComplexity =
        snapshot == null || snapshot.getAverageComplexity() == null
            ? 0.0
            : snapshot.getAverageComplexity();
    double botPercentage = totalEdits == 0 ? 0.0 : (botEdits * 100.0) / totalEdits;

    return new KpiDto(totalEdits, botPercentage, averageComplexity);
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

  private static long safeCount(Long count) {
    return count == null ? 0L : count;
  }
}