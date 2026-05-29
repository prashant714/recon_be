package com.reconciliation.exception_record;

import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExceptionRecordServiceTest {

    private final ExceptionRecordRepository repository = mock(ExceptionRecordRepository.class);
    private final ExceptionRecordService service = new ExceptionRecordService(repository);

    @Test
    void createsNewExceptionWhenNoActiveOneExists() {
        when(repository.existsByExceptionTypeAndTransactionIdAndStatusIn(
                ExceptionType.MISSING_CAPTURE, 10L, List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW)))
                .thenReturn(false);
        when(repository.save(any(ExceptionRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExceptionRecord record = service.createForTransaction(
                ExceptionType.MISSING_CAPTURE,
                Severity.HIGH,
                10L,
                100L,
                120L,
                "INR",
                "desc",
                "merchant_001");

        assertThat(record.getDiscrepancyAmount()).isEqualTo(20L);
        verify(repository).save(any(ExceptionRecord.class));
    }

    @Test
    void returnsNullWhenActiveExceptionAlreadyExists() {
        when(repository.existsByExceptionTypeAndTransactionIdAndStatusIn(
                ExceptionType.ORPHAN_REFUND, 12L, List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW)))
                .thenReturn(true);

        ExceptionRecord result = service.createForTransaction(
                ExceptionType.ORPHAN_REFUND,
                Severity.HIGH,
                12L,
                null,
                null,
                "INR",
                "desc",
                "merchant_001");

        assertThat(result).isNull();
        verify(repository, never()).save(any(ExceptionRecord.class));
    }

    @Test
    void orderAlertStoresNullTransactionId() {
        when(repository.save(any(ExceptionRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExceptionRecord record = service.createForOrderAlert(
                ExceptionType.MISSING_CAPTURE,
                Severity.HIGH,
                "ord_001",
                100L,
                "INR",
                "desc",
                "merchant_001");

        assertThat(record.getTransactionId()).isNull();
        verify(repository).save(argThat(saved -> saved.getTransactionId() == null));
    }

    @Test
    void detectsActiveSettlementExceptionByTypeAndSettlementId() {
        when(repository.existsByExceptionTypeAndSettlementIdAndStatusIn(
                ExceptionType.OVERDUE_BANK_CREDIT,
                99L,
                List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW)))
                .thenReturn(true);

        assertThat(service.alreadyExistsForSettlement(
                ExceptionType.OVERDUE_BANK_CREDIT, 99L)).isTrue();
    }
}
