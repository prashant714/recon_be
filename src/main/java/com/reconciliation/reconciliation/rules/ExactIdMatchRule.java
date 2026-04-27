package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExactIdMatchRule implements ReconciliationRule {

    private final TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Override
    public String getName() {
        return "ExactIdMatchRule";
    }

    @Override
    @Transactional
    public void evaluate() {
        // In this architecture, "ExactIdMatch" means we have a valid Order ID
        // from the provider that matches our internal system.
        // We select transactions in PENDING_SETTLEMENT state.

        List<Transaction> candidates = transactionRepository
                .findByStatusAndReconciliationStatusAndEventOccurredAtBefore(
                    com.reconciliation.common.enums.TransactionStatus.CAPTURED,
                    ReconciliationStatus.PENDING_SETTLEMENT,
                    java.time.OffsetDateTime.now().minusMinutes(5)
                );

        for (Transaction txn : candidates) {
            if (txn.getOrderId() != null && !txn.getOrderId().isBlank()) {
                // We have an order link — mark as MATCHED
                txn.setReconciliationStatus(ReconciliationStatus.MATCHED);
                txn.setMatchedAt(java.time.OffsetDateTime.now());
                transactionRepository.save(txn);
            } else {
                // No order ID — cannot reconcile automatically
                String desc = "Captured transaction has no internal Order ID reference.";
                ExceptionRecord record = exceptionRecordService.createForTransaction(
                        ExceptionType.UNMATCHED_PAYMENT,
                        com.reconciliation.common.enums.Severity.MEDIUM,
                        txn.getId(),
                        txn.getPresentmentAmount(),
                        null,
                        txn.getPresentmentCurrency(),
                        desc,
                        txn.getMerchantId()
                );

                if (record != null) {
                    txn.setReconciliationStatus(ReconciliationStatus.EXCEPTION);
                    txn.setExceptionId(record.getId());
                    transactionRepository.save(txn);
                }
            }
        }
    }
}
