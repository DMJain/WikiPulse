package com.wikipulse.producer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wikipulse.producer.domain.WikiEditEvent;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
public class KafkaProducerConfig {

  @Bean
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
  }

  @Bean
  public ProducerFactory<String, WikiEditEvent> producerFactory(
      KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
    Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

    DefaultKafkaProducerFactory<String, WikiEditEvent> factory =
        new DefaultKafkaProducerFactory<>(props);
    factory.setKeySerializer(new StringSerializer());

    JsonSerializer<WikiEditEvent> jsonSerializer = new JsonSerializer<>(objectMapper);
    jsonSerializer.setAddTypeInfo(false);
    factory.setValueSerializer(jsonSerializer);

    return factory;
  }

  @Bean
  public KafkaTemplate<String, WikiEditEvent> kafkaTemplate(
      ProducerFactory<String, WikiEditEvent> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }
}
