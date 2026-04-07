package com.wikipulse.worker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "analytics_rollups",
    indexes = {
      @Index(name = "idx_rollup_time_bucket_language", columnList = "time_bucket, language"),
      @Index(
          name = "idx_rollup_time_bucket_language_namespace",
          columnList = "time_bucket, language, namespace")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_rollup_time_bucket_language_namespace",
          columnNames = {"time_bucket", "language", "namespace"})
    })
public class AnalyticsRollup {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  @Column(name = "time_bucket", nullable = false)
  private Instant timeBucket;

  @Column(name = "language", nullable = false, length = 128)
  private String language;

  @Column(name = "namespace", nullable = false, length = 128)
  private String namespace;

  @Column(name = "total_edits", nullable = false)
  private Long totalEdits;

  @Column(name = "bot_edits", nullable = false)
  private Long botEdits;

  public Long getId() {
    return id;
  }

  public Instant getTimeBucket() {
    return timeBucket;
  }

  public void setTimeBucket(Instant timeBucket) {
    this.timeBucket = timeBucket;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public Long getTotalEdits() {
    return totalEdits;
  }

  public void setTotalEdits(Long totalEdits) {
    this.totalEdits = totalEdits;
  }

  public Long getBotEdits() {
    return botEdits;
  }

  public void setBotEdits(Long botEdits) {
    this.botEdits = botEdits;
  }
}