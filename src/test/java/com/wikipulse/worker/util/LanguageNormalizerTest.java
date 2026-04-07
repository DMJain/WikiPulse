package com.wikipulse.worker.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageNormalizerTest {

  private final WikiMetadataNormalizer normalizer = new WikiMetadataNormalizer();

  @Test
  void shouldNormalizeFrenchWikipediaHost() {
    assertThat(normalizer.normalizeServerUrl("fr.wikipedia.org")).isEqualTo("French");
  }

  @Test
  void shouldNormalizeRussianWikinewsHost() {
    assertThat(normalizer.normalizeServerUrl("ru.wikinews.org")).isEqualTo("Russian");
  }

  @Test
  void shouldCapitalizeUnknownSubdomain() {
    assertThat(normalizer.normalizeServerUrl("unknown.org")).isEqualTo("Unknown");
  }
}