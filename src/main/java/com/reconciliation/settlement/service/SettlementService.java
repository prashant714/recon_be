package com.reconciliation.settlement.service;

import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final TransactionRepository transactionRepository;

    public Map<String, Object> list(
            String provider,
            String status,
            LocalDate dateFrom,
            LocalDate dateTo,
            int page,
            int limit) {
        List<Settlement> filtered = settlementRepository.findAll().stream()
                .filter(s -> provider == null || provider.equalsIgnoreCase(s.getProvider()))
                .filter(s -> status == null || s.getSettlementStatus().name().equalsIgnoreCase(status))
                .filter(s -> dateFrom == null || (s.getBankCreditDate() != null && !s.getBankCreditDate().isBefore(dateFrom)))
                .filter(s -> dateTo == null || (s.getBankCreditDate() != null && !s.getBankCreditDate().isAfter(dateTo)))
                .sorted(Comparator.comparing(Settlement::getSettledAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        return paginate(filtered, page, limit);
    }

    public Map<String, Object> detail(Long id) {
        Settlement settlement = settlementRepository.findById(id).orElseThrow();
        List<Transaction> linked = transactionsForSettlement(settlement);
        return Map.of(
                "settlement", settlement,
                "linkedTransactionCount", linked.size(),
                "transactionsNetAmount", linked.stream()
                        .map(Transaction::getNetAmount)
                        .filter(java.util.Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .sum());
    }

    public List<Transaction> transactions(Long id) {
        Settlement settlement = settlementRepository.findById(id).orElseThrow();
        return transactionsForSettlement(settlement);
    }

    private List<Transaction> transactionsForSettlement(Settlement settlement) {
        return transactionRepository.findAll().stream()
                .filter(tx -> settlement.getProviderSettlementId().equals(tx.getSettlementId()))
                .sorted(Comparator.comparing(Transaction::getEventOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private Map<String, Object> paginate(List<?> items, int page, int limit) {
        int safePage = Math.max(page, 0);
        int safeLimit = Math.max(limit, 1);
        int from = Math.min(safePage * safeLimit, items.size());
        int to = Math.min(from + safeLimit, items.size());
        return Map.of(
                "items", items.subList(from, to),
                "page", safePage,
                "limit", safeLimit,
                "total", items.size());
    }
}
