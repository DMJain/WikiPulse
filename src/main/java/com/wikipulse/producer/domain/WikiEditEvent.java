package com.wikipulse.producer.domain;

import java.time.Instant;

public record WikiEditEvent(
    Long id,
    String title,
    String user,
    Instant timestamp,
    String type,
    Boolean bot,
    String comment,
    Meta meta) {

  public record Meta(String domain, String stream, String uri, Instant dt) {}
}
