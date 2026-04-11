package com.wikipulse.worker.topology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wikipulse.producer.domain.WikiEditEvent;
import com.wikipulse.worker.domain.AnomalyAlert;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnomalyDetectionTopologyTest {

    private static final String INPUT_TOPIC = "wiki-edits";
    private static final String OUTPUT_TOPIC = "wiki-anomalies";

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, byte[]> inputTopic;
    private TestOutputTopic<String, byte[]> outputTopic;

    @BeforeEach
    void setUp() {
        StreamsBuilder streamsBuilder = new StreamsBuilder();
        AnomalyDetectionTopology topology = new AnomalyDetectionTopology(streamsBuilder);
        topology.configureTopology(streamsBuilder);

        testDriver = new TopologyTestDriver(streamsBuilder.build(), streamProperties());
        inputTopic =
                testDriver.createInputTopic(
                        INPUT_TOPIC,
                        new StringSerializer(),
                        new ByteArraySerializer());
        outputTopic =
                testDriver.createOutputTopic(
                        OUTPUT_TOPIC,
                        new StringDeserializer(),
                        new ByteArrayDeserializer());
    }

    @AfterEach
    void tearDown() {
        if (testDriver != null) {
            testDriver.close();
        }
    }

    @Test
    void shouldEmitTrendSpikeAlertWhenTwentyEditsOccurInOneWindow() {
        Instant baseTimestamp = Instant.parse("2026-04-09T12:00:00Z");

        for (int i = 0; i < 20; i++) {
            Instant eventTime = baseTimestamp.plusSeconds(i);
            WikiEditEvent event = buildEvent(1000L + i, "Breaking_News", false, eventTime);
            inputTopic.pipeInput("Breaking_News", toJsonBytes(event), eventTime);
        }

        List<AnomalyAlert> alerts = readOutputAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().anomalyType()).isEqualTo("TREND_SPIKE");
        assertThat(alerts.getFirst().pageTitle()).isEqualTo("Breaking_News");
        assertThat(alerts.getFirst().eventCount()).isEqualTo(20L);
    }

    @Test
    void shouldEmitEditWarAlertWhenFiveRevertsOccurInOneWindow() {
        Instant baseTimestamp = Instant.parse("2026-04-09T13:00:00Z");

        for (int i = 0; i < 5; i++) {
            Instant eventTime = baseTimestamp.plusSeconds(i);
            WikiEditEvent event = buildEvent(2000L + i, "Controversial_Topic", true, eventTime);
            inputTopic.pipeInput("Controversial_Topic", toJsonBytes(event), eventTime);
        }

        List<AnomalyAlert> alerts = readOutputAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().anomalyType()).isEqualTo("EDIT_WAR");
        assertThat(alerts.getFirst().pageTitle()).isEqualTo("Controversial_Topic");
        assertThat(alerts.getFirst().eventCount()).isEqualTo(5L);
    }

    private Properties streamProperties() {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "anomaly-detection-topology-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        return properties;
    }

    private List<AnomalyAlert> readOutputAlerts() {
        return outputTopic.readValuesToList().stream().map(this::fromJsonBytes).toList();
    }

    private byte[] toJsonBytes(WikiEditEvent event) {
        try {
            return objectMapper.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize WikiEditEvent", e);
        }
    }

    private AnomalyAlert fromJsonBytes(byte[] payload) {
        try {
            return objectMapper.readValue(payload, AnomalyAlert.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize AnomalyAlert", e);
        }
    }

    private WikiEditEvent buildEvent(Long id, String title, Boolean isRevert, Instant timestamp) {
        return new WikiEditEvent(
                id,
                title,
                "TestUser" + id,
                timestamp,
                "edit",
                false,
                "phase-22-test",
                "https://en.wikipedia.org",
                0,
                "US",
                "New York",
                42,
                isRevert,
                false,
                new WikiEditEvent.Meta(
                        "en.wikipedia.org",
                        "recentchange",
                        "https://en.wikipedia.org/wiki/" + title,
                        timestamp));
    }
}
