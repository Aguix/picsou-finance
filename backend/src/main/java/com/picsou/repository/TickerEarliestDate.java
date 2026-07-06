package com.picsou.repository;

import java.time.LocalDate;

/**
 * Projection for the earliest transaction date of a given ticker — used to anchor the historical
 * price backfill per coin (each coin from its own first transaction) instead of a global window.
 */
public interface TickerEarliestDate {
    String getTicker();
    LocalDate getEarliestDate();
}
