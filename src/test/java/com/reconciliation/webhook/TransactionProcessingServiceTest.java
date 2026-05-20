package com.reconciliation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.service.NormalizationService;
import com.reconciliation.transaction.service.TransactionService;
import com.reconciliation.user.service.UserIdentityService;
import com.reconciliation.webhook.service.TransactionProcessingService;
import com.reconciliation.webhook.service.WebhookEventStatusService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    private final TransactionProcessingService service = new TransactionProcessingService(
            webhookEventRepository,
            normalizationService,
            transactionService,
            userIdentityService,
            webhookEventStatusService,
            objectMapper
    );

    TransactionProcessingServiceTest() {
        ReflectionTestUtils.setField(service, "defaultMerchantId", "merchant_001");
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
        when(transactionService.upsert(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
}
