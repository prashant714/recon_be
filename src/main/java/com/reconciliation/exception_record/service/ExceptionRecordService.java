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
     * Deduplicates: skips if an OPEN/IN_REVIEW exception of the same type already exists for this settlement.
     */
    @Transactional
    public ExceptionRecord createForSettlement(
            ExceptionType type, Severity severity, Long settlementId,
            Long expected, Long actual, String currency,
            String description, String merchantId) {

        if (alreadyExistsForSettlement(type, settlementId)) {
            log.debug("Exception {} already exists for settlement {}", type, settlementId);
            return null;
        }

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

    @Transactional(readOnly = true)
    public boolean alreadyExistsForSettlement(ExceptionType exceptionType, Long settlementId) {
        if (settlementId == null) {
            return false;
        }
        return exceptionRecordRepository.existsByExceptionTypeAndSettlementIdAndStatusIn(
                exceptionType, settlementId, ACTIVE_STATUSES);
    }

    /**
     * Create an exception for an unmatched bank statement entry (no linked transaction or settlement).
     */
    @Transactional
    public ExceptionRecord createForBankEntry(
            ExceptionType type, Severity severity, Long amount,
            String currency, String description, String merchantId) {

        ExceptionRecord record = ExceptionRecord.builder()
                .merchantId(merchantId)
                .exceptionType(type)
                .severity(severity)
                .transactionId(null)
                .settlementId(null)
                .expectedAmount(null)
                .actualAmount(amount)
                .discrepancyAmount(null)
                .currency(currency)
                .description(description)
                .status(com.reconciliation.common.enums.ExceptionStatus.OPEN)
                .detectedAt(OffsetDateTime.now())
                .build();

        return exceptionRecordRepository.save(record);
    }

    /**
     * Create an alert-style exception for an order that has no linked transaction yet.
     * Deduplicates by checking for existing open exceptions with the same type, merchant, and orderId.
     */
    @Transactional
    public ExceptionRecord createForOrderAlert(
            ExceptionType type, Severity severity, String orderId,
            Long expectedAmount, String currency, String description, String merchantId) {

        if (exceptionRecordRepository.existsByExceptionTypeAndMerchantIdAndDescriptionContainingAndStatusIn(
                type, merchantId, "Order " + orderId + " ",
                List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW))) {
            log.debug("Order alert exception {} already exists for orderId {}", type, orderId);
            return null;
        }

        ExceptionRecord record = ExceptionRecord.builder()
                .merchantId(merchantId)
                .exceptionType(type)
                .severity(severity)
                .transactionId(null)
                .expectedAmount(expectedAmount)
                .actualAmount(0L)
                .discrepancyAmount(expectedAmount)
                .currency(currency)
                .description(description)
                .status(com.reconciliation.common.enums.ExceptionStatus.OPEN)
                .detectedAt(OffsetDateTime.now())
                .build();

        return exceptionRecordRepository.save(record);
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
