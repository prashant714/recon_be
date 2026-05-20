package com.reconciliation.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import com.reconciliation.paymentflow.service.PaymentFlowEventService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookIngestionService {

    private final WebhookEventRepository webhookEventRepository;
    private final TransactionProcessingService processingService;
    private final PaymentFlowEventService paymentFlowEventService;
    private final ObjectMapper objectMapper;

    /**
     * Entry point for all incoming events — both webhook and polling.
     * Stores raw event, then hands off async processing.
     * Returns immediately — never blocks the webhook controller.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
            paymentFlowEventService.record(
                    provider,
                    providerEventId,
                    extractObjectId(payload, provider),
                    null,
                    null,
                    source,
                    "INGEST_RECEIVED",
                    "RECEIVED",
                    "Webhook or polled event received",
                    metadata("eventType", eventType));

            if (webhookEventRepository.existsByProviderAndProviderEventId(provider, providerEventId)) {
                log.info("Duplicate event ignored provider={} id={}", provider, providerEventId);
                paymentFlowEventService.record(
                        provider,
                        providerEventId,
                        extractObjectId(payload, provider),
                        null,
                        null,
                        source,
                        "INGEST_DUPLICATE",
                        "IGNORED",
                        "Duplicate provider event ignored",
                        metadata("eventType", eventType));
                return;
            }

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
                paymentFlowEventService.record(
                        provider,
                        providerEventId,
                        extractObjectId(payload, provider),
                        null,
                        null,
                        source,
                        "INGEST_DUPLICATE",
                        "IGNORED",
                        "Duplicate provider event ignored after insert race",
                        metadata("eventType", eventType));
                return;
            }

            paymentFlowEventService.record(
                    provider,
                    providerEventId,
                    extractObjectId(payload, provider),
                    saved.getId(),
                    null,
                    source,
                    "INGEST_STORED",
                    "SUCCESS",
                    "Raw event stored",
                    metadata("eventType", eventType));

            // Async: do not await — controller already returned 200
            processingService.processAsync(saved.getId(), provider);

        } catch (Exception e) {
            log.error("Failed to ingest event from provider={}: {}", provider, e.getMessage(), e);
        }
    }

    private String extractObjectId(JsonNode payload, String provider) {
        return switch (provider) {
            case "razorpay" -> firstText(
                    payload.path("payload").path("payment").path("entity").path("id").asText(null),
                    payload.path("payload").path("refund").path("entity").path("id").asText(null),
                    payload.path("payload").path("settlement").path("entity").path("id").asText(null),
                    payload.path("payload").path("dispute").path("entity").path("id").asText(null)
            );
            case "stripe" -> firstText(
                    payload.path("data").path("object").path("id").asText(null),
                    payload.path("id").asText(null)
            );
            default -> payload.path("id").asText(null);
        };
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
