package com.reconciliation.bank;

import com.reconciliation.bank.entity.BankStatementEntry;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import com.reconciliation.bank.service.BankStatementMatchingService;
import com.reconciliation.common.enums.BankEntryStatus;
import com.reconciliation.common.enums.ExceptionType;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.exception_record.service.ExceptionRecordService;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BankStatementMatchingServiceTest {

    private BankStatementEntryRepository bankEntryRepository;
    private SettlementRepository settlementRepository;
    private ExceptionRecordService exceptionRecordService;
    private BankStatementMatchingService service;

    @BeforeEach
    void setUp() {
        bankEntryRepository = mock(BankStatementEntryRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        exceptionRecordService = mock(ExceptionRecordService.class);

        service = new BankStatementMatchingService(
                bankEntryRepository, settlementRepository, exceptionRecordService);
        ReflectionTestUtils.setField(service, "tolerancePaisa", 500L);
    }

    // ─── Pass 1: UTR exact match ──────────────────────────────────────────────

    @Test
    void pass1UtrMatch_marksEntryMatchedAndSettlementMatchedToBank() {
        Settlement settlement = settlement("setl_001", 48800000L, "123456789");
        BankStatementEntry entry = creditEntry(48800000L, "123456789", "RAZORPAY SETTLEMENT");

        when(settlementRepository.findByUtrNumber("123456789")).thenReturn(Optional.of(settlement));
        when(bankEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.matchEntry(entry);

        assertThat(entry.getMatchStatus()).isEqualTo(BankEntryStatus.MATCHED);
        assertThat(entry.getMatchedBy()).isEqualTo("UTR");
        assertThat(entry.getMatchedSettlementId()).isEqualTo(settlement.getId());
        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.MATCHED_TO_BANK);
        verify(settlementRepository).save(settlement);
    }

    @Test
    void pass1UtrMatch_amountsDiffer_createsBankAmountMismatchException() {
        // UTR matches but settlement net is 48800000, bank credited only 47000000 — critical mismatch
        Settlement settlement = settlement("setl_001", 48800000L, "123456789");
        BankStatementEntry entry = creditEntry(47000000L, "123456789", "RAZORPAY SETTLEMENT");

        when(settlementRepository.findByUtrNumber("123456789")).thenReturn(Optional.of(settlement));
        when(bankEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.matchEntry(entry);

        assertThat(entry.getMatchStatus()).isEqualTo(BankEntryStatus.UNMATCHED);
        assertThat(entry.getMatchedBy()).contains("MISMATCH");
        verify(exceptionRecordService).createForSettlement(
                eq(ExceptionType.BANK_AMOUNT_MISMATCH), eq(com.reconciliation.common.enums.Severity.CRITICAL),
                eq(settlement.getId()), anyLong(), anyLong(), any(), any(), any());
    }

    // ─── Pass 2: Amount + Date fuzzy match ───────────────────────────────────

    @Test
    void pass2AmountDate_marksEntryMatchedAndSettlementMatchedToBank() {
        Settlement settlement = settlement("setl_002", 29300000L, null);
        settlement.setBankCreditDate(LocalDate.now().minusDays(1));
        BankStatementEntry entry = creditEntry(29300000L, null, "RAZORPAY PAYMENTS PVT LTD");
        entry.setEntryDate(LocalDate.now());

        when(settlementRepository.findByUtrNumber(any())).thenReturn(Optional.empty());
        when(settlementRepository.findSettledByNetAmountAndCreditDateRange(
                eq("merchant_001"),
                eq(29299500L),
                eq(29300500L),
                any(),
                any())).thenReturn(List.of(settlement));
        when(bankEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.matchEntry(entry);

        assertThat(entry.getMatchStatus()).isEqualTo(BankEntryStatus.MATCHED);
        assertThat(entry.getMatchedBy()).isEqualTo("AMOUNT_DATE");
        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.MATCHED_TO_BANK);
    }

    // ─── Pass 3: Narration parse ──────────────────────────────────────────────

    @Test
    void pass3Narration_marksEntryMatchedAndSettlementMatchedToBank() {
        Settlement settlement = settlement("setl_ABC123", 10000000L, null);
        BankStatementEntry entry = creditEntry(10000000L, null, "RAZORPAY*SETTLEMENT*setl_ABC123");
        entry.setEntryDate(LocalDate.now());

        when(settlementRepository.findByUtrNumber(any())).thenReturn(Optional.empty());
        when(settlementRepository.findSettledByNetAmountAndCreditDateRange(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(settlementRepository.findByProviderSettlementIdInNarration(any()))
                .thenReturn(List.of(settlement));
        when(bankEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.matchEntry(entry);

        assertThat(entry.getMatchStatus()).isEqualTo(BankEntryStatus.MATCHED);
        assertThat(entry.getMatchedBy()).isEqualTo("NARRATION");
        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.MATCHED_TO_BANK);
    }

    // ─── No match ────────────────────────────────────────────────────────────

    @Test
    void noPassSucceeds_entryStaysPending() {
        BankStatementEntry entry = creditEntry(10000000L, null, "RAZORPAY SETTLEMENT");
        entry.setEntryDate(LocalDate.now());

        when(settlementRepository.findByUtrNumber(any())).thenReturn(Optional.empty());
        when(settlementRepository.findSettledByNetAmountAndCreditDateRange(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(settlementRepository.findByProviderSettlementIdInNarration(any())).thenReturn(List.of());

        service.matchEntry(entry);

        assertThat(entry.getMatchStatus()).isEqualTo(BankEntryStatus.PENDING);
        verify(bankEntryRepository, never()).save(any());
    }

    // ─── DR entry ────────────────────────────────────────────────────────────

    @Test
    void debitEntry_isMarkedIgnored() {
        BankStatementEntry entry = BankStatementEntry.builder()
                .creditDebit("DR").amount(50000L).merchantId("m1")
                .narration("Vendor payment").build();
        when(bankEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.matchEntry(entry);

        assertThat(entry.getMatchStatus()).isEqualTo(BankEntryStatus.IGNORED);
        verify(settlementRepository, never()).findByUtrNumber(any());
    }

    // ─── Non-payment-gateway credit ──────────────────────────────────────────

    @Test
    void creditWithNoGatewayKeyword_isMarkedIgnored() {
        BankStatementEntry entry = creditEntry(100000L, null, "Interest credit from HDFC");
        when(bankEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.matchEntry(entry);

        assertThat(entry.getMatchStatus()).isEqualTo(BankEntryStatus.IGNORED);
        verify(settlementRepository, never()).findByUtrNumber(any());
    }

    // ─── Retroactive: settlement arrives after bank entry ────────────────────

    @Test
    void tryMatchBySettlement_matchesExistingPendingBankEntry() {
        Settlement settlement = settlement("setl_RET", 20000000L, "UTR999");
        settlement.setBankCreditDate(LocalDate.now());

        BankStatementEntry pending = creditEntry(20000000L, "UTR999", "RAZORPAY SETTLEMENT");
        pending.setMatchStatus(BankEntryStatus.PENDING);

        when(bankEntryRepository.findByMerchantIdAndUtrNumber(any(), eq("UTR999")))
                .thenReturn(Optional.of(pending));
        when(bankEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.tryMatchBySettlement(settlement);

        assertThat(pending.getMatchStatus()).isEqualTo(BankEntryStatus.MATCHED);
        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.MATCHED_TO_BANK);
    }

    @Test
    void tryMatchBySettlement_notSettledStatus_skipsCompletely() {
        Settlement pending = settlement("setl_P", 10000L, "UTR111");
        pending.setSettlementStatus(SettlementStatus.PENDING);

        service.tryMatchBySettlement(pending);

        verifyNoInteractions(bankEntryRepository);
    }

    // ─── Batch rematch ───────────────────────────────────────────────────────

    @Test
    void rematchPending_matchesEntriesThatNowHaveASettlement() {
        Settlement settlement = settlement("setl_LATE", 5000000L, "UTR_LATE");
        BankStatementEntry entry = creditEntry(5000000L, "UTR_LATE", "RAZORPAY SETTLEMENT");
        entry.setMatchStatus(BankEntryStatus.PENDING);
        entry.setEntryDate(LocalDate.now());

        when(bankEntryRepository.findByMatchStatus(BankEntryStatus.PENDING)).thenReturn(List.of(entry));
        when(settlementRepository.findByUtrNumber("UTR_LATE")).thenReturn(Optional.of(settlement));
        when(bankEntryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        int matched = service.rematchPending();

        assertThat(matched).isEqualTo(1);
        assertThat(entry.getMatchStatus()).isEqualTo(BankEntryStatus.MATCHED);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Settlement settlement(String providerId, Long netAmount, String utr) {
        return Settlement.builder()
                .id(Long.parseLong(Math.abs(providerId.hashCode()) + "1"))
                .provider("razorpay")
                .providerSettlementId(providerId)
                .merchantId("merchant_001")
                .grossAmount(netAmount + 100000L)
                .netAmount(netAmount)
                .totalFees(100000L)
                .currency("INR")
                .utrNumber(utr)
                .settlementStatus(SettlementStatus.SETTLED)
                .build();
    }

    private BankStatementEntry creditEntry(Long amount, String utr, String narration) {
        return BankStatementEntry.builder()
                .merchantId("merchant_001")
                .uploadBatchId("batch_test")
                .entryDate(LocalDate.now())
                .amount(amount)
                .currency("INR")
                .creditDebit("CR")
                .utrNumber(utr)
                .narration(narration)
                .providerHint("razorpay")
                .matchStatus(BankEntryStatus.PENDING)
                .build();
    }
}
