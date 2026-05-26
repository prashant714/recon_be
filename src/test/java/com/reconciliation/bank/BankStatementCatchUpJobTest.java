package com.reconciliation.bank;

import com.reconciliation.bank.entity.BankStatementEntry;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import com.reconciliation.bank.service.BankStatementMatchingService;
import com.reconciliation.common.enums.BankEntryStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.reconciliation.job.BankStatementCatchUpJob;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BankStatementCatchUpJobTest {

    private BankStatementMatchingService matchingService;
    private BankStatementEntryRepository bankEntryRepository;
    private SettlementRepository settlementRepository;
    private ExceptionRecordService exceptionRecordService;
    private BankStatementCatchUpJob job;

    @BeforeEach
    void setUp() {
        matchingService = mock(BankStatementMatchingService.class);
        bankEntryRepository = mock(BankStatementEntryRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        exceptionRecordService = mock(ExceptionRecordService.class);

        job = new BankStatementCatchUpJob(
                matchingService, bankEntryRepository, settlementRepository, exceptionRecordService);

        ReflectionTestUtils.setField(job, "unmatchedEntryGraceHours", 48);
        ReflectionTestUtils.setField(job, "overdueSettlementDays", 7);
    }

    // ─── Phase 1: retry pending entries ──────────────────────────────────────

    @Test
    void retryPending_delegatesToMatchingServiceAndCountsResult() {
        when(matchingService.rematchPending()).thenReturn(3);
        when(bankEntryRepository.findOverduePendingCredits(any())).thenReturn(List.of());
        when(settlementRepository.findSettledBeforeCutoff(any())).thenReturn(List.of());

        Map<String, Integer> result = job.run();

        assertThat(result.get("rematched")).isEqualTo(3);
        verify(matchingService).rematchPending();
    }

    // ─── Phase 2: overdue PENDING bank entries ────────────────────────────────

    @Test
    void overdueEntry_flaggedAsUnmatchedAndExceptionCreated() {
        BankStatementEntry overdue = pendingCreditEntry(1L, 48800000L, "UTR_OLD");
        overdue.setCreatedAt(OffsetDateTime.now().minusHours(72)); // 72h > 48h threshold

        when(matchingService.rematchPending()).thenReturn(0);
        when(bankEntryRepository.findOverduePendingCredits(any())).thenReturn(List.of(overdue));
        when(bankEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementRepository.findSettledBeforeCutoff(any())).thenReturn(List.of());

        Map<String, Integer> result = job.run();

        assertThat(overdue.getMatchStatus()).isEqualTo(BankEntryStatus.UNMATCHED);
        assertThat(result.get("unmatchedEntriesFlagged")).isEqualTo(1);
        verify(exceptionRecordService).createForBankEntry(
                eq(ExceptionType.UNMATCHED_BANK_CREDIT),
                eq(Severity.MEDIUM),
                eq(48800000L), eq("INR"), any(), eq("merchant_001"));
        verify(bankEntryRepository).save(overdue);
    }

    @Test
    void freshEntry_notYetOverdue_isNotFlagged() {
        BankStatementEntry fresh = pendingCreditEntry(2L, 10000000L, "UTR_NEW");
        // fresh entry is < 48h old — handled by findOverduePendingCredits query filter
        // In unit test: the repository returns nothing (simulating fresh entries not returned)

        when(matchingService.rematchPending()).thenReturn(0);
        when(bankEntryRepository.findOverduePendingCredits(any())).thenReturn(List.of()); // not returned
        when(settlementRepository.findSettledBeforeCutoff(any())).thenReturn(List.of());

        Map<String, Integer> result = job.run();

        assertThat(result.get("unmatchedEntriesFlagged")).isEqualTo(0);
        verify(bankEntryRepository, never()).save(fresh);
    }

    // ─── Phase 3: settlements with no bank credit confirmation ───────────────

    @Test
    void overdueSettlement_createsOverdueBankCreditException() {
        Settlement overdue = settlement(10L, "setl_OLD", 20000000L);

        when(matchingService.rematchPending()).thenReturn(0);
        when(bankEntryRepository.findOverduePendingCredits(any())).thenReturn(List.of());
        when(settlementRepository.findSettledBeforeCutoff(any())).thenReturn(List.of(overdue));
        when(exceptionRecordService.alreadyExistsForSettlement(ExceptionType.OVERDUE_BANK_CREDIT, 10L))
                .thenReturn(false);

        Map<String, Integer> result = job.run();

        assertThat(result.get("overdueSettlementsFlagged")).isEqualTo(1);
        verify(exceptionRecordService).createForSettlement(
                eq(ExceptionType.OVERDUE_BANK_CREDIT),
                eq(Severity.HIGH),
                eq(10L), anyLong(), isNull(), any(), any(), any());
    }

    @Test
    void alreadyFlaggedSettlement_isNotFlaggedAgain() {
        Settlement overdue = settlement(11L, "setl_FLAGGED", 20000000L);

        when(matchingService.rematchPending()).thenReturn(0);
        when(bankEntryRepository.findOverduePendingCredits(any())).thenReturn(List.of());
        when(settlementRepository.findSettledBeforeCutoff(any())).thenReturn(List.of(overdue));
        when(exceptionRecordService.alreadyExistsForSettlement(ExceptionType.OVERDUE_BANK_CREDIT, 11L))
                .thenReturn(true);

        Map<String, Integer> result = job.run();

        assertThat(result.get("overdueSettlementsFlagged")).isEqualTo(0);
        verify(exceptionRecordService, never()).createForSettlement(
                eq(ExceptionType.OVERDUE_BANK_CREDIT), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── Full run summary ─────────────────────────────────────────────────────

    @Test
    void fullRun_returnsSummaryWithAllThreePhases() {
        when(matchingService.rematchPending()).thenReturn(2);
        when(bankEntryRepository.findOverduePendingCredits(any())).thenReturn(List.of());
        when(settlementRepository.findSettledBeforeCutoff(any())).thenReturn(List.of());

        Map<String, Integer> result = job.run();

        assertThat(result).containsKeys("rematched", "unmatchedEntriesFlagged", "overdueSettlementsFlagged");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private BankStatementEntry pendingCreditEntry(Long id, Long amount, String utr) {
        BankStatementEntry e = BankStatementEntry.builder()
                .merchantId("merchant_001")
                .uploadBatchId("batch_test")
                .entryDate(LocalDate.now().minusDays(3))
                .amount(amount)
                .currency("INR")
                .creditDebit("CR")
                .utrNumber(utr)
                .narration("RAZORPAY SETTLEMENT")
                .matchStatus(BankEntryStatus.PENDING)
                .build();
        e.setId(id);
        e.setCreatedAt(OffsetDateTime.now().minusHours(72));
        e.setUpdatedAt(OffsetDateTime.now().minusHours(72));
        return e;
    }

    private Settlement settlement(Long id, String providerId, Long netAmount) {
        Settlement s = Settlement.builder()
                .provider("razorpay")
                .providerSettlementId(providerId)
                .merchantId("merchant_001")
                .grossAmount(netAmount + 100000L)
                .netAmount(netAmount)
                .totalFees(100000L)
                .currency("INR")
                .settlementStatus(SettlementStatus.SETTLED)
                .settledAt(OffsetDateTime.now().minusDays(10))
                .build();
        s.setId(id);
        return s;
    }
}
