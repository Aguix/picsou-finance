package com.picsou.config;

import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * Automatically backfills historical prices on startup if holding tickers have no price history.
 * Idempotent — the gap-aware backfill only fetches dates that aren't already stored.
 *
 * <p>The window is anchored to the portfolio's own history — the earliest transaction touching the
 * held tickers — so an account older than a year isn't truncated and a young one doesn't over-fetch.
 * Holdings with no transaction timeline (bank-synced or manually-entered positions) fall back to a
 * 12-month window.
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

        LocalDate earliestTx = transactionRepository.findEarliestDateByTickerIn(tickers);
        LocalDate from = earliestTx != null ? earliestTx : LocalDate.now().minusMonths(12);
        int saved = priceService.backfillHistoricalPrices(tickers, from);
        log.info("Price backfill complete: {} snapshots saved for {} tickers (from {})",
            saved, tickers.size(), from);
    }
}
