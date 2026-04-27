package com.reconciliation.reconciliation.service;

import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.reconciliation.rules.ReconciliationRule;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ReconciliationEngine {

    private final List<ReconciliationRule> rules;
    private final io.micrometer.core.instrument.Counter exceptionsCreated;
    private final io.micrometer.core.instrument.Timer reconciliationTimer;

    public ReconciliationEngine(List<ReconciliationRule> rules,
                                io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.rules = rules;
        this.exceptionsCreated = io.micrometer.core.instrument.Counter.builder("reconciliation.exceptions.created")
                .description("Total exceptions created by reconciliation engine")
                .register(meterRegistry);
        this.reconciliationTimer = io.micrometer.core.instrument.Timer.builder("reconciliation.run.duration")
                .description("Time taken to run all reconciliation rules")
                .register(meterRegistry);
    }

    /**
     * Runs all rules in sequence.
     * One rule failing never stops the others.
     * Spring auto-discovers all ReconciliationRule beans.
     */
    public void runAll() {
        reconciliationTimer.record(() -> {
            log.info("Starting reconciliation engine — {} rules loaded", rules.size());

            for (ReconciliationRule rule : rules) {
                try {
                    log.debug("Running rule: {}", rule.getName());
                    rule.evaluate();
                    log.debug("Completed rule: {}", rule.getName());
                } catch (Exception e) {
                    log.error("Rule {} failed: {}", rule.getName(), e.getMessage(), e);
                    // Continue to next rule — one failure must not block others
                }
            }

            log.info("Reconciliation engine completed");
        });
    }
}
