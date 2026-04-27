package com.reconciliation.reconciliation.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.reconciliation.reconciliation.service.ReconciliationEngine;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationJob {

    private final ReconciliationEngine engine;

    @Bean
    public RecurringTask<Void> reconciliationTask() {
        return new RecurringTask<>("reconciliation-engine",
                Schedules.fixedDelay(Duration.ofMinutes(5)),
                Void.class) {
            @Override
            public void executeRecurringly(
                    TaskInstance<Void> taskInstance,
                    ExecutionContext executionContext) {
                log.info("Reconciliation job starting");
                engine.runAll();
                log.info("Reconciliation job completed");
            }
        };
    }
}
