package com.reconciliation.polling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.Entity;
import com.razorpay.RazorpayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.razorpay.key-id:}")
    private String keyId;

    @Value("${app.razorpay.key-secret:}")
    private String keySecret;

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
        if (!hasUsableCredentials()) {
            log.info("Skipping Razorpay {} polling because real API credentials are not configured", entity);
            return results;
        }

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
                    org.json.JSONObject item = items.getJSONObject(i);
                    String eventType = eventTypeFor(entity, item);
                    if (eventType == null) {
                        continue;
                    }

                    // Wrap each item in a synthetic webhook-like envelope
                    // so our normalization service can parse it uniformly
                    org.json.JSONObject envelope = new org.json.JSONObject();
                    envelope.put("id",    syntheticEventId(entity, item));
                    envelope.put("event", eventType);
                    envelope.put("payload", new org.json.JSONObject()
                            .put(entity.equals("payments") ? "payment" : "refund",
                                 new org.json.JSONObject().put("entity", item)));

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
                List<? extends Entity> items = "payments".equals(entity)
                        ? razorpayClient.payments.fetchAll(options)
                        : razorpayClient.refunds.fetchAll(options);

                org.json.JSONArray jsonArray = new org.json.JSONArray();
                for (Entity item : items) {
                    jsonArray.put(item.toJson());
                }
                return jsonArray;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) throw new RuntimeException(e);
                log.warn("Razorpay API retry {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
                try { Thread.sleep(RETRY_DELAY_MS * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return null;
    }

    private boolean hasUsableCredentials() {
        return hasText(keyId)
                && hasText(keySecret)
                && !keyId.contains("placeholder")
                && !keySecret.contains("placeholder");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String syntheticEventId(String entity, org.json.JSONObject item) {
        String objectId = item.optString("id", null);
        if (hasText(objectId)) {
            return "poll_" + entity + "_" + objectId;
        }
        return "poll_" + entity + "_" + java.util.UUID.randomUUID();
    }

    private String eventTypeFor(String entity, org.json.JSONObject item) {
        if ("refunds".equals(entity)) {
            return "refund.processed";
        }
        String status = item.optString("status", "");
        return switch (status) {
            case "captured" -> "payment.captured";
            case "authorized" -> "payment.authorized";
            case "failed" -> "payment.failed";
            default -> null;
        };
    }
}
