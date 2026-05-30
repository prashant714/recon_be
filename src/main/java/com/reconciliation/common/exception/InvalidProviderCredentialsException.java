package com.reconciliation.common.exception;

public class InvalidProviderCredentialsException extends RuntimeException {

    public InvalidProviderCredentialsException(String provider) {
        super("Invalid credentials for provider: " + provider);
    }
}
