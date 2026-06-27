package com.reconciliation.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.order.service.OrderMatchingService;
import com.reconciliation.paymentflow.service.PaymentFlowEventService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.service.SettlementService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.service.NormalizationService;
import com.reconciliation.transaction.service.TransactionService;
import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.transaction.service.TransactionUpsertResult;
import com.reconciliation.user.service.UserIdentityService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionProcessingService {

    private final WebhookEventRepository webhookEventRepository;
    private final NormalizationService normalizationService;
    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final UserIdentityService userIdentityService;
    private final WebhookEventStatusService webhookEventStatusService;
    private final PaymentFlowEventService paymentFlowEventService;
    private final OrderMatchingService orderMatchingService;
    private final SettlementService settlementService;
    private final ExceptionRecordRepository exceptionRecordRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Value("${app.merchant.id:merchant_001}")
    private String defaultMerchantId;

    /**
     * Runs in a separate thread pool (webhookProcessingExecutor).
     * Reads the raw event, normalizes it, resolves user, upserts transaction.
     */
    @Async("webhookProcessingExecutor")
    public void processAsync(Long webhookEventId, String provider) {
        Optional<WebhookEvent> eventOpt = webhookEventRepository.findById(webhookEventId);
        if (eventOpt.isEmpty()) {
            log.error("WebhookEvent not found id={}", webhookEventId);
            return;
        }

        WebhookEvent webhookEvent = eventOpt.get();

        // Retry on DB serialization conflicts (40001). These are transient and safe to retry.
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processInternal(webhookEvent, provider);
                return;
            } catch (CannotAcquireLockException e) {
                if (attempt == maxAttempts) {
                    log.error("Failed to process webhook event id={} after {} attempts: {}",
                            webhookEventId, maxAttempts, e.getMessage(), e);
                    webhookEventStatusService.markFailed(webhookEventId, e.getMessage());
                    return;
                }
                log.warn("Serialization conflict processing event id={} (attempt {}), retrying",
                        webhookEventId, attempt);
                try { Thread.sleep(50L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void processInternal(WebhookEvent webhookEvent, String provider) {
        Long webhookEventId = webhookEvent.getId();
        try {
            JsonNode payload = objectMapper.convertValue(
                webhookEvent.getPayload(), JsonNode.class
            );
            String merchantId = firstText(webhookEvent.getMerchantId(), defaultMerchantId);
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

            if ("razorpay".equals(provider) && "settlement.processed".equals(webhookEvent.getEventType())) {
                Settlement settlement = settlementService.upsertRazorpaySettlement(payload, merchantId);
                paymentFlowEventService.record(
                        provider,
                        webhookEvent.getProviderEventId(),
                        settlement.getProviderSettlementId(),
                        webhookEventId,
                        null,
                        webhookEvent.getSource(),
                        "SETTLEMENT_UPSERT",
                        "SUCCESS",
                        "Settlement record evaluated",
                        metadata("merchantId", merchantId));
                webhookEventStatusService.markProcessed(webhookEventId);
                paymentFlowEventService.record(
                        provider,
                        webhookEvent.getProviderEventId(),
                        settlement.getProviderSettlementId(),
                        webhookEventId,
                        null,
                        webhookEvent.getSource(),
                        "PROCESSING_COMPLETED",
                        "SUCCESS",
                        "Settlement webhook fully processed",
                        null);
                return;
            }

            Transaction transaction = route(payload, provider, webhookEvent.getEventType(), merchantId);

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

            TransactionUpsertResult upsertResult = transactionTemplate.execute(status ->
                    transactionService.upsert(transaction));
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

            orderMatchingService.tryMatchByTransaction(persisted);

            if (persisted.getEventType() == EventType.PAYMENT) {
                relinkOrphanRefunds(persisted);
            }

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

    private Transaction route(JsonNode payload, String provider, String eventType, String merchantId) {
        return switch (provider) {
            case "razorpay" -> routeRazorpay(payload, eventType, merchantId);
            case "stripe"   -> routeStripe(payload, eventType);
            default -> {
                log.warn("Unknown provider: {}", provider);
                yield null;
            }
        };
    }

    private Transaction routeRazorpay(JsonNode payload, String eventType, String merchantId) {
        return switch (eventType) {
            case "payment.authorized" ->
                normalizationService.normalizeRazorpayPaymentAuthorized(payload, merchantId);
            case "payment.captured" ->
                normalizationService.normalizeRazorpayPaymentCaptured(payload, merchantId);
            case "payment.failed" ->
                normalizationService.normalizeRazorpayPaymentFailed(payload, merchantId);
            case "refund.processed" ->
                normalizationService.normalizeRazorpayRefundProcessed(payload, merchantId);
            // settlement.processed is handled earlier in processAsync() before route() is called
            case "dispute.created" ->
                normalizationService.normalizeRazorpayDisputeCreated(payload, merchantId);
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

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * When a payment arrives, check if any orphan refunds reference it and re-link them.
     * Also auto-closes the ORPHAN_REFUND exception if one was created.
     */
    private void relinkOrphanRefunds(Transaction payment) {
        List<Transaction> orphanRefunds = transactionRepository.findOrphanRefunds(
                payment.getMerchantId(),
                java.time.OffsetDateTime.now());

        for (Transaction refund : orphanRefunds) {
            Optional<Transaction> parent = transactionRepository
                    .findPaymentByProviderTransactionId(
                            refund.getProvider(),
                            refund.getMerchantId(),
                            extractParentIdFromRawPayload(refund));

            if (parent.isPresent() && parent.get().getId().equals(payment.getId())) {
                refund.setParentTransactionId(payment.getId());
                refund.setReconciliationStatus(ReconciliationStatus.PENDING);
                transactionRepository.save(refund);

                if (refund.getExceptionId() != null) {
                    exceptionRecordRepository.findById(refund.getExceptionId()).ifPresent(ex -> {
                        if (ex.getExceptionType() == ExceptionType.ORPHAN_REFUND
                                && (ex.getStatus() == ExceptionStatus.OPEN
                                    || ex.getStatus() == ExceptionStatus.IN_REVIEW)) {
                            ex.setStatus(ExceptionStatus.RESOLVED);
                            ex.setResolvedAt(java.time.OffsetDateTime.now());
                            ex.setResolvedBy("system:auto-relink");
                            ex.setResolutionNotes("Parent payment " + payment.getProviderTransactionId()
                                    + " arrived and was auto-linked");
                            exceptionRecordRepository.save(ex);
                        }
                    });
                    refund.setExceptionId(null);
                    transactionRepository.save(refund);
                }

                log.info("Auto-relinked orphan refund {} to payment {}",
                        refund.getProviderTransactionId(), payment.getProviderTransactionId());
            }
        }
    }

    private String extractParentIdFromRawPayload(Transaction refund) {
        try {
            JsonNode payload = objectMapper.convertValue(refund.getRawPayload(), JsonNode.class);
            if ("razorpay".equals(refund.getProvider())) {
                return payload.path("payload").path("refund").path("entity")
                        .path("payment_id").asText(null);
            } else if ("stripe".equals(refund.getProvider())) {
                return payload.path("data").path("object").path("charge").asText(null);
            }
        } catch (Exception e) {
            log.debug("Could not extract parent ID from refund raw payload: {}", e.getMessage());
        }
        return null;
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
