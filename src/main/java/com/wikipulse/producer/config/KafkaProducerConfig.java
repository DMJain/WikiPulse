package com.wikipulse.producer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;
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
  public ProducerFactory<String, Object> producerFactory(
      KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
    Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
    DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props);

    JsonSerializer<Object> jsonSerializer = new JsonSerializer<>(objectMapper);
    jsonSerializer.setAddTypeInfo(false);
    factory.setValueSerializer(jsonSerializer);

    return factory;
  }

  @Bean
  public KafkaTemplate<String, Object> kafkaTemplate(
      ProducerFactory<String, Object> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }
}
