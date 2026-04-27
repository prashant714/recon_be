package com.reconciliation.admin.service;

import com.reconciliation.audit.service.AuditService;
import com.reconciliation.polling.service.RazorpayPollingService;
import com.reconciliation.polling.service.StripePollingService;
import com.reconciliation.webhook.service.TransactionProcessingService;
import com.reconciliation.webhook.service.WebhookIngestionService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final WebhookEventRepository webhookEventRepository;
    private final TransactionProcessingService transactionProcessingService;
    private final RazorpayPollingService razorpayPollingService;
    private final StripePollingService stripePollingService;
    private final WebhookIngestionService webhookIngestionService;
    private final AuditService auditService;

    @Transactional
    public Map<String, Object> replay(Long webhookEventId, String actor, String ipAddress) {
        WebhookEvent event = webhookEventRepository.findById(webhookEventId).orElseThrow();
        event.setProcessed(false);
        event.setProcessedAt(null);
        event.setProcessingError(null);
        webhookEventRepository.save(event);
        transactionProcessingService.processAsync(webhookEventId, event.getProvider());
        auditService.log(actor, "webhook_replayed", "webhook_event", webhookEventId, null, event, ipAddress);
        return Map.of(
                "status", "queued",
                "webhookEventId", webhookEventId,
                "provider", event.getProvider(),
                "eventType", event.getEventType());
    }

    public Map<String, Object> poll(String provider, OffsetDateTime from, OffsetDateTime to,
                                    String actor, String ipAddress) {
        int fetched = switch (provider.toLowerCase()) {
            case "razorpay" -> {
                List<byte[]> payments = razorpayPollingService.fetchPayments(from, to);
                List<byte[]> refunds  = razorpayPollingService.fetchRefunds(from, to);
                int count = 0;
                for (byte[] payload : payments) {
                    webhookIngestionService.ingestAsync(payload, "razorpay", "admin-poll");
                    count++;
                }
                for (byte[] payload : refunds) {
                    webhookIngestionService.ingestAsync(payload, "razorpay", "admin-poll");
                    count++;
                }
                log.info("Admin poll Razorpay: {} events fetched from={} to={}", count, from, to);
                yield count;
            }
            case "stripe" -> {
                List<byte[]> charges = stripePollingService.fetchCharges(from, to);
                List<byte[]> refunds = stripePollingService.fetchRefunds(from, to);
                int count = 0;
                for (byte[] payload : charges) {
                    webhookIngestionService.ingestAsync(payload, "stripe", "admin-poll");
                    count++;
                }
                for (byte[] payload : refunds) {
                    webhookIngestionService.ingestAsync(payload, "stripe", "admin-poll");
                    count++;
                }
                log.info("Admin poll Stripe: {} events fetched from={} to={}", count, from, to);
                yield count;
            }
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };

        auditService.log(actor, "admin_poll_triggered", "provider", null, null,
                Map.of("provider", provider, "from", from.toString(), "to", to.toString(), "fetched", fetched),
                ipAddress);

        return Map.of("status", "accepted", "provider", provider,
                "from", from, "to", to, "fetched", fetched);
    }
}
