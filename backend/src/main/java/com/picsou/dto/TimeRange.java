package com.picsou.dto;

import java.time.LocalDate;

public enum TimeRange {
    _1D,
    _7D,
    _1M,
    _3M,
    YTD,
    _1Y,
    ALL;

    public LocalDate fromDate() {
        LocalDate today = LocalDate.now();
        return switch (this) {
            case _1D -> today.minusDays(1);
            case _7D -> today.minusDays(7);
            case _1M -> today.minusMonths(1);
            case _3M -> today.minusMonths(3);
            case YTD -> today.withDayOfYear(1);
            case _1Y -> today.minusYears(1);
            case ALL -> LocalDate.of(2020, 1, 1);
        };
    }

    public static TimeRange fromString(String value) {
        try {
            return valueOf("_" + value);
        } catch (IllegalArgumentException e) {
            return _1Y; // default
        }
    }
}
