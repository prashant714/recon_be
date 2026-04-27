package com.reconciliation.common.exception;

public class UnsupportedWebhookEventException extends RuntimeException {

    public UnsupportedWebhookEventException(String message) {
        super(message);
    }
}
