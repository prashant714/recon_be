package com.reconciliation.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RazorpayConfig {

    @Bean
    public RazorpayClient razorpayClient(
            @Value("${app.razorpay.key-id}") String keyId,
            @Value("${app.razorpay.key-secret}") String keySecret) throws RazorpayException {
        if (keyId.contains("placeholder") || keySecret.contains("placeholder")) {
            log.warn("Razorpay credentials are placeholders — global client will not work. "
                    + "Per-merchant credentials via ProviderConnection are used for polling.");
        }
        return new RazorpayClient(keyId, keySecret);
    }
}
