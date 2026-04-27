package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.transaction.entity.Transaction;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class MissingCaptureRule implements ReconciliationRule {

    private final com.reconciliation.transaction.repository.TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;

    @org.springframework.beans.factory.annotation.Value("${app.reconciliation.missing-capture-threshold-hours:24}")
    private int thresholdHours;

    @Override
    public String getName() {
        return "MissingCaptureRule";
    }

    @Override
    @Transactional
    public void evaluate() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(thresholdHours);

        // Find payments stuck in AUTHORIZED for too long
        List<Transaction> stuck = transactionRepository
                .findByStatusAndEventOccurredAtBefore(
                    TransactionStatus.AUTHORIZED, cutoff
                );

        for (Transaction txn : stuck) {
            String desc = String.format(
                "Transaction %s stuck in AUTHORIZED for >%d hours. Likely auto-expired or missed capture.",
                txn.getProviderTransactionId(), thresholdHours
            );

            ExceptionRecord record = exceptionRecordService.createForTransaction(
                    ExceptionType.MISSING_CAPTURE,
                    Severity.HIGH,
                    txn.getId(),
                    txn.getPresentmentAmount(),
                    0L,
                    txn.getPresentmentCurrency(),
                    desc,
                    txn.getMerchantId()
            );

            if (record != null) {
                txn.setExceptionId(record.getId());
                txn.setReconciliationStatus(ReconciliationStatus.EXCEPTION);
                transactionRepository.save(txn);
            }
        }

        if (!stuck.isEmpty()) {
            log.info("MissingCaptureRule: flagged {} transactions as exceptions", stuck.size());
        }
    }
}
