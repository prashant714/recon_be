package com.reconciliation.common.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class TimestampUtils {

    private TimestampUtils() {
    }

    public static OffsetDateTime fromUnixSeconds(long epochSeconds) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }
}
