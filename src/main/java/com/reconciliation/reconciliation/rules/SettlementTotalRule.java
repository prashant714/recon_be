package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.ReportLineMatchStatus;
import com.reconciliation.settlement.repository.SettlementReportLineRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(20)
public class SettlementTotalRule implements ReconciliationRule {

    private final com.reconciliation.settlement.repository.SettlementRepository settlementRepository;
    private final com.reconciliation.transaction.repository.TransactionRepository transactionRepository;
    private final SettlementReportLineRepository reportLineRepository;
    private final com.reconciliation.exception_record.service.ExceptionRecordService exceptionRecordService;

    @org.springframework.beans.factory.annotation.Value("${app.reconciliation.amount-tolerance-paisa:100}")
    private long tolerancePaisa;

    @Override
    public String getName() {
        return "SettlementTotalRule";
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void evaluate() {
        // Find settlements that are in SETTLED status but haven't been reconciled yet
        List<com.reconciliation.settlement.entity.Settlement> settled = settlementRepository
                .findBySettlementStatus(com.reconciliation.common.enums.SettlementStatus.SETTLED);

        for (com.reconciliation.settlement.entity.Settlement settlement : settled) {
            if (!isProviderReportReady(settlement)) {
                log.debug("Settlement {} total check skipped until provider report is fully matched",
                        settlement.getProviderSettlementId());
                continue;
            }

            Long transactionSum = transactionRepository
                    .sumNetAmountBySettlementId(settlement.getProviderSettlementId());

            if (transactionSum == null) transactionSum = 0L;

            long diff = Math.abs(settlement.getNetAmount() - transactionSum);

            if (diff > tolerancePaisa) {
                String description = String.format(
                    "Settlement %s total mismatch. Expected: %d %s, Transaction sum: %d %s, Difference: %d paisa.",
                    settlement.getProviderSettlementId(),
                    settlement.getNetAmount(), settlement.getCurrency(),
                    transactionSum, settlement.getCurrency(),
                    diff
                );

                exceptionRecordService.createForSettlement(
                    com.reconciliation.common.enums.ExceptionType.SETTLEMENT_DISCREPANCY,
                    com.reconciliation.common.enums.Severity.CRITICAL,
                    settlement.getId(),
                    settlement.getNetAmount(),
                    transactionSum,
                    settlement.getCurrency(),
                    description,
                    settlement.getMerchantId()
                );

                log.error("CRITICAL settlement discrepancy: settlementId={} diff={}",
                          settlement.getProviderSettlementId(), diff);

                // Mark settlement as discrepant
                settlement.setSettlementStatus(com.reconciliation.common.enums.SettlementStatus.DISCREPANT);
                settlementRepository.save(settlement);
            } else if (settlement.getBankCreditAmount() != null && settlement.getBankCreditDate() != null) {
                settlement.setSettlementStatus(com.reconciliation.common.enums.SettlementStatus.MATCHED_TO_BANK);
                settlementRepository.save(settlement);
                log.info("Settlement {} reconciled and matched to bank", settlement.getProviderSettlementId());
            } else {
                log.info("Settlement {} totals verified, awaiting bank credit confirmation",
                        settlement.getProviderSettlementId());
            }
        }
    }

    private boolean isProviderReportReady(com.reconciliation.settlement.entity.Settlement settlement) {
        long lineCount = reportLineRepository.countBySettlementId(settlement.getId());
        if (lineCount == 0) {
            return false;
        }
        long pending = reportLineRepository.countBySettlementIdAndMatchStatus(
                settlement.getId(), ReportLineMatchStatus.PENDING);
        if (pending > 0) {
            return false;
        }
        return settlement.getTransactionCount() == null || lineCount >= settlement.getTransactionCount();
    }
}
