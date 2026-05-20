package com.reconciliation.transaction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reconciliation.common.enums.EventType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.common.util.CurrencyUtils;
import com.reconciliation.common.util.TimestampUtils;
import com.reconciliation.transaction.entity.Transaction;
import java.util.Map;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    public Transaction normalizeStripeChargeSucceeded(
            JsonNode payload, String merchantId) {

        JsonNode charge = stripeObject(payload);
        JsonNode pmd = charge.path("payment_method_details");

        return Transaction.builder()
                .provider("stripe")
                .providerTransactionId(charge.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .eventType(EventType.PAYMENT)
                .status(TransactionStatus.CAPTURED)
                .presentmentAmount(charge.path("amount").asLong(0))
                .presentmentCurrency(currency(charge.path("currency").asText("USD")))
                // Stripe fees are NOT in the webhook — fetched by polling separately
                .feeAmount(null)
                .taxAmount(null)
                .netAmount(null)
                .payerEmail(charge.path("receipt_email").asText(
                    charge.path("billing_details").path("email").asText(null)))
                .payerName(charge.path("billing_details").path("name").asText(null))
                .paymentMethod(pmd.path("type").asText(null))
                .cardLast4(pmd.path("card").path("last4").asText(null))
                .cardNetwork(pmd.path("card").path("brand").asText(null))
                .eventOccurredAt(fromUnix(payload.path("created").asLong()))
                .capturedAt(OffsetDateTime.now())
                .reconciliationStatus(ReconciliationStatus.PENDING_SETTLEMENT)
                .build();
    }

    public Transaction normalizeStripePaymentSucceeded(
            JsonNode payload, String merchantId) {
        // charge.succeeded is the primary handler for Stripe payments — it carries
        // payment_method_details directly. payment_intent.succeeded is skipped.
        log.debug("payment_intent.succeeded — handled by charge.succeeded, skipping");
        return null;
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

        JsonNode firstRefund = charge.path("refunds").path("data").get(0);
        String refundId = (firstRefund != null && !firstRefund.isMissingNode())
                ? firstRefund.path("id").asText()
                : "re_" + charge.path("id").asText();

        return Transaction.builder()
                .provider("stripe")
                .providerTransactionId(refundId)
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

    public Transaction normalizeStripeRefundCreated(
            JsonNode payload, String merchantId) {

        JsonNode refund = stripeObject(payload);

        return Transaction.builder()
                .provider("stripe")
                .providerTransactionId(refund.path("id").asText())
                .providerEventId(payload.path("id").asText(null))
                .merchantId(merchantId)
                .eventType(EventType.REFUND)
                .status(TransactionStatus.REFUNDED)
                .presentmentAmount(refund.path("amount").asLong(0))
                .presentmentCurrency(currency(refund.path("currency").asText("USD")))
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
                .payerName(firstText(
                        p.path("name").asText(null),
                        p.path("card").path("name").asText(null)))
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

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private OffsetDateTime fromUnix(long epochSeconds) {
        if (epochSeconds == 0) return OffsetDateTime.now();
        return OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(epochSeconds), java.time.ZoneOffset.UTC
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

}
