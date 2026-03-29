package com.wikipulse.worker.consumer;

import com.wikipulse.producer.model.WikiEditEvent;
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

  @KafkaListener(topics = "wiki-edits", groupId = "wikipulse-worker-group")
  public void consumeEdit(
      WikiEditEvent event,
      @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
      Acknowledgment acknowledgment) {
    try {
      if (event != null) {
        log.info(
            "[Partition-{}] Received Edit: '{}' by '{}'", partition, event.title(), event.user());
      }
      // Explicit manual acknowledgment
      acknowledgment.acknowledge();
    } catch (Exception e) {
      log.error("[Partition-{}] Error processing event: {}", partition, e.getMessage());
      // Not acknowledging ensures message is handled according to retry tracking (if configured)
    }
  }
}
