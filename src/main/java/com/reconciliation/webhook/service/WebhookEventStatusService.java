package com.reconciliation.webhook.service;

import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebhookEventStatusService {

    private final WebhookEventRepository webhookEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(Long webhookEventId) {
        webhookEventRepository.markAsProcessed(webhookEventId, OffsetDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long webhookEventId, String error) {
        webhookEventRepository.markAsFailed(webhookEventId, OffsetDateTime.now(), error);
    }
}
