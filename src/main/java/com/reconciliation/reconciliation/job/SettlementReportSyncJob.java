package com.reconciliation.reconciliation.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.service.ProviderConnectionService;
import com.reconciliation.polling.service.RazorpayPollingService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.entity.SettlementReportLine;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.settlement.repository.SettlementReportLineRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SettlementReportSyncJob {

    private final SettlementRepository settlementRepository;
    private final SettlementReportLineRepository reportLineRepository;
    private final RazorpayPollingService razorpayPollingService;
    private final ProviderConnectionService providerConnectionService;

    public SettlementReportSyncJob(SettlementRepository settlementRepository,
                                   SettlementReportLineRepository reportLineRepository,
                                   RazorpayPollingService razorpayPollingService,
                                   ProviderConnectionService providerConnectionService) {
        this.settlementRepository = settlementRepository;
        this.reportLineRepository = reportLineRepository;
        this.razorpayPollingService = razorpayPollingService;
        this.providerConnectionService = providerConnectionService;
    }

    @Bean
    public RecurringTask<Void> settlementReportSyncTask() {
        return new RecurringTask<>("settlement-report-sync",
                Schedules.fixedDelay(Duration.ofHours(2)),
                Void.class) {
            @Override
            public void executeRecurringly(TaskInstance<Void> taskInstance, ExecutionContext ctx) {
                log.info("Settlement report sync job starting");
                try {
                    sync();
                } catch (Exception e) {
                    log.error("Settlement report sync job failed", e);
                }
            }
        };
    }

    public void sync() {
        List<Settlement> settled = settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED);
        List<Settlement> discrepant = settlementRepository.findBySettlementStatus(SettlementStatus.DISCREPANT);

        int totalSynced = 0;
        for (Settlement s : concat(settled, discrepant)) {
            if (!"razorpay".equalsIgnoreCase(s.getProvider())) continue;

            long existingLineCount = reportLineRepository
                    .countBySettlementIdAndMatchStatus(s.getId(),
                            com.reconciliation.common.enums.ReportLineMatchStatus.MATCHED)
                    + reportLineRepository.countBySettlementIdAndMatchStatus(s.getId(),
                            com.reconciliation.common.enums.ReportLineMatchStatus.PENDING);

            if (s.getTransactionCount() != null && existingLineCount >= s.getTransactionCount()) {
                log.debug("Settlement {} already synced ({} lines)", s.getProviderSettlementId(), existingLineCount);
                continue;
            }

            Optional<ProviderConnection> connOpt = providerConnectionService
                    .findActiveConnection(s.getMerchantId(), "razorpay");
            if (connOpt.isEmpty()) {
                log.warn("No active Razorpay connection for merchant={} — skipping settlement {} report sync",
                        s.getMerchantId(), s.getProviderSettlementId());
                continue;
            }

            ProviderConnection conn = connOpt.get();
            String keyId = providerConnectionService.decryptApiKey(conn);
            String keySecret = providerConnectionService.decryptSecret(conn);

            List<Map<String, Object>> reportLines =
                    razorpayPollingService.fetchPaymentsBySettlementId(keyId, keySecret, s.getProviderSettlementId());

            int added = 0;
            for (Map<String, Object> line : reportLines) {
                String providerTxnId = (String) line.get("providerTxnId");
                if (providerTxnId == null) continue;

                boolean exists = reportLineRepository.existsBySettlementIdAndProviderTxnId(
                        s.getId(), providerTxnId);
                if (exists) continue;

                reportLineRepository.save(SettlementReportLine.builder()
                        .settlementId(s.getId())
                        .provider(s.getProvider())
                        .providerTxnId(providerTxnId)
                        .entityType((String) line.get("entityType"))
                        .grossAmount(toLong(line.get("grossAmount")))
                        .feeAmount(toLong(line.get("feeAmount")))
                        .netAmount(toLong(line.get("netAmount")))
                        .currency((String) line.get("currency"))
                        .build());
                added++;
            }

            if (added > 0) {
                log.info("Settlement {} synced {} new report lines", s.getProviderSettlementId(), added);
                totalSynced += added;
            }
        }
        log.info("Settlement report sync complete — {} total lines added", totalSynced);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> concat(List<T> a, List<T> b) {
        List<T> result = new java.util.ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }
}
