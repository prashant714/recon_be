package com.reconciliation.dashboard.service;

import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TransactionRepository transactionRepository;
    private final ExceptionRecordRepository exceptionRecordRepository;

    public Map<String, Object> summary(String merchantId, int days) {
        OffsetDateTime from = OffsetDateTime.now().minusDays(days);

        long totalTransactions = transactionRepository.countByMerchantIdAndEventOccurredAtAfter(merchantId, from);
        long matched = transactionRepository.countByMerchantIdAndReconciliationStatusAndEventOccurredAtAfter(
                merchantId, ReconciliationStatus.MATCHED, from);
        long openExceptions = exceptionRecordRepository.countOpenExceptions(merchantId, from);
        double matchRate = totalTransactions == 0 ? 0.0 : (matched * 100.0) / totalTransactions;

        Map<String, Map<String, Long>> byProvider = new LinkedHashMap<>();
        for (Object[] row : transactionRepository.findProviderSummaryForMerchantSince(merchantId, from)) {
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

    public Map<String, Object> metrics(String merchantId) {
        OffsetDateTime from = OffsetDateTime.now().minusDays(30);
        long processed = transactionRepository.countByMerchantIdAndEventOccurredAtAfter(merchantId, from);
        long matched = transactionRepository.countByMerchantIdAndReconciliationStatusAndEventOccurredAtAfter(
                merchantId, ReconciliationStatus.MATCHED, from);
        long exceptionCount = exceptionRecordRepository.countByMerchantIdAndStatusIn(merchantId,
                List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW));

        return Map.of(
                "transactionsProcessed", processed,
                "openExceptions", exceptionCount,
                "matchRate", processed == 0 ? 0.0 : matched * 100.0 / processed,
                "webhookQueueDepth", 0,
                "status", "ok");
    }

    public Map<String, Object> activity(String merchantId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        int eachLimit = Math.max(safeLimit, 8);

        List<Map<String, Object>> items = new ArrayList<>();
        transactionRepository.findByMerchantIdOrderByEventOccurredAtDesc(
                        merchantId, PageRequest.of(0, eachLimit))
                .forEach(tx -> items.add(transactionActivity(tx)));

        exceptionRecordRepository.findByMerchantIdOrderByDetectedAtDesc(
                        merchantId, PageRequest.of(0, eachLimit))
                .forEach(ex -> items.add(exceptionActivity(ex)));

        List<Map<String, Object>> sorted = items.stream()
                .sorted(Comparator.comparing(item -> (OffsetDateTime) item.get("time"),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .toList();

        return Map.of("items", sorted);
    }

    public Map<String, Object> trends(String merchantId, int days) {
        int safeDays = Math.min(Math.max(days, 1), 90);
        LocalDate start = LocalDate.now().minusDays(safeDays - 1L);
        OffsetDateTime since = start.atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (int i = 0; i < safeDays; i++) {
            buckets.put(start.plusDays(i), new long[] {0L, 0L, 0L});
        }

        for (Object[] row : transactionRepository.findDailyTransactionTrend(merchantId, since)) {
            LocalDate date = toLocalDate(row[0]);
            long matched = toLong(row[1]);
            long transactions = toLong(row[2]);
            if (buckets.containsKey(date)) {
                long[] values = buckets.get(date);
                values[0] = matched;
                values[2] = transactions;
            }
        }

        for (Object[] row : exceptionRecordRepository.findDailyExceptionTrend(merchantId, since)) {
            LocalDate date = toLocalDate(row[0]);
            long exceptions = toLong(row[1]);
            if (buckets.containsKey(date)) {
                buckets.get(date)[1] = exceptions;
            }
        }

        return Map.of("items", buckets.entrySet().stream()
                .map(entry -> {
                    long[] values = entry.getValue();
                    Map<String, Object> row = new HashMap<>();
                    row.put("date", entry.getKey().toString());
                    row.put("matched", values[0]);
                    row.put("exceptions", values[1]);
                    row.put("transactions", values[2]);
                    return row;
                })
                .toList());
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private Map<String, Object> transactionActivity(Transaction tx) {
        String text = switch (tx.getReconciliationStatus()) {
            case MATCHED -> title(tx.getProvider()) + " payment matched";
            case EXCEPTION -> title(tx.getProvider()) + " payment needs review";
            default -> title(tx.getProvider()) + " transaction ingested";
        };
        String type = switch (tx.getReconciliationStatus()) {
            case MATCHED -> "success";
            case EXCEPTION -> "warning";
            default -> "info";
        };
        return Map.of(
                "text", text,
                "subtext", tx.getProviderTransactionId(),
                "time", tx.getEventOccurredAt(),
                "type", type);
    }

    private Map<String, Object> exceptionActivity(ExceptionRecord ex) {
        return Map.of(
                "text", "Exception opened",
                "subtext", ex.getExceptionType().name(),
                "time", ex.getDetectedAt(),
                "type", "warning");
    }

    private String title(String value) {
        if (value == null || value.isBlank()) {
            return "Provider";
        }
        return value.substring(0, 1).toUpperCase() + value.substring(1).toLowerCase();
    }
}
