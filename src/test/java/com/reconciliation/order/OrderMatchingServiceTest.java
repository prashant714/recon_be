package com.reconciliation.order;

import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.order.service.OrderMatchingService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderMatchingServiceTest {

    private OrderRepository orderRepository;
    private TransactionRepository transactionRepository;
    private ExceptionRecordService exceptionRecordService;
    private OrderMatchingService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        exceptionRecordService = mock(ExceptionRecordService.class);

        service = new OrderMatchingService(orderRepository, transactionRepository, exceptionRecordService);
        ReflectionTestUtils.setField(service, "tolerancePaisa", 100L);
    }

    // ─── tryMatchByTransaction: payment arrives, order pre-registered ─────────

    @Test
    void exactAmountMatch_orderBecomesPaymentReceived_transactionBecomesMatched() {
        Order order = createdOrder("ord_001", 50000000L);
        Transaction txn = capturedPayment(1L, "ord_001", null, 50000000L);

        when(orderRepository.findByMerchantIdAndOrderId("merchant_001", "ord_001"))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.tryMatchByTransaction(txn);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_RECEIVED);
        assertThat(order.getAmountMatched()).isTrue();
        assertThat(order.getDiscrepancyAmount()).isEqualTo(0L);
        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
        verifyNoInteractions(exceptionRecordService);
        verify(orderRepository).save(order);
        verify(transactionRepository).save(txn);
    }

    @Test
    void withinTolerance_treatedAsExactMatch() {
        Order order = createdOrder("ord_002", 50000000L);
        Transaction txn = capturedPayment(2L, "ord_002", null, 50000050L); // 50 paisa over — within 100 paisa

        when(orderRepository.findByMerchantIdAndOrderId("merchant_001", "ord_002"))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.tryMatchByTransaction(txn);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_RECEIVED);
        assertThat(order.getAmountMatched()).isTrue();
        verifyNoInteractions(exceptionRecordService);
    }

    @Test
    void overpaidBeyondTolerance_orderBecomesOverpaid_exceptionCreated() {
        Order order = createdOrder("ord_003", 50000000L);
        Transaction txn = capturedPayment(3L, "ord_003", null, 51000000L); // 1000000 paisa over

        when(orderRepository.findByMerchantIdAndOrderId("merchant_001", "ord_003"))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.tryMatchByTransaction(txn);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.OVERPAID);
        assertThat(order.getAmountMatched()).isFalse();
        assertThat(order.getDiscrepancyAmount()).isEqualTo(1000000L);
        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        verify(exceptionRecordService).createForTransaction(
                eq(ExceptionType.ORDER_AMOUNT_MISMATCH), any(), eq(3L),
                eq(50000000L), eq(51000000L), any(), any(), any());
    }

    @Test
    void underpaidBeyondTolerance_orderBecomesUnderpaid_exceptionCreated() {
        Order order = createdOrder("ord_004", 50000000L);
        Transaction txn = capturedPayment(4L, "ord_004", null, 49000000L); // 1000000 paisa short

        when(orderRepository.findByMerchantIdAndOrderId("merchant_001", "ord_004"))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.tryMatchByTransaction(txn);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.UNDERPAID);
        assertThat(order.getDiscrepancyAmount()).isEqualTo(-1000000L);
        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);
        verify(exceptionRecordService).createForTransaction(
                eq(ExceptionType.ORDER_AMOUNT_MISMATCH), any(), eq(4L),
                eq(50000000L), eq(49000000L), any(), any(), any());
    }

    @Test
    void transactionWithNeitherOrderIdNorProviderOrderId_skipsLookup() {
        Transaction txn = capturedPayment(5L, null, null, 50000000L);

        service.tryMatchByTransaction(txn);

        verifyNoInteractions(orderRepository);
        verifyNoInteractions(exceptionRecordService);
    }

    @Test
    void noPrerregisteredOrder_noAction() {
        Transaction txn = capturedPayment(6L, "ord_UNKNOWN", null, 50000000L);

        when(orderRepository.findByMerchantIdAndOrderId("merchant_001", "ord_UNKNOWN"))
                .thenReturn(Optional.empty());

        service.tryMatchByTransaction(txn);

        verify(orderRepository, never()).save(any());
        verifyNoInteractions(exceptionRecordService);
    }

    @Test
    void nonCapturedTransaction_skipsEntireFlow() {
        Transaction txn = Transaction.builder()
                .id(7L)
                .merchantId("merchant_001")
                .orderId("ord_007")
                .status(TransactionStatus.FAILED)
                .eventType(EventType.PAYMENT)
                .presentmentAmount(50000000L)
                .presentmentCurrency("INR")
                .build();

        service.tryMatchByTransaction(txn);

        verifyNoInteractions(orderRepository);
    }

    // ─── tryMatchByOrder: order registered, payment already exists ───────────

    @Test
    void tryMatchByOrder_paymentAlreadyExists_matchApplied() {
        Order order = createdOrder("ord_RETRO", 20000000L);
        order.setProviderOrderId(null);
        Transaction txn = capturedPayment(8L, "ord_RETRO", null, 20000000L);

        when(transactionRepository.findFirstCapturedByMerchantIdAndOrderId("merchant_001", "ord_RETRO"))
                .thenReturn(Optional.of(txn));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.tryMatchByOrder(order);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_RECEIVED);
        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
    }

    @Test
    void tryMatchByOrder_providerOrderMatch_backfillsMerchantOrderIdOnTransaction() {
        Order order = createdOrder("ord_PROVIDER_BRIDGE", 20000000L);
        order.setProviderOrderId("order_abc123");
        Transaction txn = capturedPayment(18L, null, "order_abc123", 20000000L);

        when(transactionRepository.findByProviderAndMerchantIdAndProviderOrderId(
                "razorpay", "merchant_001", "order_abc123"))
                .thenReturn(Optional.of(txn));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.tryMatchByOrder(order);

        assertThat(txn.getOrderId()).isEqualTo("ord_PROVIDER_BRIDGE");
        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
    }

    @Test
    void tryMatchByOrder_noExistingTransaction_noAction() {
        Order order = createdOrder("ord_EARLY", 20000000L);
        order.setProviderOrderId(null);

        when(transactionRepository.findFirstCapturedByMerchantIdAndOrderId("merchant_001", "ord_EARLY"))
                .thenReturn(Optional.empty());

        service.tryMatchByOrder(order);

        verify(orderRepository, never()).save(any());
        verifyNoInteractions(exceptionRecordService);
    }

    // ─── Idempotency ─────────────────────────────────────────────────────────

    @Test
    void orderAlreadyPaymentReceived_matchIsSkipped() {
        Order order = createdOrder("ord_DONE", 50000000L);
        order.setOrderStatus(OrderStatus.PAYMENT_RECEIVED);
        Transaction txn = capturedPayment(9L, "ord_DONE", null, 50000000L);

        when(orderRepository.findByMerchantIdAndOrderId("merchant_001", "ord_DONE"))
                .thenReturn(Optional.of(order));

        service.tryMatchByTransaction(txn);

        verify(orderRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(exceptionRecordService);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Order createdOrder(String orderId, Long expectedAmount) {
        Order o = Order.builder()
                .merchantId("merchant_001")
                .orderId(orderId)
                .expectedAmount(expectedAmount)
                .currency("INR")
                .orderStatus(OrderStatus.CREATED)
                .amountMatched(false)
                .build();
        o.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        o.setUpdatedAt(OffsetDateTime.now().minusMinutes(5));
        return o;
    }

    private Transaction capturedPayment(Long id, String orderId, String providerOrderId, Long amount) {
        return Transaction.builder()
                .id(id)
                .provider("razorpay")
                .providerTransactionId("pay_" + id)
                .merchantId("merchant_001")
                .orderId(orderId)
                .providerOrderId(providerOrderId)
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(amount)
                .presentmentCurrency("INR")
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
    }
}
