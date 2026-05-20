package com.reconciliation.transaction.service;

import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.transaction.entity.Transaction;

public record TransactionUpsertResult(
        Transaction transaction,
        Action action,
        TransactionStatus previousStatus) {

    public enum Action {
        CREATED,
        UPDATED,
        IGNORED
    }
}
