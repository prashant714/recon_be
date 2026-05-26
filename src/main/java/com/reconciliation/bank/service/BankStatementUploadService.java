package com.reconciliation.bank.service;

import com.reconciliation.bank.entity.BankStatementUpload;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import com.reconciliation.bank.repository.BankStatementUploadRepository;
import com.reconciliation.common.enums.BankEntryStatus;
import com.reconciliation.common.enums.BankStatementUploadStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class BankStatementUploadService {

    private final BankStatementUploadRepository uploadRepository;
    private final BankStatementEntryRepository entryRepository;
    private final BankStatementIngestionService ingestionService;
    private final BankStatementMatchingService matchingService;

    public Map<String, Object> upload(MultipartFile file, String merchantId, String source, String provider) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException("Only CSV files are accepted");
        }

        String uploadId = "bsu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BankStatementUpload upload = uploadRepository.save(BankStatementUpload.builder()
                .uploadId(uploadId)
                .merchantId(merchantId)
                .fileName(fileName)
                .status(BankStatementUploadStatus.ACCEPTED)
                .progress(0)
                .message("Bank statement accepted for reconciliation.")
                .build());

        try {
            upload.setStatus(BankStatementUploadStatus.PROCESSING);
            upload.setProgress(25);
            uploadRepository.save(upload);

            Map<String, Object> ingestResult = ingestionService.ingest(file, merchantId, "INR", uploadId);
            int rowsParsed = toInt(ingestResult.get("totalRowsParsed"));
            int matchedRows = toInt(ingestResult.get("matched"));
            int parseErrors = toInt(ingestResult.get("parseErrors"));

            upload.setRowsParsed(rowsParsed);
            upload.setMatchedRows(matchedRows);
            upload.setExceptionRows(parseErrors);
            upload.setStatus(BankStatementUploadStatus.COMPLETED);
            upload.setProgress(100);
            upload.setMessage("Bank statement accepted for reconciliation.");
            upload = uploadRepository.save(upload);

            Map<String, Object> response = uploadResponse(upload);
            response.put("status", BankStatementUploadStatus.ACCEPTED.name());
            return response;
        } catch (RuntimeException ex) {
            upload.setStatus(BankStatementUploadStatus.FAILED);
            upload.setProgress(100);
            upload.setMessage(ex.getMessage());
            uploadRepository.save(upload);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String merchantId, String uploadId) {
        BankStatementUpload upload = getUpload(merchantId, uploadId);
        return uploadResponse(upload);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recent(String merchantId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        return Map.of("items", uploadRepository.findByMerchantId(
                        merchantId,
                        PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "uploadedAt")))
                .getContent()
                .stream()
                .map(this::uploadResponse)
                .toList());
    }

    @Transactional
    public Map<String, Object> reconcile(String merchantId, String uploadId) {
        BankStatementUpload upload = getUpload(merchantId, uploadId);
        upload.setStatus(BankStatementUploadStatus.PROCESSING);
        upload.setProgress(50);
        uploadRepository.save(upload);

        matchingService.rematchPending(merchantId, uploadId);
        refreshCounts(upload);
        upload.setStatus(BankStatementUploadStatus.COMPLETED);
        upload.setProgress(100);
        uploadRepository.save(upload);

        return Map.of(
                "jobId", "recon_job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "uploadId", uploadId,
                "status", "STARTED",
                "message", "Bank statement reconciliation started.");
    }

    private BankStatementUpload getUpload(String merchantId, String uploadId) {
        return uploadRepository.findByMerchantIdAndUploadId(merchantId, uploadId)
                .orElseThrow(() -> new IllegalArgumentException("Upload not found: " + uploadId));
    }

    private void refreshCounts(BankStatementUpload upload) {
        String merchantId = upload.getMerchantId();
        String uploadId = upload.getUploadId();
        upload.setRowsParsed((int) entryRepository.countByMerchantIdAndUploadBatchId(merchantId, uploadId));
        upload.setMatchedRows((int) entryRepository.countByMerchantIdAndUploadBatchIdAndMatchStatus(
                merchantId, uploadId, BankEntryStatus.MATCHED));
        upload.setExceptionRows((int) entryRepository.countByMerchantIdAndUploadBatchIdAndMatchStatus(
                merchantId, uploadId, BankEntryStatus.UNMATCHED));
    }

    private Map<String, Object> uploadResponse(BankStatementUpload upload) {
        return new java.util.LinkedHashMap<>(Map.of(
                "uploadId", upload.getUploadId(),
                "fileName", upload.getFileName(),
                "status", upload.getStatus().name(),
                "progress", upload.getProgress(),
                "rowsParsed", upload.getRowsParsed(),
                "matchedRows", upload.getMatchedRows(),
                "exceptionRows", upload.getExceptionRows(),
                "uploadedAt", upload.getUploadedAt(),
                "message", upload.getMessage() == null ? "" : upload.getMessage()));
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
