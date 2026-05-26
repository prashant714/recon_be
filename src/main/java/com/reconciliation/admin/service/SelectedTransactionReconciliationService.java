package com.reconciliation.admin.service;

import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.ReportLineMatchStatus;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.entity.SettlementReportLine;
import com.reconciliation.settlement.repository.SettlementReportLineRepository;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SelectedTransactionReconciliationService {

    private final TransactionRepository transactionRepository;
    private final SettlementReportLineRepository reportLineRepository;
    private final SettlementRepository settlementRepository;

    @Transactional
    public Map<String, Object> reconcile(List<Long> transactionIds, String mode) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new IllegalArgumentException("transactionIds is required");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int reconciled = 0;
        int failed = 0;

        for (Long transactionId : transactionIds) {
            Map<String, Object> result = reconcileOne(transactionId);
            results.add(result);
            if ("MATCHED".equals(result.get("status"))) {
                reconciled++;
            } else {
                failed++;
            }
        }

        return Map.of(
                "requested", transactionIds.size(),
                "reconciled", reconciled,
                "failed", failed,
                "results", results);
    }

    private Map<String, Object> reconcileOne(Long transactionId) {
        Transaction tx = transactionRepository.findById(transactionId).orElse(null);
        if (tx == null) {
            return Map.of(
                    "transactionId", transactionId,
                    "status", "FAILED",
                    "reason", "Transaction not found");
        }

        if (tx.getReconciliationStatus() == ReconciliationStatus.MATCHED) {
            return Map.of("transactionId", transactionId, "status", "MATCHED");
        }

        SettlementReportLine line = reportLineRepository
                .findFirstByProviderAndProviderTxnId(tx.getProvider(), tx.getProviderTransactionId())
                .orElse(null);
        if (line != null && amountsMatch(tx, line)) {
            line.setMatchStatus(ReportLineMatchStatus.MATCHED);
            line.setMatchedToTxnId(tx.getId());
            reportLineRepository.save(line);

            settlementRepository.findById(line.getSettlementId())
                    .map(Settlement::getProviderSettlementId)
                    .ifPresent(tx::setSettlementId);
            tx.setReconciliationStatus(ReconciliationStatus.MATCHED);
            tx.setMatchedAt(OffsetDateTime.now());
            transactionRepository.save(tx);
            return Map.of("transactionId", transactionId, "status", "MATCHED");
        }

        if (tx.getSettlementId() != null && !tx.getSettlementId().isBlank()) {
            tx.setReconciliationStatus(ReconciliationStatus.MATCHED);
            tx.setMatchedAt(OffsetDateTime.now());
            transactionRepository.save(tx);
            return Map.of("transactionId", transactionId, "status", "MATCHED");
        }

        return Map.of(
                "transactionId", transactionId,
                "status", "FAILED",
                "reason", "No matching settlement found");
    }

    private boolean amountsMatch(Transaction tx, SettlementReportLine line) {
        Long netAmount = tx.getNetAmount();
        if (netAmount == null || line.getNetAmount() == null) {
            return false;
        }
        return Math.abs(netAmount - line.getNetAmount()) <= 100L;
    }
}
