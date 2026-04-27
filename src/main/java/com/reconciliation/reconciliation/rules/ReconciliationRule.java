package com.reconciliation.reconciliation.rules;

import com.reconciliation.transaction.entity.Transaction;
import java.util.List;

public interface ReconciliationRule {

    String getName();

    void evaluate();
}
