package com.wikipulse.worker.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import com.wikipulse.worker.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = {
      RedisConfig.class,
      DeduplicationService.class,
      org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class
    })
@Testcontainers
class DeduplicationIntegrationTests {

  @Container
  static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", redis::getFirstMappedPort);
  }

  @Autowired private DeduplicationService deduplicationService;

  @Autowired private RedisTemplate<String, String> redisTemplate;

  @Test
  void testIsDuplicate_returnsFalseForNewEditAndTrueForExisting() {
    Long testEditId = 12345L;

    // First time should not be duplicate
    boolean firstCheck = deduplicationService.isDuplicate(testEditId);
    assertThat(firstCheck).isFalse();

    // Key should exist with TTL
    String key = "edit:processed:" + testEditId;
    assertThat(redisTemplate.hasKey(key)).isTrue();
    Long ttl = redisTemplate.getExpire(key);
    assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(24 * 60 * 60);

    // Second time should be duplicate
    boolean secondCheck = deduplicationService.isDuplicate(testEditId);
    assertThat(secondCheck).isTrue();
  }
}
