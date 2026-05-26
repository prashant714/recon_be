package com.reconciliation.reconciliation;

import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.reconciliation.rules.DuplicateCaptureRule;
import com.reconciliation.reconciliation.rules.ExactIdMatchRule;
import com.reconciliation.reconciliation.rules.MissingCaptureRule;
import com.reconciliation.reconciliation.rules.OrphanRefundRule;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.transaction.service.TransactionService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleBehaviorTest {

    @Test
    void exactIdMatchFlagsMissingOrderId() {
        TransactionRepository repository = mock(TransactionRepository.class);
        ExceptionRecordService exceptionRecordService = mock(ExceptionRecordService.class);
        ExactIdMatchRule rule = new ExactIdMatchRule(repository, exceptionRecordService);

        Transaction tx = payment(1L, null);
        when(repository.findByStatusAndReconciliationStatusAndEventOccurredAtBefore(
                eq(TransactionStatus.CAPTURED),
                eq(ReconciliationStatus.PENDING_SETTLEMENT),
                any()))
                .thenReturn(List.of(tx));
        when(exceptionRecordService.createForTransaction(any(), any(), eq(1L), any(), any(), any(), any(), any()))
                .thenReturn(ExceptionRecord.builder().id(77L).build());

        rule.evaluate();

        assertThat(tx.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        assertThat(tx.getExceptionId()).isEqualTo(77L);
        verify(repository).save(tx);
    }

    @Test
    void exactIdMatchLeavesProviderOrderPaymentsForUnmatchedOrderRule() {
        TransactionRepository repository = mock(TransactionRepository.class);
        ExceptionRecordService exceptionRecordService = mock(ExceptionRecordService.class);
        ExactIdMatchRule rule = new ExactIdMatchRule(repository, exceptionRecordService);

        Transaction tx = payment(11L, null);
        tx.setProviderOrderId("order_abc123");
        when(repository.findByStatusAndReconciliationStatusAndEventOccurredAtBefore(
                eq(TransactionStatus.CAPTURED),
                eq(ReconciliationStatus.PENDING_SETTLEMENT),
                any()))
                .thenReturn(List.of(tx));

        rule.evaluate();

        assertThat(tx.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING_SETTLEMENT);
        verify(exceptionRecordService, never()).createForTransaction(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).save(tx);
    }

    @Test
    void missingCaptureCreatesException() {
        TransactionRepository repository = mock(TransactionRepository.class);
        ExceptionRecordService exceptionRecordService = mock(ExceptionRecordService.class);
        MissingCaptureRule rule = new MissingCaptureRule(repository, exceptionRecordService);

        Transaction tx = Transaction.builder()
                .id(2L)
                .providerTransactionId("pay_2")
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.AUTHORIZED)
                .presentmentAmount(1000L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.now().minusDays(2))
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
        when(repository.findByStatusAndEventOccurredAtBefore(eq(TransactionStatus.AUTHORIZED), any()))
                .thenReturn(List.of(tx));
        when(exceptionRecordService.createForTransaction(any(), any(), eq(2L), any(), any(), any(), any(), any()))
                .thenReturn(ExceptionRecord.builder().id(88L).build());

        rule.evaluate();

        assertThat(tx.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        assertThat(tx.getExceptionId()).isEqualTo(88L);
    }

    @Test
    void orphanRefundCreatesExceptionWhenParentMissing() {
        TransactionRepository repository = mock(TransactionRepository.class);
        TransactionService transactionService = mock(TransactionService.class);
        ExceptionRecordService exceptionRecordService = mock(ExceptionRecordService.class);
        OrphanRefundRule rule = new OrphanRefundRule(repository, transactionService, exceptionRecordService);

        Transaction refund = Transaction.builder()
                .id(3L)
                .merchantId("merchant_001")
                .providerTransactionId("rfnd_3")
                .eventType(EventType.REFUND)
                .status(TransactionStatus.REFUNDED)
                .presentmentAmount(200L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.now().minusMinutes(30))
                .build();

        when(repository.findOrphanRefunds(any())).thenReturn(List.of(refund));
        when(exceptionRecordService.createForTransaction(any(), any(), eq(3L), any(), any(), any(), any(), any()))
                .thenReturn(ExceptionRecord.builder().id(44L).build());

        rule.evaluate();

        assertThat(refund.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        assertThat(refund.getExceptionId()).isEqualTo(44L);
        verify(repository).save(refund);
    }

    @Test
    void duplicateCaptureFlagsAllTransactions() {
        TransactionRepository repository = mock(TransactionRepository.class);
        ExceptionRecordService exceptionRecordService = mock(ExceptionRecordService.class);
        DuplicateCaptureRule rule = new DuplicateCaptureRule(repository, exceptionRecordService);

        Transaction one = payment(4L, "order_123");
        Transaction two = payment(5L, "order_123");
        when(repository.findDuplicateCapturedOrderKeys())
                .thenReturn(java.util.Collections.singletonList(new Object[]{"merchant_001", "order_123"}));
        when(repository.findCapturedPaymentsByMerchantAndOrder("merchant_001", "order_123"))
                .thenReturn(List.of(one, two));
        when(exceptionRecordService.createForTransaction(any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(ExceptionRecord.builder().id(100L).build());

        rule.evaluate();

        assertThat(one.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        assertThat(two.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        verify(repository).save(one);
        verify(repository).save(two);
    }

    private static Transaction payment(Long id, String orderId) {
        return Transaction.builder()
                .id(id)
                .provider("RAZORPAY")
                .providerTransactionId("pay_" + id)
                .merchantId("merchant_001")
                .orderId(orderId)
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(1000L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.now().minusMinutes(10))
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();
    }
}
