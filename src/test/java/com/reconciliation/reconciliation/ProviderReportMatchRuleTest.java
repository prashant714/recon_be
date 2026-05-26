package com.reconciliation.reconciliation;

import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.ReportLineMatchStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.reconciliation.rules.ProviderReportMatchRule;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.entity.SettlementReportLine;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.settlement.repository.SettlementReportLineRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderReportMatchRuleTest {

    private SettlementReportLineRepository reportLineRepository;
    private TransactionRepository transactionRepository;
    private SettlementRepository settlementRepository;
    private ExceptionRecordService exceptionRecordService;
    private ProviderReportMatchRule rule;

    @BeforeEach
    void setUp() {
        reportLineRepository = mock(SettlementReportLineRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        exceptionRecordService = mock(ExceptionRecordService.class);

        rule = new ProviderReportMatchRule(
                reportLineRepository, transactionRepository, settlementRepository, exceptionRecordService);
        ReflectionTestUtils.setField(rule, "tolerancePaisa", 100L);
    }

    // ─── No pending lines ─────────────────────────────────────────────────────

    @Test
    void noPendingLines_evaluateIsNoOp() {
        when(reportLineRepository.findByMatchStatus(ReportLineMatchStatus.PENDING))
                .thenReturn(List.of());

        rule.evaluate();

        verifyNoInteractions(transactionRepository, settlementRepository, exceptionRecordService);
    }

    // ─── Transaction not in our DB ────────────────────────────────────────────

    @Test
    void pendingLine_txnNotInDb_markedNotFoundAndExceptionCreated() {
        SettlementReportLine line = reportLine(1L, "razorpay", "pay_GHOST", 50000000L, 500000L, 49500000L);
        Settlement settlement = settlement(1L, "setl_001");

        when(reportLineRepository.findByMatchStatus(ReportLineMatchStatus.PENDING))
                .thenReturn(List.of(line));
        when(transactionRepository.findByProviderAndProviderTransactionId("razorpay", "pay_GHOST"))
                .thenReturn(Optional.empty());
        when(settlementRepository.findById(1L)).thenReturn(Optional.of(settlement));
        when(reportLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        rule.evaluate();

        assertThat(line.getMatchStatus()).isEqualTo(ReportLineMatchStatus.NOT_FOUND_IN_DB);
        verify(exceptionRecordService).createForSettlement(
                eq(ExceptionType.PROVIDER_REPORT_MISMATCH),
                eq(Severity.HIGH),
                eq(1L), anyLong(), anyLong(), any(), any(), any());
    }

    // ─── Amount matches ───────────────────────────────────────────────────────

    @Test
    void pendingLine_amountMatchesWithinTolerance_markedMatched_txnSetMatched() {
        SettlementReportLine line = reportLine(2L, "razorpay", "pay_001", 50000000L, 500000L, 49500000L);
        Transaction txn = capturedTxn(10L, "razorpay", "pay_001", 49500050L); // 50 paisa diff — within 100
        Settlement settlement = settlement(2L, "setl_002");

        when(reportLineRepository.findByMatchStatus(ReportLineMatchStatus.PENDING))
                .thenReturn(List.of(line));
        when(transactionRepository.findByProviderAndProviderTransactionId("razorpay", "pay_001"))
                .thenReturn(Optional.of(txn));
        when(settlementRepository.findById(2L)).thenReturn(Optional.of(settlement));
        when(reportLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        rule.evaluate();

        assertThat(line.getMatchStatus()).isEqualTo(ReportLineMatchStatus.MATCHED);
        assertThat(line.getMatchedToTxnId()).isEqualTo(10L);
        assertThat(txn.getSettlementId()).isEqualTo("setl_002");
        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
        verifyNoInteractions(exceptionRecordService);
    }

    @Test
    void pendingLine_txnAlreadyMatched_lineMarkedMatchedWithoutDoubleWrite() {
        SettlementReportLine line = reportLine(3L, "razorpay", "pay_002", 10000000L, 100000L, 9900000L);
        Transaction txn = capturedTxn(11L, "razorpay", "pay_002", 9900000L);
        txn.setReconciliationStatus(ReconciliationStatus.MATCHED);
        txn.setSettlementId("setl_003");
        Settlement settlement = settlement(3L, "setl_003");

        when(reportLineRepository.findByMatchStatus(ReportLineMatchStatus.PENDING))
                .thenReturn(List.of(line));
        when(transactionRepository.findByProviderAndProviderTransactionId("razorpay", "pay_002"))
                .thenReturn(Optional.of(txn));
        when(settlementRepository.findById(3L)).thenReturn(Optional.of(settlement));
        when(reportLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        rule.evaluate();

        assertThat(line.getMatchStatus()).isEqualTo(ReportLineMatchStatus.MATCHED);
        // txn already MATCHED — save not called again
        verify(transactionRepository, never()).save(any());
    }

    // ─── Amount mismatch ─────────────────────────────────────────────────────

    @Test
    void pendingLine_amountMismatchBeyondTolerance_markedAmountMismatch_exceptionCreated() {
        SettlementReportLine line = reportLine(4L, "razorpay", "pay_003", 50000000L, 500000L, 49500000L);
        Transaction txn = capturedTxn(12L, "razorpay", "pay_003", 48000000L); // 1,500,000 paisa short
        Settlement settlement = settlement(4L, "setl_004");

        when(reportLineRepository.findByMatchStatus(ReportLineMatchStatus.PENDING))
                .thenReturn(List.of(line));
        when(transactionRepository.findByProviderAndProviderTransactionId("razorpay", "pay_003"))
                .thenReturn(Optional.of(txn));
        when(settlementRepository.findById(4L)).thenReturn(Optional.of(settlement));
        when(reportLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        rule.evaluate();

        assertThat(line.getMatchStatus()).isEqualTo(ReportLineMatchStatus.AMOUNT_MISMATCH);
        assertThat(line.getMatchedToTxnId()).isEqualTo(12L);
        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        verify(exceptionRecordService).createForTransaction(
                eq(ExceptionType.PROVIDER_REPORT_MISMATCH),
                eq(Severity.HIGH),
                eq(12L), anyLong(), anyLong(), any(), any(), any());
    }

    // ─── getName ─────────────────────────────────────────────────────────────

    @Test
    void getName_returnsRuleName() {
        assertThat(rule.getName()).isEqualTo("ProviderReportMatchRule");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private SettlementReportLine reportLine(Long settlementId, String provider, String providerTxnId,
                                            Long gross, Long fee, Long net) {
        return SettlementReportLine.builder()
                .settlementId(settlementId)
                .provider(provider)
                .providerTxnId(providerTxnId)
                .entityType("payment")
                .grossAmount(gross)
                .feeAmount(fee)
                .netAmount(net)
                .currency("INR")
                .matchStatus(ReportLineMatchStatus.PENDING)
                .build();
    }

    private Transaction capturedTxn(Long id, String provider, String providerTxnId, Long netAmount) {
        return Transaction.builder()
                .id(id)
                .provider(provider)
                .providerTransactionId(providerTxnId)
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(netAmount + 500000L)
                .presentmentCurrency("INR")
                .netAmount(netAmount)
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
    }

    private Settlement settlement(Long id, String providerSettlementId) {
        Settlement s = Settlement.builder()
                .provider("razorpay")
                .providerSettlementId(providerSettlementId)
                .merchantId("merchant_001")
                .grossAmount(50000000L)
                .netAmount(49500000L)
                .totalFees(500000L)
                .currency("INR")
                .build();
        s.setId(id);
        return s;
    }
}
