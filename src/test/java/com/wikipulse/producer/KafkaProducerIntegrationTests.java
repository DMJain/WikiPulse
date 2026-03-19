package com.wikipulse.producer;

import com.wikipulse.producer.model.WikiEditEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class KafkaProducerIntegrationTests {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static Consumer<String, String> consumer;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeAll
    static void setupConsumer() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(kafka.getBootstrapServers(), "test-group", "false");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(Collections.singletonList("wiki-edits"));
    }

    @AfterAll
    static void teardownConsumer() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void shouldPublishWikiEditEventSuccessfully() {
        WikiEditEvent event = new WikiEditEvent(
            12345L,
            "TestDataBot",
            "Main_Page",
            "Fixed typo in test data",
            Instant.parse("2026-03-19T10:00:00Z")
        );

        kafkaTemplate.send("wiki-edits", String.valueOf(event.id()), event);

        ConsumerRecord<String, String> receivedRecord = KafkaTestUtils.getSingleRecord(consumer, "wiki-edits", Duration.ofSeconds(10));
        
        assertThat(receivedRecord.key()).isEqualTo("12345");
        assertThat(receivedRecord.value()).contains("TestDataBot");
        assertThat(receivedRecord.value()).contains("Main_Page");
        assertThat(receivedRecord.value()).contains("2026-03-19T10:00:00Z");
    }
}
