package com.reconciliation.reconciliation.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.service.ProviderConnectionService;
import com.reconciliation.polling.service.RazorpayPollingService;
import com.reconciliation.polling.service.StripePollingService;
import com.reconciliation.webhook.service.WebhookIngestionService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GapFillerJob {

    private final RazorpayPollingService razorpayPollingService;
    private final StripePollingService stripePollingService;
    private final WebhookIngestionService ingestionService;
    private final ProviderConnectionService providerConnectionService;
    private final io.micrometer.core.instrument.Counter gapsFilled;

    @org.springframework.beans.factory.annotation.Value("${app.polling.gap-filler-lookback-minutes:30}")
    private int lookbackMinutes;

    public GapFillerJob(RazorpayPollingService razorpayPollingService,
                        StripePollingService stripePollingService,
                        WebhookIngestionService ingestionService,
                        ProviderConnectionService providerConnectionService,
                        io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.razorpayPollingService = razorpayPollingService;
        this.stripePollingService   = stripePollingService;
        this.ingestionService       = ingestionService;
        this.providerConnectionService = providerConnectionService;
        this.gapsFilled = io.micrometer.core.instrument.Counter.builder("polling.gaps.filled")
                .description("Events picked up by polling that were missed by webhooks")
                .register(meterRegistry);
    }

    @Bean
    public RecurringTask<Void> gapFillerTask() {
        return new RecurringTask<Void>(
                "gap-filler",
                Schedules.fixedDelay(Duration.ofMinutes(15)),
                Void.class) {

            @Override
            public void executeRecurringly(
                    TaskInstance<Void> taskInstance,
                    ExecutionContext executionContext) {
                log.info("Gap filler job starting");
                try {
                    OffsetDateTime to   = OffsetDateTime.now();
                    OffsetDateTime from = to.minusMinutes(lookbackMinutes);
                    runForWindow(from, to);
                } catch (Exception e) {
                    log.error("Gap filler job failed", e);
                }
            }
        };
    }

    public void runForWindow(OffsetDateTime from, OffsetDateTime to) {
        log.info("Gap filler running window {} to {}", from, to);

        fillRazorpayAllMerchants(from, to);
        fillStripe(from, to);
    }

    private void fillRazorpayAllMerchants(OffsetDateTime from, OffsetDateTime to) {
        List<ProviderConnection> connections = providerConnectionService.findAllActiveByProvider("razorpay");

        if (connections.isEmpty()) {
            log.info("No active Razorpay connections found — skipping gap fill");
            return;
        }

        for (ProviderConnection conn : connections) {
            try {
                String keyId = providerConnectionService.decryptApiKey(conn);
                String keySecret = providerConnectionService.decryptSecret(conn);
                String merchantId = conn.getMerchantId();

                List<byte[]> payments = razorpayPollingService.fetchPayments(keyId, keySecret, from, to);
                List<byte[]> refunds  = razorpayPollingService.fetchRefunds(keyId, keySecret, from, to);

                int count = 0;
                for (byte[] payload : payments) {
                    ingestionService.ingestAsync(payload, "razorpay", "polling", merchantId);
                    count++;
                }
                for (byte[] payload : refunds) {
                    ingestionService.ingestAsync(payload, "razorpay", "polling", merchantId);
                    count++;
                }

                gapsFilled.increment(count);
                log.info("Razorpay gap filler for merchant={}: {} events fetched", merchantId, count);

            } catch (Exception e) {
                log.error("Razorpay gap filler failed for merchant={}: {}", conn.getMerchantId(), e.getMessage(), e);
            }
        }
    }

    private void fillStripe(OffsetDateTime from, OffsetDateTime to) {
        try {
            List<byte[]> charges = stripePollingService.fetchCharges(from, to);
            List<byte[]> refunds = stripePollingService.fetchRefunds(from, to);

            int count = 0;
            for (byte[] payload : charges) {
                ingestionService.ingestAsync(payload, "stripe", "polling");
                count++;
            }
            for (byte[] payload : refunds) {
                ingestionService.ingestAsync(payload, "stripe", "polling");
                count++;
            }

            gapsFilled.increment(count);
            log.info("Stripe gap filler: {} events fetched", count);

        } catch (Exception e) {
            log.error("Stripe gap filler failed: {}", e.getMessage(), e);
        }
    }
}
