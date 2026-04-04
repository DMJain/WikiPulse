package com.wikipulse.worker.service;

import com.wikipulse.producer.domain.WikiEditEvent;
import com.wikipulse.worker.entity.ProcessedEdit;
import com.wikipulse.worker.repository.ProcessedEditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessedEditService {

  private static final Logger log = LoggerFactory.getLogger(ProcessedEditService.class);
  private final ProcessedEditRepository repository;

  public ProcessedEditService(ProcessedEditRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void saveEdit(WikiEditEvent event, boolean isBot, int complexityScore) {
    ProcessedEdit entity = new ProcessedEdit();
    entity.setIsBot(isBot);
    entity.setComplexityScore(complexityScore);
    entity.setId(event.id());
    entity.setUserName(event.user());
    entity.setPageTitle(event.title());
    entity.setEditComment(event.comment());
    entity.setEditTimestamp(event.timestamp());

    repository.save(entity);
    if (log.isDebugEnabled()) {
      log.debug("Saved ProcessedEdit with ID {}", entity.getId());
    }
  }
}
