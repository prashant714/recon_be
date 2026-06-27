package com.reconciliation.webhook;

import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.webhook.controller.RazorpayWebhookController;
import com.reconciliation.webhook.controller.StripeWebhookController;
import com.reconciliation.config.JwtConfig;
import com.reconciliation.merchant.repository.MerchantRepository;
import com.reconciliation.webhook.service.RazorpaySignatureService;
import com.reconciliation.webhook.service.StripeSignatureService;
import com.reconciliation.webhook.service.WebhookIngestionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {RazorpayWebhookController.class, StripeWebhookController.class})
@Import({WebhookControllerTest.TestConfig.class, RazorpaySignatureService.class, StripeSignatureService.class})
@TestPropertySource(properties = {
        "app.stripe.webhook-secret=whsec_test_secret"
})
class WebhookControllerTest {

    private static final Path FIXTURES = Path.of("src/test/resources/webhook-fixtures");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WebhookIngestionService webhookIngestionService;

    @MockBean
    private JwtConfig jwtConfig;

    @MockBean
    private MerchantRepository merchantRepository;

    @BeforeEach
    void setUp() {
        when(merchantRepository.findByStatus("ACTIVE")).thenReturn(List.of(
                Merchant.builder()
                        .merchantId("merchant_001")
                        .webhookSecret("razorpay_webhook_secret")
                        .status("ACTIVE")
                        .build()));
    }

    @Test
    void acceptsRazorpayFixtureWithValidSignature() throws Exception {
        byte[] body = fixtureBytes("razorpay/payment.captured.json");

        mockMvc.perform(post("/webhooks/razorpay")
                        .contentType(APPLICATION_JSON)
                        .header("X-Razorpay-Signature", razorpaySignature(body, "razorpay_webhook_secret"))
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("received"));

        verify(webhookIngestionService).ingestAsync(
                any(byte[].class), eq("razorpay"), eq("webhook"), eq("merchant_001"));
    }

    @Test
    void rejectsRazorpayFixtureWithBadSignature() throws Exception {
        byte[] body = fixtureBytes("razorpay/payment.captured.json");

        mockMvc.perform(post("/webhooks/razorpay")
                        .contentType(APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "bad-signature")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid signature"));

        verify(webhookIngestionService, never()).ingestAsync(any(byte[].class), any(), any(), any());
    }

    @Test
    void acceptsStripeFixtureWithValidSignature() throws Exception {
        byte[] body = fixtureBytes("stripe/payment_intent.succeeded.json");

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", stripeSignature(body, "whsec_test_secret"))
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("received"));

        verify(webhookIngestionService).ingestAsync(any(byte[].class), eq("stripe"), eq("webhook"));
    }

    @Test
    void rejectsStripeFixtureWithBadSignature() throws Exception {
        byte[] body = fixtureBytes("stripe/payment_intent.succeeded.json");

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=bad")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid signature"));

        verify(webhookIngestionService, never()).ingestAsync(any(byte[].class), any(), any());
    }

    private static byte[] fixtureBytes(String relativePath) throws IOException {
        return Files.readAllBytes(FIXTURES.resolve(relativePath));
    }

    private static String razorpaySignature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static String stripeSignature(byte[] body, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + new String(body, StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(
                mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + signature;
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        org.springframework.security.web.SecurityFilterChain testFilterChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }
}
