package com.wikipulse.worker.consumer;

import com.wikipulse.producer.domain.WikiEditEvent;
import com.wikipulse.worker.metrics.WorkerMetrics;
import com.wikipulse.worker.service.AnalyticsService;
import com.wikipulse.worker.service.DeduplicationService;
import com.wikipulse.worker.service.ProcessedEditService;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class WikiEditConsumer {

    private static final Logger log = LoggerFactory.getLogger(WikiEditConsumer.class);

    private final DeduplicationService deduplicationService;
    private final AnalyticsService analyticsService;
    private final ProcessedEditService processedEditService;
    private final WorkerMetrics workerMetrics;

    public WikiEditConsumer(DeduplicationService deduplicationService,
                            AnalyticsService analyticsService,
                            ProcessedEditService processedEditService,
                            WorkerMetrics workerMetrics) {
        this.deduplicationService = deduplicationService;
        this.analyticsService = analyticsService;
        this.processedEditService = processedEditService;
        this.workerMetrics = workerMetrics;
    }

    @KafkaListener(
            topics = "wiki-edits",
            groupId = "wikipulse-worker-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(WikiEditEvent event, Acknowledgment acknowledgment) {
        Timer.Sample sample = workerMetrics.startTimer();

        try {
            if (event == null || event.id() == null || event.id() <= 0) {
                log.warn("Skipping malformed event payload (null or invalid id). Acknowledging offset.");
                acknowledgment.acknowledge();
                return;
            }

            boolean isDuplicate = deduplicationService.isDuplicate(event.id());
            if (isDuplicate) {
                log.debug("Duplicate event ignored: {}", event.id());
                acknowledgment.acknowledge();
                return;
            }

            int complexity = analyticsService.calculateComplexity(event);
            boolean isBot = analyticsService.detectBot(event.user());
            if (isBot) {
                workerMetrics.incrementBots();
            }

            processedEditService.saveEdit(event, isBot, complexity);

            workerMetrics.incrementProcessed();
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("[CRITICAL ERROR] Failed to process WikiEditEvent ID: {}", event.id(), e);
            workerMetrics.incrementError();
            throw new RuntimeException("Consumer processing failed", e); // Wrap to satisfy compiler and trigger DLT
        } finally {
            workerMetrics.stopTimer(sample); // Assures no survivorship bias in latency monitoring
        }
    }
}