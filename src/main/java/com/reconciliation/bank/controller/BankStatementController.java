package com.reconciliation.bank.controller;

import com.reconciliation.bank.entity.BankStatementEntry;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import com.reconciliation.bank.service.BankStatementIngestionService;
import com.reconciliation.common.enums.BankEntryStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/bank-statements")
@RequiredArgsConstructor
public class BankStatementController {

    private final BankStatementIngestionService ingestionService;
    private final BankStatementEntryRepository bankEntryRepository;

    /**
     * Upload a bank statement CSV.
     * Content-Type: multipart/form-data
     * Form fields:
     *   file     — the CSV file (required)
     *   currency — 3-letter currency code, default INR (optional)
     *
     * Authentication: merchant JWT (sets merchantId request attribute).
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "currency", defaultValue = "INR") String currency,
            HttpServletRequest httpRequest) {

        String merchantId = (String) httpRequest.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Uploaded file is empty"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".csv") && !filename.endsWith(".CSV"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only CSV files are accepted"));
        }

        Map<String, Object> result = ingestionService.ingest(file, merchantId, currency);
        return ResponseEntity.ok(result);
    }

    /**
     * List bank statement entries for the authenticated merchant.
     * Optional filter: status=PENDING|MATCHED|UNMATCHED|IGNORED
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {

        String merchantId = (String) httpRequest.getAttribute("merchantId");
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }

        PageRequest pageable = PageRequest.of(page, Math.min(limit, 100),
                Sort.by(Sort.Direction.DESC, "entryDate"));

        Page<BankStatementEntry> entries;
        if (status != null) {
            try {
                BankEntryStatus entryStatus = BankEntryStatus.valueOf(status.toUpperCase());
                entries = bankEntryRepository.findByMerchantIdAndMatchStatus(merchantId, entryStatus, pageable);
            } catch (IllegalArgumentException e) {
                entries = bankEntryRepository.findByMerchantId(merchantId, pageable);
            }
        } else {
            entries = bankEntryRepository.findByMerchantId(merchantId, pageable);
        }

        long pendingCount = bankEntryRepository.countByMerchantIdAndMatchStatus(merchantId, BankEntryStatus.PENDING);
        long matchedCount = bankEntryRepository.countByMerchantIdAndMatchStatus(merchantId, BankEntryStatus.MATCHED);

        return ResponseEntity.ok(Map.of(
                "content", entries.getContent(),
                "totalElements", entries.getTotalElements(),
                "totalPages", entries.getTotalPages(),
                "page", page,
                "summary", Map.of(
                        "pending", pendingCount,
                        "matched", matchedCount
                )
        ));
    }
}
