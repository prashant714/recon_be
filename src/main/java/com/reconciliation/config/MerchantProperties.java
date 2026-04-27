package com.reconciliation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.merchant")
public record MerchantProperties(String id) {
}
