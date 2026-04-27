package com.reconciliation.exception_record.service;

import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExceptionRecordService {

    private final ExceptionRecordRepository exceptionRecordRepository;
    private static final List<ExceptionStatus> ACTIVE_STATUSES =
            List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW);

    /**
     * Create an exception record for a specific transaction.
     * Checks for existing OPEN exceptions for the same (transactionId, type)
     * to avoid double-reporting during repeated engine runs.
     */
    @Transactional
    public ExceptionRecord createForTransaction(
            ExceptionType type, Severity severity, Long transactionId,
            Long expected, Long actual, String currency,
            String description, String merchantId) {

        if (alreadyHasOpenException(transactionId, type)) {
            log.debug("Exception {} already exists for transaction {}", type, transactionId);
            return null;
        }

        Long discrepancy = (expected != null && actual != null) ? (actual - expected) : null;

        ExceptionRecord record = ExceptionRecord.builder()
                .merchantId(merchantId)
                .exceptionType(type)
                .severity(severity)
                .transactionId(transactionId)
                .expectedAmount(expected)
                .actualAmount(actual)
                .discrepancyAmount(discrepancy)
                .currency(currency)
                .description(description)
                .status(com.reconciliation.common.enums.ExceptionStatus.OPEN)
                .detectedAt(OffsetDateTime.now())
                .build();

        return exceptionRecordRepository.save(record);
    }

    /**
     * Create an exception record for a settlement.
     */
    @Transactional
    public ExceptionRecord createForSettlement(
            ExceptionType type, Severity severity, Long settlementId,
            Long expected, Long actual, String currency,
            String description, String merchantId) {

        Long discrepancy = (expected != null && actual != null) ? (actual - expected) : null;

        ExceptionRecord record = ExceptionRecord.builder()
                .merchantId(merchantId)
                .exceptionType(type)
                .severity(severity)
                .settlementId(settlementId)
                .expectedAmount(expected)
                .actualAmount(actual)
                .discrepancyAmount(discrepancy)
                .currency(currency)
                .description(description)
                .status(com.reconciliation.common.enums.ExceptionStatus.OPEN)
                .detectedAt(OffsetDateTime.now())
                .build();

        return exceptionRecordRepository.save(record);
    }

    private boolean alreadyHasOpenException(Long transactionId, ExceptionType type) {
        if (transactionId == null) return false;
        return exceptionRecordRepository
            .existsByExceptionTypeAndTransactionIdAndStatusIn(
                type, transactionId, List.of(
                    com.reconciliation.common.enums.ExceptionStatus.OPEN,
                    com.reconciliation.common.enums.ExceptionStatus.IN_REVIEW
                )
            );
    }

    @Transactional(readOnly = true)
    public boolean alreadyExists(ExceptionType exceptionType, Long transactionId) {
        if (transactionId == null) {
            return false;
        }
        return exceptionRecordRepository.existsByExceptionTypeAndTransactionIdAndStatusIn(
                exceptionType, transactionId, ACTIVE_STATUSES);
    }

    @Transactional
    public void closeException(Long exceptionId) {
        exceptionRecordRepository.findById(exceptionId).ifPresent(record -> {
            record.setStatus(ExceptionStatus.RESOLVED);
            record.setResolvedAt(OffsetDateTime.now());
            exceptionRecordRepository.save(record);
        });
    }
}
