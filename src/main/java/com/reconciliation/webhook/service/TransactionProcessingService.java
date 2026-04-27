package com.reconciliation.webhook.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.exception.UnsupportedWebhookEventException;
import com.reconciliation.config.MerchantProperties;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.service.NormalizationService;
import com.reconciliation.transaction.service.TransactionService;
import com.reconciliation.user.service.UserIdentityService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
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

            Transaction transaction = route(payload, provider, webhookEvent.getEventType());

            if (transaction == null) {
                log.info("Event type={} not handled — skipping id={}",
                         webhookEvent.getEventType(), webhookEventId);
                markProcessed(webhookEvent);
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
            }

            // For refunds: link to parent payment
            if (transaction.getEventType() == com.reconciliation.common.enums.EventType.REFUND) {
                transactionService.linkRefundToParent(transaction, payload, provider);
            }

            Transaction persisted = transactionService.upsert(transaction);

            // Increment user aggregates atomically
            if (persisted.getUserId() != null) {
                userIdentityService.incrementAggregates(
                    persisted.getUserId(),
                    persisted.getPresentmentAmount(),
                    persisted.getStatus() == com.reconciliation.common.enums.TransactionStatus.FAILED
                );
            }

            markProcessed(webhookEvent);
            log.info("Processed event provider={} type={} txnId={}",
                     provider, webhookEvent.getEventType(),
                     transaction.getProviderTransactionId());

        } catch (Exception e) {
            log.error("Failed to process webhook event id={}: {}", webhookEventId, e.getMessage(), e);
            webhookEvent.setProcessed(true);
            webhookEvent.setProcessedAt(OffsetDateTime.now());
            webhookEvent.setProcessingError(e.getMessage());
            webhookEventRepository.save(webhookEvent);
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

    private void markProcessed(WebhookEvent event) {
        event.setProcessed(true);
        event.setProcessedAt(OffsetDateTime.now());
        webhookEventRepository.save(event);
    }
}
