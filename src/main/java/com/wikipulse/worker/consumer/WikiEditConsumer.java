package com.wikipulse.worker.consumer;

import com.wikipulse.producer.model.WikiEditEvent;
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

    @KafkaListener(topics = "wiki-edits", groupId = "wikipulse-worker-group")
    public void consume(WikiEditEvent event, Acknowledgment acknowledgment) {
        Timer.Sample sample = workerMetrics.startTimer();
        
        try {
            boolean isNew = deduplicationService.isNewEdit(event);
            if (!isNew) {
                log.debug("Duplicate event ignored: {}", event.id());
                acknowledgment.acknowledge();
                return;
            }

            analyticsService.processAnalytics(event);
            processedEditService.save(event);

            workerMetrics.incrementProcessed();
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("[CRITICAL ERROR] Failed to process WikiEditEvent ID: {}", event.id(), e);
            workerMetrics.incrementError();
            throw e; // Rethrow to trigger the ErrorHandler (Retry & DLT)
        } finally {
            workerMetrics.stopTimer(sample); // Assures no survivorship bias in latency monitoring
        }
    }
}