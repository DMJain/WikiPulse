package com.wikipulse.worker.api.dto;

import com.wikipulse.worker.domain.ProcessedEdit;
import java.time.Instant;

public record EditUpdateDto(
    Long id,
    String userName,
    String pageTitle,
    String eventType,
    Instant editTimestamp,
    Boolean isBot,
    Integer complexityScore) {

  public static EditUpdateDto from(ProcessedEdit edit) {
    return new EditUpdateDto(
        edit.getId(),
        edit.getUserName(),
        edit.getPageTitle(),
        edit.getEventType(),
        edit.getEditTimestamp(),
        edit.getIsBot(),
        edit.getComplexityScore());
  }
}