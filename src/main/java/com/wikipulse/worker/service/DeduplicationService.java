package com.wikipulse.worker.service;

import java.time.Duration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeduplicationService {

  private static final String DEDUP_KEY_PREFIX = "edit:processed:";
  private static final Duration DEDUP_TTL = Duration.ofHours(24);

  private final RedisTemplate<String, String> redisTemplate;

  public DeduplicationService(RedisTemplate<String, String> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public boolean isDuplicate(Long editId) {
    if (editId == null || editId <= 0) {
      return true;
    }
    Boolean setIfAbsentRes =
        redisTemplate
            .opsForValue()
            .setIfAbsent(DEDUP_KEY_PREFIX + editId, "true", DEDUP_TTL);
    return !Boolean.TRUE.equals(setIfAbsentRes);
  }
}
