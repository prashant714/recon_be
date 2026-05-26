package com.reconciliation.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.order.entity.Order;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.order.service.OrderMatchingService;
import com.reconciliation.paymentflow.service.PaymentFlowEventService;
import com.reconciliation.settlement.service.SettlementService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.transaction.service.NormalizationService;
import com.reconciliation.transaction.service.TransactionService;
import com.reconciliation.transaction.service.TransactionUpsertResult;
import com.reconciliation.user.service.UserIdentityService;
import com.reconciliation.webhook.service.TransactionProcessingService;
import com.reconciliation.webhook.service.WebhookEventStatusService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full-flow mock integration test that exercises:
 * 1. Webhook event ingestion (stored to DB)
 * 2. Normalization (Razorpay payment.captured)
 * 3. User identity resolution
 * 4. Transaction upsert (create)
 * 5. Order matching (pre-registered order matched to transaction)
 * 6. Settlement linking
 *
 * No database or Spring context required — all dependencies are mocked.
 * Provide mock data representing a complete Razorpay payment lifecycle.
 */
class FullFlowMockIntegrationTest {

    // ─── Mock data ────────────────────────────────────────────────
    private static final String MERCHANT_ID = "merchant_test_001";
    private static final String PROVIDER = "razorpay";
    private static final String PROVIDER_EVENT_ID = "evt_FULLFLOW001";
    private static final String PROVIDER_PAYMENT_ID = "pay_FULLFLOW001";
    private static final String PROVIDER_ORDER_ID = "order_FULLFLOW001";
    private static final String MERCHANT_ORDER_ID = "MO-2024-001";
    private static final long PAYMENT_AMOUNT = 50000L; // 500.00 INR
    private static final long FEE = 1180L;             // 11.80 INR
    private static final long TAX = 180L;              // 1.80 INR
    private static final long NET_AMOUNT = PAYMENT_AMOUNT - FEE - TAX; // 48640
    private static final String PAYER_EMAIL = "customer@example.com";
    private static final String PAYER_PHONE = "9876543210";
    private static final Long USER_ID = 42L;

    // ─── Dependencies ─────────────────────────────────────────────
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookEventRepository webhookEventRepository = mock(WebhookEventRepository.class);
    private final NormalizationService normalizationService = new NormalizationService();
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final UserIdentityService userIdentityService = mock(UserIdentityService.class);
    private final WebhookEventStatusService webhookEventStatusService = mock(WebhookEventStatusService.class);
    private final PaymentFlowEventService paymentFlowEventService = mock(PaymentFlowEventService.class);
    private final OrderMatchingService orderMatchingService = mock(OrderMatchingService.class);
    private final SettlementService settlementService = mock(SettlementService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ExceptionRecordService exceptionRecordService = mock(ExceptionRecordService.class);

    private TransactionProcessingService processingService;

    // Captures
    private final AtomicReference<Transaction> capturedTransaction = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        processingService = new TransactionProcessingService(
                webhookEventRepository,
                normalizationService,
                transactionService,
                userIdentityService,
                webhookEventStatusService,
                paymentFlowEventService,
                orderMatchingService,
                settlementService,
                objectMapper,
                transactionTemplate
        );
        ReflectionTestUtils.setField(processingService, "defaultMerchantId", MERCHANT_ID);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("Full flow: Razorpay payment.captured → normalize → user resolve → upsert → order match")
    void razorpayPaymentCapturedFullFlow() {
        // ─── ARRANGE ──────────────────────────────────────────────

        // 1. Simulate stored webhook event (as if WebhookIngestionService already persisted it)
        WebhookEvent storedEvent = WebhookEvent.builder()
                .id(100L)
                .provider(PROVIDER)
                .providerEventId(PROVIDER_EVENT_ID)
                .merchantId(MERCHANT_ID)
                .eventType("payment.captured")
                .receivedAt(OffsetDateTime.now())
                .payload(razorpayPaymentCapturedPayload())
                .signatureValid(true)
                .source("webhook")
                .processed(false)
                .build();

        when(webhookEventRepository.findById(100L)).thenReturn(Optional.of(storedEvent));

        // 2. User identity resolution
        when(userIdentityService.resolveUserId(MERCHANT_ID, PAYER_EMAIL, PAYER_PHONE, null))
                .thenReturn(USER_ID);

        // 3. Transaction upsert captures the transaction and returns it
        when(transactionService.upsert(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            tx.setId(1001L); // simulate DB-assigned ID
            capturedTransaction.set(tx);
            return new TransactionUpsertResult(tx, TransactionUpsertResult.Action.CREATED, null);
        });

        // ─── ACT ─────────────────────────────────────────────────
        processingService.processAsync(100L, PROVIDER);

        // ─── ASSERT ──────────────────────────────────────────────

        // Verify normalization produced correct transaction fields
        Transaction tx = capturedTransaction.get();
        assertThat(tx).isNotNull();
        assertThat(tx.getProvider()).isEqualTo(PROVIDER);
        assertThat(tx.getProviderTransactionId()).isEqualTo(PROVIDER_PAYMENT_ID);
        assertThat(tx.getProviderEventId()).isEqualTo(PROVIDER_EVENT_ID);
        assertThat(tx.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(tx.getEventType()).isEqualTo(EventType.PAYMENT);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.CAPTURED);
        assertThat(tx.getPresentmentAmount()).isEqualTo(PAYMENT_AMOUNT);
        assertThat(tx.getPresentmentCurrency()).isEqualTo("INR");
        assertThat(tx.getFeeAmount()).isEqualTo(FEE);
        assertThat(tx.getTaxAmount()).isEqualTo(TAX);
        assertThat(tx.getNetAmount()).isEqualTo(NET_AMOUNT);
        assertThat(tx.getProviderOrderId()).isEqualTo(PROVIDER_ORDER_ID);
        assertThat(tx.getPaymentMethod()).isEqualTo("upi");
        assertThat(tx.getVpa()).isEqualTo("customer@upi");
        assertThat(tx.getPayerEmail()).isEqualTo(PAYER_EMAIL);
        assertThat(tx.getPayerPhone()).isEqualTo(PAYER_PHONE);
        assertThat(tx.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING_SETTLEMENT);
        assertThat(tx.getCapturedAt()).isNotNull();

        // Verify user identity was resolved and set on the transaction
        assertThat(tx.getUserId()).isEqualTo(USER_ID);

        // Verify user aggregates were refreshed
        verify(userIdentityService).refreshAggregates(USER_ID);

        // Verify order matching was attempted
        verify(orderMatchingService).tryMatchByTransaction(tx);

        // Verify webhook was marked as processed
        verify(webhookEventStatusService).markProcessed(100L);

        // Verify flow events were logged
        verify(paymentFlowEventService).record(
                eq(PROVIDER), eq(PROVIDER_EVENT_ID), eq(PROVIDER_PAYMENT_ID),
                eq(100L), any(), any(), eq("PROCESSING_STARTED"), any(), any(), any());
        verify(paymentFlowEventService).record(
                eq(PROVIDER), eq(PROVIDER_EVENT_ID), eq(PROVIDER_PAYMENT_ID),
                eq(100L), any(), any(), eq("PROCESSING_COMPLETED"), eq("SUCCESS"), any(), any());
    }

    @Test
    @DisplayName("Full flow: Order matching succeeds when order is pre-registered with matching amount")
    void orderMatchingSucceedsWithCorrectAmount() {
        // ─── ARRANGE ──────────────────────────────────────────────

        // Real OrderMatchingService with mocked repositories
        OrderMatchingService realOrderMatcher = new OrderMatchingService(
                orderRepository, transactionRepository, exceptionRecordService);
        ReflectionTestUtils.setField(realOrderMatcher, "tolerancePaisa", 100L);

        // Pre-registered order
        Order preRegisteredOrder = Order.builder()
                .id(500L)
                .merchantId(MERCHANT_ID)
                .orderId(MERCHANT_ORDER_ID)
                .providerOrderId(PROVIDER_ORDER_ID)
                .expectedAmount(PAYMENT_AMOUNT)
                .currency("INR")
                .orderStatus(OrderStatus.CREATED)
                .build();

        // Captured transaction from webhook processing
        Transaction capturedTxn = Transaction.builder()
                .id(1001L)
                .provider(PROVIDER)
                .providerTransactionId(PROVIDER_PAYMENT_ID)
                .merchantId(MERCHANT_ID)
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(PAYMENT_AMOUNT)
                .presentmentCurrency("INR")
                .providerOrderId(PROVIDER_ORDER_ID)
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();

        when(orderRepository.findByMerchantIdAndAnyOrderId(MERCHANT_ID, null, PROVIDER_ORDER_ID))
                .thenReturn(Optional.empty());
        when(orderRepository.findByMerchantIdAndProviderOrderId(MERCHANT_ID, PROVIDER_ORDER_ID))
                .thenReturn(Optional.of(preRegisteredOrder));

        // ─── ACT ─────────────────────────────────────────────────
        realOrderMatcher.tryMatchByTransaction(capturedTxn);

        // ─── ASSERT ──────────────────────────────────────────────

        // Order should be marked PAYMENT_RECEIVED
        assertThat(preRegisteredOrder.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_RECEIVED);
        assertThat(preRegisteredOrder.getAmountMatched()).isTrue();
        assertThat(preRegisteredOrder.getTransactionId()).isEqualTo(1001L);
        assertThat(preRegisteredOrder.getDiscrepancyAmount()).isEqualTo(0L);
        assertThat(preRegisteredOrder.getMatchedAt()).isNotNull();

        // Transaction should be marked MATCHED
        assertThat(capturedTxn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
        assertThat(capturedTxn.getMatchedAt()).isNotNull();
        assertThat(capturedTxn.getOrderId()).isEqualTo(MERCHANT_ORDER_ID);

        // Both should be persisted
        verify(orderRepository).save(preRegisteredOrder);
        verify(transactionRepository).save(capturedTxn);

        // No exception should be created (amounts match)
        verify(exceptionRecordService, never()).createForTransaction(
                any(), any(), anyLong(), anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Full flow: Order matching raises exception when amount mismatches beyond tolerance")
    void orderMatchingRaisesExceptionOnAmountMismatch() {
        // ─── ARRANGE ──────────────────────────────────────────────

        OrderMatchingService realOrderMatcher = new OrderMatchingService(
                orderRepository, transactionRepository, exceptionRecordService);
        ReflectionTestUtils.setField(realOrderMatcher, "tolerancePaisa", 100L);

        long expectedAmount = 50000L;
        long actualAmount = 55000L; // 50 INR overpayment

        Order order = Order.builder()
                .id(501L)
                .merchantId(MERCHANT_ID)
                .orderId("MO-MISMATCH-001")
                .providerOrderId("order_MISMATCH001")
                .expectedAmount(expectedAmount)
                .currency("INR")
                .orderStatus(OrderStatus.CREATED)
                .build();

        Transaction txn = Transaction.builder()
                .id(1002L)
                .provider(PROVIDER)
                .providerTransactionId("pay_MISMATCH001")
                .merchantId(MERCHANT_ID)
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(actualAmount)
                .presentmentCurrency("INR")
                .providerOrderId("order_MISMATCH001")
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();

        when(orderRepository.findByMerchantIdAndProviderOrderId(MERCHANT_ID, "order_MISMATCH001"))
                .thenReturn(Optional.of(order));

        ExceptionRecord mockException = ExceptionRecord.builder()
                .id(200L)
                .exceptionType(ExceptionType.ORDER_AMOUNT_MISMATCH)
                .build();
        when(exceptionRecordService.createForTransaction(
                eq(ExceptionType.ORDER_AMOUNT_MISMATCH), eq(Severity.HIGH),
                eq(1002L), eq(expectedAmount), eq(actualAmount), eq("INR"),
                any(), eq(MERCHANT_ID)))
                .thenReturn(mockException);

        // ─── ACT ─────────────────────────────────────────────────
        realOrderMatcher.tryMatchByTransaction(txn);

        // ─── ASSERT ──────────────────────────────────────────────

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.OVERPAID);
        assertThat(order.getAmountMatched()).isFalse();
        assertThat(order.getDiscrepancyAmount()).isEqualTo(5000L);
        assertThat(txn.getReconciliationStatus()).isEqualTo(ReconciliationStatus.EXCEPTION);

        verify(exceptionRecordService).createForTransaction(
                eq(ExceptionType.ORDER_AMOUNT_MISMATCH), eq(Severity.HIGH),
                eq(1002L), eq(expectedAmount), eq(actualAmount), eq("INR"),
                any(), eq(MERCHANT_ID));
    }

    @Test
    @DisplayName("Full flow: Refund webhook normalizes correctly and links to parent")
    void razorpayRefundFlowNormalizesCorrectly() {
        // ─── ARRANGE ──────────────────────────────────────────────

        String refundEventId = "evt_REFUND001";
        String refundId = "rfnd_REFUND001";
        long refundAmount = 25000L;

        WebhookEvent refundEvent = WebhookEvent.builder()
                .id(200L)
                .provider(PROVIDER)
                .providerEventId(refundEventId)
                .merchantId(MERCHANT_ID)
                .eventType("refund.processed")
                .receivedAt(OffsetDateTime.now())
                .payload(razorpayRefundPayload(refundEventId, refundId, refundAmount))
                .signatureValid(true)
                .source("webhook")
                .processed(false)
                .build();

        when(webhookEventRepository.findById(200L)).thenReturn(Optional.of(refundEvent));

        when(transactionService.upsert(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            tx.setId(2001L);
            capturedTransaction.set(tx);
            return new TransactionUpsertResult(tx, TransactionUpsertResult.Action.CREATED, null);
        });

        // ─── ACT ─────────────────────────────────────────────────
        processingService.processAsync(200L, PROVIDER);

        // ─── ASSERT ──────────────────────────────────────────────

        Transaction tx = capturedTransaction.get();
        assertThat(tx).isNotNull();
        assertThat(tx.getProvider()).isEqualTo(PROVIDER);
        assertThat(tx.getProviderTransactionId()).isEqualTo(refundId);
        assertThat(tx.getEventType()).isEqualTo(EventType.REFUND);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(tx.getPresentmentAmount()).isEqualTo(refundAmount);
        assertThat(tx.getPresentmentCurrency()).isEqualTo("INR");
        assertThat(tx.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING);
        assertThat(tx.getRefundedAt()).isNotNull();

        // Refund linking should have been called
        verify(transactionService).linkRefundToParent(any(Transaction.class), any(JsonNode.class), eq(PROVIDER));

        verify(webhookEventStatusService).markProcessed(200L);
    }

    @Test
    @DisplayName("Full flow: Duplicate event is skipped — webhook marked processed, no transaction created")
    void duplicateWebhookEventIsSkippedGracefully() {
        // ─── ARRANGE ──────────────────────────────────────────────

        WebhookEvent event = WebhookEvent.builder()
                .id(300L)
                .provider(PROVIDER)
                .providerEventId("evt_DUP001")
                .merchantId(MERCHANT_ID)
                .eventType("payment.captured")
                .receivedAt(OffsetDateTime.now())
                .payload(razorpayPaymentCapturedPayload())
                .signatureValid(true)
                .source("webhook")
                .processed(false)
                .build();

        when(webhookEventRepository.findById(300L)).thenReturn(Optional.of(event));

        // Simulate the transaction already exists with the same timestamp —
        // upsert returns IGNORED
        Transaction existingTx = Transaction.builder()
                .id(999L)
                .provider(PROVIDER)
                .providerTransactionId(PROVIDER_PAYMENT_ID)
                .status(TransactionStatus.CAPTURED)
                .reconciliationStatus(ReconciliationStatus.MATCHED)
                .build();
        when(transactionService.upsert(any(Transaction.class)))
                .thenReturn(new TransactionUpsertResult(
                        existingTx, TransactionUpsertResult.Action.IGNORED, TransactionStatus.CAPTURED));

        when(userIdentityService.resolveUserId(eq(MERCHANT_ID), any(), any(), any()))
                .thenReturn(USER_ID);

        // ─── ACT ─────────────────────────────────────────────────
        processingService.processAsync(300L, PROVIDER);

        // ─── ASSERT ──────────────────────────────────────────────

        // Processing should still complete successfully (idempotent)
        verify(webhookEventStatusService).markProcessed(300L);
        verify(orderMatchingService).tryMatchByTransaction(existingTx);
    }

    @Test
    @DisplayName("Full flow: Failed payment webhook normalizes with MATCHED status (no action needed)")
    void failedPaymentWebhookNormalizesCorrectly() {
        // ─── ARRANGE ──────────────────────────────────────────────

        WebhookEvent failedEvent = WebhookEvent.builder()
                .id(400L)
                .provider(PROVIDER)
                .providerEventId("evt_FAIL001")
                .merchantId(MERCHANT_ID)
                .eventType("payment.failed")
                .receivedAt(OffsetDateTime.now())
                .payload(razorpayPaymentFailedPayload())
                .signatureValid(true)
                .source("webhook")
                .processed(false)
                .build();

        when(webhookEventRepository.findById(400L)).thenReturn(Optional.of(failedEvent));

        when(transactionService.upsert(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            tx.setId(3001L);
            capturedTransaction.set(tx);
            return new TransactionUpsertResult(tx, TransactionUpsertResult.Action.CREATED, null);
        });

        // ─── ACT ─────────────────────────────────────────────────
        processingService.processAsync(400L, PROVIDER);

        // ─── ASSERT ──────────────────────────────────────────────

        Transaction tx = capturedTransaction.get();
        assertThat(tx).isNotNull();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
        assertThat(tx.getFeeAmount()).isNull();
        assertThat(tx.getNetAmount()).isNull();

        verify(webhookEventStatusService).markProcessed(400L);
    }

    @Test
    @DisplayName("Full flow: Settlement webhook routes to SettlementService, not TransactionService")
    void settlementWebhookRoutesToSettlementService() {
        // ─── ARRANGE ──────────────────────────────────────────────

        String settlementId = "setl_FLOW001";
        WebhookEvent settlementEvent = WebhookEvent.builder()
                .id(500L)
                .provider(PROVIDER)
                .providerEventId("evt_SETL001")
                .merchantId(MERCHANT_ID)
                .eventType("settlement.processed")
                .receivedAt(OffsetDateTime.now())
                .payload(razorpaySettlementPayload(settlementId))
                .signatureValid(true)
                .source("webhook")
                .processed(false)
                .build();

        when(webhookEventRepository.findById(500L)).thenReturn(Optional.of(settlementEvent));

        var settlement = com.reconciliation.settlement.entity.Settlement.builder()
                .provider(PROVIDER)
                .providerSettlementId(settlementId)
                .merchantId(MERCHANT_ID)
                .grossAmount(100000L)
                .netAmount(97640L)
                .currency("INR")
                .build();
        when(settlementService.upsertRazorpaySettlement(any(JsonNode.class), eq(MERCHANT_ID)))
                .thenReturn(settlement);

        // ─── ACT ─────────────────────────────────────────────────
        processingService.processAsync(500L, PROVIDER);

        // ─── ASSERT ──────────────────────────────────────────────

        verify(settlementService).upsertRazorpaySettlement(any(JsonNode.class), eq(MERCHANT_ID));
        verify(transactionService, never()).upsert(any(Transaction.class));
        verify(webhookEventStatusService).markProcessed(500L);
    }

    // ─── MOCK PAYLOAD BUILDERS ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> razorpayPaymentCapturedPayload() {
        String json = """
                {
                  "entity": "event",
                  "id": "%s",
                  "event": "payment.captured",
                  "contains": ["payment"],
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "entity": "payment",
                        "amount": %d,
                        "currency": "INR",
                        "status": "captured",
                        "order_id": "%s",
                        "method": "upi",
                        "amount_refunded": 0,
                        "captured": true,
                        "description": "Test payment",
                        "vpa": "customer@upi",
                        "email": "%s",
                        "contact": "%s",
                        "notes": {"merchant_order_ref": "%s"},
                        "fee": %d,
                        "tax": %d,
                        "created_at": 1710000300
                      }
                    }
                  },
                  "created_at": 1710000300
                }
                """.formatted(
                PROVIDER_EVENT_ID, PROVIDER_PAYMENT_ID, PAYMENT_AMOUNT,
                PROVIDER_ORDER_ID, PAYER_EMAIL, PAYER_PHONE,
                MERCHANT_ORDER_ID, FEE, TAX);
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> razorpayRefundPayload(String eventId, String refundId, long amount) {
        String json = """
                {
                  "entity": "event",
                  "id": "%s",
                  "event": "refund.processed",
                  "contains": ["refund"],
                  "payload": {
                    "refund": {
                      "entity": {
                        "id": "%s",
                        "entity": "refund",
                        "amount": %d,
                        "currency": "INR",
                        "payment_id": "%s",
                        "status": "processed",
                        "created_at": 1710001000
                      }
                    }
                  },
                  "created_at": 1710001000
                }
                """.formatted(eventId, refundId, amount, PROVIDER_PAYMENT_ID);
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> razorpayPaymentFailedPayload() {
        String json = """
                {
                  "entity": "event",
                  "id": "evt_FAIL001",
                  "event": "payment.failed",
                  "contains": ["payment"],
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_FAIL001",
                        "entity": "payment",
                        "amount": 30000,
                        "currency": "INR",
                        "status": "failed",
                        "order_id": "order_FAIL001",
                        "method": "netbanking",
                        "bank": "HDFC",
                        "email": "failed@example.com",
                        "contact": "9000000000",
                        "created_at": 1710002000
                      }
                    }
                  },
                  "created_at": 1710002000
                }
                """;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> razorpaySettlementPayload(String settlementId) {
        String json = """
                {
                  "entity": "event",
                  "id": "evt_SETL001",
                  "event": "settlement.processed",
                  "contains": ["settlement"],
                  "payload": {
                    "settlement": {
                      "entity": {
                        "id": "%s",
                        "entity": "settlement",
                        "amount": 100000,
                        "status": "processed",
                        "fees": 2360,
                        "tax": 360,
                        "utr": "UTR123456789",
                        "created_at": 1710003000
                      }
                    }
                  },
                  "created_at": 1710003000
                }
                """.formatted(settlementId);
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
