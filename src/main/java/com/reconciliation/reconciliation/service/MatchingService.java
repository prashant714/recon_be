package com.reconciliation.reconciliation.service;

import com.reconciliation.transaction.entity.Transaction;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MatchingService {

    public List<Transaction> selectCandidates(List<Transaction> transactions) {
        return transactions;
    }
}
