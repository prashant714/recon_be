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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(40)
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
        // Finds CAPTURED payments still in PENDING_SETTLEMENT after the grace window.
        // OrderMatchingService handles real-time matching at ingestion; anything still
        // sitting here has no pre-registered order and must be flagged for ops review.

        List<Transaction> candidates = transactionRepository
                .findByStatusAndReconciliationStatusAndEventOccurredAtBefore(
                    com.reconciliation.common.enums.TransactionStatus.CAPTURED,
                    ReconciliationStatus.PENDING_SETTLEMENT,
                    java.time.OffsetDateTime.now().minusMinutes(5)
                );

        for (Transaction txn : candidates) {
            if (StringUtils.hasText(txn.getProviderOrderId())) {
                continue;
            }
            String desc = "Captured payment has no order reference. Cannot reconcile automatically.";

            ExceptionRecord record = exceptionRecordService.createForTransaction(
                    ExceptionType.UNMATCHED_PAYMENT,
                    Severity.MEDIUM,
                    txn.getId(),
                    txn.getPresentmentAmount(),
                    null,
                    txn.getPresentmentCurrency(),
                    desc,
                    txn.getMerchantId());

            if (record != null) {
                txn.setReconciliationStatus(ReconciliationStatus.EXCEPTION);
                txn.setExceptionId(record.getId());
                transactionRepository.save(txn);
            }
        }
    }
}
