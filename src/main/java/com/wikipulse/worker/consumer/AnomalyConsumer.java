package com.wikipulse.worker.consumer;

import com.wikipulse.worker.domain.AnomalyAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class AnomalyConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnomalyConsumer.class);
    private static final String ANOMALY_TOPIC = "/topic/anomalies";

    private final SimpMessagingTemplate messagingTemplate;

    public AnomalyConsumer(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @KafkaListener(
            topics = "wiki-anomalies",
            groupId = "wikipulse-ui-group",
            containerFactory = "anomalyListenerContainerFactory")
    public void consume(AnomalyAlert alert, Acknowledgment acknowledgment) {
        try {
            if (alert == null) {
                log.warn("Received null AnomalyAlert payload. Acknowledging offset.");
                acknowledgment.acknowledge();
                return;
            }

            messagingTemplate.convertAndSend(ANOMALY_TOPIC, alert);
            acknowledgment.acknowledge();
        } catch (Exception ex) {
            String alertId = alert != null ? alert.id() : "unknown";
            log.error("Failed to broadcast anomaly alert {} to websocket clients", alertId, ex);
            throw new RuntimeException("Anomaly consumer processing failed", ex);
        }
    }
}
