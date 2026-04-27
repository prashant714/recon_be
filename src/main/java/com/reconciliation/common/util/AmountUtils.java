package com.reconciliation.common.util;

public final class AmountUtils {

    private AmountUtils() {
    }

    public static boolean isWithinTolerance(Long expected, Long actual, long tolerance) {
        if (expected == null || actual == null) {
            return false;
        }
        return Math.abs(expected - actual) <= tolerance;
    }
}
