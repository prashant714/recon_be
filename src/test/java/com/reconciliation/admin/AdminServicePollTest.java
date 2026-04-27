package com.reconciliation.admin;

import com.reconciliation.admin.service.AdminService;
import com.reconciliation.audit.service.AuditService;
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
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        razorpayPollingService  = mock(RazorpayPollingService.class);
        stripePollingService    = mock(StripePollingService.class);
        webhookIngestionService = mock(WebhookIngestionService.class);
        auditService            = mock(AuditService.class);

        adminService = new AdminService(
                mock(WebhookEventRepository.class),
                mock(TransactionProcessingService.class),
                razorpayPollingService,
                stripePollingService,
                webhookIngestionService,
                auditService
        );
    }

    @Test
    void razorpayPollFetchesPaymentsAndRefundsThenIngests() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        byte[] payment1 = "{\"id\":\"pay_1\",\"event\":\"payment.captured\"}".getBytes();
        byte[] payment2 = "{\"id\":\"pay_2\",\"event\":\"payment.captured\"}".getBytes();
        byte[] refund1  = "{\"id\":\"rfnd_1\",\"event\":\"refund.processed\"}".getBytes();

        when(razorpayPollingService.fetchPayments(from, to)).thenReturn(List.of(payment1, payment2));
        when(razorpayPollingService.fetchRefunds(from, to)).thenReturn(List.of(refund1));

        Map<String, Object> result = adminService.poll("razorpay", from, to, "admin", "127.0.0.1");

        verify(webhookIngestionService).ingestAsync(payment1, "razorpay", "admin-poll");
        verify(webhookIngestionService).ingestAsync(payment2, "razorpay", "admin-poll");
        verify(webhookIngestionService).ingestAsync(refund1,  "razorpay", "admin-poll");
        verify(webhookIngestionService, times(3)).ingestAsync(any(), eq("razorpay"), eq("admin-poll"));

        assertThat(result.get("fetched")).isEqualTo(3);
        assertThat(result.get("status")).isEqualTo("accepted");
        assertThat(result.get("provider")).isEqualTo("razorpay");
    }

    @Test
    void razorpayPollWithEmptyResultsReturnsZeroFetched() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        when(razorpayPollingService.fetchPayments(from, to)).thenReturn(List.of());
        when(razorpayPollingService.fetchRefunds(from, to)).thenReturn(List.of());

        Map<String, Object> result = adminService.poll("razorpay", from, to, "admin", "127.0.0.1");

        verify(webhookIngestionService, never()).ingestAsync(any(), any(), any());
        assertThat(result.get("fetched")).isEqualTo(0);
    }

    @Test
    void stripePollFetchesChargesAndRefundsThenIngests() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(2);
        OffsetDateTime to   = OffsetDateTime.now();

        byte[] charge1 = "{\"id\":\"ch_1\",\"type\":\"charge.succeeded\"}".getBytes();
        byte[] refund1 = "{\"id\":\"re_1\",\"type\":\"refund.created\"}".getBytes();
        byte[] refund2 = "{\"id\":\"re_2\",\"type\":\"refund.created\"}".getBytes();

        when(stripePollingService.fetchCharges(from, to)).thenReturn(List.of(charge1));
        when(stripePollingService.fetchRefunds(from, to)).thenReturn(List.of(refund1, refund2));

        Map<String, Object> result = adminService.poll("stripe", from, to, "admin", "127.0.0.1");

        verify(webhookIngestionService).ingestAsync(charge1, "stripe", "admin-poll");
        verify(webhookIngestionService).ingestAsync(refund1, "stripe", "admin-poll");
        verify(webhookIngestionService).ingestAsync(refund2, "stripe", "admin-poll");
        verify(webhookIngestionService, times(3)).ingestAsync(any(), eq("stripe"), eq("admin-poll"));

        assertThat(result.get("fetched")).isEqualTo(3);
        assertThat(result.get("provider")).isEqualTo("stripe");
    }

    @Test
    void pollAuditsEveryCall() {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        when(razorpayPollingService.fetchPayments(from, to)).thenReturn(List.of());
        when(razorpayPollingService.fetchRefunds(from, to)).thenReturn(List.of());

        adminService.poll("razorpay", from, to, "ops-user", "10.0.0.1");

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
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        OffsetDateTime to   = OffsetDateTime.now();

        assertThatThrownBy(() -> adminService.poll("paypal", from, to, "admin", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paypal");
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
                stripePollingService, webhookIngestionService, auditService);

        Map<String, Object> result = svc.replay(10L, "admin", "127.0.0.1");

        assertThat(event.isProcessed()).isFalse();
        assertThat(event.getProcessingError()).isNull();
        assertThat(event.getProcessedAt()).isNull();
        verify(processingService).processAsync(10L, "razorpay");
        assertThat(result.get("status")).isEqualTo("queued");
    }
}
