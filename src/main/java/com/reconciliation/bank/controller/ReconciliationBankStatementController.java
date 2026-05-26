package com.reconciliation.bank.controller;

import com.reconciliation.bank.service.BankStatementUploadService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/reconciliation/bank-statements")
@RequiredArgsConstructor
public class ReconciliationBankStatementController {

    private final BankStatementUploadService uploadService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("statement") MultipartFile statement,
            @RequestParam(value = "source", defaultValue = "bank_statement") String source,
            @RequestParam(value = "provider", defaultValue = "bank") String provider,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        return ResponseEntity.ok(uploadService.upload(statement, merchantId, source, provider));
    }

    @GetMapping("/{uploadId}")
    public ResponseEntity<?> status(@PathVariable String uploadId, HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        return ResponseEntity.ok(uploadService.status(merchantId, uploadId));
    }

    @GetMapping
    public ResponseEntity<?> recent(
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        return ResponseEntity.ok(uploadService.recent(merchantId, limit));
    }

    @PostMapping("/{uploadId}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable String uploadId, HttpServletRequest request) {
        String merchantId = merchantId(request);
        if (merchantId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Merchant authentication required"));
        }
        return ResponseEntity.ok(uploadService.reconcile(merchantId, uploadId));
    }

    private String merchantId(HttpServletRequest request) {
        return (String) request.getAttribute("merchantId");
    }
}
