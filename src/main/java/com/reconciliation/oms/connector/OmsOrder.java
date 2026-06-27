package com.reconciliation.oms.connector;

import java.time.OffsetDateTime;
import java.util.Map;

public record OmsOrder(
    String orderId,
    String providerOrderId,
    long expectedAmount,
    String currency,
    String omsStatus,
    OffsetDateTime orderDate,
    Map<String, Object> rawPayload,
    Map<String, Object> metadata
) {}
