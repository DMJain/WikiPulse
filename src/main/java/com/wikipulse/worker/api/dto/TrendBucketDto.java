package com.wikipulse.worker.api.dto;

import java.time.Instant;

public record TrendBucketDto(Instant timeBucket, Long totalEdits, Long botEdits) {}
