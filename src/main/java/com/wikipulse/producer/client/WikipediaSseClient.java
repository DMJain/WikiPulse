package com.wikipulse.producer.client;

import com.wikipulse.producer.model.WikiEditEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

@Component
public class WikipediaSseClient {

  private static final Logger log = LoggerFactory.getLogger(WikipediaSseClient.class);
  private static final String WIKIPEDIA_STREAM_URL =
      "https://stream.wikimedia.org/v2/stream/recentchange";

  private final WebClient webClient;
  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final AtomicInteger eventCount = new AtomicInteger(0);

  public WikipediaSseClient(
      WebClient.Builder webClientBuilder, KafkaTemplate<String, Object> kafkaTemplate) {
    this.webClient = webClientBuilder.build();
    this.kafkaTemplate = kafkaTemplate;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void consumeStream() {
    log.info("Starting Wikipedia SSE Stream consumer...");

    webClient
        .get()
        .uri(WIKIPEDIA_STREAM_URL)
        .retrieve()
        .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
        .filter(this::isEditEvent)
        .map(this::mapToWikipediaSseEvent)
        .map(this::mapToWikiEditEvent)
        .onBackpressureBuffer(10000)
        .doOnNext(
            event -> {
              int count = eventCount.incrementAndGet();
              if (count % 100 == 0) {
                log.info(
                    "Ingested and published event #{}: User: '{}', Title: '{}'",
                    count,
                    event.user(),
                    event.title());
              }
              kafkaTemplate.send("wiki-edits", event.title(), event);
            })
        .doOnError(e -> log.error("Stream error: {}", e.getMessage()))
        .retryWhen(
            Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(30))
                .doBeforeRetry(
                    retrySignal ->
                        log.warn(
                            "Connection dropped. Reconnecting... (Attempt #{})",
                            retrySignal.totalRetries() + 1)))
        .subscribe(
            event -> {},
            error -> log.error("Terminal stream error", error),
            () -> log.info("Stream completed (unexpected for infinite stream)"));
  }

  private boolean isEditEvent(Map<String, Object> rawEvent) {
    return "edit".equals(rawEvent.get("type"));
  }

  private WikipediaSseEvent mapToWikipediaSseEvent(Map<String, Object> rawEvent) {
    return new WikipediaSseEvent(rawEvent);
  }

  private WikiEditEvent mapToWikiEditEvent(WikipediaSseEvent sseEvent) {
    Map<String, Object> raw = sseEvent.rawData();

    Long id = null;
    Object idObj = raw.get("id");
    if (idObj instanceof Number num) {
      id = num.longValue();
    } else if (idObj instanceof String str) {
      try {
        id = Long.parseLong(str);
      } catch (Exception ignored) {
      }
    }

    String user = (String) raw.get("user");
    String title = (String) raw.get("title");
    String comment = (String) raw.get("comment");

    Instant timestamp = Instant.now();
    Object tsObj = raw.get("timestamp");
    if (tsObj instanceof Number num) {
      timestamp = Instant.ofEpochSecond(num.longValue());
    }

    return new WikiEditEvent(id, user, title, comment, timestamp);
  }
}
