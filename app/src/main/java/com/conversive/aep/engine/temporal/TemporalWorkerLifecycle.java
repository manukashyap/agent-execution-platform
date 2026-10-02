package com.conversive.aep.engine.temporal;

import io.temporal.worker.WorkerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** Starts polling only once the context is ready, so every registration made during startup is included. */
public class TemporalWorkerLifecycle {

    private final WorkerFactory factory;
    private final boolean enabled;

    public TemporalWorkerLifecycle(WorkerFactory factory, boolean enabled) {
        this.factory = factory;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled && !factory.isStarted()) {
            factory.start();
        }
    }
}
