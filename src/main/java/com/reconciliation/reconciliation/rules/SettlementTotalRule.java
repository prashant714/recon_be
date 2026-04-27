package com.reconciliation.reconciliation.rules;

import com.reconciliation.transaction.entity.Transaction;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementTotalRule implements ReconciliationRule {

    private final com.reconciliation.settlement.repository.SettlementRepository settlementRepository;
    private final com.reconciliation.transaction.repository.TransactionRepository transactionRepository;
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
            } else {
                // Total matches — mark as MATCHED_TO_BANK (or just leave as SETTLED if that's the end state)
                // Reference code doesn't explicitly mark it matched, but it's good practice.
                log.info("Settlement {} reconciled successfully", settlement.getProviderSettlementId());
            }
        }
    }
}
