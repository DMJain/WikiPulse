package com.wikipulse.worker.domain;

import java.time.Instant;

public record AnomalyAlert(
        String id,
        String pageTitle,
        String anomalyType,
        Long eventCount,
        Instant windowStart,
        Instant windowEnd) {}
