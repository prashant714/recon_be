package com.reconciliation.common.enums;

public enum BankEntryStatus {
    PENDING,    // uploaded, not yet matched
    MATCHED,    // successfully matched to a settlement
    UNMATCHED,  // grace period expired, no match found — exception created
    IGNORED     // DR entry or non-payment-gateway credit — skipped intentionally
}
