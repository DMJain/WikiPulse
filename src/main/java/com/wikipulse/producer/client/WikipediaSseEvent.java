package com.wikipulse.producer.client;

import java.util.Map;

public record WikipediaSseEvent(Map<String, Object> rawData) {}
