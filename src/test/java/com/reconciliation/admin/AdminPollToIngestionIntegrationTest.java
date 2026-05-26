package com.reconciliation.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.reconciliation.admin.service.AdminService;
import com.reconciliation.audit.service.AuditService;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.service.ProviderConnectionService;
import com.reconciliation.polling.service.RazorpayPollingService;
import com.reconciliation.polling.service.StripePollingService;
import com.reconciliation.paymentflow.service.PaymentFlowEventService;
import com.reconciliation.webhook.service.TransactionProcessingService;
import com.reconciliation.webhook.service.WebhookIngestionService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminPollToIngestionIntegrationTest {

    private WebhookEventRepository eventRepository;
    private TransactionProcessingService processingService;
    private WebhookIngestionService realIngestionService;
    private RazorpayPollingService razorpayPollingService;
    private StripePollingService stripePollingService;
    private ProviderConnectionService providerConnectionService;
    private AdminService adminService;

    private static final String MERCHANT_ID = "merchant_001";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        eventRepository    = mock(WebhookEventRepository.class);
        processingService  = mock(TransactionProcessingService.class);
        razorpayPollingService = mock(RazorpayPollingService.class);
        stripePollingService   = mock(StripePollingService.class);
        providerConnectionService = mock(ProviderConnectionService.class);

        realIngestionService = new WebhookIngestionService(
                eventRepository, processingService, mock(PaymentFlowEventService.class), objectMapper);

        adminService = new AdminService(
                eventRepository,
                processingService,
                razorpayPollingService,
                stripePollingService,
                realIngestionService,
                mock(AuditService.class),
                providerConnectionService);

        stubRazorpayConnection();
    }

    private void stubRazorpayConnection() {
        ProviderConnection conn = ProviderConnection.builder()
                .merchantId(MERCHANT_ID)
                .provider("razorpay")
                .apiKeyEncrypted("enc_key")
                .secretEncrypted("enc_secret")
                .apiKeyMasked("rzp_****1234")
                .build();
        when(providerConnectionService.findActiveConnection(MERCHANT_ID, "razorpay"))
                .thenReturn(Optional.of(conn));
        when(providerConnectionService.decryptApiKey(conn)).thenReturn("rzp_key");
        when(providerConnectionService.decryptSecret(conn)).thenReturn("rzp_secret");
    }

    @Test
    void pollToIngestionSavesEventAndQueuesProcessing() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        byte[] payload = """
                {"id":"evt_poll_001","event":"payment.captured",
                 "payload":{"payment":{"entity":{"id":"pay_001"}}}}
                """.getBytes();

        when(razorpayPollingService.fetchPayments("rzp_key", "rzp_secret", from, to))
                .thenReturn(List.of(payload));
        when(razorpayPollingService.fetchRefunds("rzp_key", "rzp_secret", from, to))
                .thenReturn(List.of());

        WebhookEvent saved = WebhookEvent.builder().id(42L).build();
        when(eventRepository.save(any(WebhookEvent.class))).thenReturn(saved);

        Map<String, Object> result = adminService.poll("razorpay", from, to, MERCHANT_ID, "admin", "127.0.0.1");

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(eventRepository).save(captor.capture());
        WebhookEvent captured = captor.getValue();
        assertThat(captured.getProvider()).isEqualTo("razorpay");
        assertThat(captured.getSource()).isEqualTo("admin-poll");
        assertThat(captured.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(captured.getEventType()).isEqualTo("payment.captured");
        assertThat(captured.isProcessed()).isFalse();

        verify(processingService).processAsync(42L, "razorpay");
        assertThat(result.get("fetched")).isEqualTo(1);
    }

    @Test
    void pollToIngestionDeduplicatesDuplicateEvent() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        byte[] payload = """
                {"id":"evt_dup_001","event":"payment.captured",
                 "payload":{"payment":{"entity":{"id":"pay_dup"}}}}
                """.getBytes();

        when(razorpayPollingService.fetchPayments("rzp_key", "rzp_secret", from, to))
                .thenReturn(List.of(payload));
        when(razorpayPollingService.fetchRefunds("rzp_key", "rzp_secret", from, to))
                .thenReturn(List.of());

        when(eventRepository.save(any(WebhookEvent.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        adminService.poll("razorpay", from, to, MERCHANT_ID, "admin", "127.0.0.1");

        verify(processingService, never()).processAsync(any(), any());
    }

    @Test
    void stripePollIngestionUsesCorrectProvider() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();
        String merchantId = "merchant_stripe";

        byte[] payload = """
                {"id":"poll_ch_abc","type":"charge.succeeded",
                 "data":{"object":{"id":"ch_abc","payment_intent":"pi_abc"}}}
                """.getBytes();

        when(stripePollingService.fetchCharges(from, to)).thenReturn(List.of(payload));
        when(stripePollingService.fetchRefunds(from, to)).thenReturn(List.of());

        WebhookEvent saved = WebhookEvent.builder().id(55L).build();
        when(eventRepository.save(any(WebhookEvent.class))).thenReturn(saved);

        adminService.poll("stripe", from, to, merchantId, "admin", "127.0.0.1");

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo("stripe");
        assertThat(captor.getValue().getSource()).isEqualTo("admin-poll");
        assertThat(captor.getValue().getMerchantId()).isEqualTo(merchantId);

        verify(processingService).processAsync(55L, "stripe");
    }

    @Test
    void multiplePayloadsEachSavedAndQueued() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        byte[] p1 = "{\"id\":\"evt_1\",\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\"}}}}".getBytes();
        byte[] p2 = "{\"id\":\"evt_2\",\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_2\"}}}}".getBytes();

        when(razorpayPollingService.fetchPayments("rzp_key", "rzp_secret", from, to))
                .thenReturn(List.of(p1, p2));
        when(razorpayPollingService.fetchRefunds("rzp_key", "rzp_secret", from, to))
                .thenReturn(List.of());

        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> {
            WebhookEvent e = inv.getArgument(0);
            e.setId((long)(int)(Math.random() * 1000));
            return e;
        });

        Map<String, Object> result = adminService.poll("razorpay", from, to, MERCHANT_ID, "admin", "127.0.0.1");

        verify(eventRepository, times(2)).save(any(WebhookEvent.class));
        verify(processingService, times(2)).processAsync(any(), eq("razorpay"));
        assertThat(result.get("fetched")).isEqualTo(2);
    }
}
