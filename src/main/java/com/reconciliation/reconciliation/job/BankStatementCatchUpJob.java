package com.reconciliation.reconciliation.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.reconciliation.bank.entity.BankStatementEntry;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import com.reconciliation.bank.service.BankStatementMatchingService;
import com.reconciliation.common.enums.BankEntryStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class BankStatementCatchUpJob {

    private final BankStatementMatchingService matchingService;
    private final BankStatementEntryRepository bankEntryRepository;
    private final SettlementRepository settlementRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Value("${app.bank-matching.unmatched-entry-grace-hours:48}")
    private int unmatchedEntryGraceHours;

    @Value("${app.bank-matching.overdue-settlement-days:7}")
    private int overdueSettlementDays;

    public BankStatementCatchUpJob(BankStatementMatchingService matchingService,
                                   BankStatementEntryRepository bankEntryRepository,
                                   SettlementRepository settlementRepository,
                                   ExceptionRecordService exceptionRecordService) {
        this.matchingService = matchingService;
        this.bankEntryRepository = bankEntryRepository;
        this.settlementRepository = settlementRepository;
        this.exceptionRecordService = exceptionRecordService;
    }

    /** Runs daily at 9 AM — retries pending entries and flags overdue situations. */
    @Bean
    public RecurringTask<Void> bankStatementCatchUpTask() {
        return new RecurringTask<>("bank-statement-catch-up",
                Schedules.cron("0 0 9 * * *"),
                Void.class) {
            @Override
            public void executeRecurringly(TaskInstance<Void> taskInstance, ExecutionContext ctx) {
                log.info("Bank statement catch-up job starting");
                try {
                    run();
                } catch (Exception e) {
                    log.error("Bank statement catch-up job failed", e);
                }
            }
        };
    }

    public Map<String, Integer> run() {
        int rematched = retryPending();
        int unmatchedFlagged = flagOverdueEntries();
        int settlementsFlagged = flagSettlementsWithNoBankCredit();

        Map<String, Integer> summary = Map.of(
                "rematched", rematched,
                "unmatchedEntriesFlagged", unmatchedFlagged,
                "overdueSettlementsFlagged", settlementsFlagged);

        log.info("Bank statement catch-up complete: {}", summary);
        return summary;
    }

    /** Re-run three-pass matching for all PENDING entries. */
    private int retryPending() {
        int count = matchingService.rematchPending();
        log.info("Catch-up: re-matched {} previously pending bank entries", count);
        return count;
    }

    /**
     * Bank CR entries still PENDING after the grace period (48h default).
     * These are credits where no settlement was ever found — could mean
     * Razorpay sent money but never sent a settlement webhook.
     */
    private int flagOverdueEntries() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(unmatchedEntryGraceHours);
        List<BankStatementEntry> overdue = bankEntryRepository.findOverduePendingCredits(cutoff);
        int flagged = 0;

        for (BankStatementEntry entry : overdue) {
            String desc = String.format(
                    "Bank credit of %d paisa on %s (UTR=%s, narration='%s') has been pending for "
                    + ">%d hours with no matching settlement found.",
                    entry.getAmount(), entry.getEntryDate(),
                    entry.getUtrNumber() != null ? entry.getUtrNumber() : "N/A",
                    entry.getNarration(), unmatchedEntryGraceHours);

            exceptionRecordService.createForBankEntry(
                    ExceptionType.UNMATCHED_BANK_CREDIT,
                    Severity.MEDIUM,
                    entry.getId(),
                    entry.getAmount(),
                    entry.getCurrency(),
                    desc,
                    entry.getMerchantId());

            entry.setMatchStatus(BankEntryStatus.UNMATCHED);
            bankEntryRepository.save(entry);
            flagged++;
            log.warn("Flagged overdue bank entry id={} amount={} date={}",
                    entry.getId(), entry.getAmount(), entry.getEntryDate());
        }
        return flagged;
    }

    /**
     * Settlements in SETTLED state older than overdueSettlementDays that never received
     * a matching bank credit. Razorpay said it settled, but we have no bank confirmation.
     */
    private int flagSettlementsWithNoBankCredit() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(overdueSettlementDays);
        List<Settlement> overdue = settlementRepository.findSettledBeforeCutoff(cutoff);
        int flagged = 0;

        for (Settlement s : overdue) {
            boolean alreadyFlagged = exceptionRecordService.alreadyExistsForSettlement(
                    ExceptionType.OVERDUE_BANK_CREDIT, s.getId());
            if (!alreadyFlagged) {
                String desc = String.format(
                        "Settlement %s (provider=%s, netAmount=%d %s) has been in SETTLED state "
                        + "for >%d days without a confirmed bank credit. "
                        + "Razorpay marked it settled but no matching bank statement entry found.",
                        s.getProviderSettlementId(), s.getProvider(),
                        s.getNetAmount(), s.getCurrency(), overdueSettlementDays);

                exceptionRecordService.createForSettlement(
                        ExceptionType.OVERDUE_BANK_CREDIT,
                        Severity.HIGH,
                        s.getId(),
                        s.getNetAmount(),
                        null,
                        s.getCurrency(),
                        desc,
                        s.getMerchantId());

                flagged++;
                log.warn("Settlement {} overdue for bank credit confirmation", s.getProviderSettlementId());
            }
        }
        return flagged;
    }
}
