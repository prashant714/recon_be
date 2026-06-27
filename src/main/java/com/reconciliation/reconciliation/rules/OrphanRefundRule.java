package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.service.TransactionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrphanRefundRule implements ReconciliationRule {

    private final com.reconciliation.transaction.repository.TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final ExceptionRecordService exceptionRecordService;

    @Override
    public String getName() {
        return "OrphanRefundRule";
    }

    @Override
    @Transactional
    public void evaluate() {
        java.time.OffsetDateTime cutoff = java.time.OffsetDateTime.now().minusMinutes(10);

        for (String merchantId : transactionRepository.findMerchantIdsWithOrphanRefunds()) {
            List<Transaction> orphans = transactionRepository.findOrphanRefunds(merchantId, cutoff);

            for (Transaction refund : orphans) {
                String desc = String.format(
                    "Refund %s could not be linked to any parent payment transaction.",
                    refund.getProviderTransactionId()
                );

                ExceptionRecord record = exceptionRecordService.createForTransaction(
                        ExceptionType.ORPHAN_REFUND,
                        Severity.HIGH,
                        refund.getId(),
                        refund.getPresentmentAmount(),
                        null,
                        refund.getPresentmentCurrency(),
                        desc,
                        refund.getMerchantId()
                );

                if (record != null) {
                    refund.setExceptionId(record.getId());
                    refund.setReconciliationStatus(ReconciliationStatus.EXCEPTION);
                    transactionRepository.save(refund);
                }
            }
        }
    }
}
