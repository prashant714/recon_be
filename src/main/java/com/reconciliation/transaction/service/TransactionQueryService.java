package com.reconciliation.transaction.service;

import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final TransactionRepository transactionRepository;

    public Map<String, Object> list(
            String provider,
            String status,
            String orderId,
            String payerEmail,
            OffsetDateTime dateFrom,
            OffsetDateTime dateTo,
            int page,
            int limit) {
        List<Transaction> filtered = transactionRepository.findAll().stream()
                .filter(tx -> provider == null || provider.equalsIgnoreCase(tx.getProvider()))
                .filter(tx -> status == null || tx.getStatus().name().equalsIgnoreCase(status))
                .filter(tx -> orderId == null || orderId.equalsIgnoreCase(tx.getOrderId()))
                .filter(tx -> payerEmail == null || payerEmail.equalsIgnoreCase(tx.getPayerEmail()))
                .filter(tx -> dateFrom == null || (tx.getEventOccurredAt() != null && !tx.getEventOccurredAt().isBefore(dateFrom)))
                .filter(tx -> dateTo == null || (tx.getEventOccurredAt() != null && !tx.getEventOccurredAt().isAfter(dateTo)))
                .sorted(Comparator.comparing(Transaction::getEventOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int safePage = Math.max(page, 0);
        int safeLimit = Math.max(limit, 1);
        int from = Math.min(safePage * safeLimit, filtered.size());
        int to = Math.min(from + safeLimit, filtered.size());
        return Map.of(
                "items", filtered.subList(from, to),
                "page", safePage,
                "limit", safeLimit,
                "total", filtered.size());
    }

    public Map<String, Object> detail(Long id) {
        Transaction transaction = transactionRepository.findById(id).orElseThrow();
        List<Transaction> related = new ArrayList<>();
        if (transaction.getParentTransactionId() != null) {
            transactionRepository.findById(transaction.getParentTransactionId()).ifPresent(related::add);
        }
        related.addAll(transactionRepository.findAll().stream()
                .filter(tx -> id.equals(tx.getParentTransactionId()))
                .toList());
        return Map.of(
                "transaction", transaction,
                "relatedTransactions", related);
    }
}
