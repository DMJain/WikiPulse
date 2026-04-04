package com.wikipulse.worker.service;

import com.wikipulse.producer.domain.WikiEditEvent;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

  private final StringRedisTemplate redisTemplate;

  public AnalyticsService(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public int calculateComplexity(WikiEditEvent event) {
    if (event == null) {
      return 0;
    }
    int score = 0;
    String comment = event.comment() != null ? event.comment() : "";
    String title = event.title() != null ? event.title() : "";

    score += comment.length();
    score += (title.length() * 2);

    String lowerComment = comment.toLowerCase();
    if (lowerComment.contains("vandalism") || lowerComment.contains("revert")) {
      score += 50;
    }

    return score;
  }

  public boolean detectBot(String userName) {
    if (userName == null || userName.isBlank()) {
      return false;
    }
    String key = "bot:velocity:" + userName;
    Long count = redisTemplate.opsForValue().increment(key);

    // Set TTL only on the first increment to ensure a rolling 60s window from the first edit
    if (count != null && count == 1L) {
      redisTemplate.expire(key, Duration.ofSeconds(60));
    }

    return count != null && count > 5;
  }
}
