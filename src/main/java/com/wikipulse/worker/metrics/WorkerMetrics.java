package com.wikipulse.worker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class WorkerMetrics {
    private final MeterRegistry meterRegistry;
    private final Counter editsProcessed;
    private final Counter botsDetected;
    private final Counter errorsTotal;
    private final Timer processingTimer;

    public WorkerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        this.editsProcessed = Counter.builder("wikipulse_edits_processed_total")
                .description("Total number of Wikipedia edits processed")
                .register(meterRegistry);
                
        this.botsDetected = Counter.builder("wikipulse_bots_detected_total")
                .description("Total number of bots detected")
                .register(meterRegistry);
                
        this.errorsTotal = Counter.builder("wikipulse_errors_total")
                .description("Total failed processing attempts")
                .register(meterRegistry);
                
        this.processingTimer = Timer.builder("wikipulse_processing_latency")
                .description("Latency of processing Wikipedia edits")
                .register(meterRegistry);
    }
    
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }
    
    public void stopTimer(Timer.Sample sample) {
        sample.stop(processingTimer);
    }
    
    public void incrementProcessed() {
        editsProcessed.increment();
    }
    
    public void incrementBots() {
        botsDetected.increment();
    }
    
    public void incrementError() {
        errorsTotal.increment();
    }
}