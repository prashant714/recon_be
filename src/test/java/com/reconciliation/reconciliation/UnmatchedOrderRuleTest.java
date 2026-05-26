package com.reconciliation.reconciliation;

import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.reconciliation.rules.UnmatchedOrderRule;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnmatchedOrderRuleTest {

    private OrderRepository orderRepository;
    private TransactionRepository transactionRepository;
    private ExceptionRecordService exceptionRecordService;
    private UnmatchedOrderRule rule;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        exceptionRecordService = mock(ExceptionRecordService.class);

        rule = new UnmatchedOrderRule(orderRepository, transactionRepository, exceptionRecordService);
        ReflectionTestUtils.setField(rule, "paymentGraceMinutes", 30);
        ReflectionTestUtils.setField(rule, "orderGraceMinutes", 15);
    }

    // ─── Phase 1: orders with no payment ─────────────────────────────────────

    @Test
    void staleCreatedOrder_missingPaymentExceptionCreated() {
        Order stale = staleOrder("ord_001", 20000000L);

        when(orderRepository.findStaleCreatedOrders(any())).thenReturn(List.of(stale));
        when(transactionRepository.findCapturedWithProviderOrderIdAndNoMatchedOrder(any()))
                .thenReturn(List.of());

        rule.evaluate();

        verify(exceptionRecordService).createForOrderAlert(
                eq(ExceptionType.MISSING_PAYMENT),
                eq(Severity.HIGH),
                eq("ord_001"),
                eq(20000000L),
                eq("INR"),
                argThat(desc -> desc.contains("ord_001") && desc.contains("30")),
                eq("merchant_001"));
    }

    @Test
    void freshOrder_notReturnedByRepository_nothingFlagged() {
        // Repo query filters by cutoff — fresh orders are never returned
        when(orderRepository.findStaleCreatedOrders(any())).thenReturn(List.of());
        when(transactionRepository.findCapturedWithProviderOrderIdAndNoMatchedOrder(any()))
                .thenReturn(List.of());

        rule.evaluate();

        verify(exceptionRecordService, never()).createForOrderAlert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void multipleStaleOrders_exceptionsCreatedForEach() {
        Order order1 = staleOrder("ord_A", 10000000L);
        Order order2 = staleOrder("ord_B", 20000000L);

        when(orderRepository.findStaleCreatedOrders(any())).thenReturn(List.of(order1, order2));
        when(transactionRepository.findCapturedWithProviderOrderIdAndNoMatchedOrder(any()))
                .thenReturn(List.of());

        rule.evaluate();

        verify(exceptionRecordService, times(2)).createForOrderAlert(
                eq(ExceptionType.MISSING_PAYMENT), any(), any(), any(), any(), any(), any());
    }

    // ─── Phase 2: captured payments with no pre-registered order ─────────────

    @Test
    void capturedPaymentWithProviderOrderIdAndNoOrder_unregisteredPaymentExceptionCreated() {
        Transaction txn = capturedWithProviderOrderId(1L, "order_rzp_001", 15000000L);

        when(orderRepository.findStaleCreatedOrders(any())).thenReturn(List.of());
        when(transactionRepository.findCapturedWithProviderOrderIdAndNoMatchedOrder(any()))
                .thenReturn(List.of(txn));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        rule.evaluate();

        verify(exceptionRecordService).createForTransaction(
                eq(ExceptionType.UNREGISTERED_PAYMENT),
                eq(Severity.MEDIUM),
                eq(1L),
                isNull(),
                eq(15000000L),
                eq("INR"),
                argThat(desc -> desc.contains("order_rzp_001")),
                eq("merchant_001"));

        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        verify(transactionRepository).save(txn);
    }

    @Test
    void recentCapturedPayment_notReturnedByRepository_nothingFlagged() {
        // Grace window enforced by DB query — repo returns nothing for fresh payments
        when(orderRepository.findStaleCreatedOrders(any())).thenReturn(List.of());
        when(transactionRepository.findCapturedWithProviderOrderIdAndNoMatchedOrder(any()))
                .thenReturn(List.of());

        rule.evaluate();

        verify(exceptionRecordService, never()).createForTransaction(
                eq(ExceptionType.UNREGISTERED_PAYMENT), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── getName ─────────────────────────────────────────────────────────────

    @Test
    void getName_returnsRuleName() {
        assertThat(rule.getName()).isEqualTo("UnmatchedOrderRule");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Order staleOrder(String orderId, Long expectedAmount) {
        Order o = Order.builder()
                .merchantId("merchant_001")
                .orderId(orderId)
                .expectedAmount(expectedAmount)
                .currency("INR")
                .orderStatus(OrderStatus.CREATED)
                .amountMatched(false)
                .build();
        o.setCreatedAt(OffsetDateTime.now().minusMinutes(60));
        o.setUpdatedAt(OffsetDateTime.now().minusMinutes(60));
        return o;
    }

    private Transaction capturedWithProviderOrderId(Long id, String providerOrderId, Long amount) {
        return Transaction.builder()
                .id(id)
                .provider("razorpay")
                .providerTransactionId("pay_" + id)
                .merchantId("merchant_001")
                .providerOrderId(providerOrderId)
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(amount)
                .presentmentCurrency("INR")
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
    }
}
