package com.wikipulse.worker.api.dto;

public record EditBehaviorDto(Long totalEdits, Double revertRatePct, Double avgAbsoluteByteDiff) {}
