package com.wikipulse.worker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

@Entity
@Table(
    name = "processed_edits",
    indexes = {
      @Index(name = "idx_user_timestamp", columnList = "user_name, edit_timestamp"),
      @Index(name = "idx_page_title", columnList = "page_title")
    })
public class ProcessedEdit implements Persistable<Long> {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  @Column(name = "user_name", nullable = false, length = 255)
  private String userName;

  @Column(name = "page_title", nullable = false, length = 1000)
  private String pageTitle;

  @Column(name = "event_type", length = 64)
  private String eventType;

  @Column(name = "edit_comment", columnDefinition = "TEXT")
  private String editComment;

  @Column(name = "edit_timestamp", nullable = false)
  private Instant editTimestamp;

  @Column(name = "is_bot")
  private Boolean isBot;

  @Column(name = "complexity_score")
  private Integer complexityScore;

  public ProcessedEdit() {}

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUserName() {
    return userName;
  }

  public void setUserName(String userName) {
    this.userName = userName;
  }

  public String getPageTitle() {
    return pageTitle;
  }

  public void setPageTitle(String pageTitle) {
    this.pageTitle = pageTitle;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public String getEditComment() {
    return editComment;
  }

  public void setEditComment(String editComment) {
    this.editComment = editComment;
  }

  public Instant getEditTimestamp() {
    return editTimestamp;
  }

  public void setEditTimestamp(Instant editTimestamp) {
    this.editTimestamp = editTimestamp;
  }

  public Boolean getIsBot() {
    return isBot;
  }

  public void setIsBot(Boolean isBot) {
    this.isBot = isBot;
  }

  public Integer getComplexityScore() {
    return complexityScore;
  }

  public void setComplexityScore(Integer complexityScore) {
    this.complexityScore = complexityScore;
  }

  @Override
  public boolean isNew() {
    return true;
  }
}
