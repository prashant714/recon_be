package com.reconciliation.reconciliation.rules;

import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.ReportLineMatchStatus;
import com.reconciliation.common.enums.Severity;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.entity.SettlementReportLine;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.settlement.repository.SettlementReportLineRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(10)
public class ProviderReportMatchRule implements ReconciliationRule {

    private final SettlementReportLineRepository reportLineRepository;
    private final TransactionRepository transactionRepository;
    private final SettlementRepository settlementRepository;
    private final ExceptionRecordService exceptionRecordService;

    @Value("${app.reconciliation.amount-tolerance-paisa:100}")
    private long tolerancePaisa;

    @Override
    public String getName() {
        return "ProviderReportMatchRule";
    }

    @Override
    @Transactional
    public void evaluate() {
        List<SettlementReportLine> pendingLines =
                reportLineRepository.findByMatchStatus(ReportLineMatchStatus.PENDING);

        if (pendingLines.isEmpty()) return;

        log.info("ProviderReportMatchRule: evaluating {} pending report lines", pendingLines.size());

        for (SettlementReportLine line : pendingLines) {
            processLine(line);
        }
    }

    private void processLine(SettlementReportLine line) {
        Optional<Transaction> txnOpt = transactionRepository
                .findByProviderAndProviderTransactionId(line.getProvider(), line.getProviderTxnId());

        if (txnOpt.isEmpty()) {
            // Transaction exists in Razorpay's settlement but not in our DB
            line.setMatchStatus(ReportLineMatchStatus.NOT_FOUND_IN_DB);
            reportLineRepository.save(line);

            Settlement settlement = settlementRepository.findById(line.getSettlementId()).orElse(null);
            if (settlement != null) {
                String desc = String.format(
                        "Provider report for settlement %s contains transaction %s (net=%d) "
                        + "which does not exist in our database.",
                        settlement.getProviderSettlementId(), line.getProviderTxnId(), line.getNetAmount());

                exceptionRecordService.createForSettlement(
                        ExceptionType.PROVIDER_REPORT_MISMATCH,
                        Severity.HIGH,
                        line.getSettlementId(),
                        line.getNetAmount(),
                        0L,
                        line.getCurrency(),
                        desc,
                        settlement.getMerchantId());
            }
            log.warn("ProviderReportMatchRule: txn {} not found in DB for settlement {}",
                    line.getProviderTxnId(), line.getSettlementId());
            return;
        }

        Transaction txn = txnOpt.get();
        long diff = Math.abs(txn.getNetAmount() != null ? txn.getNetAmount() - line.getNetAmount() : line.getNetAmount());
        Settlement settlement = settlementRepository.findById(line.getSettlementId()).orElse(null);

        if (diff <= tolerancePaisa) {
            // amounts match — mark line and transaction as matched
            line.setMatchStatus(ReportLineMatchStatus.MATCHED);
            line.setMatchedToTxnId(txn.getId());

            boolean txnChanged = false;
            if (settlement != null
                    && settlement.getProviderSettlementId() != null
                    && !settlement.getProviderSettlementId().equals(txn.getSettlementId())) {
                txn.setSettlementId(settlement.getProviderSettlementId());
                txnChanged = true;
            }
            if (txn.getReconciliationStatus() != ReconciliationStatus.MATCHED) {
                txn.setReconciliationStatus(ReconciliationStatus.MATCHED);
                txn.setMatchedAt(OffsetDateTime.now());
                txnChanged = true;
            }
            if (txnChanged) {
                transactionRepository.save(txn);
            }
            log.debug("ProviderReportMatchRule: matched txn {} net={}",
                    line.getProviderTxnId(), line.getNetAmount());
        } else {
            // amounts differ — mismatch
            line.setMatchStatus(ReportLineMatchStatus.AMOUNT_MISMATCH);
            line.setMatchedToTxnId(txn.getId());

            String settlementRef = settlement != null ? settlement.getProviderSettlementId() : "unknown";

            String desc = String.format(
                    "Settlement %s: transaction %s has net amount mismatch. "
                    + "Provider report: %d, our DB: %d, difference: %d paisa.",
                    settlementRef, line.getProviderTxnId(),
                    line.getNetAmount(), txn.getNetAmount() != null ? txn.getNetAmount() : 0L, diff);

            exceptionRecordService.createForTransaction(
                    ExceptionType.PROVIDER_REPORT_MISMATCH,
                    Severity.HIGH,
                    txn.getId(),
                    line.getNetAmount(),
                    txn.getNetAmount(),
                    line.getCurrency(),
                    desc,
                    txn.getMerchantId());

            txn.setReconciliationStatus(ReconciliationStatus.EXCEPTION);
            transactionRepository.save(txn);

            log.warn("ProviderReportMatchRule: amount mismatch for txn {} reportNet={} dbNet={} diff={}",
                    line.getProviderTxnId(), line.getNetAmount(), txn.getNetAmount(), diff);
        }

        reportLineRepository.save(line);
    }
}
