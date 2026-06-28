package com.reconciliation.oms.service;

import com.reconciliation.oms.connector.OmsOrder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OmsOrderIngestionService {

    private final OmsOrderPersistenceService persistenceService;

    public OmsIngestionResult ingest(String merchantId, String omsProvider, List<OmsOrder> omsOrders) {
        int created = 0, updated = 0, skipped = 0;

        for (OmsOrder oms : omsOrders) {
            if (shouldSkip(oms.omsStatus())) {
                skipped++;
                continue;
            }

            try {
                boolean wasCreated = persistenceService.upsert(merchantId, omsProvider, oms);
                if (wasCreated) created++; else updated++;
            } catch (DataIntegrityViolationException e) {
                // Polling and webhook both tried to INSERT the same new order concurrently.
                // The other path won — update the record it just created.
                log.debug("OMS concurrent insert race for order={} — retrying as update", oms.orderId());
                try {
                    persistenceService.forceUpdate(merchantId, oms);
                    updated++;
                } catch (Exception e2) {
                    log.warn("OMS order skipped after concurrent conflict: orderId={}", oms.orderId());
                    skipped++;
                }
            }
        }

        log.info("OMS ingestion merchant={} provider={}: created={} updated={} skipped={}",
                merchantId, omsProvider, created, updated, skipped);
        return new OmsIngestionResult(created, updated, skipped);
    }

    private boolean shouldSkip(String omsStatus) {
        return "draft".equalsIgnoreCase(omsStatus) || "void".equalsIgnoreCase(omsStatus);
    }
}
