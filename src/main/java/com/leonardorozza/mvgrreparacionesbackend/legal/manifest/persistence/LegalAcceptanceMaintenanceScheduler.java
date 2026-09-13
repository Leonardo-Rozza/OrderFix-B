package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Optional scheduler in the explicitly opened maintenance context; never a web component. */
final class LegalAcceptanceMaintenanceScheduler {
    enum Outcome { NOT_RUN, BATCH_COMPLETED, WORK_REMAINS, RETRY_REQUIRED, RECONCILIATION_REQUIRED }
    private static final Logger LOG=LoggerFactory.getLogger(LegalAcceptanceMaintenanceScheduler.class);
    private final LegalAcceptanceRetentionService service;
    private volatile Outcome outcome=Outcome.NOT_RUN;
    LegalAcceptanceMaintenanceScheduler(LegalAcceptanceRetentionService service) { this.service=Objects.requireNonNull(service); }
    Outcome lastOutcome() { return outcome; }
    @Scheduled(fixedDelay=60_000,initialDelay=60_000)
    void run() {
        try {
            var batch=service.runNext();
            outcome=batch.pending() ? Outcome.WORK_REMAINS : Outcome.BATCH_COMPLETED;
            LOG.info("Mantenimiento legal observado: {}",outcome);
        }
        catch (LegalAcceptanceMaintenanceException failure) {
            outcome=failure.code()==LegalAcceptanceMaintenanceException.Code.UNKNOWN
                    ? Outcome.RECONCILIATION_REQUIRED : Outcome.RETRY_REQUIRED;
            LOG.warn("Mantenimiento legal pendiente: {}",outcome);
        } catch (RuntimeException failure) {
            outcome=Outcome.RETRY_REQUIRED;
            LOG.warn("Mantenimiento legal pendiente: {}",outcome);
        }
    }
}
