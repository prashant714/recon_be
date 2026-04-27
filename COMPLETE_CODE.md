# Complete Implementation Code
## Payment Reconciliation Platform — All Phases

> Every file needed from Phase 1 to Phase 4.
> Copy each file exactly to the path shown. Do not rename packages.

---

# PHASE 1 — Core Ingestion Pipeline

---

## `config/AsyncConfig.java`

```java
package com.reconciliation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "webhookProcessingExecutor")
    public Executor webhookProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("webhook-processor-");
        // CallerRunsPolicy: if queue full, run in caller thread instead of dropping
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

---

## `webhook/service/WebhookIngestionService.java`

```java
package com.reconciliation.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookIngestionService {

    private final WebhookEventRepository webhookEventRepository;
    private final TransactionProcessingService processingService;
    private final ObjectMapper objectMapper;

    /**
     * Entry point for all incoming events — both webhook and polling.
     * Stores raw event, then hands off async processing.
     * Returns immediately — never blocks the webhook controller.
     */
    @Transactional
    public void ingestAsync(byte[] rawBody, String provider, String source) {
        try {
            JsonNode payload = objectMapper.readTree(rawBody);

            String providerEventId = extractEventId(payload, provider);
            String eventType       = extractEventType(payload, provider);

            if (providerEventId == null || providerEventId.isBlank()) {
                log.warn("Received {} event with no event ID — discarding", provider);
                return;
            }

            log.info("Received event provider={} type={} id={} source={}",
                     provider, eventType, providerEventId, source);

            WebhookEvent event = WebhookEvent.builder()
                    .provider(provider)
                    .providerEventId(providerEventId)
                    .eventType(eventType)
                    .receivedAt(OffsetDateTime.now())
                    .payload(objectMapper.convertValue(payload, Map.class))
                    .signatureValid(true)   // already verified in controller
                    .source(source)
                    .processed(false)
                    .build();

            WebhookEvent saved;
            try {
                saved = webhookEventRepository.save(event);
            } catch (DataIntegrityViolationException e) {
                // UNIQUE (provider, provider_event_id) violated — duplicate event
                log.info("Duplicate event ignored provider={} id={}", provider, providerEventId);
                return;
            }

            // Async: do not await — controller already returned 200
            processingService.processAsync(saved.getId(), provider);

        } catch (Exception e) {
            log.error("Failed to ingest event from provider={}: {}", provider, e.getMessage(), e);
        }
    }

    private String extractEventId(JsonNode payload, String provider) {
        return switch (provider) {
            case "razorpay" -> payload.path("id").asText(null);
            case "stripe"   -> payload.path("id").asText(null);
            default         -> payload.path("id").asText(null);
        };
    }

    private String extractEventType(JsonNode payload, String provider) {
        return switch (provider) {
            // Razorpay uses "event" field: "payment.captured"
            case "razorpay" -> payload.path("event").asText("unknown");
            // Stripe uses "type" field: "payment_intent.succeeded"
            case "stripe"   -> payload.path("type").asText("unknown");
            default         -> "unknown";
        };
    }
}
```

---

## `webhook/service/TransactionProcessingService.java`

```java
package com.reconciliation.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.service.NormalizationService;
import com.reconciliation.transaction.service.TransactionService;
import com.reconciliation.user.service.UserIdentityService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionProcessingService {

    private final WebhookEventRepository webhookEventRepository;
    private final NormalizationService normalizationService;
    private final TransactionService transactionService;
    private final UserIdentityService userIdentityService;
    private final ObjectMapper objectMapper;

    @Value("${app.merchant.id:merchant_001}")
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
            if ("refund".equals(transaction.getEventType().name().toLowerCase())) {
                transactionService.linkRefundToParent(transaction, payload, provider);
            }

            transactionService.upsert(transaction);

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
```

---

## `transaction/service/NormalizationService.java` (complete — all methods)

```java
package com.reconciliation.transaction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reconciliation.common.enums.*;
import com.reconciliation.transaction.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@Slf4j
public class NormalizationService {

    // ─────────────────────────────────────────────────────────────
    // RAZORPAY
    // ─────────────────────────────────────────────────────────────

    public Transaction normalizeRazorpayPaymentAuthorized(
            JsonNode payload, String merchantId) {

        JsonNode p = payment(payload);
        return base(p, payload, merchantId, "razorpay")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.AUTHORIZED)
                // fee is NOT present at authorized stage
                .feeAmount(null)
                .taxAmount(null)
                .netAmount(null)
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
    }

    public Transaction normalizeRazorpayPaymentCaptured(
            JsonNode payload, String merchantId) {

        JsonNode p = payment(payload);
        long amount = p.path("amount").asLong(0);
        long fee    = p.path("fee").asLong(0);
        long tax    = p.path("tax").asLong(0);

        return base(p, payload, merchantId, "razorpay")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .feeAmount(fee)
                .taxAmount(tax)
                .netAmount(amount - fee - tax)
                .capturedAt(OffsetDateTime.now())
                // Wait for settlement before matching — fees are final but
                // settlement grouping not yet known
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();
    }

    public Transaction normalizeRazorpayPaymentFailed(
            JsonNode payload, String merchantId) {

        JsonNode p = payment(payload);
        return base(p, payload, merchantId, "razorpay")
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.FAILED)
                .feeAmount(null)
                .taxAmount(null)
                .netAmount(null)
                .reconciliationStatus(ReconciliationStatus.MATCHED) // failed = no action needed
                .build();
    }

    public Transaction normalizeRazorpayRefundProcessed(
            JsonNode payload, String merchantId) {

        JsonNode r = refund(payload);
        long amount = r.path("amount").asLong(0);

        return Transaction.builder()
                .provider("razorpay")
                .providerTransactionId(r.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .eventType(EventType.REFUND)
                .status(TransactionStatus.REFUNDED)
                .presentmentAmount(amount)
                .presentmentCurrency(currency(r.path("currency").asText("INR")))
                .feeAmount(null)
                .taxAmount(null)
                .netAmount(null)
                .eventOccurredAt(fromUnix(r.path("created_at").asLong()))
                .refundedAt(OffsetDateTime.now())
                // parentTransactionId resolved later in TransactionService
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
    }

    public Transaction normalizeRazorpaySettlementProcessed(
            JsonNode payload, String merchantId) {
        // Settlement creates a Settlement entity, not a Transaction.
        // Return null — handled separately in SettlementService.
        log.debug("settlement.processed event — handled by SettlementService, not here");
        return null;
    }

    public Transaction normalizeRazorpayDisputeCreated(
            JsonNode payload, String merchantId) {

        JsonNode d = payload.path("payload").path("dispute").path("entity");
        long amount = d.path("amount").asLong(0);

        return Transaction.builder()
                .provider("razorpay")
                .providerTransactionId(d.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .eventType(EventType.CHARGEBACK)
                .status(TransactionStatus.DISPUTED)
                .presentmentAmount(amount)
                .presentmentCurrency(currency(d.path("currency").asText("INR")))
                .eventOccurredAt(fromUnix(d.path("created_at").asLong()))
                .reconciliationStatus(ReconciliationStatus.EXCEPTION)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // STRIPE
    // ─────────────────────────────────────────────────────────────

    public Transaction normalizeStripePaymentSucceeded(
            JsonNode payload, String merchantId) {

        JsonNode intent = stripeObject(payload);

        return Transaction.builder()
                .provider("stripe")
                .providerTransactionId(intent.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(intent.path("amount").asLong(0))
                .presentmentCurrency(currency(intent.path("currency").asText("USD")))
                // Stripe fees are NOT in the webhook — fetched by polling separately
                .feeAmount(null)
                .taxAmount(null)
                .netAmount(null)
                .payerEmail(intent.path("receipt_email").asText(null))
                .paymentMethod(stripePaymentMethod(intent))
                .eventOccurredAt(fromUnix(payload.path("created").asLong()))
                .capturedAt(OffsetDateTime.now())
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();
    }

    public Transaction normalizeStripePaymentFailed(
            JsonNode payload, String merchantId) {

        JsonNode intent = stripeObject(payload);

        return Transaction.builder()
                .provider("stripe")
                .providerTransactionId(intent.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.FAILED)
                .presentmentAmount(intent.path("amount").asLong(0))
                .presentmentCurrency(currency(intent.path("currency").asText("USD")))
                .feeAmount(null)
                .taxAmount(null)
                .netAmount(null)
                .eventOccurredAt(fromUnix(payload.path("created").asLong()))
                .reconciliationStatus(ReconciliationStatus.MATCHED)
                .build();
    }

    public Transaction normalizeStripeChargeRefunded(
            JsonNode payload, String merchantId) {

        JsonNode charge = stripeObject(payload);
        long refundedAmount = charge.path("amount_refunded").asLong(0);

        return Transaction.builder()
                .provider("stripe")
                .providerTransactionId("re_" + charge.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .eventType(EventType.REFUND)
                .status(TransactionStatus.REFUNDED)
                .presentmentAmount(refundedAmount)
                .presentmentCurrency(currency(charge.path("currency").asText("USD")))
                .feeAmount(null)
                .taxAmount(null)
                .netAmount(null)
                .eventOccurredAt(fromUnix(payload.path("created").asLong()))
                .refundedAt(OffsetDateTime.now())
                .reconciliationStatus(ReconciliationStatus.PENDING)
                .build();
    }

    public Transaction normalizeStripeDisputeCreated(
            JsonNode payload, String merchantId) {

        JsonNode dispute = stripeObject(payload);

        return Transaction.builder()
                .provider("stripe")
                .providerTransactionId(dispute.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .eventType(EventType.CHARGEBACK)
                .status(TransactionStatus.DISPUTED)
                .presentmentAmount(dispute.path("amount").asLong(0))
                .presentmentCurrency(currency(dispute.path("currency").asText("USD")))
                .eventOccurredAt(fromUnix(payload.path("created").asLong()))
                .reconciliationStatus(ReconciliationStatus.EXCEPTION)
                .build();
    }

    public Transaction normalizeStripePayoutPaid(
            JsonNode payload, String merchantId) {
        // Payout creates a Settlement entity, not a Transaction.
        log.debug("payout.paid — handled by SettlementService");
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // SHARED HELPERS
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds common Razorpay payment fields shared across authorized/captured/failed.
     */
    private Transaction.TransactionBuilder base(
            JsonNode p, JsonNode payload, String merchantId, String provider) {

        return Transaction.builder()
                .provider(provider)
                .providerTransactionId(p.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .orderId(p.path("order_id").asText(null))
                .providerOrderId(p.path("order_id").asText(null))
                .presentmentAmount(p.path("amount").asLong(0))
                .presentmentCurrency(currency(p.path("currency").asText("INR")))
                .paymentMethod(p.path("method").asText(null))
                .paymentMethodDetail(razorpayMethodDetail(p))
                .cardLast4(cardLast4(p))
                .cardNetwork(cardNetwork(p))
                .bank(p.path("bank").asText(null))
                .vpa(p.path("vpa").asText(null))
                .payerEmail(p.path("email").asText(null))
                .payerPhone(p.path("contact").asText(null))
                .eventOccurredAt(fromUnix(p.path("created_at").asLong()));
    }

    private JsonNode payment(JsonNode payload) {
        return payload.path("payload").path("payment").path("entity");
    }

    private JsonNode refund(JsonNode payload) {
        return payload.path("payload").path("refund").path("entity");
    }

    private JsonNode stripeObject(JsonNode payload) {
        return payload.path("data").path("object");
    }

    private OffsetDateTime fromUnix(long epochSeconds) {
        if (epochSeconds == 0) return OffsetDateTime.now();
        return OffsetDateTime.ofInstant(
            Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC
        );
    }

    private String currency(String raw) {
        if (raw == null || raw.isBlank()) return "INR";
        return raw.toUpperCase().trim();
    }

    private String cardLast4(JsonNode p) {
        JsonNode card = p.path("card");
        if (!card.isMissingNode() && !card.isNull()) {
            String l4 = card.path("last4").asText(null);
            return (l4 != null && !l4.isBlank()) ? l4 : null;
        }
        return null;
    }

    private String cardNetwork(JsonNode p) {
        JsonNode card = p.path("card");
        if (!card.isMissingNode() && !card.isNull()) {
            return card.path("network").asText(null);
        }
        return null;
    }

    private String razorpayMethodDetail(JsonNode p) {
        String method = p.path("method").asText(null);
        if (method == null) return null;
        return switch (method) {
            case "card"       -> p.path("card").path("network").asText(null);
            case "upi"        -> p.path("vpa").asText(null);
            case "netbanking" -> p.path("bank").asText(null);
            case "wallet"     -> p.path("wallet").asText(null);
            default           -> null;
        };
    }

    private String stripePaymentMethod(JsonNode intent) {
        JsonNode charges = intent.path("charges").path("data");
        if (charges.isArray() && charges.size() > 0) {
            JsonNode charge = charges.get(0);
            return charge.path("payment_method_details")
                         .path("type").asText(null);
        }
        return null;
    }
}
```

---

## `transaction/service/TransactionService.java`

```java
package com.reconciliation.transaction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;

    /**
     * Upsert a transaction with timestamp guard.
     * Only updates an existing record if the incoming event is NEWER.
     * This prevents out-of-order events from overwriting newer state.
     */
    @Transactional
    public Transaction upsert(Transaction incoming) {
        Optional<Transaction> existing = transactionRepository
                .findByProviderAndProviderTransactionId(
                    incoming.getProvider(),
                    incoming.getProviderTransactionId()
                );

        if (existing.isEmpty()) {
            log.debug("Inserting new transaction provider={} id={}",
                      incoming.getProvider(), incoming.getProviderTransactionId());
            return transactionRepository.save(incoming);
        }

        Transaction current = existing.get();

        // Timestamp guard: only update if incoming event is newer
        if (incoming.getEventOccurredAt().isBefore(current.getEventOccurredAt())) {
            log.debug("Skipping out-of-order event for txn={} (incoming {} < existing {})",
                      current.getProviderTransactionId(),
                      incoming.getEventOccurredAt(),
                      current.getEventOccurredAt());
            return current;
        }

        // Apply updates — never overwrite immutable fields
        current.setStatus(incoming.getStatus());
        current.setEventOccurredAt(incoming.getEventOccurredAt());
        current.setReconciliationStatus(incoming.getReconciliationStatus());

        if (incoming.getFeeAmount() != null)        current.setFeeAmount(incoming.getFeeAmount());
        if (incoming.getTaxAmount() != null)        current.setTaxAmount(incoming.getTaxAmount());
        if (incoming.getNetAmount() != null)        current.setNetAmount(incoming.getNetAmount());
        if (incoming.getSettlementId() != null)     current.setSettlementId(incoming.getSettlementId());
        if (incoming.getSettlementDate() != null)   current.setSettlementDate(incoming.getSettlementDate());
        if (incoming.getUtrNumber() != null)        current.setUtrNumber(incoming.getUtrNumber());
        if (incoming.getCapturedAt() != null)       current.setCapturedAt(incoming.getCapturedAt());
        if (incoming.getRefundedAt() != null)       current.setRefundedAt(incoming.getRefundedAt());
        if (incoming.getUserId() != null)           current.setUserId(incoming.getUserId());
        if (incoming.getPayerEmail() != null)       current.setPayerEmail(incoming.getPayerEmail());
        if (incoming.getPayerPhone() != null)       current.setPayerPhone(incoming.getPayerPhone());

        current.setUpdatedAt(OffsetDateTime.now());

        log.debug("Updated existing transaction provider={} id={} newStatus={}",
                  current.getProvider(), current.getProviderTransactionId(), current.getStatus());

        return transactionRepository.save(current);
    }

    /**
     * Links a refund to its original payment.
     * Razorpay refund payloads include payment_id.
     * Stripe refund payloads include charge id in charge field.
     */
    @Transactional
    public void linkRefundToParent(Transaction refund, JsonNode payload, String provider) {
        String originalPaymentId = extractOriginalPaymentId(payload, provider);
        if (originalPaymentId == null) return;

        Optional<Transaction> parent = transactionRepository
                .findByProviderAndProviderTransactionId(provider, originalPaymentId);

        if (parent.isPresent()) {
            refund.setParentTransactionId(parent.get().getId());
            log.debug("Linked refund {} to parent payment {}",
                      refund.getProviderTransactionId(), originalPaymentId);
        } else {
            log.warn("No parent payment found for refund={} originalId={}",
                     refund.getProviderTransactionId(), originalPaymentId);
            // Leave parentTransactionId null — OrphanRefundRule will flag it
        }
    }

    @Transactional
    public void updateReconciliationStatus(Long id, ReconciliationStatus status) {
        transactionRepository.updateReconciliationStatus(
            id, status,
            status == ReconciliationStatus.MATCHED ? OffsetDateTime.now() : null,
            OffsetDateTime.now()
        );
    }

    public Optional<Transaction> findById(Long id) {
        return transactionRepository.findById(id);
    }

    public Optional<Transaction> findByProviderAndTransactionId(
            String provider, String txnId) {
        return transactionRepository
                .findByProviderAndProviderTransactionId(provider, txnId);
    }

    public List<Transaction> findByMerchantAndOrderId(
            String merchantId, String orderId) {
        return transactionRepository
                .findByMerchantIdAndOrderId(merchantId, orderId);
    }

    private String extractOriginalPaymentId(JsonNode payload, String provider) {
        return switch (provider) {
            case "razorpay" -> {
                // Razorpay refund entity contains payment_id
                JsonNode refundEntity = payload.path("payload")
                                               .path("refund").path("entity");
                yield refundEntity.path("payment_id").asText(null);
            }
            case "stripe" -> {
                // Stripe charge.refunded — the charge id IS the payment id
                JsonNode charge = payload.path("data").path("object");
                yield charge.path("payment_intent").asText(null);
            }
            default -> null;
        };
    }
}
```

---

## `user/service/UserIdentityService.java`

```java
package com.reconciliation.user.service;

import com.reconciliation.user.entity.User;
import com.reconciliation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserIdentityService {

    private final UserRepository userRepository;

    /**
     * Find or create a user by email or phone.
     * Uses SERIALIZABLE isolation to prevent duplicate user creation
     * under concurrent ingestion.
     *
     * Returns null if neither email nor phone is provided (e.g. UPI VPA only).
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Long resolveUserId(String merchantId, String email,
                              String phone, String name) {
        if ((email == null || email.isBlank()) &&
            (phone == null || phone.isBlank())) {
            return null; // UPI-only — no stable identity to resolve
        }

        String normalizedEmail = normalize(email);
        String normalizedPhone = normalizePhone(phone);

        // 1. Try by email first
        if (normalizedEmail != null) {
            Optional<User> byEmail = userRepository
                    .findByMerchantIdAndEmail(merchantId, normalizedEmail);
            if (byEmail.isPresent()) {
                updateLastSeen(byEmail.get());
                return byEmail.get().getId();
            }
        }

        // 2. Try by phone
        if (normalizedPhone != null) {
            Optional<User> byPhone = userRepository
                    .findByMerchantIdAndPhone(merchantId, normalizedPhone);
            if (byPhone.isPresent()) {
                // Backfill email if we now have it
                User u = byPhone.get();
                if (u.getEmail() == null && normalizedEmail != null) {
                    u.setEmail(normalizedEmail);
                }
                updateLastSeen(u);
                return u.getId();
            }
        }

        // 3. Create new user
        return createUser(merchantId, normalizedEmail, normalizedPhone, name);
    }

    /**
     * Increment transaction aggregates on user.
     * Called after a transaction is successfully upserted.
     */
    @Transactional
    public void incrementAggregates(Long userId, long amount, boolean failed) {
        if (userId == null) return;
        userRepository.incrementAggregates(userId, amount, failed ? 1 : 0);
    }

    private Long createUser(String merchantId, String email,
                            String phone, String name) {
        try {
            User user = User.builder()
                    .merchantId(merchantId)
                    .email(email)
                    .phone(phone)
                    .name(name)
                    .firstSeenAt(OffsetDateTime.now())
                    .lastSeenAt(OffsetDateTime.now())
                    .totalTxnCount(0)
                    .totalTxnAmount(0L)
                    .failedTxnCount(0)
                    .distinctPaymentMethods(0)
                    .build();

            User saved = userRepository.save(user);
            log.info("Created new user id={} merchantId={}", saved.getId(), merchantId);
            return saved.getId();

        } catch (DataIntegrityViolationException e) {
            // Race condition: another thread created this user simultaneously
            // Try once more to fetch
            if (email != null) {
                return userRepository.findByMerchantIdAndEmail(merchantId, email)
                        .map(User::getId).orElse(null);
            }
            return null;
        }
    }

    private void updateLastSeen(User user) {
        user.setLastSeenAt(OffsetDateTime.now());
        userRepository.save(user);
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) return null;
        return email.toLowerCase().trim();
    }

    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        // Strip spaces, dashes, dots — keep leading + for international
        String cleaned = phone.replaceAll("[\\s\\-\\.]", "").trim();
        // Ensure Indian numbers have +91 prefix
        if (cleaned.startsWith("0")) cleaned = "+91" + cleaned.substring(1);
        if (cleaned.length() == 10) cleaned = "+91" + cleaned;
        return cleaned;
    }
}
```

---

## `user/repository/UserRepository.java`

```java
package com.reconciliation.user.repository;

import com.reconciliation.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByMerchantIdAndEmail(String merchantId, String email);

    Optional<User> findByMerchantIdAndPhone(String merchantId, String phone);

    @Modifying
    @Query("""
        UPDATE User u SET
            u.totalTxnCount  = u.totalTxnCount + 1,
            u.totalTxnAmount = u.totalTxnAmount + :amount,
            u.failedTxnCount = u.failedTxnCount + :failedIncrement,
            u.lastSeenAt     = CURRENT_TIMESTAMP,
            u.updatedAt      = CURRENT_TIMESTAMP
        WHERE u.id = :userId
    """)
    void incrementAggregates(
        @Param("userId") Long userId,
        @Param("amount") long amount,
        @Param("failedIncrement") int failedIncrement
    );
}
```

---

## `user/entity/User.java`

```java
package com.reconciliation.user.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 60)
    private String merchantId;

    @Column(length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(length = 120)
    private String name;

    @Column(name = "first_seen_at")
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "total_txn_count", nullable = false)
    @Builder.Default
    private Integer totalTxnCount = 0;

    @Column(name = "total_txn_amount", nullable = false)
    @Builder.Default
    private Long totalTxnAmount = 0L;

    @Column(name = "failed_txn_count", nullable = false)
    @Builder.Default
    private Integer failedTxnCount = 0;

    @Column(name = "distinct_payment_methods", nullable = false)
    @Builder.Default
    private Integer distinctPaymentMethods = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

---

# PHASE 2 — Reconciliation Engine

---

## `exception_record/entity/ExceptionRecord.java`

```java
package com.reconciliation.exception_record.entity;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "exception_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExceptionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 60)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false,
            columnDefinition = "exception_type")
    private ExceptionType exceptionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "exception_severity")
    @Builder.Default
    private Severity severity = Severity.MEDIUM;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "expected_amount")
    private Long expectedAmount;

    @Column(name = "actual_amount")
    private Long actualAmount;

    @Column(name = "discrepancy_amount")
    private Long discrepancyAmount;

    @Column(length = 3)
    private String currency;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "exception_status")
    @Builder.Default
    private String status = "open";

    @Column(name = "resolved_by", length = 60)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (detectedAt == null) detectedAt = OffsetDateTime.now();
        if (updatedAt == null)  updatedAt  = OffsetDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

---

## `exception_record/repository/ExceptionRecordRepository.java`

```java
package com.reconciliation.exception_record.repository;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExceptionRecordRepository extends JpaRepository<ExceptionRecord, Long> {

    boolean existsByExceptionTypeAndTransactionId(
        ExceptionType type, Long transactionId
    );

    boolean existsByExceptionTypeAndSettlementId(
        ExceptionType type, Long settlementId
    );

    Page<ExceptionRecord> findByMerchantIdAndStatusAndDetectedAtAfter(
        String merchantId, String status,
        OffsetDateTime after, Pageable pageable
    );

    Page<ExceptionRecord> findByMerchantIdAndDetectedAtAfter(
        String merchantId, OffsetDateTime after, Pageable pageable
    );

    @Query("""
        SELECT e.exceptionType, COUNT(e) FROM ExceptionRecord e
        WHERE e.merchantId = :merchantId
          AND e.detectedAt > :after
        GROUP BY e.exceptionType
    """)
    List<Object[]> countByTypeForMerchant(
        @Param("merchantId") String merchantId,
        @Param("after") OffsetDateTime after
    );

    @Query("""
        SELECT COUNT(e) FROM ExceptionRecord e
        WHERE e.merchantId = :merchantId
          AND e.status = 'open'
          AND e.detectedAt > :after
    """)
    long countOpenExceptions(
        @Param("merchantId") String merchantId,
        @Param("after") OffsetDateTime after
    );
}
```

---

## `exception_record/service/ExceptionRecordService.java`

```java
package com.reconciliation.exception_record.service;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExceptionRecordService {

    private final ExceptionRecordRepository exceptionRecordRepository;

    @Transactional
    public ExceptionRecord createForTransaction(
            ExceptionType type,
            Severity severity,
            Long transactionId,
            Long expectedAmount,
            Long actualAmount,
            String currency,
            String description,
            String merchantId) {

        // Prevent duplicate exception for same transaction + type
        if (transactionId != null &&
            exceptionRecordRepository.existsByExceptionTypeAndTransactionId(
                type, transactionId)) {
            log.debug("Exception already exists type={} txnId={}", type, transactionId);
            return null;
        }

        long discrepancy = (expectedAmount != null && actualAmount != null)
                ? Math.abs(expectedAmount - actualAmount) : 0L;

        ExceptionRecord record = ExceptionRecord.builder()
                .merchantId(merchantId)
                .exceptionType(type)
                .severity(severity)
                .transactionId(transactionId)
                .expectedAmount(expectedAmount)
                .actualAmount(actualAmount)
                .discrepancyAmount(discrepancy)
                .currency(currency)
                .description(description)
                .status("open")
                .build();

        ExceptionRecord saved = exceptionRecordRepository.save(record);
        log.info("Exception created type={} severity={} txnId={} description={}",
                 type, severity, transactionId, description);
        return saved;
    }

    @Transactional
    public ExceptionRecord createForSettlement(
            ExceptionType type,
            Severity severity,
            Long settlementId,
            Long expectedAmount,
            Long actualAmount,
            String currency,
            String description,
            String merchantId) {

        if (settlementId != null &&
            exceptionRecordRepository.existsByExceptionTypeAndSettlementId(
                type, settlementId)) {
            log.debug("Exception already exists type={} settlementId={}", type, settlementId);
            return null;
        }

        long discrepancy = (expectedAmount != null && actualAmount != null)
                ? Math.abs(expectedAmount - actualAmount) : 0L;

        ExceptionRecord record = ExceptionRecord.builder()
                .merchantId(merchantId)
                .exceptionType(type)
                .severity(severity)
                .settlementId(settlementId)
                .expectedAmount(expectedAmount)
                .actualAmount(actualAmount)
                .discrepancyAmount(discrepancy)
                .currency(currency)
                .description(description)
                .status("open")
                .build();

        return exceptionRecordRepository.save(record);
    }
}
```

---

## `reconciliation/rules/ReconciliationRule.java`

```java
package com.reconciliation.reconciliation.rules;

public interface ReconciliationRule {
    String getName();
    void evaluate();
}
```

---

## `reconciliation/rules/MissingCaptureRule.java`

```java
package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MissingCaptureRule implements ReconciliationRule {

    private final TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Value("${app.reconciliation.missing-capture-threshold-hours:24}")
    private int thresholdHours;

    @Override
    public String getName() { return "MissingCaptureRule"; }

    @Override
    public void evaluate() {
        OffsetDateTime threshold = OffsetDateTime.now().minusHours(thresholdHours);

        List<Transaction> stale = transactionRepository.findStaleAuthorizedPayments(threshold);

        for (Transaction txn : stale) {
            String description = String.format(
                "Payment %s authorized but not captured after %d hours. " +
                "Provider: %s, Order: %s, Amount: %d %s. Auto-expiry risk.",
                txn.getProviderTransactionId(),
                thresholdHours,
                txn.getProvider(),
                txn.getOrderId(),
                txn.getPresentmentAmount(),
                txn.getPresentmentCurrency()
            );

            exceptionRecordService.createForTransaction(
                ExceptionType.MISSING_CAPTURE,
                Severity.HIGH,
                txn.getId(),
                txn.getPresentmentAmount(),
                null,
                txn.getPresentmentCurrency(),
                description,
                txn.getMerchantId()
            );

            log.warn("Missing capture detected txnId={} provider={} orderId={}",
                     txn.getProviderTransactionId(), txn.getProvider(), txn.getOrderId());
        }

        if (!stale.isEmpty()) {
            log.info("MissingCaptureRule flagged {} transactions", stale.size());
        }
    }
}
```

---

## `reconciliation/rules/OrphanRefundRule.java`

```java
package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.*;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrphanRefundRule implements ReconciliationRule {

    private final TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Override
    public String getName() { return "OrphanRefundRule"; }

    @Override
    public void evaluate() {
        // Refunds without a parent, ingested more than 10 minutes ago
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(10);

        List<Transaction> orphans = transactionRepository
                .findOrphanRefunds(cutoff);

        for (Transaction refund : orphans) {
            String description = String.format(
                "Refund %s has no matching parent payment. " +
                "Provider: %s, Amount: %d %s. " +
                "Possible manual refund or missing payment event.",
                refund.getProviderTransactionId(),
                refund.getProvider(),
                refund.getPresentmentAmount(),
                refund.getPresentmentCurrency()
            );

            exceptionRecordService.createForTransaction(
                ExceptionType.ORPHAN_REFUND,
                Severity.HIGH,
                refund.getId(),
                null,
                refund.getPresentmentAmount(),
                refund.getPresentmentCurrency(),
                description,
                refund.getMerchantId()
            );

            log.warn("Orphan refund detected txnId={} provider={}",
                     refund.getProviderTransactionId(), refund.getProvider());
        }
    }
}
```

---

## `reconciliation/rules/DuplicateCaptureRule.java`

```java
package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.*;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateCaptureRule implements ReconciliationRule {

    private final TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Override
    public String getName() { return "DuplicateCaptureRule"; }

    @Override
    public void evaluate() {
        List<Object[]> duplicates = transactionRepository.findDuplicateCaptureOrders();

        for (Object[] row : duplicates) {
            String merchantId = (String) row[0];
            String orderId    = (String) row[1];
            long   count      = (Long) row[2];

            List<Transaction> txns = transactionRepository
                    .findCapturedPaymentsByMerchantAndOrder(merchantId, orderId);

            for (Transaction txn : txns) {
                String description = String.format(
                    "Order %s has %d captured payments. Possible duplicate charge. " +
                    "Provider: %s, Transaction: %s, Amount: %d %s.",
                    orderId, count,
                    txn.getProvider(),
                    txn.getProviderTransactionId(),
                    txn.getPresentmentAmount(),
                    txn.getPresentmentCurrency()
                );

                exceptionRecordService.createForTransaction(
                    ExceptionType.DUPLICATE_CAPTURE,
                    Severity.CRITICAL,
                    txn.getId(),
                    null,
                    txn.getPresentmentAmount(),
                    txn.getPresentmentCurrency(),
                    description,
                    txn.getMerchantId()
                );
            }

            log.error("CRITICAL: Duplicate capture detected orderId={} count={}",
                      orderId, count);
        }
    }
}
```

---

## `reconciliation/rules/ExactIdMatchRule.java`

```java
package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.*;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExactIdMatchRule implements ReconciliationRule {

    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final ExceptionRecordService exceptionRecordService;

    @Value("${app.reconciliation.amount-tolerance-paisa:100}")
    private long tolerancePaisa;

    @Override
    public String getName() { return "ExactIdMatchRule"; }

    @Override
    public void evaluate() {
        // Find captured payments in PENDING state older than 5 minutes
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(5);
        List<Transaction> pending = transactionRepository
                .findByStatusAndReconciliationStatusAndEventOccurredAtBefore(
                    TransactionStatus.CAPTURED,
                    ReconciliationStatus.PENDING,
                    cutoff
                );

        for (Transaction txn : pending) {
            // MVP: if orderId is present, consider it matched
            // Phase 5+: cross-check with merchant's order system via API
            if (txn.getOrderId() != null && !txn.getOrderId().isBlank()) {
                transactionService.updateReconciliationStatus(
                    txn.getId(), ReconciliationStatus.MATCHED
                );
                log.debug("Matched txn={} orderId={}", txn.getProviderTransactionId(), txn.getOrderId());
            } else {
                // No order ID — flag as unmatched
                exceptionRecordService.createForTransaction(
                    ExceptionType.UNMATCHED_PAYMENT,
                    Severity.MEDIUM,
                    txn.getId(),
                    txn.getPresentmentAmount(),
                    null,
                    txn.getPresentmentCurrency(),
                    String.format("Payment %s has no order ID. Cannot reconcile to an order.",
                                  txn.getProviderTransactionId()),
                    txn.getMerchantId()
                );
                transactionService.updateReconciliationStatus(
                    txn.getId(), ReconciliationStatus.EXCEPTION
                );
            }
        }

        if (!pending.isEmpty()) {
            log.info("ExactIdMatchRule processed {} transactions", pending.size());
        }
    }
}
```

---

## `reconciliation/rules/SettlementTotalRule.java`

```java
package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementTotalRule implements ReconciliationRule {

    private final SettlementRepository settlementRepository;
    private final TransactionRepository transactionRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Value("${app.reconciliation.amount-tolerance-paisa:100}")
    private long tolerancePaisa;

    @Override
    public String getName() { return "SettlementTotalRule"; }

    @Override
    public void evaluate() {
        List<Settlement> settled = settlementRepository
                .findBySettlementStatus("settled");

        for (Settlement settlement : settled) {
            Long transactionSum = transactionRepository
                    .sumNetAmountBySettlementId(settlement.getProviderSettlementId());

            if (transactionSum == null) transactionSum = 0L;

            long diff = Math.abs(settlement.getNetAmount() - transactionSum);

            if (diff > tolerancePaisa) {
                String description = String.format(
                    "Settlement %s total mismatch. " +
                    "Expected: %d %s, Transaction sum: %d %s, Difference: %d paisa.",
                    settlement.getProviderSettlementId(),
                    settlement.getNetAmount(), settlement.getCurrency(),
                    transactionSum, settlement.getCurrency(),
                    diff
                );

                exceptionRecordService.createForSettlement(
                    ExceptionType.SETTLEMENT_DISCREPANCY,
                    Severity.CRITICAL,
                    settlement.getId(),
                    settlement.getNetAmount(),
                    transactionSum,
                    settlement.getCurrency(),
                    description,
                    settlement.getMerchantId()
                );

                log.error("CRITICAL settlement discrepancy: settlementId={} diff={}",
                          settlement.getProviderSettlementId(), diff);

                // Mark settlement as discrepant
                settlement.setSettlementStatus("discrepant");
                settlementRepository.save(settlement);
            }
        }
    }
}
```

---

## `reconciliation/service/ReconciliationEngine.java`

```java
package com.reconciliation.reconciliation.service;

import com.reconciliation.reconciliation.rules.ReconciliationRule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@Slf4j
public class ReconciliationEngine {

    private final List<ReconciliationRule> rules;
    private final Counter exceptionsCreated;
    private final Timer reconciliationTimer;

    public ReconciliationEngine(List<ReconciliationRule> rules,
                                MeterRegistry meterRegistry) {
        this.rules = rules;
        this.exceptionsCreated = Counter.builder("reconciliation.exceptions.created")
                .description("Total exceptions created by reconciliation engine")
                .register(meterRegistry);
        this.reconciliationTimer = Timer.builder("reconciliation.run.duration")
                .description("Time taken to run all reconciliation rules")
                .register(meterRegistry);
    }

    /**
     * Runs all rules in sequence.
     * One rule failing never stops the others.
     * Spring auto-discovers all ReconciliationRule beans.
     */
    public void runAll() {
        reconciliationTimer.record(() -> {
            log.info("Starting reconciliation engine — {} rules loaded", rules.size());

            for (ReconciliationRule rule : rules) {
                try {
                    log.debug("Running rule: {}", rule.getName());
                    rule.evaluate();
                    log.debug("Completed rule: {}", rule.getName());
                } catch (Exception e) {
                    log.error("Rule {} failed: {}", rule.getName(), e.getMessage(), e);
                    // Continue to next rule — one failure must not block others
                }
            }

            log.info("Reconciliation engine completed");
        });
    }
}
```

---

## `reconciliation/job/ReconciliationJob.java` (complete)

```java
package com.reconciliation.reconciliation.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.reconciliation.reconciliation.service.ReconciliationEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
@Slf4j
public class ReconciliationJob {

    private final ReconciliationEngine engine;

    public ReconciliationJob(ReconciliationEngine engine) {
        this.engine = engine;
    }

    @Bean
    public RecurringTask<Void> reconciliationTask() {
        return new RecurringTask<Void>(
                "reconciliation-engine",
                Schedules.fixedDelay(Duration.ofMinutes(5)),
                Void.class) {

            @Override
            public void executeRecurringTask(
                    TaskInstance<Void> taskInstance,
                    ExecutionContext executionContext) {
                log.info("Reconciliation job triggered by db-scheduler");
                try {
                    engine.runAll();
                } catch (Exception e) {
                    log.error("Reconciliation job failed", e);
                }
            }
        };
    }
}
```

---

## `reconciliation/job/GapFillerJob.java`

```java
package com.reconciliation.reconciliation.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.reconciliation.polling.service.RazorpayPollingService;
import com.reconciliation.polling.service.StripePollingService;
import com.reconciliation.webhook.service.WebhookIngestionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Component
@Slf4j
public class GapFillerJob {

    private final RazorpayPollingService razorpayPollingService;
    private final StripePollingService stripePollingService;
    private final WebhookIngestionService ingestionService;
    private final Counter gapsFilled;

    @Value("${app.polling.gap-filler-lookback-minutes:30}")
    private int lookbackMinutes;

    public GapFillerJob(RazorpayPollingService razorpayPollingService,
                        StripePollingService stripePollingService,
                        WebhookIngestionService ingestionService,
                        MeterRegistry meterRegistry) {
        this.razorpayPollingService = razorpayPollingService;
        this.stripePollingService   = stripePollingService;
        this.ingestionService       = ingestionService;
        this.gapsFilled = Counter.builder("polling.gaps.filled")
                .description("Events picked up by polling that were missed by webhooks")
                .register(meterRegistry);
    }

    @Bean
    public RecurringTask<Void> gapFillerTask() {
        return new RecurringTask<Void>(
                "gap-filler",
                Schedules.fixedDelay(Duration.ofMinutes(15)),
                Void.class) {

            @Override
            public void executeRecurringTask(
                    TaskInstance<Void> taskInstance,
                    ExecutionContext executionContext) {
                log.info("Gap filler job starting");
                try {
                    OffsetDateTime to   = OffsetDateTime.now();
                    OffsetDateTime from = to.minusMinutes(lookbackMinutes);
                    runForWindow(from, to);
                } catch (Exception e) {
                    log.error("Gap filler job failed", e);
                }
            }
        };
    }

    /**
     * Also callable manually from AdminController for recovery scenarios.
     */
    public void runForWindow(OffsetDateTime from, OffsetDateTime to) {
        log.info("Gap filler running window {} to {}", from, to);

        fillRazorpay(from, to);
        fillStripe(from, to);
    }

    private void fillRazorpay(OffsetDateTime from, OffsetDateTime to) {
        try {
            List<byte[]> payments = razorpayPollingService.fetchPayments(from, to);
            List<byte[]> refunds  = razorpayPollingService.fetchRefunds(from, to);

            int count = 0;
            for (byte[] payload : payments) {
                ingestionService.ingestAsync(payload, "razorpay", "polling");
                count++;
            }
            for (byte[] payload : refunds) {
                ingestionService.ingestAsync(payload, "razorpay", "polling");
                count++;
            }

            gapsFilled.increment(count);
            log.info("Razorpay gap filler: {} events fetched", count);

        } catch (Exception e) {
            log.error("Razorpay gap filler failed: {}", e.getMessage(), e);
        }
    }

    private void fillStripe(OffsetDateTime from, OffsetDateTime to) {
        try {
            List<byte[]> charges = stripePollingService.fetchCharges(from, to);
            List<byte[]> refunds = stripePollingService.fetchRefunds(from, to);

            int count = 0;
            for (byte[] payload : charges) {
                ingestionService.ingestAsync(payload, "stripe", "polling");
                count++;
            }
            for (byte[] payload : refunds) {
                ingestionService.ingestAsync(payload, "stripe", "polling");
                count++;
            }

            gapsFilled.increment(count);
            log.info("Stripe gap filler: {} events fetched", count);

        } catch (Exception e) {
            log.error("Stripe gap filler failed: {}", e.getMessage(), e);
        }
    }
}
```

---

## `polling/service/RazorpayPollingService.java`

```java
package com.reconciliation.polling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.RazorpayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RazorpayPollingService {

    private final RazorpayClient razorpayClient;
    private final ObjectMapper objectMapper;

    private static final int PAGE_SIZE = 100;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public List<byte[]> fetchPayments(OffsetDateTime from, OffsetDateTime to) {
        log.debug("Polling Razorpay payments from={} to={}", from, to);
        return fetchPaginated("payments", from, to);
    }

    public List<byte[]> fetchRefunds(OffsetDateTime from, OffsetDateTime to) {
        log.debug("Polling Razorpay refunds from={} to={}", from, to);
        return fetchPaginated("refunds", from, to);
    }

    private List<byte[]> fetchPaginated(String entity, OffsetDateTime from, OffsetDateTime to) {
        List<byte[]> results = new ArrayList<>();
        int skip = 0;

        while (true) {
            try {
                org.json.JSONObject options = new org.json.JSONObject();
                options.put("from",  from.toEpochSecond());
                options.put("to",    to.toEpochSecond());
                options.put("count", PAGE_SIZE);
                options.put("skip",  skip);

                org.json.JSONArray items = fetchWithRetry(entity, options);

                if (items == null || items.length() == 0) break;

                for (int i = 0; i < items.length(); i++) {
                    // Wrap each item in a synthetic webhook-like envelope
                    // so our normalization service can parse it uniformly
                    org.json.JSONObject envelope = new org.json.JSONObject();
                    envelope.put("id",    "poll_" + entity + "_" + skip + "_" + i);
                    envelope.put("event", entity.equals("payments")
                                         ? "payment.captured" : "refund.processed");
                    envelope.put("payload", new org.json.JSONObject()
                            .put(entity.equals("payments") ? "payment" : "refund",
                                 new org.json.JSONObject().put("entity", items.get(i))));

                    results.add(envelope.toString().getBytes());
                }

                if (items.length() < PAGE_SIZE) break;
                skip += PAGE_SIZE;

            } catch (Exception e) {
                log.error("Razorpay polling failed for {}: {}", entity, e.getMessage());
                break;
            }
        }

        return results;
    }

    private org.json.JSONArray fetchWithRetry(String entity, org.json.JSONObject options) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                // RazorpayClient API differs by entity type
                if ("payments".equals(entity)) {
                    return razorpayClient.payments.fetchAll(options).toJSONArray();
                } else {
                    return razorpayClient.refunds.fetchAll(options).toJSONArray();
                }
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) throw new RuntimeException(e);
                log.warn("Razorpay API retry {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
                try { Thread.sleep(RETRY_DELAY_MS * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return null;
    }
}
```

---

## `polling/service/StripePollingService.java`

```java
package com.reconciliation.polling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Charge;
import com.stripe.model.ChargeCollection;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.param.ChargeListParams;
import com.stripe.param.RefundListParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class StripePollingService {

    private final ObjectMapper objectMapper;

    public StripePollingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<byte[]> fetchCharges(OffsetDateTime from, OffsetDateTime to) {
        List<byte[]> results = new ArrayList<>();
        String startingAfter = null;

        try {
            while (true) {
                ChargeListParams.Builder paramsBuilder = ChargeListParams.builder()
                        .setCreatedRange(ChargeListParams.Created.builder()
                                .setGte(from.toEpochSecond())
                                .setLte(to.toEpochSecond())
                                .build())
                        .setLimit(100L);

                if (startingAfter != null) {
                    paramsBuilder.setStartingAfter(startingAfter);
                }

                ChargeCollection collection = Charge.list(paramsBuilder.build());

                for (Charge charge : collection.getData()) {
                    // Wrap in envelope matching Stripe webhook structure
                    String envelope = String.format(
                        "{\"id\":\"poll_%s\",\"type\":\"charge.succeeded\"," +
                        "\"created\":%d,\"data\":{\"object\":%s}}",
                        charge.getId(),
                        charge.getCreated(),
                        charge.toJson()
                    );
                    results.add(envelope.getBytes());
                    startingAfter = charge.getId();
                }

                if (!collection.getHasMore()) break;
            }
        } catch (Exception e) {
            log.error("Stripe charges polling failed: {}", e.getMessage(), e);
        }

        log.debug("Fetched {} charges from Stripe", results.size());
        return results;
    }

    public List<byte[]> fetchRefunds(OffsetDateTime from, OffsetDateTime to) {
        List<byte[]> results = new ArrayList<>();
        String startingAfter = null;

        try {
            while (true) {
                RefundListParams.Builder paramsBuilder = RefundListParams.builder()
                        .setCreatedRange(RefundListParams.Created.builder()
                                .setGte(from.toEpochSecond())
                                .setLte(to.toEpochSecond())
                                .build())
                        .setLimit(100L);

                if (startingAfter != null) {
                    paramsBuilder.setStartingAfter(startingAfter);
                }

                RefundCollection collection = Refund.list(paramsBuilder.build());

                for (Refund refund : collection.getData()) {
                    String envelope = String.format(
                        "{\"id\":\"poll_%s\",\"type\":\"refund.created\"," +
                        "\"created\":%d,\"data\":{\"object\":%s}}",
                        refund.getId(),
                        refund.getCreated(),
                        refund.toJson()
                    );
                    results.add(envelope.getBytes());
                    startingAfter = refund.getId();
                }

                if (!collection.getHasMore()) break;
            }
        } catch (Exception e) {
            log.error("Stripe refunds polling failed: {}", e.getMessage(), e);
        }

        return results;
    }
}
```

---

## Additional Repository Methods — add to `TransactionRepository.java`

```java
// Add these methods to the existing TransactionRepository interface

@Query("""
    SELECT t FROM Transaction t
    WHERE t.eventType = com.reconciliation.common.enums.EventType.REFUND
      AND t.parentTransactionId IS NULL
      AND t.ingestedAt < :cutoff
""")
List<Transaction> findOrphanRefunds(@Param("cutoff") OffsetDateTime cutoff);

@Query("""
    SELECT t.merchantId, t.orderId, COUNT(t)
    FROM Transaction t
    WHERE t.status = com.reconciliation.common.enums.TransactionStatus.CAPTURED
      AND t.eventType = com.reconciliation.common.enums.EventType.PAYMENT
      AND t.orderId IS NOT NULL
    GROUP BY t.merchantId, t.orderId
    HAVING COUNT(t) > 1
""")
List<Object[]> findDuplicateCaptureOrders();

@Query("""
    SELECT COALESCE(SUM(t.netAmount), 0)
    FROM Transaction t
    WHERE t.settlementId = :settlementId
""")
Long sumNetAmountBySettlementId(@Param("settlementId") String settlementId);
```

---

## `settlement/repository/SettlementRepository.java`

```java
package com.reconciliation.settlement.repository;

import com.reconciliation.settlement.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByProviderAndProviderSettlementId(
        String provider, String providerSettlementId
    );

    List<Settlement> findBySettlementStatus(String settlementStatus);

    List<Settlement> findByMerchantIdOrderBySettledAtDesc(String merchantId);
}
```

---

# PHASE 3 — API + Dashboard

---

## `config/JwtConfig.java`

```java
package com.reconciliation.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.Key;
import java.util.Date;

@Component
public class JwtConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiry-hours:24}")
    private int expiryHours;

    private Key getKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateToken(String email) {
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(
                    System.currentTimeMillis() + (long) expiryHours * 3600 * 1000
                ))
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
```

---

## `config/JwtFilter.java`

```java
package com.reconciliation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtConfig jwtConfig;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtConfig.isValid(token)) {
                String email = jwtConfig.extractEmail(token);
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                        email, null, List.of()
                    );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

Update `SecurityConfig.java` — add this line before `return http.build()`:
```java
http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
```

Also add to `application.yml`:
```yaml
app:
  jwt:
    secret: ${JWT_SECRET}   # min 32 chars
    expiry-hours: 24
```

---

## `exception_record/controller/ExceptionController.java`

```java
package com.reconciliation.exception_record.controller;

import com.reconciliation.audit.service.AuditService;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/exceptions")
@RequiredArgsConstructor
public class ExceptionController {

    private final ExceptionRecordRepository exceptionRecordRepository;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> listExceptions(
            @RequestParam(defaultValue = "7")   int    days,
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "50")   int    limit,
            Authentication auth) {

        String merchantId = "merchant_001"; // Phase 6: derive from auth
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);
        PageRequest pageable = PageRequest.of(page, limit,
                Sort.by(Sort.Direction.DESC, "detectedAt"));

        Page<ExceptionRecord> results;
        if ("all".equals(status)) {
            results = exceptionRecordRepository
                    .findByMerchantIdAndDetectedAtAfter(merchantId, since, pageable);
        } else {
            results = exceptionRecordRepository
                    .findByMerchantIdAndStatusAndDetectedAtAfter(
                        merchantId, status, since, pageable);
        }

        long openCount = exceptionRecordRepository
                .countOpenExceptions(merchantId, since);

        return ResponseEntity.ok(Map.of(
            "summary", Map.of(
                "totalOpen", openCount,
                "page",  page,
                "limit", limit,
                "totalPages", results.getTotalPages()
            ),
            "exceptions", results.getContent()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExceptionRecord> getException(@PathVariable Long id) {
        return exceptionRecordRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ExceptionRecord> resolveException(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {

        return exceptionRecordRepository.findById(id).map(exception -> {
            String oldStatus = exception.getStatus();
            String newStatus = body.get("status");
            String notes     = body.get("notes");

            exception.setStatus(newStatus);
            exception.setResolutionNotes(notes);
            exception.setResolvedBy(auth.getName());
            if ("resolved".equals(newStatus) || "ignored".equals(newStatus)) {
                exception.setResolvedAt(OffsetDateTime.now());
            }

            ExceptionRecord saved = exceptionRecordRepository.save(exception);

            // Audit trail
            auditService.log(
                auth.getName(),
                "exception_" + newStatus,
                "exception",
                id,
                Map.of("status", oldStatus),
                Map.of("status", newStatus, "notes", notes != null ? notes : ""),
                request.getRemoteAddr()
            );

            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }
}
```

---

## `transaction/controller/TransactionController.java`

```java
package com.reconciliation.transaction.controller;

import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository transactionRepository;

    @GetMapping
    public ResponseEntity<Page<Transaction>> listTransactions(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String orderId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int limit) {

        PageRequest pageable = PageRequest.of(page, limit,
                Sort.by(Sort.Direction.DESC, "eventOccurredAt"));

        // Simple filter — add Specification pattern in Phase 6 for complex filters
        Page<Transaction> results = transactionRepository
                .findAll(pageable);

        return ResponseEntity.ok(results);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getTransaction(@PathVariable Long id) {
        return transactionRepository.findById(id).map(txn -> {
            // Fetch related refunds / chargebacks
            // In Phase 5: also fetch user profile
            return ResponseEntity.ok(Map.of("transaction", txn));
        }).orElse(ResponseEntity.notFound().build());
    }
}
```

---

## `settlement/controller/SettlementController.java`

```java
package com.reconciliation.settlement.controller;

import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementRepository settlementRepository;
    private final TransactionRepository transactionRepository;

    @GetMapping
    public ResponseEntity<List<Settlement>> listSettlements() {
        return ResponseEntity.ok(
            settlementRepository.findAll()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getSettlement(@PathVariable Long id) {
        return settlementRepository.findById(id).map(settlement -> {
            Long txnSum = transactionRepository
                    .sumNetAmountBySettlementId(settlement.getProviderSettlementId());

            return ResponseEntity.ok(Map.of(
                "settlement", settlement,
                "transactionSum", txnSum != null ? txnSum : 0L,
                "discrepancy", Math.abs(
                    settlement.getNetAmount() - (txnSum != null ? txnSum : 0L)
                )
            ));
        }).orElse(ResponseEntity.notFound().build());
    }
}
```

---

## `dashboard/controller/DashboardController.java`

```java
package com.reconciliation.dashboard.controller;

import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final TransactionRepository transactionRepository;
    private final ExceptionRecordRepository exceptionRecordRepository;

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(defaultValue = "7") int days) {

        String merchantId = "merchant_001";
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);

        long total   = transactionRepository.count();
        long matched = transactionRepository
                .countByReconciliationStatus("MATCHED");
        long open    = exceptionRecordRepository
                .countOpenExceptions(merchantId, since);

        double matchRate = total > 0 ? (double) matched / total * 100 : 0;

        // Exception breakdown by type
        List<Object[]> byType = exceptionRecordRepository
                .countByTypeForMerchant(merchantId, since);

        Map<String, Long> exceptionByType = new HashMap<>();
        for (Object[] row : byType) {
            exceptionByType.put(row[0].toString(), (Long) row[1]);
        }

        return ResponseEntity.ok(Map.of(
            "periodDays",        days,
            "totalTransactions", total,
            "matched",           matched,
            "openExceptions",    open,
            "matchRate",         Math.round(matchRate * 10.0) / 10.0,
            "exceptionsByType",  exceptionByType
        ));
    }
}
```

---

## `admin/controller/AdminController.java`

```java
package com.reconciliation.admin.controller;

import com.reconciliation.reconciliation.job.GapFillerJob;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import com.reconciliation.webhook.service.WebhookIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final GapFillerJob gapFillerJob;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookIngestionService ingestionService;

    /**
     * Manually trigger gap filler for a specific window.
     * Use for recovery after downtime.
     *
     * POST /api/v1/admin/poll
     * Body: { "from": "2026-04-14T10:00:00Z", "to": "2026-04-14T12:00:00Z" }
     */
    @PostMapping("/poll")
    public ResponseEntity<Map<String, Object>> manualPoll(
            @RequestBody Map<String, String> body) {

        OffsetDateTime from = OffsetDateTime.parse(body.get("from"));
        OffsetDateTime to   = OffsetDateTime.parse(body.get("to"));

        log.info("Manual poll triggered: {} to {}", from, to);

        gapFillerJob.runForWindow(from, to);

        return ResponseEntity.ok(Map.of(
            "message", "Gap fill triggered for window " + from + " to " + to,
            "status", "processing"
        ));
    }

    /**
     * Replay a failed webhook event by ID.
     * Resets processed=false so worker picks it up again.
     *
     * POST /api/v1/admin/replay
     * Body: { "webhookEventId": 12345 }
     */
    @PostMapping("/replay")
    public ResponseEntity<Map<String, Object>> replay(
            @RequestBody Map<String, Long> body) {

        Long eventId = body.get("webhookEventId");

        return webhookEventRepository.findById(eventId).map(event -> {
            log.info("Replaying webhook event id={} provider={} type={}",
                     eventId, event.getProvider(), event.getEventType());

            event.setProcessed(false);
            event.setProcessedAt(null);
            event.setProcessingError(null);
            webhookEventRepository.save(event);

            // Re-queue for processing
            ingestionService.ingestAsync(
                event.getPayload().toString().getBytes(),
                event.getProvider(),
                "replay"
            );

            return ResponseEntity.ok(Map.of(
                "message", "Event queued for reprocessing",
                "eventId", eventId
            ));
        }).orElse(ResponseEntity.notFound().build());
    }
}
```

---

## `audit/service/AuditService.java`

```java
package com.reconciliation.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.audit.entity.AuditLog;
import com.reconciliation.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.net.InetAddress;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Async
    public void log(String actor, String action, String entityType,
                    Long entityId, Object oldValue, Object newValue,
                    String ipAddress) {
        try {
            AuditLog entry = AuditLog.builder()
                    .actor(actor)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .oldValue(objectMapper.convertValue(oldValue, com.fasterxml.jackson.databind.JsonNode.class))
                    .newValue(objectMapper.convertValue(newValue, com.fasterxml.jackson.databind.JsonNode.class))
                    .ipAddress(ipAddress)
                    .createdAt(OffsetDateTime.now())
                    .build();

            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write audit log: {}", e.getMessage());
        }
    }
}
```

---

## `audit/entity/AuditLog.java`

```java
package com.reconciliation.audit.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String actor;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", length = 30)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private JsonNode oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private JsonNode newValue;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
```

---

## `audit/repository/AuditLogRepository.java`

```java
package com.reconciliation.audit.repository;

import com.reconciliation.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByEntityTypeAndEntityId(
        String entityType, Long entityId, Pageable pageable
    );

    Page<AuditLog> findByActor(String actor, Pageable pageable);
}
```

---

# PHASE 4 — Hardening

---

## `common/util/EncryptionService.java`

```java
package com.reconciliation.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for PII fields (email, phone).
 * Key must be exactly 32 bytes. Store in secrets manager — never in code.
 */
@Service
public class EncryptionService {

    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH  = 12;   // 96 bits for GCM
    private static final int    TAG_LENGTH = 128;  // bits

    private final SecretKeySpec keySpec;

    public EncryptionService(@Value("${app.encryption.key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "Encryption key must be 32 bytes (256-bit). Got: " + keyBytes.length
            );
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec,
                        new GCMParameterSpec(TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));

            // Prepend IV to ciphertext so we can decrypt later
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv,        0, combined, 0,         IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            byte[] iv        = new byte[IV_LENGTH];
            byte[] encrypted = new byte[combined.length - IV_LENGTH];

            System.arraycopy(combined, 0,         iv,        0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                        new GCMParameterSpec(TAG_LENGTH, iv));

            return new String(cipher.doFinal(encrypted), "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
```

Add to `.env.example`:
```bash
# Generate with: openssl rand -base64 32
APP_ENCRYPTION_KEY=your_32_byte_base64_encoded_key
```

Add to `application.yml`:
```yaml
app:
  encryption:
    key: ${APP_ENCRYPTION_KEY}
```

---

## `config/RateLimitConfig.java`

Add `bucket4j` to `pom.xml` first:
```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>
```

```java
package com.reconciliation.config;

import io.github.bucket4j.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limit webhook endpoints to 1000 requests/minute per IP.
 * Prevents a misconfigured or malicious provider from flooding the queue.
 */
@Component
public class RateLimitConfig implements Filter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        String path = httpReq.getRequestURI();

        // Only rate-limit webhook endpoints
        if (!path.startsWith("/webhooks/")) {
            chain.doFilter(request, response);
            return;
        }

        String ip = httpReq.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(ip, this::newBucket);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            HttpServletResponse httpResp = (HttpServletResponse) response;
            httpResp.setStatus(429);
            httpResp.getWriter().write("{\"error\":\"Too many requests\"}");
        }
    }

    private Bucket newBucket(String ip) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(1000,
                    Refill.intervally(1000, Duration.ofMinutes(1))))
                .build();
    }
}
```

---

## `ReconciliationApplication.java`

```java
package com.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
```

---

## `TransactionRepository.java` — add missing count method

```java
// Add to TransactionRepository interface
@Query("SELECT COUNT(t) FROM Transaction t WHERE t.reconciliationStatus = :status")
long countByReconciliationStatus(@Param("status") String status);
```

---

# TESTS

---

## `webhook/RazorpaySignatureServiceTest.java`

```java
package com.reconciliation.webhook;

import com.reconciliation.webhook.service.RazorpaySignatureService;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignatureServiceTest {

    private static final String SECRET = "test_webhook_secret_12345";
    private final RazorpaySignatureService service =
        new RazorpaySignatureService(SECRET);

    @Test
    void validSignature_returnsTrue() throws Exception {
        String body = "{\"event\":\"payment.captured\",\"id\":\"evt_test123\"}";
        String sig  = computeHmac(body, SECRET);

        assertThat(service.verify(body.getBytes(), sig)).isTrue();
    }

    @Test
    void invalidSignature_returnsFalse() {
        String body = "{\"event\":\"payment.captured\",\"id\":\"evt_test123\"}";
        assertThat(service.verify(body.getBytes(), "invalidsignature")).isFalse();
    }

    @Test
    void tamperedBody_returnsFalse() throws Exception {
        String originalBody  = "{\"event\":\"payment.captured\",\"amount\":1000}";
        String tamperedBody  = "{\"event\":\"payment.captured\",\"amount\":9999}";
        String sig = computeHmac(originalBody, SECRET);

        assertThat(service.verify(tamperedBody.getBytes(), sig)).isFalse();
    }

    private String computeHmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
    }
}
```

---

## `reconciliation/ReconciliationEngineTest.java`

```java
package com.reconciliation.reconciliation;

import com.reconciliation.common.enums.*;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.reconciliation.rules.MissingCaptureRule;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MissingCaptureRuleTest {

    @Mock TransactionRepository transactionRepository;
    @Mock ExceptionRecordService exceptionRecordService;

    @InjectMocks MissingCaptureRule rule;

    @Test
    void authorizedPaymentOlderThan24h_createsException() {
        Transaction stale = Transaction.builder()
                .id(1L)
                .providerTransactionId("pay_test123")
                .provider("razorpay")
                .status(TransactionStatus.AUTHORIZED)
                .eventOccurredAt(OffsetDateTime.now().minusHours(26))
                .presentmentAmount(50000L)
                .presentmentCurrency("INR")
                .merchantId("merchant_001")
                .build();

        when(transactionRepository.findStaleAuthorizedPayments(any()))
                .thenReturn(List.of(stale));

        rule.evaluate();

        verify(exceptionRecordService).createForTransaction(
            eq(ExceptionType.MISSING_CAPTURE),
            eq(Severity.HIGH),
            eq(1L),
            eq(50000L),
            isNull(),
            eq("INR"),
            anyString(),
            eq("merchant_001")
        );
    }

    @Test
    void noStaleAuthorizations_createsNoExceptions() {
        when(transactionRepository.findStaleAuthorizedPayments(any()))
                .thenReturn(List.of());

        rule.evaluate();

        verifyNoInteractions(exceptionRecordService);
    }
}
```

---

## `webhook/NormalizationServiceTest.java`

```java
package com.reconciliation.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.common.enums.*;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.service.NormalizationService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NormalizationServiceTest {

    private final NormalizationService service = new NormalizationService();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String RAZORPAY_CAPTURED = """
        {
          "id": "evt_razorpay_001",
          "event": "payment.captured",
          "payload": {
            "payment": {
              "entity": {
                "id": "pay_test123",
                "amount": 50000,
                "currency": "INR",
                "status": "captured",
                "order_id": "order_001",
                "method": "card",
                "fee": 1180,
                "tax": 180,
                "email": "user@example.com",
                "contact": "+919876543210",
                "created_at": 1713000000
              }
            }
          }
        }
        """;

    @Test
    void razorpayPaymentCaptured_normalizesCorrectly() throws Exception {
        JsonNode payload = mapper.readTree(RAZORPAY_CAPTURED);

        Transaction txn = service.normalizeRazorpayPaymentCaptured(
            payload, "merchant_001"
        );

        assertThat(txn.getProviderTransactionId()).isEqualTo("pay_test123");
        assertThat(txn.getProvider()).isEqualTo("razorpay");
        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.CAPTURED);
        assertThat(txn.getPresentmentAmount()).isEqualTo(50000L);
        assertThat(txn.getPresentmentCurrency()).isEqualTo("INR");
        assertThat(txn.getFeeAmount()).isEqualTo(1180L);
        assertThat(txn.getTaxAmount()).isEqualTo(180L);
        assertThat(txn.getNetAmount()).isEqualTo(50000L - 1180L - 180L);
        assertThat(txn.getOrderId()).isEqualTo("order_001");
        assertThat(txn.getPayerEmail()).isEqualTo("user@example.com");
        assertThat(txn.getMerchantId()).isEqualTo("merchant_001");
        assertThat(txn.getReconciliationStatus())
            .isEqualTo(ReconciliationStatus.PENDING_SETTLEMENT);
    }

    @Test
    void amountsAreAlwaysBigInt_neverFloat() throws Exception {
        JsonNode payload = mapper.readTree(RAZORPAY_CAPTURED);
        Transaction txn = service.normalizeRazorpayPaymentCaptured(
            payload, "merchant_001"
        );
        // This test fails if anyone changes amount fields to Double/Float
        assertThat(txn.getPresentmentAmount()).isInstanceOf(Long.class);
        assertThat(txn.getFeeAmount()).isInstanceOf(Long.class);
    }
}
```

---

# ENVIRONMENT VARIABLES — FINAL REFERENCE

```bash
# .env (local only — NEVER commit this file)

# Database
DB_URL=jdbc:postgresql://localhost:5432/reconciliation_dev
DB_USERNAME=recon_user
DB_PASSWORD=recon_pass

# Razorpay (use TEST keys during development)
RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxx
RAZORPAY_KEY_SECRET=your_key_secret
RAZORPAY_WEBHOOK_SECRET=your_webhook_secret

# Stripe (use test keys during development)
STRIPE_SECRET_KEY=sk_test_xxxxxxxxxxxx
STRIPE_WEBHOOK_SECRET=whsec_xxxxxxxxxxxx

# JWT
JWT_SECRET=your_minimum_32_character_jwt_secret_key_here

# Encryption (generate: openssl rand -base64 32)
APP_ENCRYPTION_KEY=your_base64_encoded_32_byte_key

# App
SPRING_PROFILES_ACTIVE=local
MERCHANT_ID=merchant_001
```

---

# pom.xml — ADDITIONS for Phase 3 and 4

Add these to existing pom.xml:

```xml
<!-- JWT — Phase 3 -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>

<!-- Rate limiting — Phase 4 -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>

<!-- JSON for Razorpay SDK -->
<dependency>
    <groupId>org.json</groupId>
    <artifactId>json</artifactId>
    <version>20240303</version>
</dependency>
```
