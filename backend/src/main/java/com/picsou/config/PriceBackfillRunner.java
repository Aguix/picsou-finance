package com.picsou.config;

import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.TickerEarliestDate;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Automatically backfills historical prices on startup if holding tickers have no price history.
 * Idempotent — the gap-aware backfill only fetches dates that aren't already stored.
 *
 * <p>Each ticker is anchored to <em>its own</em> earliest transaction — so a coin bought last month
 * isn't fetched from a coin bought two years ago — instead of a single global window. Holdings with
 * no transaction timeline (bank-synced or manually-entered positions) fall back to a 12-month window.
 */
@Component
public class PriceBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PriceBackfillRunner.class);

    private final PriceService priceService;
    private final AccountHoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    public PriceBackfillRunner(PriceService priceService, AccountHoldingRepository holdingRepository,
                               TransactionRepository transactionRepository) {
        this.priceService = priceService;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> tickers = holdingRepository.findDistinctTickers();
        if (tickers.isEmpty()) {
            log.debug("No holding tickers found — skipping price backfill");
            return;
        }

        // Per-ticker anchor: each coin from its own first transaction; fall back to 12 months for
        // tickers with no transaction (bank-synced / manual holdings).
        LocalDate fallback = LocalDate.now().minusMonths(12);
        Map<String, LocalDate> firstByTicker = new HashMap<>();
        for (String ticker : tickers) {
            firstByTicker.put(ticker.toUpperCase(), fallback);
        }
        for (TickerEarliestDate d : transactionRepository.findEarliestDatesByTickerIn(tickers)) {
            if (d.getEarliestDate() != null) {
                firstByTicker.put(d.getTicker().toUpperCase(), d.getEarliestDate());
            }
        }

        int saved = priceService.backfillHistoricalPrices(firstByTicker);
        log.info("Price backfill complete: {} snapshots saved for {} tickers", saved, tickers.size());
    }
}
