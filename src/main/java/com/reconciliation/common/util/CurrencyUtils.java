package com.reconciliation.common.util;

import java.util.Locale;

public final class CurrencyUtils {

    private CurrencyUtils() {
    }

    public static String normalize(String currency) {
        return currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    }
}
