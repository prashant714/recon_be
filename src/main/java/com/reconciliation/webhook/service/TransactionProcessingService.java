package com.reconciliation.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.paymentflow.service.PaymentFlowEventService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.service.NormalizationService;
import com.reconciliation.transaction.service.TransactionService;
import com.reconciliation.transaction.service.TransactionUpsertResult;
import com.reconciliation.user.service.UserIdentityService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionProcessingService {

    private final WebhookEventRepository webhookEventRepository;
    private final NormalizationService normalizationService;
    private final TransactionService transactionService;
    private final UserIdentityService userIdentityService;
    private final WebhookEventStatusService webhookEventStatusService;
    private final PaymentFlowEventService paymentFlowEventService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.merchant.id:merchant_001}")
    private String defaultMerchantId;

    /**
     * Runs in a separate thread pool (webhookProcessingExecutor).
     * Reads the raw event, normalizes it, resolves user, upserts transaction.
     */
    @Async("webhookProcessingExecutor")
    @Transactional
    public void processAsync(Long webhookEventId, String provider) {
        Optional<WebhookEvent> eventOpt = webhookEventRepository.findById(webhookEventId);
        if (eventOpt.isEmpty()) {
            log.error("WebhookEvent not found id={}", webhookEventId);
            return;
        }

        WebhookEvent webhookEvent = eventOpt.get();

        try {
            JsonNode payload = objectMapper.convertValue(
                webhookEvent.getPayload(), JsonNode.class
            );
            paymentFlowEventService.record(
                    provider,
                    webhookEvent.getProviderEventId(),
                    extractTransactionId(payload, provider),
                    webhookEventId,
                    null,
                    webhookEvent.getSource(),
                    "PROCESSING_STARTED",
                    "RUNNING",
                    "Webhook event picked for processing",
                    metadata("eventType", webhookEvent.getEventType()));

            Transaction transaction = route(payload, provider, webhookEvent.getEventType());

            if (transaction == null) {
                log.info("Event type={} not handled — skipping id={}",
                         webhookEvent.getEventType(), webhookEventId);
                paymentFlowEventService.record(
                        provider,
                        webhookEvent.getProviderEventId(),
                        null,
                        webhookEventId,
                        null,
                        webhookEvent.getSource(),
                        "PROCESSING_SKIPPED",
                        "IGNORED",
                        "Event type is not handled",
                        metadata("eventType", webhookEvent.getEventType()));
                webhookEventStatusService.markProcessed(webhookEventId);
                return;
            }

            // Resolve user identity from payer data
            if (transaction.getPayerEmail() != null || transaction.getPayerPhone() != null) {
                Long userId = userIdentityService.resolveUserId(
                    transaction.getMerchantId(),
                    transaction.getPayerEmail(),
                    transaction.getPayerPhone(),
                    transaction.getPayerName()
                );
                transaction.setUserId(userId);
                paymentFlowEventService.record(
                        provider,
                        webhookEvent.getProviderEventId(),
                        transaction.getProviderTransactionId(),
                        webhookEventId,
                        userId,
                        webhookEvent.getSource(),
                        "USER_RESOLVED",
                        "SUCCESS",
                        "User identity resolved for payment flow",
                        metadata(
                                "email", transaction.getPayerEmail(),
                                "phone", transaction.getPayerPhone(),
                                "payerName", transaction.getPayerName()));
            }

            // For refunds: link to parent payment
            if (transaction.getEventType() == com.reconciliation.common.enums.EventType.REFUND) {
                transactionService.linkRefundToParent(transaction, payload, provider);
            }

            TransactionUpsertResult upsertResult = transactionService.upsert(transaction);
            Transaction persisted = upsertResult.transaction();
            paymentFlowEventService.record(
                    provider,
                    webhookEvent.getProviderEventId(),
                    transaction.getProviderTransactionId(),
                    webhookEventId,
                    persisted.getUserId(),
                    webhookEvent.getSource(),
                    "TRANSACTION_UPSERT",
                    upsertResult.action().name(),
                    "Transaction record evaluated",
                    metadata(
                            "previousStatus", String.valueOf(upsertResult.previousStatus()),
                            "currentStatus", String.valueOf(persisted.getStatus()),
                            "eventType", String.valueOf(persisted.getEventType())));

            // Recompute aggregates from canonical transactions so retries,
            // replays, and multi-stage provider events stay idempotent.
            if (persisted.getUserId() != null) {
                userIdentityService.refreshAggregates(persisted.getUserId());
                paymentFlowEventService.record(
                        provider,
                        webhookEvent.getProviderEventId(),
                        transaction.getProviderTransactionId(),
                        webhookEventId,
                        persisted.getUserId(),
                        webhookEvent.getSource(),
                        "USER_AGGREGATES_REFRESHED",
                        "SUCCESS",
                        "User aggregates refreshed from canonical transactions",
                        null);
            }

            webhookEventStatusService.markProcessed(webhookEventId);
            paymentFlowEventService.record(
                    provider,
                    webhookEvent.getProviderEventId(),
                    transaction.getProviderTransactionId(),
                    webhookEventId,
                    persisted.getUserId(),
                    webhookEvent.getSource(),
                    "PROCESSING_COMPLETED",
                    "SUCCESS",
                    "Webhook event fully processed",
                    null);
            log.info("Processed event provider={} type={} txnId={}",
                     provider, webhookEvent.getEventType(),
                     transaction.getProviderTransactionId());

        } catch (Exception e) {
            log.error("Failed to process webhook event id={}: {}", webhookEventId, e.getMessage(), e);
            webhookEventStatusService.markFailed(webhookEventId, e.getMessage());
            paymentFlowEventService.record(
                    provider,
                    webhookEvent.getProviderEventId(),
                    null,
                    webhookEventId,
                    null,
                    webhookEvent.getSource(),
                    "PROCESSING_FAILED",
                    "FAILED",
                    e.getMessage(),
                    metadata("eventType", webhookEvent.getEventType()));
        }
    }

    private Transaction route(JsonNode payload, String provider, String eventType) {
        return switch (provider) {
            case "razorpay" -> routeRazorpay(payload, eventType);
            case "stripe"   -> routeStripe(payload, eventType);
            default -> {
                log.warn("Unknown provider: {}", provider);
                yield null;
            }
        };
    }

    private Transaction routeRazorpay(JsonNode payload, String eventType) {
        return switch (eventType) {
            case "payment.authorized" ->
                normalizationService.normalizeRazorpayPaymentAuthorized(payload, defaultMerchantId);
            case "payment.captured" ->
                normalizationService.normalizeRazorpayPaymentCaptured(payload, defaultMerchantId);
            case "payment.failed" ->
                normalizationService.normalizeRazorpayPaymentFailed(payload, defaultMerchantId);
            case "refund.processed" ->
                normalizationService.normalizeRazorpayRefundProcessed(payload, defaultMerchantId);
            case "settlement.processed" ->
                normalizationService.normalizeRazorpaySettlementProcessed(payload, defaultMerchantId);
            case "dispute.created" ->
                normalizationService.normalizeRazorpayDisputeCreated(payload, defaultMerchantId);
            default -> {
                log.debug("Unhandled Razorpay event type: {}", eventType);
                yield null;
            }
        };
    }

    private Transaction routeStripe(JsonNode payload, String eventType) {
        return switch (eventType) {
            case "charge.succeeded" ->
                normalizationService.normalizeStripeChargeSucceeded(payload, defaultMerchantId);
            case "payment_intent.succeeded" ->
                normalizationService.normalizeStripePaymentSucceeded(payload, defaultMerchantId);
            case "payment_intent.payment_failed" ->
                normalizationService.normalizeStripePaymentFailed(payload, defaultMerchantId);
            case "charge.refunded" ->
                normalizationService.normalizeStripeChargeRefunded(payload, defaultMerchantId);
            case "refund.created" ->
                normalizationService.normalizeStripeRefundCreated(payload, defaultMerchantId);
            case "charge.dispute.created" ->
                normalizationService.normalizeStripeDisputeCreated(payload, defaultMerchantId);
            case "payout.paid" ->
                normalizationService.normalizeStripePayoutPaid(payload, defaultMerchantId);
            default -> {
                log.debug("Unhandled Stripe event type: {}", eventType);
                yield null;
            }
        };
    }

    private String extractTransactionId(JsonNode payload, String provider) {
        return switch (provider) {
            case "razorpay" -> payload.path("payload").path("payment").path("entity").path("id")
                    .asText(payload.path("payload").path("refund").path("entity").path("payment_id").asText(null));
            case "stripe" -> payload.path("data").path("object").path("id").asText(null);
            default -> null;
        };
    }

    private Map<String, Object> metadata(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                values.put(String.valueOf(pairs[i]), value);
            }
        }
        return values.isEmpty() ? null : values;
    }
}
