package com.reconciliation.oms.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.reconciliation.common.enums.ConnectionStatus;
import com.reconciliation.common.enums.ProviderType;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.repository.ProviderConnectionRepository;
import com.reconciliation.oms.connector.OmsConnector;
import com.reconciliation.oms.connector.OmsOrder;
import com.reconciliation.oms.service.OmsIngestionResult;
import com.reconciliation.oms.service.OmsOrderIngestionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OmsPollingJob {

    private final ProviderConnectionRepository connectionRepository;
    private final OmsOrderIngestionService ingestionService;
    private final Map<String, OmsConnector> connectorsByProvider;
    private final Counter omsSyncedCounter;

    @Value("${app.oms.polling-interval-minutes:15}")
    private int pollingIntervalMinutes;

    @Value("${app.oms.lookback-minutes:30}")
    private int lookbackMinutes;

    public OmsPollingJob(ProviderConnectionRepository connectionRepository,
                         OmsOrderIngestionService ingestionService,
                         List<OmsConnector> connectors,
                         MeterRegistry meterRegistry) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.connectorsByProvider = connectors.stream()
                .collect(Collectors.toMap(OmsConnector::getProvider, Function.identity()));
        this.omsSyncedCounter = Counter.builder("oms.orders.synced")
                .description("Orders synced from OMS providers")
                .register(meterRegistry);
    }

    @Bean
    public RecurringTask<Void> omsPollingTask() {
        return new RecurringTask<Void>(
                "oms-polling",
                Schedules.fixedDelay(Duration.ofMinutes(pollingIntervalMinutes)),
                Void.class) {

            @Override
            public void executeRecurringly(
                    TaskInstance<Void> taskInstance,
                    ExecutionContext executionContext) {
                log.info("OMS polling job starting");
                try {
                    OffsetDateTime to = OffsetDateTime.now();
                    OffsetDateTime from = to.minusMinutes(lookbackMinutes);
                    pollAllConnections(from, to);
                } catch (Exception e) {
                    log.error("OMS polling job failed", e);
                }
            }
        };
    }

    void pollAllConnections(OffsetDateTime from, OffsetDateTime to) {
        List<ProviderConnection> connections = connectionRepository
                .findByProviderTypeAndStatus(ProviderType.OMS, ConnectionStatus.ACTIVE);

        log.info("OMS polling: found {} active OMS connections", connections.size());

        for (ProviderConnection conn : connections) {
            try {
                pollSingleConnection(conn, from, to);
            } catch (Exception e) {
                log.error("OMS polling failed for connection={} provider={}: {}",
                        conn.getId(), conn.getProvider(), e.getMessage(), e);
            }
        }
    }

    private void pollSingleConnection(ProviderConnection conn,
                                       OffsetDateTime from, OffsetDateTime to) {
        OmsConnector connector = connectorsByProvider.get(conn.getProvider());
        if (connector == null) {
            log.warn("No OMS connector registered for provider={}", conn.getProvider());
            return;
        }

        List<OmsOrder> orders = connector.fetchOrders(conn, from, to);
        if (orders.isEmpty()) {
            log.debug("OMS polling: no orders from provider={} connection={}",
                    conn.getProvider(), conn.getId());
            return;
        }

        OmsIngestionResult result = ingestionService.ingest(
                conn.getMerchantId(), conn.getProvider(), orders);

        omsSyncedCounter.increment(result.created() + result.updated());
        log.info("OMS polling connection={}: created={} updated={} skipped={}",
                conn.getId(), result.created(), result.updated(), result.skipped());
    }
}
