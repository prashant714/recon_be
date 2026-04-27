package com.reconciliation.reconciliation;

import com.reconciliation.audit.service.AuditService;
import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.reconciliation.job.SettlementReconcilerJob;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementReconcilerJobTest {

    private SettlementRepository settlementRepository;
    private ExceptionRecordRepository exceptionRecordRepository;
    private ExceptionRecordService exceptionRecordService;
    private AuditService auditService;
    private SettlementReconcilerJob job;

    @BeforeEach
    void setUp() {
        settlementRepository    = mock(SettlementRepository.class);
        exceptionRecordRepository = mock(ExceptionRecordRepository.class);
        exceptionRecordService  = mock(ExceptionRecordService.class);
        auditService            = mock(AuditService.class);

        job = new SettlementReconcilerJob(
                settlementRepository, exceptionRecordRepository,
                exceptionRecordService, auditService);
    }

    @Test
    void settledSettlementWithNoOpenExceptionsIsClosedAsMatchedToBank() {
        Settlement s = settlement(1L, "setl_001", SettlementStatus.SETTLED);
        when(settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED)).thenReturn(List.of(s));
        when(exceptionRecordRepository.existsBySettlementIdAndStatusIn(eq(1L), anyCollection())).thenReturn(false);
        when(settlementRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of());

        Map<String, Object> result = job.run("admin", "127.0.0.1");

        assertThat(s.getSettlementStatus()).isEqualTo(SettlementStatus.MATCHED_TO_BANK);
        verify(settlementRepository).save(s);
        assertThat(result.get("closedAsMatched")).isEqualTo(1);
        assertThat(result.get("remainedDiscrepant")).isEqualTo(0);
    }

    @Test
    void settledSettlementWithOpenExceptionIsNotClosed() {
        Settlement s = settlement(2L, "setl_002", SettlementStatus.SETTLED);
        when(settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED)).thenReturn(List.of(s));
        when(exceptionRecordRepository.existsBySettlementIdAndStatusIn(eq(2L), anyCollection())).thenReturn(true);
        when(settlementRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of());

        Map<String, Object> result = job.run("admin", "127.0.0.1");

        assertThat(s.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        verify(settlementRepository, never()).save(s);
        assertThat(result.get("closedAsMatched")).isEqualTo(0);
        assertThat(result.get("remainedDiscrepant")).isEqualTo(1);
    }

    @Test
    void overdueSettlementWithNoExistingFlagCreatesException() {
        Settlement overdue = settlement(3L, "setl_003", SettlementStatus.PENDING);
        when(settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED)).thenReturn(List.of());
        when(settlementRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(overdue));
        when(exceptionRecordRepository.existsBySettlementIdAndStatusIn(eq(3L), anyCollection())).thenReturn(false);

        Map<String, Object> result = job.run("admin", "127.0.0.1");

        verify(exceptionRecordService).createForSettlement(
                eq(ExceptionType.SETTLEMENT_DISCREPANCY),
                eq(Severity.HIGH),
                eq(3L),
                anyLong(),
                isNull(),
                anyString(),
                anyString(),
                anyString());
        assertThat(result.get("overdueSettlementsFlagged")).isEqualTo(1);
    }

    @Test
    void overdueSettlementAlreadyFlaggedIsSkipped() {
        Settlement overdue = settlement(4L, "setl_004", SettlementStatus.PENDING);
        when(settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED)).thenReturn(List.of());
        when(settlementRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(overdue));
        when(exceptionRecordRepository.existsBySettlementIdAndStatusIn(eq(4L), anyCollection())).thenReturn(true);

        Map<String, Object> result = job.run("admin", "127.0.0.1");

        verify(exceptionRecordService, never()).createForSettlement(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(result.get("overdueSettlementsFlagged")).isEqualTo(0);
    }

    @Test
    void mixedBatchProcessesBothPhasesIndependently() {
        Settlement matched   = settlement(5L, "setl_005", SettlementStatus.SETTLED);
        Settlement discrepant = settlement(6L, "setl_006", SettlementStatus.SETTLED);
        Settlement overdue   = settlement(7L, "setl_007", SettlementStatus.PENDING);

        when(settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED))
                .thenReturn(List.of(matched, discrepant));
        when(exceptionRecordRepository.existsBySettlementIdAndStatusIn(eq(5L), anyCollection())).thenReturn(false);
        when(exceptionRecordRepository.existsBySettlementIdAndStatusIn(eq(6L), anyCollection())).thenReturn(true);
        when(settlementRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of(overdue));
        when(exceptionRecordRepository.existsBySettlementIdAndStatusIn(eq(7L), anyCollection())).thenReturn(false);

        Map<String, Object> result = job.run("admin", "127.0.0.1");

        assertThat(result.get("closedAsMatched")).isEqualTo(1);
        assertThat(result.get("remainedDiscrepant")).isEqualTo(1);
        assertThat(result.get("overdueSettlementsFlagged")).isEqualTo(1);
        assertThat(matched.getSettlementStatus()).isEqualTo(SettlementStatus.MATCHED_TO_BANK);
        assertThat(discrepant.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
    }

    @Test
    void runAlwaysAudits() {
        when(settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED)).thenReturn(List.of());
        when(settlementRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of());

        job.run("scheduler", "localhost");

        verify(auditService).log(
                eq("scheduler"),
                eq("settlement_reconciler_run"),
                eq("settlement"),
                isNull(),
                isNull(),
                any(),
                eq("localhost"));
    }

    @Test
    void emptyRunReturnsZeroSummary() {
        when(settlementRepository.findBySettlementStatus(SettlementStatus.SETTLED)).thenReturn(List.of());
        when(settlementRepository.findBySettlementStatusAndCreatedAtBefore(eq(SettlementStatus.PENDING), any()))
                .thenReturn(List.of());

        Map<String, Object> result = job.run("admin", "127.0.0.1");

        assertThat(result.get("closedAsMatched")).isEqualTo(0);
        assertThat(result.get("remainedDiscrepant")).isEqualTo(0);
        assertThat(result.get("overdueSettlementsFlagged")).isEqualTo(0);
    }

    private static Settlement settlement(Long id, String providerSettlementId, SettlementStatus status) {
        return Settlement.builder()
                .id(id)
                .provider("razorpay")
                .providerSettlementId(providerSettlementId)
                .merchantId("merchant_001")
                .grossAmount(100000L)
                .netAmount(98000L)
                .currency("INR")
                .settlementStatus(status)
                .createdAt(OffsetDateTime.now().minusDays(10))
                .updatedAt(OffsetDateTime.now().minusDays(10))
                .build();
    }
}
