package com.reconciliation.exception_record;

import com.reconciliation.audit.service.AuditService;
import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.exception_record.service.ExceptionQueryService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExceptionQueryServiceTest {

    private ExceptionRecordRepository exceptionRecordRepository;
    private TransactionRepository transactionRepository;
    private AuditService auditService;
    private ExceptionQueryService service;

    @BeforeEach
    void setUp() {
        exceptionRecordRepository = mock(ExceptionRecordRepository.class);
        transactionRepository     = mock(TransactionRepository.class);
        auditService              = mock(AuditService.class);
        service = new ExceptionQueryService(exceptionRecordRepository, transactionRepository, auditService);
    }

    // --- list() ---

    @Test
    void listReturnsAllExceptionsWhenNoFilters() {
        ExceptionRecord rec = openException(1L, "merchant_001");
        when(exceptionRecordRepository.findAll()).thenReturn(List.of(rec));

        Map<String, Object> result = service.list(7, null, null, null, 0, 50);

        assertThat((List<?>) result.get("items")).hasSize(1);
        assertThat(result.get("total")).isEqualTo(1);
    }

    @Test
    void listFiltersOutExceptionsOlderThanWindow() {
        ExceptionRecord old  = openException(1L, "merchant_001");
        old.setDetectedAt(OffsetDateTime.now().minusDays(30));
        ExceptionRecord recent = openException(2L, "merchant_001");
        recent.setDetectedAt(OffsetDateTime.now().minusDays(3));

        when(exceptionRecordRepository.findAll()).thenReturn(List.of(old, recent));

        Map<String, Object> result = service.list(7, null, null, null, 0, 50);

        assertThat((List<?>) result.get("items")).hasSize(1);
        assertThat(result.get("total")).isEqualTo(1);
    }

    @Test
    void listFiltersbyStatus() {
        ExceptionRecord open   = openException(1L, "merchant_001");
        ExceptionRecord resolved = resolvedException(2L, "merchant_001");

        when(exceptionRecordRepository.findAll()).thenReturn(List.of(open, resolved));

        Map<String, Object> result = service.list(30, null, null, "OPEN", 0, 50);

        assertThat((List<?>) result.get("items")).hasSize(1);
    }

    @Test
    void listFiltersByExceptionType() {
        ExceptionRecord dup     = exceptionWithType(1L, ExceptionType.DUPLICATE_CAPTURE);
        ExceptionRecord missing = exceptionWithType(2L, ExceptionType.MISSING_CAPTURE);

        when(exceptionRecordRepository.findAll()).thenReturn(List.of(dup, missing));

        Map<String, Object> result = service.list(30, null, "DUPLICATE_CAPTURE", null, 0, 50);

        assertThat((List<?>) result.get("items")).hasSize(1);
    }

    @Test
    void listPaginatesCorrectly() {
        List<ExceptionRecord> all = List.of(
                openException(1L, "m1"), openException(2L, "m1"),
                openException(3L, "m1"), openException(4L, "m1"),
                openException(5L, "m1"));

        when(exceptionRecordRepository.findAll()).thenReturn(all);

        Map<String, Object> page0 = service.list(30, null, null, null, 0, 2);
        Map<String, Object> page1 = service.list(30, null, null, null, 1, 2);

        assertThat((List<?>) page0.get("items")).hasSize(2);
        assertThat((List<?>) page1.get("items")).hasSize(2);
        assertThat(page0.get("total")).isEqualTo(5);
    }

    @Test
    void listSummaryCountsStatusesCorrectly() {
        ExceptionRecord open     = openException(1L, "m1");
        ExceptionRecord inReview = openException(2L, "m1");
        inReview.setStatus(ExceptionStatus.IN_REVIEW);
        ExceptionRecord resolved = resolvedException(3L, "m1");

        when(exceptionRecordRepository.findAll()).thenReturn(List.of(open, inReview, resolved));

        Map<String, Object> result = service.list(30, null, null, null, 0, 50);

        @SuppressWarnings("unchecked")
        Map<String, Long> summary = (Map<String, Long>) result.get("summary");
        assertThat(summary.get("open")).isEqualTo(1L);
        assertThat(summary.get("inReview")).isEqualTo(1L);
        assertThat(summary.get("resolved")).isEqualTo(1L);
    }

    // --- detail() ---

    @Test
    void detailReturnsExceptionWithLinkedTransaction() {
        ExceptionRecord rec = openException(10L, "merchant_001");
        rec.setTransactionId(99L);
        Transaction tx = Transaction.builder().id(99L).providerTransactionId("pay_99").build();

        when(exceptionRecordRepository.findById(10L)).thenReturn(Optional.of(rec));
        when(transactionRepository.findById(99L)).thenReturn(Optional.of(tx));
        when(auditService.search(eq("exception_record"), eq(10L), isNull())).thenReturn(List.of());

        Map<String, Object> result = service.detail(10L);

        assertThat(result.get("exception")).isEqualTo(rec);
        assertThat(result.get("transaction")).isEqualTo(tx);
    }

    @Test
    void detailThrowsWhenExceptionNotFound() {
        when(exceptionRecordRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(999L))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void detailHandlesNoLinkedTransaction() {
        ExceptionRecord rec = openException(20L, "merchant_001");

        when(exceptionRecordRepository.findById(20L)).thenReturn(Optional.of(rec));
        when(auditService.search(any(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.detail(20L);

        assertThat(result.get("transaction")).isNull();
    }

    // --- update() ---

    @Test
    void updateChangesStatusAndCreatesAuditEntry() {
        ExceptionRecord rec = openException(30L, "merchant_001");
        when(exceptionRecordRepository.findById(30L)).thenReturn(Optional.of(rec));
        when(exceptionRecordRepository.save(rec)).thenReturn(rec);

        ExceptionRecord updated = service.update(30L, "IN_REVIEW", "Under investigation", "ops", "10.0.0.1");

        assertThat(updated.getStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);
        verify(auditService).log(
                eq("ops"),
                eq("exception_status_updated"),
                eq("exception_record"),
                eq(30L),
                any(),
                eq(updated),
                eq("10.0.0.1"));
    }

    @Test
    void updateToResolvedSetsResolvedAtAndResolvedBy() {
        ExceptionRecord rec = openException(31L, "merchant_001");
        when(exceptionRecordRepository.findById(31L)).thenReturn(Optional.of(rec));
        when(exceptionRecordRepository.save(rec)).thenReturn(rec);

        service.update(31L, "RESOLVED", "Fixed manually", "admin", "127.0.0.1");

        assertThat(rec.getStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(rec.getResolvedAt()).isNotNull();
        assertThat(rec.getResolvedBy()).isEqualTo("admin");
    }

    @Test
    void updateToIgnoredSetsResolvedAt() {
        ExceptionRecord rec = openException(32L, "merchant_001");
        when(exceptionRecordRepository.findById(32L)).thenReturn(Optional.of(rec));
        when(exceptionRecordRepository.save(rec)).thenReturn(rec);

        service.update(32L, "IGNORED", "Not actionable", "admin", "127.0.0.1");

        assertThat(rec.getStatus()).isEqualTo(ExceptionStatus.IGNORED);
        assertThat(rec.getResolvedAt()).isNotNull();
        assertThat(rec.getResolvedBy()).isEqualTo("admin");
    }

    @Test
    void updateThrowsForUnknownException() {
        when(exceptionRecordRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(999L, "RESOLVED", null, "admin", "127.0.0.1"))
                .isInstanceOf(RuntimeException.class);
    }

    // --- helpers ---

    private static ExceptionRecord openException(Long id, String merchantId) {
        return ExceptionRecord.builder()
                .id(id)
                .merchantId(merchantId)
                .exceptionType(ExceptionType.UNMATCHED_PAYMENT)
                .severity(Severity.MEDIUM)
                .status(ExceptionStatus.OPEN)
                .description("test exception")
                .detectedAt(OffsetDateTime.now().minusDays(1))
                .updatedAt(OffsetDateTime.now().minusDays(1))
                .build();
    }

    private static ExceptionRecord resolvedException(Long id, String merchantId) {
        ExceptionRecord rec = openException(id, merchantId);
        rec.setStatus(ExceptionStatus.RESOLVED);
        rec.setResolvedAt(OffsetDateTime.now());
        return rec;
    }

    private static ExceptionRecord exceptionWithType(Long id, ExceptionType type) {
        ExceptionRecord rec = openException(id, "merchant_001");
        rec.setExceptionType(type);
        return rec;
    }
}
