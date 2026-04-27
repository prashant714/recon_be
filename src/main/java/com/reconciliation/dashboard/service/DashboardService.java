package com.reconciliation.dashboard.service;

import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TransactionRepository transactionRepository;
    private final ExceptionRecordRepository exceptionRecordRepository;

    public Map<String, Object> summary(int days) {
        OffsetDateTime from = OffsetDateTime.now().minusDays(days);
        String merchantId = "merchant_001";

        long totalTransactions = transactionRepository.countSince(from);
        long matched = transactionRepository.countByReconciliationStatusSince(from, ReconciliationStatus.MATCHED);
        long openExceptions = exceptionRecordRepository.countOpenExceptions(merchantId, from);
        double matchRate = totalTransactions == 0 ? 0.0 : (matched * 100.0) / totalTransactions;

        Map<String, Map<String, Long>> byProvider = new LinkedHashMap<>();
        for (Object[] row : transactionRepository.findProviderSummarySince(from)) {
            String provider = row[0] == null ? "unknown" : row[0].toString();
            long total = toLong(row[1]);
            long exceptions = toLong(row[2]);
            byProvider.put(provider, Map.of("total", total, "exceptions", exceptions));
        }

        Map<String, Long> byExceptionType = new LinkedHashMap<>();
        for (Object[] row : exceptionRecordRepository.countByTypeForMerchant(merchantId, from)) {
            byExceptionType.put(String.valueOf(row[0]), toLong(row[1]));
        }

        List<ExceptionRecord> recentExceptions = exceptionRecordRepository
                .findByMerchantIdAndDetectedAtAfter(
                        merchantId,
                        from,
                        org.springframework.data.domain.PageRequest.of(
                                0, 5,
                                org.springframework.data.domain.Sort.by(
                                        org.springframework.data.domain.Sort.Direction.DESC,
                                        "detectedAt")))
                .getContent();

        return Map.of(
                "days", days,
                "totalTransactions", totalTransactions,
                "matched", matched,
                "openExceptions", openExceptions,
                "matchRate", matchRate,
                "byProvider", byProvider,
                "byExceptionType", byExceptionType,
                "recentExceptions", recentExceptions.stream()
                        .sorted(Comparator.comparing(ExceptionRecord::getDetectedAt).reversed())
                        .limit(5)
                        .map(ex -> Map.of(
                                "id", ex.getId(),
                                "type", ex.getExceptionType().name(),
                                "severity", ex.getSeverity().name(),
                                "status", ex.getStatus().name()))
                        .toList());
    }

    public Map<String, Object> metrics() {
        long processed = transactionRepository.count();
        long matched = transactionRepository.countByReconciliationStatus(ReconciliationStatus.MATCHED);
        long exceptionCount = exceptionRecordRepository.countByStatusIn(
                List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW));

        return Map.of(
                "transactionsProcessed", processed,
                "openExceptions", exceptionCount,
                "matchRate", processed == 0 ? 0.0 : matched * 100.0 / processed,
                "webhookQueueDepth", 0,
                "status", "ok");
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
