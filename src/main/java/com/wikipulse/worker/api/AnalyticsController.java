package com.wikipulse.worker.api;

import com.wikipulse.worker.api.dto.BotCountDto;
import com.wikipulse.worker.api.dto.EditUpdateDto;
import com.wikipulse.worker.api.dto.LanguageCountDto;
import com.wikipulse.worker.api.dto.NamespaceCountDto;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import com.wikipulse.worker.util.WikiMetadataNormalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
      @RequestParam(name = "limit", defaultValue = "5") int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, MAX_LANGUAGE_LIMIT));
    if (limit <= 0) {
      boundedLimit = DEFAULT_LANGUAGE_LIMIT;
    }

    Map<String, Long> normalizedLanguageCounts =
        processedEditRepository.findTopLanguages(PageRequest.of(0, boundedLimit)).stream()
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
  public List<NamespaceCountDto> namespaceBreakdown() {
    Map<String, Long> normalizedNamespaceCounts =
        processedEditRepository.findNamespaceBreakdown().stream()
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
  public List<BotCountDto> botVsHumanBreakdown() {
    return processedEditRepository.findBotVsHumanBreakdown().stream()
        .map(result -> new BotCountDto(result.getIsBot(), result.getCount()))
        .toList();
  }

  private static long safeCount(Long count) {
    return count == null ? 0L : count;
  }
}