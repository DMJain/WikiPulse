package com.wikipulse.worker.bootstrap;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "wikipulse.smoke-test", name = "enabled", havingValue = "true")
public class InfrastructureSmokeTestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureSmokeTestRunner.class);
    private static final String REQUIRED_TOPIC = "wiki-edits";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaProperties kafkaProperties;

    public InfrastructureSmokeTestRunner(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            RedisTemplate<String, String> redisTemplate,
            KafkaProperties kafkaProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[SMOKE][START] Running in-cluster dependency smoke checks (Postgres, Redis, Kafka)");

        checkPostgres();
        checkRedis();
        checkKafka();

        log.info("[SMOKE][OVERALL][PASS] All dependency checks passed");
    }

    private void checkPostgres() {
        try {
            Integer queryResult = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (queryResult == null || queryResult != 1) {
                throw new IllegalStateException("SELECT 1 returned unexpected value: " + queryResult);
            }

            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                log.info(
                        "[SMOKE][POSTGRES][PASS] product={} url={} user={} driver={}",
                        metaData.getDatabaseProductName(),
                        metaData.getURL(),
                        metaData.getUserName(),
                        metaData.getDriverName());
            }
        } catch (Exception ex) {
            log.error("[SMOKE][POSTGRES][FAIL] PostgreSQL smoke check failed", ex);
            throw new IllegalStateException("PostgreSQL smoke check failed", ex);
        }
    }

    private void checkRedis() {
        String key = "wikipulse:smoke:" + UUID.randomUUID();
        String expectedValue = "ok";

        try {
            Boolean created = redisTemplate.opsForValue().setIfAbsent(key, expectedValue, Duration.ofSeconds(60));
            if (!Boolean.TRUE.equals(created)) {
                throw new IllegalStateException("SETNX did not create smoke key: " + key);
            }

            String value = redisTemplate.opsForValue().get(key);
            if (!expectedValue.equals(value)) {
                throw new IllegalStateException("Redis read-back mismatch. expected=" + expectedValue + " actual=" + value);
            }

            redisTemplate.delete(key);
            log.info("[SMOKE][REDIS][PASS] SETNX + read-back succeeded key={}", key);
        } catch (Exception ex) {
            log.error("[SMOKE][REDIS][FAIL] Redis smoke check failed", ex);
            throw new IllegalStateException("Redis smoke check failed", ex);
        }
    }

    private void checkKafka() {
        Map<String, Object> adminProps = new HashMap<>(kafkaProperties.buildAdminProperties(null));
        if (!adminProps.containsKey(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        }

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Set<String> topics = adminClient
                    .listTopics(new ListTopicsOptions().listInternal(false))
                    .names()
                    .get(20, TimeUnit.SECONDS);

            if (!topics.contains(REQUIRED_TOPIC)) {
                throw new IllegalStateException("Required topic not found: " + REQUIRED_TOPIC);
            }

            log.info(
                    "[SMOKE][KAFKA][PASS] bootstrapServers={} requiredTopic={} topics={}",
                    adminProps.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG),
                    REQUIRED_TOPIC,
                    new TreeSet<>(topics));
        } catch (Exception ex) {
            log.error("[SMOKE][KAFKA][FAIL] Kafka smoke check failed", ex);
            throw new IllegalStateException("Kafka smoke check failed", ex);
        }
    }
}
