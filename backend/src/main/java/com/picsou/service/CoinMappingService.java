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

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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

    /** Grabs the coin-id slug from a CoinGecko coin URL, e.g. {@code .../en/coins/loaded-lions}. */
    private static final Pattern COINGECKO_COIN_URL = Pattern.compile("/coins/([^/?#]+)");

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
        mapping.setCoingeckoId(coin.id());
        mapping.setCoinName(coin.name());
        mapping.setResolvedVia("USER");
        mapping.setUpdatedAt(Instant.now());

        CoinMapping saved = coinMappingRepository.save(mapping);
        log.info("User-mapped crypto ticker {} → CoinGecko id '{}' ({})", upper, coin.id(), coin.name());
        return saved;
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
