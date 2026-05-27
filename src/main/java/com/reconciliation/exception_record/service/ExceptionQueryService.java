package com.reconciliation.exception_record.service;

import com.reconciliation.audit.service.AuditService;
import com.reconciliation.common.enums.ExceptionStatus;
import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.repository.ExceptionRecordRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExceptionQueryService {

    private final ExceptionRecordRepository exceptionRecordRepository;
    private final TransactionRepository transactionRepository;
    private final AuditService auditService;

    public Map<String, Object> list(
            LocalDate fromDate,
            LocalDate toDate,
            String provider,
            String type,
            String status,
            int page,
            int limit) {
        OffsetDateTime from = fromDate.atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
        OffsetDateTime to = toDate.plusDays(1).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
        List<ExceptionRecord> filtered = exceptionRecordRepository.findAll().stream()
                .filter(ex -> ex.getDetectedAt() != null && !ex.getDetectedAt().isBefore(from) && ex.getDetectedAt().isBefore(to))
                .filter(ex -> type == null || ex.getExceptionType().name().equalsIgnoreCase(type))
                .filter(ex -> status == null || ex.getStatus().name().equalsIgnoreCase(status))
                .filter(ex -> provider == null || matchesProvider(ex, provider))
                .sorted(Comparator.comparing(ExceptionRecord::getDetectedAt).reversed())
                .toList();

        Map<String, Long> summary = Map.of(
                "open", filtered.stream().filter(ex -> ex.getStatus() == ExceptionStatus.OPEN).count(),
                "inReview", filtered.stream().filter(ex -> ex.getStatus() == ExceptionStatus.IN_REVIEW).count(),
                "resolved", filtered.stream().filter(ex -> ex.getStatus() == ExceptionStatus.RESOLVED).count()
        );

        int safePage = Math.max(page, 0);
        int safeLimit = Math.max(limit, 1);
        int fromIndex = Math.min(safePage * safeLimit, filtered.size());
        int toIndex = Math.min(fromIndex + safeLimit, filtered.size());

        return Map.of(
                "summary", summary,
                "items", filtered.subList(fromIndex, toIndex),
                "page", safePage,
                "limit", safeLimit,
                "total", filtered.size());
    }

    public Map<String, Object> detail(Long id) {
        ExceptionRecord record = exceptionRecordRepository.findById(id).orElseThrow();
        Transaction transaction = record.getTransactionId() == null
                ? null
                : transactionRepository.findById(record.getTransactionId()).orElse(null);
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("exception", record);
        response.put("transaction", transaction);
        response.put("auditLogs", auditService.search("exception_record", id, null));
        return response;
    }

    @Transactional
    public ExceptionRecord update(Long id, String newStatus, String notes, String actor, String ipAddress) {
        ExceptionRecord record = exceptionRecordRepository.findById(id).orElseThrow();
        ExceptionRecord before = ExceptionRecord.builder()
                .id(record.getId())
                .status(record.getStatus())
                .resolutionNotes(record.getResolutionNotes())
                .resolvedAt(record.getResolvedAt())
                .resolvedBy(record.getResolvedBy())
                .build();

        ExceptionStatus status = ExceptionStatus.valueOf(newStatus.toUpperCase());
        record.setStatus(status);
        record.setResolutionNotes(notes);
        if (status == ExceptionStatus.RESOLVED || status == ExceptionStatus.IGNORED) {
            record.setResolvedAt(OffsetDateTime.now());
            record.setResolvedBy(actor);
        }

        ExceptionRecord saved = exceptionRecordRepository.save(record);
        auditService.log(
                actor,
                "exception_status_updated",
                "exception_record",
                saved.getId(),
                before,
                saved,
                ipAddress);
        return saved;
    }

    private boolean matchesProvider(ExceptionRecord record, String provider) {
        if (record.getTransactionId() == null) {
            return false;
        }
        return transactionRepository.findById(record.getTransactionId())
                .map(tx -> provider.equalsIgnoreCase(tx.getProvider()))
                .orElse(false);
    }
}
