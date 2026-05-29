package com.reconciliation.bank.service;

import com.reconciliation.bank.entity.BankStatementEntry;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class BankStatementIngestionService {

    private final BankStatementEntryRepository bankEntryRepository;
    private final BankStatementMatchingService matchingService;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
    );

    /**
     * Parse a bank statement CSV, persist entries, immediately attempt matching
     * for each CR entry against existing settlements.
     *
     * @param file       uploaded CSV from merchant's net banking portal
     * @param merchantId authenticated merchant
     * @param currency   declared currency (default INR)
     * @return summary: totalRows, saved, matched, ignored, errors
     */
    @Transactional
    public Map<String, Object> ingest(MultipartFile file, String merchantId, String currency) {
        String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return ingest(file, merchantId, currency, batchId);
    }

    @Transactional
    public Map<String, Object> ingest(MultipartFile file, String merchantId, String currency, String batchId) {
        String resolvedCurrency = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "INR";

        List<BankStatementEntry> parsed = new ArrayList<>();
        int errorCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = null;
            String line;
            int lineNum = 0;
            int consecutiveFailures = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (headerLine == null) {
                    if (isHeaderLine(trimmed)) {
                        headerLine = trimmed.toLowerCase();
                    }
                    continue;
                }

                try {
                    BankStatementEntry entry = parseLine(
                            trimmed, headerLine, merchantId, batchId, resolvedCurrency);
                    if (entry != null) {
                        parsed.add(entry);
                        consecutiveFailures = 0;
                    }
                } catch (Exception e) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 3 && !parsed.isEmpty()) {
                        log.debug("Stopping parse at line {} — likely footer section", lineNum);
                        break;
                    }
                    log.warn("Skipping malformed line {} in batch {}: {}", lineNum, batchId, e.getMessage());
                    errorCount++;
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read bank statement file: " + e.getMessage(), e);
        }

        List<BankStatementEntry> saved = bankEntryRepository.saveAll(parsed);

        // Immediately try matching every CR entry against existing settlements
        int matched = 0;
        for (BankStatementEntry entry : saved) {
            if ("CR".equalsIgnoreCase(entry.getCreditDebit())) {
                matchingService.matchEntry(entry);
                if (entry.getMatchStatus() == com.reconciliation.common.enums.BankEntryStatus.MATCHED) {
                    matched++;
                }
            }
        }

        log.info("Bank statement ingest complete: batchId={} merchant={} saved={} matched={} errors={}",
                batchId, merchantId, saved.size(), matched, errorCount);

        return Map.of(
                "batchId", batchId,
                "totalRowsParsed", saved.size(),
                "matched", matched,
                "pending", saved.size() - matched,
                "parseErrors", errorCount
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV parsing — handles multiple common bank statement column layouts
    // ─────────────────────────────────────────────────────────────────────────

    private BankStatementEntry parseLine(
            String line, String headerLine,
            String merchantId, String batchId, String currency) {

        String[] cols = splitCsv(line);
        String[] headers = splitCsv(headerLine);

        Map<String, Integer> idx = buildIndex(headers);

        LocalDate date = extractDate(cols, idx, "date", "value date", "txn date", "transaction date",
                "entrydate", "entry date", "posting date", "trans date", "tran date");
        if (date == null) {
            throw new IllegalArgumentException("missing or invalid transaction date");
        }

        // Try credit/debit columns first (some banks split into separate columns)
        Long amount;
        String creditDebit;

        String creditKey = findKey(idx, "credit", "deposit", "deposit amt", "credit amt");
        String debitKey = findKey(idx, "debit", "withdrawal", "withdrawal amt", "debit amt");

        if (creditKey != null || debitKey != null) {
            String creditVal = creditKey != null ? safeGet(cols, idx.get(creditKey)) : "";
            String debitVal = debitKey != null ? safeGet(cols, idx.get(debitKey)) : "";

            if (!creditVal.isBlank() && parseAmount(creditVal) > 0) {
                amount = parseAmount(creditVal);
                creditDebit = "CR";
            } else if (!debitVal.isBlank() && parseAmount(debitVal) > 0) {
                amount = parseAmount(debitVal);
                creditDebit = "DR";
            } else {
                return null;
            }
        } else {
            // Single amount column + cr/dr column
            amount = parseAmountFromIndex(cols, idx, "amount", "txn amount");
            if (amount == null || amount == 0) return null;

            String crDrRaw = extractString(cols, idx, "cr/dr", "type", "txn type", "debit/credit",
                    "creditdebit", "credit/debit");
            creditDebit = normalizeCrDr(crDrRaw, amount);
            if (creditDebit == null) return null;
            amount = Math.abs(amount);
        }

        String narration = extractString(cols, idx,
                "narration", "description", "particulars", "remarks", "details");
        String utr = extractString(cols, idx,
                "utr", "utr no", "utr number", "utrnumber", "utr/cheque no",
                "reference no", "reference number", "ref no/cheque no", "chq no", "chq/ref no");
        if (utr == null && narration != null) {
            utr = extractUtrFromNarration(narration);
        }
        String bankRef = extractString(cols, idx,
                "bank reference", "bankreference", "bank ref", "reference");

        return BankStatementEntry.builder()
                .merchantId(merchantId)
                .uploadBatchId(batchId)
                .entryDate(date)
                .amount(amount)
                .currency(currency)
                .creditDebit(creditDebit.toUpperCase())
                .utrNumber(normalizeUtr(utr))
                .bankReference(bankRef)
                .narration(narration)
                .providerHint(detectProvider(narration))
                .build();
    }

    private boolean isHeaderLine(String line) {
        String lower = line.toLowerCase();
        String[] fields = splitCsv(lower);
        boolean hasDate = false;
        boolean hasAmount = false;
        for (String field : fields) {
            String f = field.trim();
            if (f.contains("date")) hasDate = true;
            if (f.equals("debit") || f.equals("credit") || f.equals("amount")
                    || f.equals("deposit") || f.equals("withdrawal")
                    || f.equals("deposit amt") || f.equals("withdrawal amt")
                    || f.equals("txn amount") || f.equals("creditdebit")) {
                hasAmount = true;
            }
        }
        return hasDate && hasAmount;
    }

    private String findKey(Map<String, Integer> idx, String... candidates) {
        for (String key : candidates) {
            if (idx.containsKey(key)) return key;
        }
        return null;
    }

    private Map<String, Integer> buildIndex(String[] headers) {
        Map<String, Integer> idx = new java.util.LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            idx.put(headers[i].trim().toLowerCase(), i);
        }
        return idx;
    }

    private LocalDate extractDate(String[] cols, Map<String, Integer> idx, String... keys) {
        for (String key : keys) {
            if (idx.containsKey(key)) {
                String val = safeGet(cols, idx.get(key));
                LocalDate d = parseDate(val);
                if (d != null) return d;
            }
        }
        return null;
    }

    private String extractString(String[] cols, Map<String, Integer> idx, String... keys) {
        for (String key : keys) {
            if (idx.containsKey(key)) {
                String val = safeGet(cols, idx.get(key));
                if (!val.isBlank()) return val.trim();
            }
        }
        return null;
    }

    private Long parseAmountFromIndex(String[] cols, Map<String, Integer> idx, String... keys) {
        for (String key : keys) {
            if (idx.containsKey(key)) {
                String val = safeGet(cols, idx.get(key));
                if (!val.isBlank()) {
                    try { return parseAmount(val); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * Parse a bank amount string into paisa (multiply by 100).
     * Handles: "4,88,000.00", "488000", "-500.00", "₹500"
     */
    private long parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        String cleaned = raw.trim()
                .replace(",", "")
                .replace("₹", "")
                .replace("$", "")
                .replace(" ", "");
        boolean negative = cleaned.startsWith("-");
        cleaned = cleaned.replace("-", "").replace("+", "");
        if (cleaned.isEmpty()) return 0L;

        double value = Double.parseDouble(cleaned);
        long paisa = Math.round(value * 100);
        return negative ? -paisa : paisa;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(cleaned, fmt); }
            catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private String normalizeCrDr(String raw, long amount) {
        if (raw == null) return amount >= 0 ? "CR" : "DR";
        String upper = raw.trim().toUpperCase();
        if (upper.startsWith("CR") || upper.equals("CREDIT") || upper.equals("C")) return "CR";
        if (upper.startsWith("DR") || upper.equals("DEBIT") || upper.equals("D")) return "DR";
        return null;
    }

    private static final Pattern UTR_PATTERN = Pattern.compile(
            "(?:UTR[:\\s/]?|NEFT[/\\s])([A-Z0-9]{12,22})", Pattern.CASE_INSENSITIVE);

    private String extractUtrFromNarration(String narration) {
        Matcher m = UTR_PATTERN.matcher(narration);
        return m.find() ? m.group(1) : null;
    }

    private String normalizeUtr(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().replaceAll("[^a-zA-Z0-9]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String detectProvider(String narration) {
        if (narration == null) return null;
        String upper = narration.toUpperCase();
        if (upper.contains("RAZORPAY")) return "razorpay";
        if (upper.contains("STRIPE")) return "stripe";
        if (upper.contains("CASHFREE")) return "cashfree";
        if (upper.contains("PAYU")) return "payu";
        return null;
    }

    private String safeGet(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length) return "";
        return cols[idx] == null ? "" : cols[idx].trim().replace("\"", "");
    }

    /** Split CSV/TSV line — handles quoted fields and auto-detects tab vs comma delimiter. */
    private String[] splitCsv(String line) {
        char delimiter = line.contains("\t") ? '\t' : ',';
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result.toArray(new String[0]);
    }
}
