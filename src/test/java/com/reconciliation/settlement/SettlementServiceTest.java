package com.reconciliation.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.bank.service.BankStatementMatchingService;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.settlement.service.SettlementService;
import com.reconciliation.transaction.repository.TransactionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final BankStatementMatchingService bankStatementMatchingService =
            mock(BankStatementMatchingService.class);
    private final SettlementService service =
            new SettlementService(settlementRepository, transactionRepository, bankStatementMatchingService);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void razorpaySettlementWebhookCreatesSettlementAndTriggersBankMatch() throws Exception {
        String payload = """
                {
                  "id": "evt_setl",
                  "event": "settlement.processed",
                  "payload": {
                    "settlement": {
                      "entity": {
                        "id": "setl_123",
                        "amount": 98000,
                        "fees": 1500,
                        "tax": 500,
                        "currency": "inr",
                        "utr": "UTR123",
                        "created_at": 1710000000,
                        "transaction_count": 2
                      }
                    }
                  }
                }
                """;

        when(settlementRepository.findByProviderAndProviderSettlementId("razorpay", "setl_123"))
                .thenReturn(Optional.empty());
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Settlement settlement = service.upsertRazorpaySettlement(
                objectMapper.readTree(payload), "merchant_live");

        assertThat(settlement.getMerchantId()).isEqualTo("merchant_live");
        assertThat(settlement.getProviderSettlementId()).isEqualTo("setl_123");
        assertThat(settlement.getNetAmount()).isEqualTo(98000L);
        assertThat(settlement.getGrossAmount()).isEqualTo(100000L);
        assertThat(settlement.getCurrency()).isEqualTo("INR");
        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(settlement.getTransactionCount()).isEqualTo(2);
        verify(bankStatementMatchingService).tryMatchBySettlement(settlement);
    }
}
