package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.transaction.entity.Transaction;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateCaptureRule implements ReconciliationRule {

    private final com.reconciliation.transaction.repository.TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Override
    public String getName() {
        return "DuplicateCaptureRule";
    }

    @Override
    @Transactional
    public void evaluate() {
        // Find (merchantId, orderId) pairs that have >1 captured payments
        List<Object[]> duplicates = transactionRepository.findDuplicateCapturedOrderKeys();

        for (Object[] row : duplicates) {
            String merchantId = (String) row[0];
            String orderId    = (String) row[1];

            List<Transaction> txns = transactionRepository
                    .findCapturedPaymentsByMerchantAndOrder(merchantId, orderId);

            for (Transaction txn : txns) {
                String desc = String.format(
                    "Order %s has %d duplicate captured payments. High risk of double charge.",
                    orderId, txns.size()
                );

                ExceptionRecord record = exceptionRecordService.createForTransaction(
                        ExceptionType.DUPLICATE_CAPTURE,
                        Severity.CRITICAL,
                        txn.getId(),
                        txn.getPresentmentAmount(),
                        null, // ambiguous what expected is here
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
        }
    }
}
