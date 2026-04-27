package com.reconciliation.reconciliation.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.reconciliation.audit.service.AuditService;
import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SettlementReconcilerJob {

    private final SettlementRepository settlementRepository;
    private final ExceptionRecordRepository exceptionRecordRepository;
    private final ExceptionRecordService exceptionRecordService;
    private final AuditService auditService;

    @Value("${app.reconciliation.settlement-overdue-days:7}")
    private int overdueThresholdDays;

    public SettlementReconcilerJob(SettlementRepository settlementRepository,
                                   ExceptionRecordRepository exceptionRecordRepository,
                                   ExceptionRecordService exceptionRecordService,
                                   AuditService auditService) {
        this.settlementRepository    = settlementRepository;
        this.exceptionRecordRepository = exceptionRecordRepository;
        this.exceptionRecordService  = exceptionRecordService;
        this.auditService            = auditService;
    }

    @Bean
    public RecurringTask<Void> settlementReconcilerTask() {
        return new RecurringTask<>("settlement-reconciler",
                Schedules.cron("0 0 2 * * *"),
                Void.class) {
            @Override
            public void executeRecurringly(
                    TaskInstance<Void> taskInstance,
                    ExecutionContext executionContext) {
                log.info("Settlement reconciler job starting");
                try {
                    run("scheduler", "localhost");
                } catch (Exception e) {
                    log.error("Settlement reconciler job failed", e);
                }
            }
        };
    }

    /**
     * Also callable manually via AdminController for on-demand reconciliation.
     *
     * Phase 1 — Close SETTLED settlements with no open exceptions as MATCHED_TO_BANK.
     * Phase 2 — Flag PENDING settlements older than overdueThresholdDays as SETTLEMENT_DISCREPANCY.
     */
    public Map<String, Object> run(String actor, String ipAddress) {
        int closedAsMatched    = 0;
        int remainedDiscrepant = 0;
        int overdueCount       = 0;

        List<ExceptionStatus> activeStatuses = List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW);

        // Phase 1: close out SETTLED settlements that have no outstanding exceptions
        List<Settlement> settled = settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED);
        for (Settlement s : settled) {
            boolean hasOpenException = exceptionRecordRepository
                    .existsBySettlementIdAndStatusIn(s.getId(), activeStatuses);
            if (!hasOpenException) {
                s.setSettlementStatus(SettlementStatus.MATCHED_TO_BANK);
                settlementRepository.save(s);
                closedAsMatched++;
                log.info("Settlement {} closed → MATCHED_TO_BANK", s.getProviderSettlementId());
            } else {
                remainedDiscrepant++;
                log.warn("Settlement {} has open exceptions, skipping closure", s.getProviderSettlementId());
            }
        }

        // Phase 2: flag PENDING settlements overdue for bank credit
        OffsetDateTime overdueThreshold = OffsetDateTime.now().minusDays(overdueThresholdDays);
        List<Settlement> overdue = settlementRepository
                .findBySettlementStatusAndCreatedAtBefore(SettlementStatus.PENDING, overdueThreshold);
        for (Settlement s : overdue) {
            boolean alreadyFlagged = exceptionRecordRepository
                    .existsBySettlementIdAndStatusIn(s.getId(), activeStatuses);
            if (!alreadyFlagged) {
                String desc = String.format(
                        "Settlement %s (provider=%s) has been PENDING for >%d days without bank credit.",
                        s.getProviderSettlementId(), s.getProvider(), overdueThresholdDays);
                exceptionRecordService.createForSettlement(
                        ExceptionType.SETTLEMENT_DISCREPANCY,
                        Severity.HIGH,
                        s.getId(),
                        s.getNetAmount(),
                        null,
                        s.getCurrency(),
                        desc,
                        s.getMerchantId());
                overdueCount++;
                log.warn("Settlement {} flagged as overdue", s.getProviderSettlementId());
            }
        }

        Map<String, Object> summary = Map.of(
                "closedAsMatched", closedAsMatched,
                "remainedDiscrepant", remainedDiscrepant,
                "overdueSettlementsFlagged", overdueCount);

        auditService.log(actor, "settlement_reconciler_run", "settlement", null, null, summary, ipAddress);
        log.info("Settlement reconciler complete: {}", summary);
        return summary;
    }
}
