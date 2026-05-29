package com.reconciliation.bank.service;

import com.reconciliation.bank.entity.BankStatementEntry;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import com.reconciliation.common.enums.BankEntryStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BankStatementMatchingService {

    private final BankStatementEntryRepository bankEntryRepository;
    private final SettlementRepository settlementRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Value("${app.bank-matching.amount-tolerance-paisa:500}")
    private long tolerancePaisa;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point 1: called at upload time for each new bank entry
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void matchEntry(BankStatementEntry entry) {
        if (!"CR".equalsIgnoreCase(entry.getCreditDebit())) {
            entry.setMatchStatus(BankEntryStatus.IGNORED);
            bankEntryRepository.save(entry);
            return;
        }

        Optional<Settlement> match = tryPass1Utr(entry)
                .or(() -> tryPass2AmountDate(entry))
                .or(() -> tryPass3Narration(entry));

        if (match.isPresent()) {
            applyMatch(entry, match.get(), resolveMatchedBy(entry, match.get()));
        }
        // else: stays PENDING — catch-up job will retry and eventually flag as UNMATCHED
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point 2: called when a settlement is created/updated,
    // retroactively matches against PENDING bank entries
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void tryMatchBySettlement(Settlement settlement) {
        if (settlement.getSettlementStatus() != SettlementStatus.SETTLED) return;

        // Check if any PENDING bank entry matches this settlement
        if (settlement.getUtrNumber() != null) {
            bankEntryRepository.findByMerchantIdAndUtrNumber(
                    settlement.getMerchantId(), settlement.getUtrNumber())
                    .filter(e -> e.getMatchStatus() == BankEntryStatus.PENDING)
                    .ifPresent(entry -> applyMatch(entry, settlement, "UTR"));
        }

        // Also try amount+date for any unmatched entries around the settlement date
        if (settlement.getBankCreditDate() != null && settlement.getNetAmount() != null) {
            LocalDate creditDate = settlement.getBankCreditDate();
            List<BankStatementEntry> candidates = bankEntryRepository
                    .findPendingCreditByAmountAndDateRange(
                            settlement.getMerchantId(),
                            settlement.getNetAmount(),
                            creditDate.minusDays(1),
                            creditDate.plusDays(1));

            candidates.stream().findFirst()
                    .ifPresent(entry -> applyMatch(entry, settlement, "AMOUNT_DATE"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point 3: batch re-match for catch-up job
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public int rematchPending() {
        List<BankStatementEntry> pending = bankEntryRepository.findByMatchStatus(BankEntryStatus.PENDING)
                .stream()
                .filter(e -> "CR".equalsIgnoreCase(e.getCreditDebit()))
                .toList();

        int matched = 0;
        for (BankStatementEntry entry : pending) {
            Optional<Settlement> match = tryPass1Utr(entry)
                    .or(() -> tryPass2AmountDate(entry))
                    .or(() -> tryPass3Narration(entry));

            if (match.isPresent()) {
                applyMatch(entry, match.get(), resolveMatchedBy(entry, match.get()));
                matched++;
            }
        }
        return matched;
    }

    @Transactional
    public int rematchPending(String merchantId, String uploadBatchId) {
        List<BankStatementEntry> pending = bankEntryRepository
                .findByMerchantIdAndUploadBatchIdAndMatchStatus(
                        merchantId, uploadBatchId, BankEntryStatus.PENDING)
                .stream()
                .filter(e -> "CR".equalsIgnoreCase(e.getCreditDebit()))
                .toList();

        int matched = 0;
        for (BankStatementEntry entry : pending) {
            Optional<Settlement> match = tryPass1Utr(entry)
                    .or(() -> tryPass2AmountDate(entry))
                    .or(() -> tryPass3Narration(entry));

            if (match.isPresent()) {
                applyMatch(entry, match.get(), resolveMatchedBy(entry, match.get()));
                matched++;
            }
        }
        return matched;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Three matching passes
    // ─────────────────────────────────────────────────────────────────────────

    /** Pass 1 — exact UTR match. Confidence 100%. */
    private Optional<Settlement> tryPass1Utr(BankStatementEntry entry) {
        if (entry.getUtrNumber() == null || entry.getUtrNumber().isBlank()) return Optional.empty();
        return settlementRepository.findByUtrNumber(entry.getUtrNumber())
                .filter(s -> s.getSettlementStatus() == SettlementStatus.SETTLED);
    }

    /** Pass 2 — amount within tolerance + date ±1 day + same provider. Confidence ~85%.
     *  The DB query pre-filters by merchantId and amount range; narration is checked here. */
    private Optional<Settlement> tryPass2AmountDate(BankStatementEntry entry) {
        LocalDate date = entry.getEntryDate();
        List<Settlement> candidates = settlementRepository.findSettledByNetAmountAndCreditDateRange(
                entry.getMerchantId(),
                entry.getAmount() - tolerancePaisa,
                entry.getAmount() + tolerancePaisa,
                date.minusDays(1),
                date.plusDays(1));

        return candidates.stream()
                .filter(s -> providerMatchesNarration(s.getProvider(), entry.getNarration()))
                .findFirst();
    }

    /** Pass 3 — parse providerSettlementId from narration. Confidence ~70%. */
    private Optional<Settlement> tryPass3Narration(BankStatementEntry entry) {
        if (entry.getNarration() == null) return Optional.empty();
        String narration = entry.getNarration().toUpperCase();

        // Razorpay pattern: "RAZORPAY*SETTLEMENT*setl_ABC" or contains "SETL_"
        List<Settlement> candidates = settlementRepository.findByProviderSettlementIdInNarration(narration);
        return candidates.stream()
                .filter(s -> s.getSettlementStatus() == SettlementStatus.SETTLED)
                .findFirst();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Apply a confirmed match
    // ─────────────────────────────────────────────────────────────────────────

    private void applyMatch(BankStatementEntry entry, Settlement settlement, String matchedBy) {
        long diff = Math.abs(settlement.getNetAmount() - entry.getAmount());

        if (diff <= tolerancePaisa) {
            // Clean match
            entry.setMatchStatus(BankEntryStatus.MATCHED);
            entry.setMatchedBy(matchedBy);
            entry.setMatchedSettlementId(settlement.getId());

            settlement.setSettlementStatus(SettlementStatus.MATCHED_TO_BANK);
            settlement.setBankCreditAmount(entry.getAmount());
            settlement.setBankCreditDate(entry.getEntryDate());
            if (settlement.getUtrNumber() == null && entry.getUtrNumber() != null) {
                settlement.setUtrNumber(entry.getUtrNumber());
            }
            settlementRepository.save(settlement);

            log.info("Bank matched: settlementId={} batchId={} via={} amount={}",
                    settlement.getProviderSettlementId(), entry.getUploadBatchId(), matchedBy, entry.getAmount());
        } else {
            // Identifiers matched but amounts differ — flag as UNMATCHED so the
            // catch-up job keeps retrying and ops can see it clearly as unresolved.
            entry.setMatchStatus(BankEntryStatus.UNMATCHED);
            entry.setMatchedBy(matchedBy + "_MISMATCH");
            entry.setMatchedSettlementId(settlement.getId());

            String desc = String.format(
                    "Bank credit UTR=%s on %s matched settlement %s by %s, "
                    + "but amounts differ: bank=%d paisa, settlement.netAmount=%d paisa, diff=%d paisa.",
                    entry.getUtrNumber(), entry.getEntryDate(),
                    settlement.getProviderSettlementId(), matchedBy,
                    entry.getAmount(), settlement.getNetAmount(), diff);

            exceptionRecordService.createForSettlement(
                    ExceptionType.BANK_AMOUNT_MISMATCH,
                    Severity.CRITICAL,
                    settlement.getId(),
                    settlement.getNetAmount(),
                    entry.getAmount(),
                    entry.getCurrency(),
                    desc,
                    settlement.getMerchantId());

            log.warn("Bank amount mismatch: settlement={} bankAmount={} settlementNet={} diff={}",
                    settlement.getProviderSettlementId(), entry.getAmount(), settlement.getNetAmount(), diff);
        }

        bankEntryRepository.save(entry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean providerMatchesNarration(String provider, String narration) {
        if (narration == null || provider == null) return true; // don't filter if no info
        return narration.toUpperCase().contains(provider.toUpperCase());
    }

    private String resolveMatchedBy(BankStatementEntry entry, Settlement settlement) {
        if (entry.getUtrNumber() != null && entry.getUtrNumber().equals(settlement.getUtrNumber())) {
            return "UTR";
        }
        if (entry.getNarration() != null && settlement.getProviderSettlementId() != null
                && entry.getNarration().toUpperCase().contains(
                        settlement.getProviderSettlementId().toUpperCase())) {
            return "NARRATION";
        }
        return "AMOUNT_DATE";
    }
}
