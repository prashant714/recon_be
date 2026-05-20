package com.reconciliation.transaction.service;

import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public Optional<Transaction> findByProviderAndTransactionId(String provider, String providerTransactionId) {
        return transactionRepository.findByProviderAndProviderTransactionId(provider, providerTransactionId);
    }

    @Transactional
    public Transaction upsert(Transaction incoming) {
        transactionRepository.lockProviderTransactionId(
                incoming.getProvider(), incoming.getProviderTransactionId());

        Optional<Transaction> existing = transactionRepository.findByProviderAndProviderTransactionId(
                incoming.getProvider(), incoming.getProviderTransactionId());

        if (existing.isEmpty()) {
            return transactionRepository.save(incoming);
        }

        Transaction current = existing.get();
        if (incoming.getEventOccurredAt() != null && current.getEventOccurredAt() != null) {
            if (incoming.getEventOccurredAt().isBefore(current.getEventOccurredAt())) {
                return current;
            }
            if (incoming.getEventOccurredAt().isEqual(current.getEventOccurredAt())
                    && !isMeaningfulStateAdvance(current, incoming)) {
                return current;
            }
        }

        current.setProviderEventId(incoming.getProviderEventId());
        current.setStatus(incoming.getStatus());
        current.setEventType(incoming.getEventType());
        current.setOrderId(firstNonBlank(current.getOrderId(), incoming.getOrderId()));
        current.setProviderOrderId(firstNonBlank(current.getProviderOrderId(), incoming.getProviderOrderId()));
        current.setParentTransactionId(incoming.getParentTransactionId());
        current.setFeeAmount(coalesce(incoming.getFeeAmount(), current.getFeeAmount()));
        current.setTaxAmount(coalesce(incoming.getTaxAmount(), current.getTaxAmount()));
        current.setNetAmount(coalesce(incoming.getNetAmount(), current.getNetAmount()));
        current.setSettlementAmount(coalesce(incoming.getSettlementAmount(), current.getSettlementAmount()));
        current.setSettlementCurrency(firstNonBlank(current.getSettlementCurrency(), incoming.getSettlementCurrency()));
        current.setSettlementId(firstNonBlank(current.getSettlementId(), incoming.getSettlementId()));
        current.setSettlementDate(incoming.getSettlementDate() != null ? incoming.getSettlementDate() : current.getSettlementDate());
        current.setUtrNumber(firstNonBlank(current.getUtrNumber(), incoming.getUtrNumber()));
        current.setPaymentMethod(firstNonBlank(current.getPaymentMethod(), incoming.getPaymentMethod()));
        current.setPaymentMethodDetail(firstNonBlank(current.getPaymentMethodDetail(), incoming.getPaymentMethodDetail()));
        current.setCardLast4(firstNonBlank(current.getCardLast4(), incoming.getCardLast4()));
        current.setCardNetwork(firstNonBlank(current.getCardNetwork(), incoming.getCardNetwork()));
        current.setBank(firstNonBlank(current.getBank(), incoming.getBank()));
        current.setVpa(firstNonBlank(current.getVpa(), incoming.getVpa()));
        current.setPayerEmail(firstNonBlank(current.getPayerEmail(), incoming.getPayerEmail()));
        current.setPayerPhone(firstNonBlank(current.getPayerPhone(), incoming.getPayerPhone()));
        current.setPayerName(firstNonBlank(current.getPayerName(), incoming.getPayerName()));
        current.setUserId(incoming.getUserId() != null ? incoming.getUserId() : current.getUserId());
        current.setReconciliationStatus(incoming.getReconciliationStatus());
        current.setMatchedAt(incoming.getMatchedAt() != null ? incoming.getMatchedAt() : current.getMatchedAt());
        current.setExceptionId(incoming.getExceptionId() != null ? incoming.getExceptionId() : current.getExceptionId());
        current.setRawPayload(incoming.getRawPayload() != null ? incoming.getRawPayload() : current.getRawPayload());
        current.setNotes(mergeNotes(current.getNotes(), incoming.getNotes()));
        current.setCapturedAt(incoming.getCapturedAt() != null ? incoming.getCapturedAt() : current.getCapturedAt());
        current.setRefundedAt(incoming.getRefundedAt() != null ? incoming.getRefundedAt() : current.getRefundedAt());
        current.setEventOccurredAt(incoming.getEventOccurredAt());
        current.setUpdatedAt(OffsetDateTime.now());

        return transactionRepository.save(current);
    }

    /**
     * Links a refund to its original payment.
     * Razorpay refund payloads include payment_id.
     * Stripe refund payloads include charge id in charge field.
     */
    @Transactional
    public void linkRefundToParent(Transaction refund, com.fasterxml.jackson.databind.JsonNode payload, String provider) {
        String originalPaymentId = extractOriginalPaymentId(payload, provider);
        if (originalPaymentId == null) return;

        Optional<Transaction> parent = transactionRepository
                .findByProviderAndProviderTransactionId(provider, originalPaymentId);

        if (parent.isPresent()) {
            refund.setParentTransactionId(parent.get().getId());
        }
    }

    private String extractOriginalPaymentId(com.fasterxml.jackson.databind.JsonNode payload, String provider) {
        return switch (provider) {
            case "razorpay" -> {
                // Razorpay refund entity contains payment_id
                com.fasterxml.jackson.databind.JsonNode refundEntity = payload.path("payload")
                                               .path("refund").path("entity");
                yield refundEntity.path("payment_id").asText(null);
            }
            case "stripe" -> {
                com.fasterxml.jackson.databind.JsonNode object = payload.path("data").path("object");
                String eventType = payload.path("type").asText(null);
                if ("refund.created".equals(eventType)) {
                    yield object.path("charge").asText(null);
                }
                // Stripe charge.refunded parent payment is the charge id.
                yield object.path("id").asText(null);
            }
            default -> null;
        };
    }

    private static Long coalesce(Long incoming, Long existing) {
        return incoming != null ? incoming : existing;
    }

    private static String firstNonBlank(String current, String incoming) {
        return StringUtils.hasText(incoming) ? incoming : current;
    }

    private static Map<String, Object> mergeNotes(Map<String, Object> current, Map<String, Object> incoming) {
        if (current == null) {
            return incoming;
        }
        if (incoming == null || incoming.isEmpty()) {
            return current;
        }
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>(current);
        merged.putAll(incoming);
        return merged;
    }

    private static boolean isMeaningfulStateAdvance(Transaction current, Transaction incoming) {
        return statusRank(incoming.getStatus()) > statusRank(current.getStatus());
    }

    private static int statusRank(com.reconciliation.common.enums.TransactionStatus status) {
        if (status == null) {
            return -1;
        }
        return switch (status) {
            case PENDING -> 0;
            case AUTHORIZED -> 1;
            case FAILED, CANCELLED -> 2;
            case CAPTURED, PENDING_SETTLEMENT -> 3;
            case PARTIALLY_REFUNDED -> 4;
            case REFUNDED -> 5;
            case DISPUTED -> 6;
        };
    }
}
