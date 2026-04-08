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
      @Index(name = "idx_page_title", columnList = "page_title"),
      @Index(name = "idx_server_url", columnList = "server_url")
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

  @Column(name = "server_url", length = 255)
  private String serverUrl;

  @Column(name = "namespace")
  private Integer namespace;

  @Column(name = "country", length = 255)
  private String country;

  @Column(name = "city", length = 255)
  private String city;

  @Column(name = "byte_diff")
  private Integer byteDiff;

  @Column(name = "is_revert")
  private Boolean isRevert;

  @Column(name = "is_anonymous")
  private Boolean isAnonymous;

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

  public String getServerUrl() {
    return serverUrl;
  }

  public void setServerUrl(String serverUrl) {
    this.serverUrl = serverUrl;
  }

  public Integer getNamespace() {
    return namespace;
  }

  public void setNamespace(Integer namespace) {
    this.namespace = namespace;
  }

  public String getCountry() {
    return country;
  }

  public void setCountry(String country) {
    this.country = country;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public Integer getByteDiff() {
    return byteDiff;
  }

  public void setByteDiff(Integer byteDiff) {
    this.byteDiff = byteDiff;
  }

  public Boolean getIsRevert() {
    return isRevert;
  }

  public void setIsRevert(Boolean isRevert) {
    this.isRevert = isRevert;
  }

  public Boolean getIsAnonymous() {
    return isAnonymous;
  }

  public void setIsAnonymous(Boolean isAnonymous) {
    this.isAnonymous = isAnonymous;
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
