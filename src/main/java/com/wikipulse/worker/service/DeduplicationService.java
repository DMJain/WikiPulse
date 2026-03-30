package com.wikipulse.worker.service;

import java.time.Duration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeduplicationService {

  private final RedisTemplate<String, String> redisTemplate;

  public DeduplicationService(RedisTemplate<String, String> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public boolean isDuplicate(Long editId) {
    if (editId == null) {
      return false;
    }
    Boolean setIfAbsentRes =
        redisTemplate
            .opsForValue()
            .setIfAbsent("edit:processed:" + editId, "true", Duration.ofHours(24));
    return !Boolean.TRUE.equals(setIfAbsentRes);
  }
}
