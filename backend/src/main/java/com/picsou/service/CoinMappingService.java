package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.CoinGeckoPriceProvider.CoinCandidate;
import com.picsou.model.AccountHolding;
import com.picsou.model.CoinMapping;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.CoinMappingRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TickerEarliestDate;
import com.picsou.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves and caches ticker → CoinGecko coin-id mappings — the dynamic replacement for the
 * hardcoded map CoinGeckoPriceProvider used to carry.
 *
 * <p>Resolution order for a ticker:
 * <ol>
 *   <li>persistent {@code coin_mapping} cache (already resolved once) → done;</li>
 *   <li>CoinGecko {@code /search} for coins whose symbol equals the ticker;</li>
 *   <li>if exactly one symbol match, or one that <em>dominates</em> the others by market-cap rank,
 *       persist it as {@code AUTO};</li>
 *   <li>otherwise leave it unresolved — the operator disambiguates by supplying the CoinGecko link
 *       ({@link #setManualMapping}); we never guess between comparable coins.</li>
 * </ol>
 *
 * <p>Mappings can also be listed, corrected, and forgotten after the fact (management UI): a
 * correction that changes the coin id purges the ticker's price history — fetched under the wrong
 * coin — and refetches it under the right one.</p>
 *
 * <p>Called at crypto-import time, where the context guarantees the ticker really is a crypto —
 * so a symbol that also exists as a stock ticker can't be mis-resolved by the general price path.
 */
@Service
@RequiredArgsConstructor
public class CoinMappingService {

    private static final Logger log = LoggerFactory.getLogger(CoinMappingService.class);

    /**
     * When several coins share a symbol, the top-ranked one is accepted only if it dominates the
     * runner-up by this factor (rank is 1-based, smaller = bigger cap). E.g. a coin ranked #5 beats
     * one ranked #300 (5×5=25 ≤ 300) but not one ranked #12 (5×5=25 > 12) — those stay ambiguous.
     */
    private static final int DOMINANCE_FACTOR = 5;

    /** Grabs the coin-id slug from a CoinGecko coin URL, e.g. {@code .../en/coins/loaded-lions}. */
    private static final Pattern COINGECKO_COIN_URL = Pattern.compile("/coins/([^/?#]+)");

    private final CoinGeckoPriceProvider coinGecko;
    private final CoinMappingRepository coinMappingRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final TransactionRepository transactionRepository;
    private final AccountHoldingRepository accountHoldingRepository;
    private final PriceService priceService;

    /** All known mappings, for the management UI. */
    @Transactional(readOnly = true)
    public List<CoinMapping> listAll() {
        return coinMappingRepository.findAll(Sort.by("ticker"));
    }

    /** Resolve a single ticker, persisting the mapping if a confident match is found. */
    @Transactional
    public Optional<CoinMapping> resolve(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        String upper = ticker.trim().toUpperCase();

        Optional<CoinMapping> existing = coinMappingRepository.findByTicker(upper);
        if (existing.isPresent()) return existing;

        CoinCandidate chosen = pickDominant(coinGecko.searchBySymbol(upper));
        if (chosen == null) {
            log.info("Crypto ticker {} could not be auto-resolved to a CoinGecko id (ambiguous or unknown)", upper);
            return Optional.empty();
        }

        CoinMapping saved = coinMappingRepository.save(CoinMapping.builder()
            .ticker(upper)
            .coingeckoId(chosen.id())
            .coinName(chosen.name())
            .resolvedVia("AUTO")
            .build());
        log.info("Resolved crypto ticker {} → CoinGecko id '{}' ({})", upper, chosen.id(), chosen.name());
        return Optional.of(saved);
    }

    /**
     * Best-effort bulk resolution; unresolved tickers are simply left out of the cache. Returns the
     * (uppercase) tickers that could not be auto-resolved — the operator disambiguates those via
     * {@link #setManualMapping(String, String)}.
     */
    @Transactional
    public Set<String> resolveAll(Set<String> tickers) {
        Set<String> unresolved = new TreeSet<>();
        for (String ticker : tickers) {
            try {
                if (resolve(ticker).isEmpty()) unresolved.add(ticker.trim().toUpperCase());
            } catch (Exception e) {
                log.warn("Ticker resolution failed for {}: {}", ticker, e.getMessage());
                unresolved.add(ticker.trim().toUpperCase());
            }
        }
        return unresolved;
    }

    /**
     * Pin a ticker to the coin behind an operator-supplied CoinGecko link, overriding any prior
     * mapping. The coin id is read from the URL slug (e.g. {@code .../coins/loaded-lions}) and
     * validated against CoinGecko before it's persisted as {@code USER} — a link to a non-existent
     * coin is rejected rather than cached.
     *
     * @throws IllegalArgumentException if the link isn't a CoinGecko coin URL or the coin is unknown.
     */
    @Transactional
    public CoinMapping setManualMapping(String ticker, String coingeckoUrl) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        String upper = ticker.trim().toUpperCase();
        String coinId = extractCoinId(coingeckoUrl);

        CoinCandidate coin = coinGecko.fetchCoinById(coinId).orElseThrow(() ->
            new IllegalArgumentException("No CoinGecko coin found for id '" + coinId
                + "' — check the link points to a coin page."));

        CoinMapping mapping = coinMappingRepository.findByTicker(upper)
            .orElseGet(() -> CoinMapping.builder().ticker(upper).build());
        String previousId = mapping.getCoingeckoId();
        mapping.setCoingeckoId(coin.id());
        mapping.setCoinName(coin.name());
        mapping.setResolvedVia("USER");
        mapping.setUpdatedAt(Instant.now());

        CoinMapping saved = coinMappingRepository.save(mapping);
        log.info("User-mapped crypto ticker {} → CoinGecko id '{}' ({})", upper, coin.id(), coin.name());

        // Re-pinning to a *different* coin means every price fetched under the old id is wrong:
        // purge the ticker's history and refetch it under the corrected coin. Re-pinning to the
        // same coin keeps the history — it was fetched from the right source.
        if (previousId != null && !previousId.equals(coin.id())) {
            purgeAndRefetchPrices(upper);
        }
        return saved;
    }

    /**
     * Forget a mapping entirely — used when a ticker was mapped by mistake. The ticker's price
     * history is purged too (it was fetched under the now-disowned coin id); the ticker goes back
     * to unresolved and the next import preview re-runs auto-resolution.
     */
    @Transactional
    public void delete(String ticker) {
        String upper = ticker.trim().toUpperCase();
        CoinMapping mapping = coinMappingRepository.findByTicker(upper).orElseThrow(() ->
            new IllegalArgumentException("No coin mapping exists for ticker '" + upper + "'."));
        coinMappingRepository.delete(mapping);
        priceSnapshotRepository.deleteByTicker(upper);
        priceService.evictFromCache(upper);
        log.info("Deleted coin mapping for {} (was CoinGecko id '{}') and purged its price history",
            upper, mapping.getCoingeckoId());
    }

    /**
     * Mark a ticker as <b>worthless</b> — a delisted coin CoinGecko can neither auto-resolve nor
     * price via a link. The ticker is pinned to a known-zero value instead of left silently
     * unpriced: any price fetched while it was still listed is purged, the live cache evicted, and
     * every holding of the ticker re-valued to zero. Idempotent, and reversible — re-pinning a
     * CoinGecko link ({@link #setManualMapping}) or forgetting it ({@link #delete}) undoes it.
     */
    @Transactional
    public CoinMapping markWorthless(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        String upper = ticker.trim().toUpperCase();

        CoinMapping mapping = coinMappingRepository.findByTicker(upper)
            .orElseGet(() -> CoinMapping.builder().ticker(upper).build());
        mapping.setCoingeckoId(null);
        mapping.setCoinName(null);
        mapping.setResolvedVia("WORTHLESS");
        mapping.setUpdatedAt(Instant.now());
        CoinMapping saved = coinMappingRepository.save(mapping);

        priceSnapshotRepository.deleteByTicker(upper);
        priceService.evictFromCache(upper);
        zeroHoldings(upper);
        log.info("Marked crypto ticker {} as worthless — purged price history and zeroed its holdings", upper);
        return saved;
    }

    /** Force every holding of a ticker to a known-zero current price (assumed worthless). */
    private void zeroHoldings(String upperTicker) {
        List<AccountHolding> holdings = accountHoldingRepository.findByTickerIgnoreCase(upperTicker);
        for (AccountHolding h : holdings) {
            h.setCurrentPrice(BigDecimal.ZERO);
        }
        accountHoldingRepository.saveAll(holdings);
    }

    /**
     * Drop everything priced under the old coin id (snapshots + live cache) and backfill the
     * history under the new one, anchored to the ticker's earliest transaction (12 months when it
     * has none). Backfill failures are non-fatal — the mapping is already corrected, and the
     * boot-time runner or a later import fills the gap.
     */
    private void purgeAndRefetchPrices(String upperTicker) {
        int purged = priceSnapshotRepository.deleteByTicker(upperTicker);
        priceService.evictFromCache(upperTicker);
        log.info("Coin mapping for {} changed — purged {} price snapshots fetched under the old id",
            upperTicker, purged);
        try {
            LocalDate from = transactionRepository.findEarliestDatesByTickerIn(Set.of(upperTicker)).stream()
                .map(TickerEarliestDate::getEarliestDate)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(LocalDate.now().minusMonths(12));
            priceService.backfillHistoricalPrices(Map.of(upperTicker, from));
        } catch (Exception e) {
            log.warn("Price re-backfill after remapping {} failed (will be retried at next boot): {}",
                upperTicker, e.getMessage());
        }
    }

    /** Extract the CoinGecko coin-id slug from a coin-page URL, rejecting anything that isn't one. */
    private String extractCoinId(String coingeckoUrl) {
        if (coingeckoUrl == null || coingeckoUrl.isBlank()) {
            throw new IllegalArgumentException("A CoinGecko coin link is required.");
        }
        Matcher m = COINGECKO_COIN_URL.matcher(coingeckoUrl.trim());
        if (!m.find()) {
            throw new IllegalArgumentException(
                "Not a CoinGecko coin link — expected a URL like https://www.coingecko.com/en/coins/<id>.");
        }
        String id = m.group(1).trim().toLowerCase();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Could not read the coin id from the link.");
        }
        return id;
    }

    /**
     * Pick the single dominant coin among symbol matches, or null if the choice is ambiguous.
     * A candidate must have a market-cap rank to win; among ranked candidates the best one wins
     * only if it clearly outranks the runner-up (see {@link #DOMINANCE_FACTOR}).
     */
    private CoinCandidate pickDominant(List<CoinCandidate> candidates) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        List<CoinCandidate> ranked = candidates.stream()
            .filter(c -> c.marketCapRank() != null)
            .sorted(Comparator.comparingInt(CoinCandidate::marketCapRank))
            .toList();

        if (ranked.isEmpty()) return null;            // nobody ranked → don't guess
        if (ranked.size() == 1) return ranked.get(0); // only one ranked → it's the one

        CoinCandidate top = ranked.get(0);
        CoinCandidate second = ranked.get(1);
        boolean dominates = (long) top.marketCapRank() * DOMINANCE_FACTOR <= second.marketCapRank();
        return dominates ? top : null;
    }
}
