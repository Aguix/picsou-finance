package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.CoinGeckoPriceProvider.CoinCandidate;
import com.picsou.model.CoinMapping;
import com.picsou.repository.CoinMappingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 *       (handled in a later commit); we never guess between comparable coins.</li>
 * </ol>
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

    private final CoinGeckoPriceProvider coinGecko;
    private final CoinMappingRepository coinMappingRepository;

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

    /** Best-effort bulk resolution; unresolved tickers are simply left out of the cache. */
    @Transactional
    public void resolveAll(Set<String> tickers) {
        for (String ticker : tickers) {
            try {
                resolve(ticker);
            } catch (Exception e) {
                log.warn("Ticker resolution failed for {}: {}", ticker, e.getMessage());
            }
        }
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
