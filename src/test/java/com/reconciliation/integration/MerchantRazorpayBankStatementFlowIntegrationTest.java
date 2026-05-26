package com.reconciliation.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.bank.repository.BankStatementEntryRepository;
import com.reconciliation.bank.repository.BankStatementUploadRepository;
import com.reconciliation.common.enums.BankEntryStatus;
import com.reconciliation.common.enums.ConnectionStatus;
import com.reconciliation.common.enums.OrderStatus;
import com.reconciliation.common.enums.ReconciliationStatus;
import com.reconciliation.common.enums.ReportLineMatchStatus;
import com.reconciliation.common.enums.SettlementStatus;
import com.reconciliation.common.util.EncryptionService;
import com.reconciliation.config.JwtConfig;
import com.reconciliation.connection.entity.ProviderConnection;
import com.reconciliation.connection.repository.ProviderConnectionRepository;
import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.merchant.repository.MerchantRepository;
import com.reconciliation.order.repository.OrderRepository;
import com.reconciliation.settlement.entity.Settlement;
import com.reconciliation.settlement.entity.SettlementReportLine;
import com.reconciliation.settlement.repository.SettlementReportLineRepository;
import com.reconciliation.settlement.repository.SettlementRepository;
import com.reconciliation.transaction.entity.Transaction;
import com.reconciliation.transaction.repository.TransactionRepository;
import com.reconciliation.webhook_event.repository.WebhookEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_DB_INTEGRATION_TESTS", matches = "true")
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "app.jwt.secret=test_jwt_secret_that_is_long_enough_for_hs256_12345",
        "app.encryption.key=MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=",
        "app.razorpay.webhook-secret=default_test_webhook_secret",
        "app.merchant.id=default_test_merchant"
})
class MerchantRazorpayBankStatementFlowIntegrationTest {

    private static final String MERCHANT_ID = "merchant_e2e_razorpay";
    private static final String WEBHOOK_SECRET = "merchant_specific_webhook_secret";
    private static final String PROVIDER_ORDER_ID = "order_RZP_E2E_DB";
    private static final String PROVIDER_PAYMENT_ID = "pay_RZP_E2E_DB";
    private static final String PROVIDER_EVENT_ID = "evt_RZP_E2E_DB";
    private static final String PROVIDER_SETTLEMENT_ID = "setl_RZP_E2E_DB";
    private static final String UTR = "UTRRAZORPAYE2E";
    private static final long GROSS_AMOUNT = 10_000L;
    private static final long FEE_AMOUNT = 236L;
    private static final long TAX_AMOUNT = 36L;
    private static final long NET_AMOUNT = GROSS_AMOUNT - FEE_AMOUNT - TAX_AMOUNT;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtConfig jwtConfig;
    @Autowired private BCryptPasswordEncoder passwordEncoder;
    @Autowired private EncryptionService encryptionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ProviderConnectionRepository connectionRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementReportLineRepository reportLineRepository;
    @Autowired private BankStatementUploadRepository uploadRepository;
    @Autowired private BankStatementEntryRepository bankEntryRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private OrderRepository orderRepository;

    private String bearerToken;
    private Long settlementId;

    @BeforeEach
    void setUp() {
        cleanMerchantData();
        merchantRepository.save(Merchant.builder()
                .merchantId(MERCHANT_ID)
                .name("E2E Merchant")
                .email("e2e-razorpay@example.com")
                .apiKeyHash(passwordEncoder.encode("merchant_api_key"))
                .webhookSecret(WEBHOOK_SECRET)
                .status("ACTIVE")
                .build());
        bearerToken = "Bearer " + jwtConfig.generateMerchantToken(MERCHANT_ID);

        Settlement settlement = settlementRepository.save(Settlement.builder()
                .provider("razorpay")
                .providerSettlementId(PROVIDER_SETTLEMENT_ID)
                .merchantId(MERCHANT_ID)
                .grossAmount(GROSS_AMOUNT)
                .totalFees(FEE_AMOUNT)
                .totalTax(TAX_AMOUNT)
                .netAmount(NET_AMOUNT)
                .currency("INR")
                .bankCreditDate(LocalDate.now())
                .utrNumber(UTR)
                .settlementStatus(SettlementStatus.SETTLED)
                .transactionCount(1)
                .settledAt(OffsetDateTime.now())
                .build());
        settlementId = settlement.getId();

        reportLineRepository.save(SettlementReportLine.builder()
                .settlementId(settlementId)
                .provider("razorpay")
                .providerTxnId(PROVIDER_PAYMENT_ID)
                .entityType("payment")
                .grossAmount(GROSS_AMOUNT)
                .feeAmount(FEE_AMOUNT + TAX_AMOUNT)
                .netAmount(NET_AMOUNT)
                .currency("INR")
                .matchStatus(ReportLineMatchStatus.PENDING)
                .build());
    }

    @AfterEach
    void tearDown() {
        cleanMerchantData();
    }

    @Test
    void merchantRazorpayWebhookConnectionOrderBankStatementAndManualReconcileReachExpectedDbState()
            throws Exception {
        postConnectionAndAssertStoredEncrypted();
        patchAndReadMerchantProfile();
        registerOrder();
        uploadBankStatementAndAssertSettlementMatched();
        postSignedRazorpayWebhookAndAssertTransactionMatched();
        reconcileSelectedTransactionAndAssertReportLineMatched();
        assertDashboardEndpointsSeeTheFlow();
    }

    private void postConnectionAndAssertStoredEncrypted() throws Exception {
        mockMvc.perform(post("/api/v1/connections")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "razorpay",
                                  "apiKey": "rzp_live_e2e_key",
                                  "secret": "rzp_live_e2e_secret"
                                }
                                """))
                .andExpect(status().isOk());

        ProviderConnection connection = connectionRepository
                .findByMerchantIdAndProvider(MERCHANT_ID, "razorpay")
                .orElseThrow();
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(connection.getApiKeyMasked()).isEqualTo("rzp_****_key");
        assertThat(connection.getApiKeyEncrypted()).isNotEqualTo("rzp_live_e2e_key");
        assertThat(encryptionService.decrypt(connection.getApiKeyEncrypted()))
                .isEqualTo("rzp_live_e2e_key");
        assertThat(encryptionService.decrypt(connection.getSecretEncrypted()))
                .isEqualTo("rzp_live_e2e_secret");

        String listResponse = mockMvc.perform(get("/api/v1/connections")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listResponse).contains("razorpay", "rzp_****_key", "ACTIVE");
    }

    private void patchAndReadMerchantProfile() throws Exception {
        mockMvc.perform(patch("/api/v1/me")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "E2E Merchant Updated",
                                  "email": "e2e-razorpay-updated@example.com"
                                }
                                """))
                .andExpect(status().isOk());

        String response = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode profile = objectMapper.readTree(response);
        assertThat(profile.path("name").asText()).isEqualTo("E2E Merchant Updated");
        assertThat(profile.path("email").asText()).isEqualTo("e2e-razorpay-updated@example.com");
        assertThat(profile.path("role").asText()).isEqualTo("Admin");
    }

    private void registerOrder() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "merchant-order-e2e-db",
                                  "providerOrderId": "%s",
                                  "expectedAmount": %d,
                                  "currency": "INR",
                                  "metadata": {"source": "integration-test"}
                                }
                                """.formatted(PROVIDER_ORDER_ID, GROSS_AMOUNT)))
                .andExpect(status().isOk());

        assertThat(orderRepository.findByMerchantIdAndProviderOrderId(MERCHANT_ID, PROVIDER_ORDER_ID))
                .isPresent()
                .get()
                .extracting(order -> order.getOrderStatus())
                .isEqualTo(OrderStatus.CREATED);
    }

    private void uploadBankStatementAndAssertSettlementMatched() throws Exception {
        String csv = """
                date,description,credit,debit,utr
                %s,RAZORPAY SETTLEMENT %s,97.28,,%s
                """.formatted(LocalDate.now(), PROVIDER_SETTLEMENT_ID, UTR);

        MockMultipartFile statement = new MockMultipartFile(
                "statement",
                "razorpay-e2e-bank-statement.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        String response = mockMvc.perform(multipart("/api/v1/reconciliation/bank-statements/upload")
                        .file(statement)
                        .param("source", "bank_statement")
                        .param("provider", "bank")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode upload = objectMapper.readTree(response);
        assertThat(upload.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(upload.path("rowsParsed").asInt()).isEqualTo(1);
        assertThat(upload.path("matchedRows").asInt()).isEqualTo(1);

        String uploadId = upload.path("uploadId").asText();
        assertThat(uploadRepository.findByMerchantIdAndUploadId(MERCHANT_ID, uploadId)).isPresent();
        assertThat(bankEntryRepository.findByMerchantIdAndUploadBatchIdAndMatchStatus(
                MERCHANT_ID, uploadId, BankEntryStatus.MATCHED)).hasSize(1);

        String statusResponse = mockMvc.perform(get("/api/v1/reconciliation/bank-statements/{uploadId}", uploadId)
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(statusResponse).contains(uploadId, "COMPLETED", "razorpay-e2e-bank-statement.csv");

        String listResponse = mockMvc.perform(get("/api/v1/reconciliation/bank-statements?limit=10")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listResponse).contains(uploadId);

        String reconcileResponse = mockMvc.perform(post(
                        "/api/v1/reconciliation/bank-statements/{uploadId}/reconcile", uploadId)
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(reconcileResponse).contains("recon_job_", uploadId, "STARTED");

        Settlement settlement = settlementRepository.findById(settlementId).orElseThrow();
        assertThat(settlement.getSettlementStatus()).isEqualTo(SettlementStatus.MATCHED_TO_BANK);
        assertThat(settlement.getBankCreditAmount()).isEqualTo(NET_AMOUNT);
    }

    private void postSignedRazorpayWebhookAndAssertTransactionMatched() throws Exception {
        byte[] body = razorpayPaymentCapturedPayload().getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", hmacSha256(body, WEBHOOK_SECRET))
                        .content(body))
                .andExpect(status().isOk());

        Transaction transaction = transactionRepository
                .findByProviderAndProviderTransactionId("razorpay", PROVIDER_PAYMENT_ID)
                .orElseThrow();
        assertThat(transaction.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(transaction.getProviderOrderId()).isEqualTo(PROVIDER_ORDER_ID);
        assertThat(transaction.getPresentmentAmount()).isEqualTo(GROSS_AMOUNT);
        assertThat(transaction.getNetAmount()).isEqualTo(NET_AMOUNT);
        assertThat(transaction.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);

        assertThat(webhookEventRepository.findByProviderAndProviderEventId("razorpay", PROVIDER_EVENT_ID))
                .isPresent()
                .get()
                .satisfies(event -> {
                    assertThat(event.getMerchantId()).isEqualTo(MERCHANT_ID);
                    assertThat(event.isProcessed()).isTrue();
                    assertThat(event.getProcessingError()).isNull();
                });

        assertThat(orderRepository.findByMerchantIdAndProviderOrderId(MERCHANT_ID, PROVIDER_ORDER_ID))
                .isPresent()
                .get()
                .satisfies(order -> {
                    assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_RECEIVED);
                    assertThat(order.getAmountMatched()).isTrue();
                    assertThat(order.getTransactionId()).isEqualTo(transaction.getId());
                });
    }

    private void reconcileSelectedTransactionAndAssertReportLineMatched() throws Exception {
        Long transactionId = transactionRepository
                .findByProviderAndProviderTransactionId("razorpay", PROVIDER_PAYMENT_ID)
                .orElseThrow()
                .getId();

        String response = mockMvc.perform(post("/api/v1/admin/reconcile-transactions")
                        .header("X-Actor", "frontend-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transactionIds": [%d],
                                  "mode": "manual"
                                }
                                """.formatted(transactionId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode result = objectMapper.readTree(response);
        assertThat(result.path("requested").asInt()).isEqualTo(1);
        assertThat(result.path("reconciled").asInt()).isEqualTo(1);
        assertThat(result.path("failed").asInt()).isZero();

        SettlementReportLine line = reportLineRepository
                .findFirstByProviderAndProviderTxnId("razorpay", PROVIDER_PAYMENT_ID)
                .orElseThrow();
        assertThat(line.getMatchStatus()).isEqualTo(ReportLineMatchStatus.MATCHED);
        assertThat(line.getMatchedToTxnId()).isEqualTo(transactionId);
    }

    private void assertDashboardEndpointsSeeTheFlow() throws Exception {
        String activityResponse = mockMvc.perform(get("/api/v1/dashboard/activity?limit=8")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(activityResponse).contains(PROVIDER_PAYMENT_ID);

        String trendsResponse = mockMvc.perform(get("/api/v1/dashboard/trends?days=7")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(trendsResponse).contains("\"transactions\"");

        String summaryResponse = mockMvc.perform(get("/api/v1/dashboard/summary?days=7")
                        .header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(summaryResponse).contains("\"totalTransactions\":1", "\"matched\":1");
    }

    private String razorpayPaymentCapturedPayload() {
        return """
                {
                  "entity": "event",
                  "id": "%s",
                  "event": "payment.captured",
                  "contains": ["payment"],
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "entity": "payment",
                        "amount": %d,
                        "currency": "INR",
                        "status": "captured",
                        "order_id": "%s",
                        "method": "upi",
                        "amount_refunded": 0,
                        "captured": true,
                        "description": "E2E Razorpay capture",
                        "vpa": "customer@upi",
                        "email": "payer-e2e@example.com",
                        "contact": "9999999999",
                        "notes": {"merchant_order_ref": "merchant-order-e2e-db"},
                        "fee": %d,
                        "tax": %d,
                        "created_at": 1710000300
                      }
                    }
                  },
                  "created_at": 1710000300
                }
                """.formatted(PROVIDER_EVENT_ID, PROVIDER_PAYMENT_ID, GROSS_AMOUNT,
                PROVIDER_ORDER_ID, FEE_AMOUNT, TAX_AMOUNT);
    }

    private String hmacSha256(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private void cleanMerchantData() {
        Map<String, Object> params = Map.of(
                "merchantId", MERCHANT_ID,
                "providerPaymentId", PROVIDER_PAYMENT_ID,
                "providerEventId", PROVIDER_EVENT_ID,
                "providerSettlementId", PROVIDER_SETTLEMENT_ID,
                "email", "e2e-razorpay@example.com",
                "updatedEmail", "e2e-razorpay-updated@example.com");

        jdbcTemplate.update("""
                DELETE FROM payment_flow_events
                WHERE provider_transaction_id = ? OR provider_event_id = ?
                """, PROVIDER_PAYMENT_ID, PROVIDER_EVENT_ID);
        jdbcTemplate.update("""
                DELETE FROM settlement_report_lines
                WHERE provider_txn_id = ?
                   OR settlement_id IN (
                       SELECT id FROM settlements
                       WHERE merchant_id = ? OR provider_settlement_id = ?
                   )
                """, PROVIDER_PAYMENT_ID, MERCHANT_ID, PROVIDER_SETTLEMENT_ID);
        jdbcTemplate.update("DELETE FROM bank_statement_entries WHERE merchant_id = ?", MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM bank_statement_uploads WHERE merchant_id = ?", MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM exception_records WHERE merchant_id = ?", MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM transactions WHERE merchant_id = ? OR provider_transaction_id = ?",
                MERCHANT_ID, PROVIDER_PAYMENT_ID);
        jdbcTemplate.update("DELETE FROM webhook_events WHERE merchant_id = ? OR provider_event_id = ?",
                MERCHANT_ID, PROVIDER_EVENT_ID);
        jdbcTemplate.update("DELETE FROM orders WHERE merchant_id = ?", MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM settlements WHERE merchant_id = ? OR provider_settlement_id = ?",
                MERCHANT_ID, PROVIDER_SETTLEMENT_ID);
        jdbcTemplate.update("DELETE FROM provider_connections WHERE merchant_id = ?", MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM users WHERE merchant_id = ?", MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM merchants WHERE merchant_id = ? OR email IN (?, ?)",
                MERCHANT_ID, params.get("email"), params.get("updatedEmail"));
    }

    @TestConfiguration
    static class SyncAsyncConfig {
        @Bean(name = "webhookProcessingExecutor")
        ConcurrentTaskExecutor webhookProcessingExecutor() {
            return new ConcurrentTaskExecutor(Runnable::run);
        }
    }
}
