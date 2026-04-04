package com.wikipulse.producer.client;

import com.wikipulse.producer.domain.WikiEditEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.util.retry.Retry;

@Component
public class WikipediaSseClient {

  private static final Logger log = LoggerFactory.getLogger(WikipediaSseClient.class);
  private static final String WIKIPEDIA_STREAM_URL =
      "https://stream.wikimedia.org/v2/stream/recentchange";
  private static final String WIKI_EDITS_TOPIC = "wiki-edits";
  private static final int BACKPRESSURE_BUFFER_SIZE = 10_000;

  private final WebClient webClient;
  private final KafkaTemplate<String, WikiEditEvent> kafkaTemplate;
  private final AtomicInteger eventCount = new AtomicInteger(0);

  public WikipediaSseClient(
      WebClient.Builder webClientBuilder, KafkaTemplate<String, WikiEditEvent> kafkaTemplate) {
    this.webClient = webClientBuilder.build();
    this.kafkaTemplate = kafkaTemplate;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void consumeStream() {
    log.info("Starting Wikipedia SSE Stream consumer...");

    webClient
        .get()
        .uri(WIKIPEDIA_STREAM_URL)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .retrieve()
        .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
        .filter(this::isEditEvent)
        .map(this::mapToWikipediaSseEvent)
        .map(this::mapToWikiEditEvent)
        .onBackpressureBuffer(
            BACKPRESSURE_BUFFER_SIZE,
            droppedEvent ->
                log.error(
                    "Backpressure buffer overflow (limit {}). Last dropped event id={} title='{}'",
                    BACKPRESSURE_BUFFER_SIZE,
                    droppedEvent.id(),
                    droppedEvent.title()),
            BufferOverflowStrategy.ERROR)
        .doOnNext(this::publishToKafka)
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

    return new WikiEditEvent(
        parseLong(raw.get("id")),
        parseString(raw.get("title")),
        parseString(raw.get("user")),
        parseEpochTimestamp(raw.get("timestamp")),
        parseString(raw.get("type")),
        parseBoolean(raw.get("bot")),
        parseStringOrDefault(raw.get("comment"), ""),
        parseMeta(raw.get("meta")));
  }

  private void publishToKafka(WikiEditEvent event) {
    int count = eventCount.incrementAndGet();
    if (count % 100 == 0) {
      log.info(
          "Ingested and published event #{}: User: '{}', Title: '{}'",
          count,
          event.user(),
          event.title());
    }

    String partitionKey = event.title() != null ? event.title() : "unknown-title";
    kafkaTemplate
        .send(WIKI_EDITS_TOPIC, partitionKey, event)
        .whenComplete(
            (result, ex) -> {
              if (ex == null) {
                log.debug(
                    "Successfully published edit {} to partition {} at offset {}",
                    event.id(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
              } else {
                log.error(
                    "[CRITICAL ERROR] Failed to publish WikiEditEvent ID: {}. Reason: {}",
                    event.id(),
                    ex.getMessage(),
                    ex);
              }
            });
  }

  private Long parseLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private String parseString(Object value) {
    return value instanceof String text ? text : null;
  }

  private String parseStringOrDefault(Object value, String fallback) {
    String parsed = parseString(value);
    return parsed != null ? parsed : fallback;
  }

  private Instant parseEpochTimestamp(Object value) {
    if (value instanceof Number number) {
      return Instant.ofEpochSecond(number.longValue());
    }
    if (value instanceof String text) {
      try {
        return Instant.ofEpochSecond(Long.parseLong(text));
      } catch (NumberFormatException ignored) {
        return Instant.now();
      }
    }
    return Instant.now();
  }

  private Boolean parseBoolean(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String text) {
      return Boolean.parseBoolean(text);
    }
    return Boolean.FALSE;
  }

  private WikiEditEvent.Meta parseMeta(Object value) {
    if (!(value instanceof Map<?, ?> metaMap)) {
      return null;
    }

    String domain = parseString(metaMap.get("domain"));
    String stream = parseString(metaMap.get("stream"));
    String uri = parseString(metaMap.get("uri"));
    Instant dt = parseIsoInstant(metaMap.get("dt"));

    if (domain == null && stream == null && uri == null && dt == null) {
      return null;
    }

    return new WikiEditEvent.Meta(domain, stream, uri, dt);
  }

  private Instant parseIsoInstant(Object value) {
    if (value instanceof String text) {
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException ignored) {
        return null;
      }
    }
    return null;
  }
}
