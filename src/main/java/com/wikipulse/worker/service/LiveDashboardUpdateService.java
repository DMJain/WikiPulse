package com.wikipulse.worker.service;

import com.wikipulse.worker.api.dto.EditUpdateDto;
import com.wikipulse.worker.domain.ProcessedEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class LiveDashboardUpdateService {

  private static final Logger log = LoggerFactory.getLogger(LiveDashboardUpdateService.class);
  private static final String EDITS_TOPIC = "/topic/edits";

  private final SimpMessagingTemplate messagingTemplate;

  public LiveDashboardUpdateService(SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  public void broadcastEdit(ProcessedEdit processedEdit) {
    EditUpdateDto payload = EditUpdateDto.from(processedEdit);

    try {
      messagingTemplate.convertAndSend(EDITS_TOPIC, payload);
    } catch (Exception ex) {
      log.warn(
          "Failed to broadcast live dashboard update for edit id {}. Proceeding without websocket push.",
          processedEdit.getId(),
          ex);
    }
  }
}