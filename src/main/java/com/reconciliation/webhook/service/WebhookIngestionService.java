package com.reconciliation.webhook.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            case "razorpay" -> extractRazorpayEventId(payload);
            case "stripe"   -> payload.path("id").asText(null);
            default         -> payload.path("id").asText(null);
        };
    }

    private String extractRazorpayEventId(JsonNode payload) {
        String topLevelId = payload.path("id").asText(null);
        if (hasText(topLevelId)) {
            return topLevelId;
        }

        String eventType = payload.path("event").asText("unknown");
        String objectId = firstText(
                payload.path("payload").path("payment").path("entity").path("id").asText(null),
                payload.path("payload").path("refund").path("entity").path("id").asText(null),
                payload.path("payload").path("settlement").path("entity").path("id").asText(null),
                payload.path("payload").path("dispute").path("entity").path("id").asText(null)
        );

        return hasText(objectId) ? eventType + ":" + objectId : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
