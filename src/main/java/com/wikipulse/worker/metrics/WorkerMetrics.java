package com.wikipulse.worker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class WorkerMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter editsProcessedCounter;
    private final Counter botsDetectedCounter;

    public WorkerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.editsProcessedCounter = Counter.builder("wikipulse_edits_processed_total")
                .description("Total successful edits saved")
                .register(meterRegistry);
        this.botsDetectedCounter = Counter.builder("wikipulse_bots_detected_total")
                .description("Total edits flagged as is_bot")
                .register(meterRegistry);
    }

    public void incrementProcessed() {
        editsProcessedCounter.increment();
    }

    public void incrementBotsDetected() {
        botsDetectedCounter.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("wikipulse_processing_latency")
                .description("The time taken from Kafka reception to DB save")
                .register(meterRegistry));
    }
}
