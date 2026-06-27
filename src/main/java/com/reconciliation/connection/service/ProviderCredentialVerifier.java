package com.reconciliation.connection.service;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.reconciliation.common.exception.InvalidProviderCredentialsException;
import com.stripe.exception.AuthenticationException;
import com.stripe.model.Account;
import com.stripe.net.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderCredentialVerifier {

    /** Payment gateway verification only. OMS providers verify via OmsConnector.testConnection(). */
    public void verify(String provider, String apiKey, String secret) {
        switch (provider) {
            case "razorpay" -> verifyRazorpay(apiKey, secret);
            case "stripe"   -> verifyStripe(apiKey);
            default -> throw new IllegalArgumentException("Unsupported payment provider: " + provider);
        }
    }

    private void verifyRazorpay(String apiKey, String secret) {
        try {
            RazorpayClient client = new RazorpayClient(apiKey, secret);
            org.json.JSONObject options = new org.json.JSONObject();
            options.put("count", 1);
            client.payments.fetchAll(options);
        } catch (RazorpayException e) {
            log.warn("Razorpay credential verification failed: {}", e.getMessage());
            throw new InvalidProviderCredentialsException("razorpay");
        }
    }

    private void verifyStripe(String apiKey) {
        try {
            RequestOptions options = RequestOptions.builder().setApiKey(apiKey).build();
            Account.retrieve(options);
        } catch (AuthenticationException e) {
            log.warn("Stripe credential verification failed: {}", e.getMessage());
            throw new InvalidProviderCredentialsException("stripe");
        } catch (Exception e) {
            log.warn("Stripe credential verification failed: {}", e.getMessage());
            throw new InvalidProviderCredentialsException("stripe");
        }
    }
}
