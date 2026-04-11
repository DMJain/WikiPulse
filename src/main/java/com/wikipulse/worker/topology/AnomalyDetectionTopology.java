package com.wikipulse.worker.topology;

import com.wikipulse.producer.domain.WikiEditEvent;
import com.wikipulse.worker.domain.AnomalyAlert;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.stereotype.Component;

@Component
public class AnomalyDetectionTopology {

    private static final String INPUT_TOPIC = "wiki-edits";
    private static final String OUTPUT_TOPIC = "wiki-anomalies";
    private static final String TREND_SPIKE = "TREND_SPIKE";
    private static final String EDIT_WAR = "EDIT_WAR";
    private static final long TREND_SPIKE_THRESHOLD = 20L;
    private static final long EDIT_WAR_THRESHOLD = 5L;
    private static final Duration WINDOW_SIZE = Duration.ofSeconds(60);

    private final StreamsBuilder streamsBuilder;

    public AnomalyDetectionTopology(StreamsBuilder streamsBuilder) {
        this.streamsBuilder = streamsBuilder;
    }

    @PostConstruct
    public void initialize() {
        configureTopology(streamsBuilder);
    }

    void configureTopology(StreamsBuilder builder) {
        JsonSerde<WikiEditEvent> wikiEditEventSerde = wikiEditEventSerde();
        JsonSerde<AnomalyAlert> anomalyAlertSerde = anomalyAlertSerde();

        KStream<String, WikiEditEvent> baseStream =
                builder.stream(INPUT_TOPIC, Consumed.with(Serdes.String(), wikiEditEventSerde));

        KStream<String, AnomalyAlert> trendSpikeAlerts = baseStream
                .filter((key, event) -> event != null && event.title() != null)
                .groupBy((key, event) -> event.title(), Grouped.with(Serdes.String(), wikiEditEventSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW_SIZE))
                .count()
                .toStream()
                .filter((windowedTitle, count) -> count >= TREND_SPIKE_THRESHOLD)
                .map((windowedTitle, count) ->
                        KeyValue.pair(windowedTitle.key(),
                                createAlert(windowedTitle, TREND_SPIKE, count)));

        KStream<String, AnomalyAlert> editWarAlerts = baseStream
                .filter((key, event) ->
                        event != null
                                && event.title() != null
                                && Boolean.TRUE.equals(event.isRevert()))
                .groupBy((key, event) -> event.title(), Grouped.with(Serdes.String(), wikiEditEventSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW_SIZE))
                .count()
                .toStream()
                .filter((windowedTitle, count) -> count >= EDIT_WAR_THRESHOLD)
                .map((windowedTitle, count) ->
                        KeyValue.pair(windowedTitle.key(), createAlert(windowedTitle, EDIT_WAR, count)));

        trendSpikeAlerts
                .merge(editWarAlerts)
                .to(OUTPUT_TOPIC, Produced.with(Serdes.String(), anomalyAlertSerde));
    }

    private JsonSerde<WikiEditEvent> wikiEditEventSerde() {
        JsonSerde<WikiEditEvent> serde = new JsonSerde<>(WikiEditEvent.class);
        serde.deserializer().addTrustedPackages("*");
        serde.serializer().setAddTypeInfo(false);
        return serde;
    }

    private JsonSerde<AnomalyAlert> anomalyAlertSerde() {
        JsonSerde<AnomalyAlert> serde = new JsonSerde<>(AnomalyAlert.class);
        serde.serializer().setAddTypeInfo(false);
        return serde;
    }

    private AnomalyAlert createAlert(Windowed<String> windowedTitle, String anomalyType, Long count) {
        return new AnomalyAlert(
                UUID.randomUUID().toString(),
                windowedTitle.key(),
                anomalyType,
                count,
                Instant.ofEpochMilli(windowedTitle.window().start()),
                Instant.ofEpochMilli(windowedTitle.window().end()));
    }
}
