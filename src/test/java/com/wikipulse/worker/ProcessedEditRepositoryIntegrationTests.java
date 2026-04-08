package com.wikipulse.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wikipulse.worker.domain.ProcessedEdit;
import com.wikipulse.worker.domain.ProcessedEditRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ProcessedEditRepositoryIntegrationTests {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
  }

  @Autowired private ProcessedEditRepository repository;

  @Test
  void shouldSaveValidEvent() {
    ProcessedEdit entity = new ProcessedEdit();
    entity.setId(12345L);
    entity.setUserName("TestUser");
    entity.setPageTitle("Test_Page");
    entity.setEditComment("Fixed a typo");
    entity.setCountry("Unknown");
    entity.setCity("Unknown");
    entity.setByteDiff(0);
    entity.setIsRevert(false);
    entity.setIsAnonymous(false);
    entity.setEditTimestamp(Instant.now());

    ProcessedEdit saved = repository.saveAndFlush(entity);

    assertThat(saved).isNotNull();
    assertThat(saved.getId()).isEqualTo(12345L);

    ProcessedEdit fetched = repository.findById(12345L).orElseThrow();
    assertThat(fetched.getUserName()).isEqualTo("TestUser");
    assertThat(fetched.getPageTitle()).isEqualTo("Test_Page");
  }

  @Test
  void shouldThrowExceptionOnDuplicateId() {
    ProcessedEdit entity1 = new ProcessedEdit();
    entity1.setId(9999L);
    entity1.setUserName("FirstUser");
    entity1.setPageTitle("Some_Page");
    entity1.setCountry("Unknown");
    entity1.setCity("Unknown");
    entity1.setByteDiff(0);
    entity1.setIsRevert(false);
    entity1.setIsAnonymous(false);
    entity1.setEditTimestamp(Instant.now());

    repository.saveAndFlush(entity1);

    ProcessedEdit entity2 = new ProcessedEdit();
    entity2.setId(9999L); // Intentionally duplicate ID
    entity2.setUserName("SecondUser");
    entity2.setPageTitle("Another_Page");
    entity2.setCountry("Unknown");
    entity2.setCity("Unknown");
    entity2.setByteDiff(0);
    entity2.setIsRevert(false);
    entity2.setIsAnonymous(false);
    entity2.setEditTimestamp(Instant.now());

    assertThrows(
        DataIntegrityViolationException.class,
        () -> {
          repository.saveAndFlush(entity2);
        });
  }
}
