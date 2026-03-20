package com.wikipulse.producer.model;

import java.time.Instant;

public record WikiEditEvent(
    Long id, String user, String title, String comment, Instant timestamp) {}
