package com.reconciliation.admin;

import com.reconciliation.admin.service.AdminService;
import com.reconciliation.audit.service.AuditService;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.service.ProviderConnectionService;
import com.reconciliation.polling.service.RazorpayPollingService;
import com.reconciliation.polling.service.StripePollingService;
import com.reconciliation.webhook.service.TransactionProcessingService;
import com.reconciliation.webhook.service.WebhookIngestionService;
import com.reconciliation.webhook_event.entity.WebhookEvent;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminServicePollTest {

    private RazorpayPollingService razorpayPollingService;
    private StripePollingService stripePollingService;
    private WebhookIngestionService webhookIngestionService;
    private AuditService auditService;
    private ProviderConnectionService providerConnectionService;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        razorpayPollingService  = mock(RazorpayPollingService.class);
        stripePollingService    = mock(StripePollingService.class);
        webhookIngestionService = mock(WebhookIngestionService.class);
        auditService            = mock(AuditService.class);
        providerConnectionService = mock(ProviderConnectionService.class);

        adminService = new AdminService(
                mock(WebhookEventRepository.class),
                mock(TransactionProcessingService.class),
                razorpayPollingService,
                stripePollingService,
                webhookIngestionService,
                auditService,
                providerConnectionService
        );
    }

    private void stubRazorpayConnection(String merchantId) {
        ProviderConnection conn = ProviderConnection.builder()
                .merchantId(merchantId)
                .provider("razorpay")
                .apiKeyEncrypted("encrypted_key")
                .secretEncrypted("encrypted_secret")
                .apiKeyMasked("rzp_****1234")
                .build();
        when(providerConnectionService.findActiveConnection(merchantId, "razorpay"))
                .thenReturn(Optional.of(conn));
        when(providerConnectionService.decryptApiKey(conn)).thenReturn("rzp_test_key");
        when(providerConnectionService.decryptSecret(conn)).thenReturn("rzp_test_secret");
    }

    @Test
    void razorpayPollFetchesPaymentsAndRefundsThenIngests() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();
        String merchantId = "merchant_001";
        stubRazorpayConnection(merchantId);

        byte[] payment1 = "{\"id\":\"pay_1\",\"event\":\"payment.captured\"}".getBytes();
        byte[] payment2 = "{\"id\":\"pay_2\",\"event\":\"payment.captured\"}".getBytes();
        byte[] refund1  = "{\"id\":\"rfnd_1\",\"event\":\"refund.processed\"}".getBytes();

        when(razorpayPollingService.fetchPayments("rzp_test_key", "rzp_test_secret", from, to))
                .thenReturn(List.of(payment1, payment2));
        when(razorpayPollingService.fetchRefunds("rzp_test_key", "rzp_test_secret", from, to))
                .thenReturn(List.of(refund1));

        Map<String, Object> result = adminService.poll("razorpay", from, to, merchantId, "admin", "127.0.0.1");

        verify(webhookIngestionService).ingestAsync(payment1, "razorpay", "admin-poll", merchantId);
        verify(webhookIngestionService).ingestAsync(payment2, "razorpay", "admin-poll", merchantId);
        verify(webhookIngestionService).ingestAsync(refund1,  "razorpay", "admin-poll", merchantId);

        assertThat(result.get("fetched")).isEqualTo(3);
        assertThat(result.get("status")).isEqualTo("accepted");
        assertThat(result.get("provider")).isEqualTo("razorpay");
        assertThat(result.get("merchantId")).isEqualTo(merchantId);
    }

    @Test
    void razorpayPollWithEmptyResultsReturnsZeroFetched() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();
        String merchantId = "merchant_001";
        stubRazorpayConnection(merchantId);

        when(razorpayPollingService.fetchPayments("rzp_test_key", "rzp_test_secret", from, to))
                .thenReturn(List.of());
        when(razorpayPollingService.fetchRefunds("rzp_test_key", "rzp_test_secret", from, to))
                .thenReturn(List.of());

        Map<String, Object> result = adminService.poll("razorpay", from, to, merchantId, "admin", "127.0.0.1");

        verify(webhookIngestionService, never()).ingestAsync(any(), any(), any(), any());
        assertThat(result.get("fetched")).isEqualTo(0);
    }

    @Test
    void stripePollFetchesChargesAndRefundsThenIngests() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(2);
        OffsetDateTime to   = OffsetDateTime.now();
        String merchantId = "merchant_002";

        byte[] charge1 = "{\"id\":\"ch_1\",\"type\":\"charge.succeeded\"}".getBytes();
        byte[] refund1 = "{\"id\":\"re_1\",\"type\":\"refund.created\"}".getBytes();
        byte[] refund2 = "{\"id\":\"re_2\",\"type\":\"refund.created\"}".getBytes();

        when(stripePollingService.fetchCharges(from, to)).thenReturn(List.of(charge1));
        when(stripePollingService.fetchRefunds(from, to)).thenReturn(List.of(refund1, refund2));

        Map<String, Object> result = adminService.poll("stripe", from, to, merchantId, "admin", "127.0.0.1");

        verify(webhookIngestionService).ingestAsync(charge1, "stripe", "admin-poll", merchantId);
        verify(webhookIngestionService).ingestAsync(refund1, "stripe", "admin-poll", merchantId);
        verify(webhookIngestionService).ingestAsync(refund2, "stripe", "admin-poll", merchantId);

        assertThat(result.get("fetched")).isEqualTo(3);
        assertThat(result.get("provider")).isEqualTo("stripe");
    }

    @Test
    void pollThrowsWhenMerchantIdIsMissing() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        assertThatThrownBy(() -> adminService.poll("razorpay", from, to, null, "admin", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchantId is required");
    }

    @Test
    void pollThrowsWhenNoActiveConnection() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        when(providerConnectionService.findActiveConnection("merchant_999", "razorpay"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.poll("razorpay", from, to, "merchant_999", "admin", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No active Razorpay connection");
    }

    @Test
    void pollAuditsEveryCall() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();
        String merchantId = "merchant_001";
        stubRazorpayConnection(merchantId);

        when(razorpayPollingService.fetchPayments("rzp_test_key", "rzp_test_secret", from, to))
                .thenReturn(List.of());
        when(razorpayPollingService.fetchRefunds("rzp_test_key", "rzp_test_secret", from, to))
                .thenReturn(List.of());

        adminService.poll("razorpay", from, to, merchantId, "ops-user", "10.0.0.1");

        verify(auditService).log(
                eq("ops-user"),
                eq("admin_poll_triggered"),
                eq("provider"),
                isNull(),
                isNull(),
                any(),
                eq("10.0.0.1"));
    }

    @Test
    void pollThrowsForUnknownProvider() {
        assertThatThrownBy(() -> adminService.poll("paypal", OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now(), "merchant_001", "admin", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replayResetsProcessingFlagsAndQueuesProcessing() {
        WebhookEvent event = WebhookEvent.builder()
                .id(10L)
                .provider("razorpay")
                .eventType("payment.captured")
                .processed(true)
                .processingError("previous error")
                .build();

        WebhookEventRepository repo = mock(WebhookEventRepository.class);
        when(repo.findById(10L)).thenReturn(java.util.Optional.of(event));
        when(repo.save(any())).thenReturn(event);

        TransactionProcessingService processingService = mock(TransactionProcessingService.class);

        AdminService svc = new AdminService(repo, processingService, razorpayPollingService,
                stripePollingService, webhookIngestionService, auditService, providerConnectionService);

        Map<String, Object> result = svc.replay(10L, "admin", "127.0.0.1");

        assertThat(event.isProcessed()).isFalse();
        assertThat(event.getProcessingError()).isNull();
        assertThat(event.getProcessedAt()).isNull();
        verify(processingService).processAsync(10L, "razorpay");
        assertThat(result.get("status")).isEqualTo("queued");
    }
}
