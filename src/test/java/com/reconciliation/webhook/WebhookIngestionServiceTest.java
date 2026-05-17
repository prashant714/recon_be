package com.reconciliation.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.webhook.service.TransactionProcessingService;
import com.reconciliation.webhook.service.WebhookIngestionService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookIngestionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookEventRepository repository = mock(WebhookEventRepository.class);
    private final TransactionProcessingService processingService = mock(TransactionProcessingService.class);
    private final WebhookIngestionService service =
            new WebhookIngestionService(repository, processingService, objectMapper);

    @Test
    void savesNewEventAndQueuesAsyncProcessing() {
        String payload = """
                {"id":"evt_123","event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_123"}}}}
                """;

        when(repository.save(any(WebhookEvent.class))).thenAnswer(invocation -> {
            WebhookEvent event = invocation.getArgument(0);
            event.setId(55L);
            return event;
        });

        service.ingestAsync(payload.getBytes(), "razorpay", "webhook");

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("payment.captured");
        assertThat(captor.getValue().getProvider()).isEqualTo("razorpay");
        assertThat(captor.getValue().getProviderEventId()).isEqualTo("evt_123");
        verify(processingService).processAsync(55L, "razorpay");
    }

    @Test
    void derivesRazorpayEventIdFromNestedPaymentWhenTopLevelIdIsMissing() {
        String payload = """
                {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_123"}}}}
                """;

        when(repository.save(any(WebhookEvent.class))).thenAnswer(invocation -> {
            WebhookEvent event = invocation.getArgument(0);
            event.setId(56L);
            return event;
        });

        service.ingestAsync(payload.getBytes(), "razorpay", "webhook");

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("payment.captured");
        assertThat(captor.getValue().getProviderEventId()).isEqualTo("payment.captured:pay_123");
        verify(processingService).processAsync(56L, "razorpay");
    }

    @Test
    void ignoresDuplicateEvent() {
        String payload = """
                {"id":"evt_123","type":"payment_intent.succeeded"}
                """;

        when(repository.save(any(WebhookEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        service.ingestAsync(payload.getBytes(), "stripe", "webhook");

        verify(repository).save(any(WebhookEvent.class));
        verify(processingService, never()).processAsync(any(), any());
    }
}
