package com.reconciliation.settlement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.reconciliation.bank.service.BankStatementMatchingService;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final TransactionRepository transactionRepository;
    private final BankStatementMatchingService bankStatementMatchingService;

    /**
     * Persist a settlement and immediately attempt retroactive bank statement matching.
     * Called by settlement ingestion paths (admin, polling, webhook normalization).
     */
    @Transactional
    public Settlement saveAndMatch(Settlement settlement) {
        Settlement saved = settlementRepository.save(settlement);
        bankStatementMatchingService.tryMatchBySettlement(saved);
        return saved;
    }

    @Transactional
    public Settlement upsertRazorpaySettlement(JsonNode payload, String merchantId) {
        JsonNode entity = payload.path("payload").path("settlement").path("entity");
        String providerSettlementId = entity.path("id").asText(null);
        if (providerSettlementId == null || providerSettlementId.isBlank()) {
            throw new IllegalArgumentException("Razorpay settlement event missing settlement id");
        }

        Long netAmount = entity.path("amount").asLong(0L);
        Long totalFees = entity.path("fees").asLong(entity.path("fee").asLong(0L));
        Long totalTax = entity.path("tax").asLong(0L);
        Long grossAmount = netAmount + totalFees + totalTax;
        OffsetDateTime settledAt = fromUnix(entity.path("created_at").asLong(0L));

        Settlement settlement = settlementRepository
                .findByProviderAndProviderSettlementId("razorpay", providerSettlementId)
                .orElseGet(() -> Settlement.builder()
                        .provider("razorpay")
                        .providerSettlementId(providerSettlementId)
                        .merchantId(merchantId)
                        .build());

        settlement.setMerchantId(merchantId);
        settlement.setGrossAmount(grossAmount);
        settlement.setTotalFees(totalFees);
        settlement.setTotalTax(totalTax);
        settlement.setNetAmount(netAmount);
        settlement.setCurrency(entity.path("currency").asText("INR").toUpperCase());
        settlement.setUtrNumber(textOrNull(entity.path("utr").asText(null)));
        settlement.setSettlementStatus(SettlementStatus.SETTLED);
        settlement.setTransactionCount(entity.path("transaction_count").isNumber()
                ? entity.path("transaction_count").asInt()
                : settlement.getTransactionCount());
        settlement.setSettledAt(settledAt);
        // bankCreditDate is set only by BankStatementMatchingService when actual bank credit is confirmed

        return saveAndMatch(settlement);
    }

    public Map<String, Object> list(
            String provider,
            String status,
            LocalDate dateFrom,
            LocalDate dateTo,
            int page,
            int limit) {
        SettlementStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = SettlementStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // unknown status string — treat as no filter
            }
        }

        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(limit, 1), 100));
        Page<Settlement> results = settlementRepository.findFiltered(
                provider, statusEnum, dateFrom, dateTo, pageable);

        return Map.of(
                "items", results.getContent(),
                "page", results.getNumber(),
                "limit", results.getSize(),
                "total", results.getTotalElements());
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
        return transactionRepository.findBySettlementId(settlement.getProviderSettlementId())
                .stream()
                .sorted(Comparator.comparing(Transaction::getEventOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private OffsetDateTime fromUnix(long epochSeconds) {
        if (epochSeconds == 0L) {
            return null;
        }
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
