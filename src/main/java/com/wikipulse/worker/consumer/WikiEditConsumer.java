package com.wikipulse.worker.consumer;

import com.wikipulse.producer.model.WikiEditEvent;
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

  public WikiEditConsumer(
      DeduplicationService deduplicationService, ProcessedEditService processedEditService) {
    this.deduplicationService = deduplicationService;
    this.processedEditService = processedEditService;
  }

  @KafkaListener(topics = "wiki-edits", groupId = "wikipulse-worker-group")
  public void consumeEdit(
      WikiEditEvent event,
      @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
      Acknowledgment acknowledgment) {
    try {
      if (event != null) {
        // Step A: Redis Idempotency Check
        if (deduplicationService.isDuplicate(event.id())) {
          log.warn("[Duplicate Detected] Skipping edit {}", event.id());
          acknowledgment.acknowledge();
          return;
        }

        log.info(
            "[Partition-{}] Received Edit: '{}' by '{}'", partition, event.title(), event.user());

        // Step B: Database Save
        processedEditService.saveEdit(event);
      }

      // Step C: Offset Commit
      // Explicit manual acknowledgment ONLY after DB save succeeds
      acknowledgment.acknowledge();
    } catch (Exception e) {
      log.error("[Partition-{}] Error processing event: {}", partition, e.getMessage());
      // Not acknowledging ensures message is handled according to retry tracking
    }
  }
}
