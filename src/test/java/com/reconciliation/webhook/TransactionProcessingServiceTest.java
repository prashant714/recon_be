package com.reconciliation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.transaction.service.NormalizationService;
import com.reconciliation.transaction.service.TransactionService;
import com.reconciliation.transaction.service.TransactionUpsertResult;
import com.reconciliation.user.service.UserIdentityService;
import com.reconciliation.order.service.OrderMatchingService;
import com.reconciliation.paymentflow.service.PaymentFlowEventService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.service.SettlementService;
import com.reconciliation.webhook.service.TransactionProcessingService;
import com.reconciliation.webhook.service.WebhookEventStatusService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionProcessingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NormalizationService normalizationService = mock(NormalizationService.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final UserIdentityService userIdentityService = mock(UserIdentityService.class);
    private final WebhookEventRepository webhookEventRepository = mock(WebhookEventRepository.class);
    private final WebhookEventStatusService webhookEventStatusService = mock(WebhookEventStatusService.class);
    private final PaymentFlowEventService paymentFlowEventService = mock(PaymentFlowEventService.class);
    private final OrderMatchingService orderMatchingService = mock(OrderMatchingService.class);
    private final SettlementService settlementService = mock(SettlementService.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final ExceptionRecordRepository exceptionRecordRepository = mock(ExceptionRecordRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private final TransactionProcessingService service = new TransactionProcessingService(
            webhookEventRepository,
            normalizationService,
            transactionService,
            transactionRepository,
            userIdentityService,
            webhookEventStatusService,
            paymentFlowEventService,
            orderMatchingService,
            settlementService,
            exceptionRecordRepository,
            objectMapper,
            transactionTemplate
    );

    TransactionProcessingServiceTest() {
        ReflectionTestUtils.setField(service, "defaultMerchantId", "merchant_001");
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void processesAndMarksWebhookAsProcessed() {
        WebhookEvent event = WebhookEvent.builder()
                .id(1L)
                .provider("razorpay")
                .eventType("payment.captured")
                .payload(Map.of(
                        "id", "evt_1",
                        "event", "payment.captured",
                        "payload", Map.of("payment", Map.of("entity", Map.of("id", "pay_123")))
                ))
                .build();

        Transaction normalized = Transaction.builder()
                .provider("razorpay")
                .providerTransactionId("pay_123")
                .providerEventId("evt_1")
                .merchantId("merchant_001")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(1000L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.now())
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .payerEmail("a@example.com")
                .build();

        when(webhookEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(normalizationService.normalizeRazorpayPaymentCaptured(any(), eq("merchant_001")))
                .thenReturn(normalized);
        when(userIdentityService.resolveUserId("merchant_001", "a@example.com", null, null))
                .thenReturn(10L);
        when(transactionService.upsert(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            return new TransactionUpsertResult(tx, TransactionUpsertResult.Action.CREATED, null);
        });

        service.processAsync(1L, "razorpay");

        verify(transactionService).upsert(any(Transaction.class));
        verify(userIdentityService).refreshAggregates(10L);
        verify(webhookEventStatusService).markProcessed(1L);
        verify(webhookEventRepository, never()).save(any(WebhookEvent.class));
    }

    @Test
    void marksWebhookAsFailedWhenProcessingBlowsUp() {
        WebhookEvent event = WebhookEvent.builder()
                .id(2L)
                .provider("stripe")
                .eventType("charge.succeeded")
                .payload(Map.of("id", "evt_2", "type", "charge.succeeded",
                        "data", Map.of("object", Map.of("id", "ch_2"))))
                .build();

        when(webhookEventRepository.findById(2L)).thenReturn(Optional.of(event));
        when(normalizationService.normalizeStripeChargeSucceeded(any(), eq("merchant_001")))
                .thenThrow(new IllegalStateException("bad payload"));

        service.processAsync(2L, "stripe");

        verify(webhookEventStatusService).markFailed(eq(2L), any());
        verify(transactionService, never()).upsert(any(Transaction.class));
    }

    @Test
    void usesWebhookMerchantIdWhenNormalizingRazorpayPayment() {
        WebhookEvent event = WebhookEvent.builder()
                .id(3L)
                .provider("razorpay")
                .merchantId("merchant_live")
                .eventType("payment.captured")
                .payload(Map.of(
                        "id", "evt_3",
                        "event", "payment.captured",
                        "payload", Map.of("payment", Map.of("entity", Map.of("id", "pay_live")))
                ))
                .build();

        Transaction normalized = Transaction.builder()
                .provider("razorpay")
                .providerTransactionId("pay_live")
                .providerEventId("evt_3")
                .merchantId("merchant_live")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(1000L)
                .presentmentCurrency("INR")
                .eventOccurredAt(OffsetDateTime.now())
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();

        when(webhookEventRepository.findById(3L)).thenReturn(Optional.of(event));
        when(normalizationService.normalizeRazorpayPaymentCaptured(any(), eq("merchant_live")))
                .thenReturn(normalized);
        when(transactionService.upsert(any(Transaction.class))).thenAnswer(invocation ->
                new TransactionUpsertResult(invocation.getArgument(0), TransactionUpsertResult.Action.CREATED, null));

        service.processAsync(3L, "razorpay");

        verify(normalizationService).normalizeRazorpayPaymentCaptured(any(), eq("merchant_live"));
        verify(webhookEventStatusService).markProcessed(3L);
    }

    @Test
    void settlementProcessedWebhookUpsertsSettlementInsteadOfTransaction() {
        WebhookEvent event = WebhookEvent.builder()
                .id(4L)
                .provider("razorpay")
                .merchantId("merchant_live")
                .eventType("settlement.processed")
                .payload(Map.of(
                        "id", "evt_setl",
                        "event", "settlement.processed",
                        "payload", Map.of("settlement", Map.of("entity", Map.of("id", "setl_123")))
                ))
                .build();
        Settlement settlement = Settlement.builder()
                .provider("razorpay")
                .providerSettlementId("setl_123")
                .merchantId("merchant_live")
                .grossAmount(1000L)
                .netAmount(1000L)
                .currency("INR")
                .build();

        when(webhookEventRepository.findById(4L)).thenReturn(Optional.of(event));
        when(settlementService.upsertRazorpaySettlement(any(), eq("merchant_live")))
                .thenReturn(settlement);

        service.processAsync(4L, "razorpay");

        verify(settlementService).upsertRazorpaySettlement(any(), eq("merchant_live"));
        verify(transactionService, never()).upsert(any(Transaction.class));
        verify(webhookEventStatusService).markProcessed(4L);
    }
}
