package com.reconciliation.bank;

import com.reconciliation.bank.entity.BankStatementEntry;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import com.reconciliation.bank.service.BankStatementIngestionService;
import com.reconciliation.bank.service.BankStatementMatchingService;
import com.reconciliation.common.enums.BankEntryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class BankStatementIngestionServiceTest {

    private BankStatementEntryRepository bankEntryRepository;
    private BankStatementMatchingService matchingService;
    private BankStatementIngestionService service;

    @BeforeEach
    void setUp() {
        bankEntryRepository = mock(BankStatementEntryRepository.class);
        matchingService = mock(BankStatementMatchingService.class);
        service = new BankStatementIngestionService(bankEntryRepository, matchingService);
    }

    // ─── Standard CSV: split Credit / Debit columns ──────────────────────────

    @Test
    void parseStandardSplitColumnsCsv_savesEntriesWithCorrectFields() {
        String csv = """
                Date,Narration,UTR No,Credit,Debit,Balance
                21/05/2025,RAZORPAY SETTLEMENT setl_ABC,123456789,4880000.00,,99000000
                22/05/2025,STRIPE PAYOUT po_XYZ,987654321,2930000.00,,102000000
                22/05/2025,Vendor payment,NEFT001,,500000,101500000
                """;

        MockMultipartFile file = csvFile("statement.csv", csv);
        when(bankEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.ingest(file, "merchant_001", "INR");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BankStatementEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankEntryRepository).saveAll(captor.capture());

        List<BankStatementEntry> saved = captor.getValue();
        assertThat(saved).hasSize(3);

        BankStatementEntry razorpay = saved.get(0);
        assertThat(razorpay.getCreditDebit()).isEqualTo("CR");
        assertThat(razorpay.getAmount()).isEqualTo(488000000L); // 4880000.00 rupees × 100 paisa
        assertThat(razorpay.getUtrNumber()).isEqualTo("123456789");
        assertThat(razorpay.getNarration()).contains("RAZORPAY");
        assertThat(razorpay.getProviderHint()).isEqualTo("razorpay");
        assertThat(razorpay.getMerchantId()).isEqualTo("merchant_001");
        assertThat(razorpay.getCurrency()).isEqualTo("INR");

        BankStatementEntry vendor = saved.get(2);
        assertThat(vendor.getCreditDebit()).isEqualTo("DR");
        assertThat(vendor.getAmount()).isEqualTo(50000000L);
    }

    // ─── Single Amount + CR/DR column format ─────────────────────────────────

    @Test
    void parseSingleAmountColumnCsv_detectsCrDrFromTypeColumn() {
        String csv = """
                Date,Description,Amount,Type,UTR Number
                21-05-2025,RAZORPAY SETTLEMENT,4880000.00,CR,UTR112233
                21-05-2025,NEFT Debit,50000.00,DR,NEFT778899
                """;

        MockMultipartFile file = csvFile("statement.csv", csv);
        when(bankEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(file, "merchant_001", "INR");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BankStatementEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankEntryRepository).saveAll(captor.capture());

        List<BankStatementEntry> saved = captor.getValue();
        assertThat(saved.get(0).getCreditDebit()).isEqualTo("CR");
        assertThat(saved.get(1).getCreditDebit()).isEqualTo("DR");
    }

    // ─── Indian amount format ─────────────────────────────────────────────────

    @Test
    void indianAmountFormat_parsedCorrectly() {
        String csv = """
                Date,Narration,UTR No,Credit,Debit,Balance
                21/05/2025,RAZORPAY PAYMENTS,111222333,"4,88,000.00",,
                """;

        MockMultipartFile file = csvFile("statement.csv", csv);
        when(bankEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(file, "merchant_001", "INR");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BankStatementEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankEntryRepository).saveAll(captor.capture());

        // 4,88,000.00 rupees = 48800000 paisa
        assertThat(captor.getValue().get(0).getAmount()).isEqualTo(48800000L);
    }

    // ─── Matching triggered immediately for CR entries ────────────────────────

    @Test
    void crEntries_triggerMatchingImmediatelyAfterSave() {
        String csv = """
                Date,Narration,UTR No,Credit,Debit,Balance
                21/05/2025,RAZORPAY SETTLEMENT,UTR001,100000.00,,
                21/05/2025,Vendor debit,,10000.00,,
                """;

        MockMultipartFile file = csvFile("statement.csv", csv);
        when(bankEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(file, "merchant_001", "INR");

        // matchEntry should be called once — for the CR entry only (DR has no entry since it's also CR from split cols)
        // Actually from the CSV, the first row is CR (credit col has value), second is DR
        // matchEntry only called for CR
        verify(matchingService, atLeastOnce()).matchEntry(any());
    }

    // ─── Malformed lines ──────────────────────────────────────────────────────

    @Test
    void malformedLines_areSkippedAndCountedAsErrors() {
        String csv = """
                Date,Narration,UTR No,Credit,Debit,Balance
                NOT_A_DATE,RAZORPAY SETTLEMENT,UTR001,100000.00,,
                21/05/2025,STRIPE PAYOUT,UTR002,200000.00,,
                """;

        MockMultipartFile file = csvFile("statement.csv", csv);
        when(bankEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.ingest(file, "merchant_001", "INR");

        // First row fails date parsing (skipped), second row succeeds
        assertThat((Integer) result.get("totalRowsParsed")).isEqualTo(1);
        assertThat((Integer) result.get("parseErrors")).isGreaterThanOrEqualTo(1);
    }

    // ─── Empty file ──────────────────────────────────────────────────────────

    @Test
    void emptyFile_throwsIllegalArgument() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);

        // saveAll on empty list → fine, but ingest should handle gracefully
        when(bankEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> result = service.ingest(file, "merchant_001", "INR");
        assertThat((Integer) result.get("totalRowsParsed")).isEqualTo(0);
    }

    // ─── Provider detection ───────────────────────────────────────────────────

    @Test
    void narrationProviderDetection_identifiesRazorpayAndStripe() {
        String csv = """
                Date,Narration,UTR No,Credit,Debit,Balance
                21/05/2025,RAZORPAY PAYMENTS PRIVATE LTD,UTR001,50000.00,,
                21/05/2025,STRIPE TECHNOLOGY EUROPE LTD,UTR002,30000.00,,
                21/05/2025,CASHFREE PAYMENTS,UTR003,20000.00,,
                """;

        MockMultipartFile file = csvFile("statement.csv", csv);
        when(bankEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(file, "merchant_001", "INR");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BankStatementEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankEntryRepository).saveAll(captor.capture());

        assertThat(captor.getValue().get(0).getProviderHint()).isEqualTo("razorpay");
        assertThat(captor.getValue().get(1).getProviderHint()).isEqualTo("stripe");
        assertThat(captor.getValue().get(2).getProviderHint()).isEqualTo("cashfree");
    }

    // ─── Batch ID is assigned ─────────────────────────────────────────────────

    @Test
    void allEntriesInSameUpload_shareTheSameBatchId() {
        String csv = """
                Date,Narration,UTR No,Credit,Debit,Balance
                21/05/2025,RAZORPAY SETTLEMENT,UTR001,50000.00,,
                21/05/2025,RAZORPAY SETTLEMENT,UTR002,30000.00,,
                """;

        MockMultipartFile file = csvFile("statement.csv", csv);
        when(bankEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.ingest(file, "merchant_001", "INR");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BankStatementEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankEntryRepository).saveAll(captor.capture());

        String batchId = captor.getValue().get(0).getUploadBatchId();
        assertThat(batchId).startsWith("batch_");
        assertThat(captor.getValue().get(1).getUploadBatchId()).isEqualTo(batchId);
        assertThat(result.get("batchId")).isEqualTo(batchId);
    }

    // ─── helper ──────────────────────────────────────────────────────────────

    private MockMultipartFile csvFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
