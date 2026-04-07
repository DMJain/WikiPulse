package com.wikipulse.worker.service;

import com.wikipulse.worker.domain.AnalyticsRollup;
import com.wikipulse.worker.domain.AnalyticsRollupRepository;
import com.wikipulse.worker.domain.ProcessedEdit;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import com.wikipulse.worker.util.WikiMetadataNormalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsAggregationService {

  private static final Duration ROLLUP_WINDOW = Duration.ofMinutes(10);

  private final ProcessedEditRepository processedEditRepository;
  private final AnalyticsRollupRepository analyticsRollupRepository;
  private final WikiMetadataNormalizer wikiMetadataNormalizer;

  public AnalyticsAggregationService(
      ProcessedEditRepository processedEditRepository,
      AnalyticsRollupRepository analyticsRollupRepository,
      WikiMetadataNormalizer wikiMetadataNormalizer) {
    this.processedEditRepository = processedEditRepository;
    this.analyticsRollupRepository = analyticsRollupRepository;
    this.wikiMetadataNormalizer = wikiMetadataNormalizer;
  }

  @Scheduled(fixedRate = 600000)
  @Transactional
  public void aggregateLastTenMinutes() {
    Instant windowEnd = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant windowStart = windowEnd.minus(ROLLUP_WINDOW);
    aggregateWindow(windowStart, windowEnd);
  }

  @Transactional
  public void aggregateWindow(Instant windowStartInclusive, Instant windowEndExclusive) {
    List<ProcessedEdit> recentEdits =
        processedEditRepository.findByEditTimestampGreaterThanEqualAndEditTimestampLessThan(
            windowStartInclusive, windowEndExclusive);

    Map<RollupKey, RollupCounter> groupedCounts = new HashMap<>();
    for (ProcessedEdit edit : recentEdits) {
      String normalizedLanguage = wikiMetadataNormalizer.normalizeServerUrl(edit.getServerUrl());
      String normalizedNamespace = wikiMetadataNormalizer.normalizeNamespace(edit.getNamespace());

      RollupKey key = new RollupKey(normalizedLanguage, normalizedNamespace);
      RollupCounter counter = groupedCounts.computeIfAbsent(key, ignored -> new RollupCounter());
      counter.totalEdits++;
      if (Boolean.TRUE.equals(edit.getIsBot())) {
        counter.botEdits++;
      }
    }

    List<AnalyticsRollup> existingRollupsForBucket =
        analyticsRollupRepository.findByTimeBucket(windowStartInclusive);
    Map<RollupKey, AnalyticsRollup> existingByKey = new HashMap<>();
    for (AnalyticsRollup rollup : existingRollupsForBucket) {
      existingByKey.put(new RollupKey(rollup.getLanguage(), rollup.getNamespace()), rollup);
    }

    List<AnalyticsRollup> rollupsToPersist =
        groupedCounts.entrySet().stream()
            .map(
                entry -> {
                  RollupKey key = entry.getKey();
                  RollupCounter counter = entry.getValue();
                  AnalyticsRollup rollup =
                      existingByKey.getOrDefault(key, new AnalyticsRollup());
                  rollup.setTimeBucket(windowStartInclusive);
                  rollup.setLanguage(key.language());
                  rollup.setNamespace(key.namespace());
                  rollup.setTotalEdits(counter.totalEdits);
                  rollup.setBotEdits(counter.botEdits);
                  return rollup;
                })
            .toList();

    analyticsRollupRepository.saveAll(rollupsToPersist);
  }

  private record RollupKey(String language, String namespace) {}

  private static final class RollupCounter {
    private long totalEdits;
    private long botEdits;
  }
}