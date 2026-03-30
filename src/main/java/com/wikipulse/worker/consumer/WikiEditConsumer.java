package com.wikipulse.worker.consumer;

import com.wikipulse.producer.model.WikiEditEvent;
import com.wikipulse.worker.metrics.WorkerMetrics;
import com.wikipulse.worker.service.AnalyticsService;
import io.micrometer.core.instrument.Timer;
import com.wikipulse.worker.service.DeduplicationService;
import com.wikipulse.worker.service.ProcessedEditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
public class WikiEditConsumer {

  private static final Logger log = LoggerFactory.getLogger(WikiEditConsumer.class);

  private final DeduplicationService deduplicationService;
  private final ProcessedEditService processedEditService;
  private final AnalyticsService analyticsService;
  private final WorkerMetrics workerMetrics;

  public WikiEditConsumer(
      DeduplicationService deduplicationService,
      ProcessedEditService processedEditService,
      AnalyticsService analyticsService,
      WorkerMetrics workerMetrics) {
    this.deduplicationService = deduplicationService;
    this.processedEditService = processedEditService;
    this.analyticsService = analyticsService;
    this.workerMetrics = workerMetrics;
  }

  @KafkaListener(topics = "wiki-edits", groupId = "wikipulse-worker-group")
  public void consumeEdit(
      WikiEditEvent event,
      @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
      Acknowledgment acknowledgment) {
    Timer.Sample sample = workerMetrics.startTimer();
    try {
      if (event != null) {
        // Step A: Redis Idempotency Check
        if (deduplicationService.isDuplicate(event.id())) {
          log.warn("[Duplicate Detected] Skipping edit {}", event.id());
          workerMetrics.stopTimer(sample);
          acknowledgment.acknowledge();
          return;
        }

        log.info(
            "[Partition-{}] Received Edit: '{}' by '{}'", partition, event.title(), event.user());

        // Step B: Analytics Enrichment
        int complexity = analyticsService.calculateComplexity(event);
        boolean isBot = analyticsService.detectBot(event.user());

        if (isBot) {
          log.warn(
              "[Partition-{}] Bot Detected: User '{}', Score: {}",
              partition,
              event.user(),
              complexity);
          workerMetrics.incrementBotsDetected();
        }

        // Step C: Database Save with enriched values
        processedEditService.saveEdit(event, isBot, complexity);
        workerMetrics.incrementProcessed();
      }

      workerMetrics.stopTimer(sample);
      // Step C: Offset Commit
      // Explicit manual acknowledgment ONLY after DB save succeeds
      acknowledgment.acknowledge();
    } catch (Exception e) {
      log.error("[Partition-{}] Error processing event: {}", partition, e.getMessage());
      // Not acknowledging ensures message is handled according to retry tracking
    }
  }
}
