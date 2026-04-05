package com.wikipulse.worker.api;

import com.wikipulse.worker.api.dto.EditUpdateDto;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/edits")
public class AnalyticsController {

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 200;

  private final ProcessedEditRepository processedEditRepository;

  public AnalyticsController(ProcessedEditRepository processedEditRepository) {
    this.processedEditRepository = processedEditRepository;
  }

  @GetMapping("/recent")
  public List<EditUpdateDto> recentEdits(
      @RequestParam(name = "limit", defaultValue = "25") int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
    if (limit <= 0) {
      boundedLimit = DEFAULT_LIMIT;
    }

    return processedEditRepository.findByOrderByEditTimestampDesc(PageRequest.of(0, boundedLimit))
        .stream()
        .map(EditUpdateDto::from)
        .toList();
  }
}