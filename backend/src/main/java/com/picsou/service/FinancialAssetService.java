package com.picsou.service;

import com.picsou.adapter.price.CoinGeckoPriceProvider;
import com.picsou.adapter.price.CoinGeckoPriceProvider.CoinCandidate;
import com.picsou.model.AccountHolding;
import com.picsou.model.AssetStatus;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.FinancialAssetRepository;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves and maintains the {@code financial_asset} registry — symbol → aggregator refs — the
 * dynamic replacement for the hardcoded maps the price providers used to carry.
 *
 * <p>Crypto resolution order for a symbol:
 * <ol>
 *   <li>registered asset with a CoinGecko id (or marked WORTHLESS) → done;</li>
 *   <li>CoinGecko {@code /search} for coins whose symbol equals the ticker;</li>
 *   <li>if exactly one symbol match, or one that <em>dominates</em> the others by market-cap rank,
 *       persist it as {@code AUTO};</li>
 *   <li>otherwise keep a {@code PENDING} row — visible and linkable in the management UI, retried
 *       on the next resolve — the operator disambiguates by supplying the CoinGecko link
 *       ({@link #setManualMapping}); we never guess between comparable coins.</li>
 * </ol>
 *
 * <p>Mappings can also be listed, corrected, and forgotten after the fact (management UI): a
 * correction that changes the coin id purges the symbol's price history — fetched under the wrong
 * coin — and refetches it under the right one.</p>
 *
 * <p>{@link #resolveCrypto} is called at crypto-discovery time (import, TR sync), where the
 * context guarantees the symbol really is a crypto — so a symbol that also exists as a stock
 * ticker can't be mis-resolved by the general price path.
 */
@Service
@RequiredArgsConstructor
public class FinancialAssetService {

    private static final Logger log = LoggerFactory.getLogger(FinancialAssetService.class);

    /**
     * When several coins share a symbol, the top-ranked one is accepted only if it dominates the
     * runner-up by this factor (rank is 1-based, smaller = bigger cap). E.g. a coin ranked #5 beats
     * one ranked #300 (5×5=25 ≤ 300) but not one ranked #12 (5×5=25 > 12) — those stay ambiguous.
     */
    private static final int DOMINANCE_FACTOR = 5;

    /** Grabs the coin-id slug from a CoinGecko coin URL, e.g. {@code .../en/coins/loaded-lions}. */
    private static final Pattern COINGECKO_COIN_URL = Pattern.compile("/coins/([^/?#]+)");

    private final CoinGeckoPriceProvider coinGecko;
    private final FinancialAssetRepository assetRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final TransactionRepository transactionRepository;
    private final AccountHoldingRepository accountHoldingRepository;
    private final PriceService priceService;

    /** All known assets, for the management UI. */
    @Transactional(readOnly = true)
    public List<FinancialAsset> listAll() {
        return assetRepository.findAll(Sort.by("symbol"));
    }

    /**
     * Resolve a single crypto symbol, persisting the asset if a confident match is found.
     * Empty means unresolved — a {@code PENDING} row is kept so the symbol shows up in the
     * management UI and is retried on the next resolve.
     */
    @Transactional
    public Optional<FinancialAsset> resolveCrypto(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        String upper = ticker.trim().toUpperCase();

        Optional<FinancialAsset> existing = assetRepository.findBySymbol(upper);
        if (existing.isPresent() && existing.get().getStatus() != AssetStatus.PENDING) {
            return existing;
        }

        CoinCandidate chosen = pickDominant(coinGecko.searchBySymbol(upper));
        if (chosen == null) {
            if (existing.isEmpty()) {
                assetRepository.save(FinancialAsset.builder()
                    .symbol(upper)
                    .type(AssetType.CRYPTO)
                    .status(AssetStatus.PENDING)
                    .build());
            }
            log.info("Crypto symbol {} could not be auto-resolved to a CoinGecko id (ambiguous or unknown)", upper);
            return Optional.empty();
        }

        FinancialAsset asset = existing.orElseGet(() -> FinancialAsset.builder().symbol(upper).build());
        asset.setType(AssetType.CRYPTO);
        asset.setCoingeckoId(chosen.id());
        asset.setName(chosen.name());
        asset.setStatus(AssetStatus.AUTO);
        FinancialAsset saved = assetRepository.save(asset);
        log.info("Resolved crypto symbol {} → CoinGecko id '{}' ({})", upper, chosen.id(), chosen.name());
        return Optional.of(saved);
    }

    /** Provisional resolution of one imported symbol, shown in the preview and persisted by nothing. */
    public record AssetResolutionPreview(
        String symbol,
        AssetStatus currentStatus,
        CoinCandidate suggested,
        List<CoinCandidate> candidates
    ) {}

    /**
     * Provisional, <b>non-persisting</b> resolution for the crypto import preview. For every imported
     * symbol that isn't already settled ({@code USER}/{@code WORTHLESS}) it returns the current
     * registry status, the best market-cap match ({@link #pickDominant}, possibly null), and the full
     * candidate list from CoinGecko {@code /search} — the candidates {@link #resolveCrypto} discards
     * after picking. Nothing is written: the operator confirms or corrects the choice in the preview
     * and the import applies it as {@code USER} via {@link #applyUserMapping}. This is what stops a
     * silent {@code AUTO} mis-match (a dominant-by-market-cap guess onto the wrong coin) from being
     * frozen in the registry before the operator has seen it. A per-symbol search failure degrades to
     * an empty candidate list rather than failing the batch.
     */
    @Transactional(readOnly = true)
    public List<AssetResolutionPreview> previewResolutions(Set<String> tickers) {
        List<AssetResolutionPreview> out = new ArrayList<>();
        for (String raw : tickers) {
            if (raw == null || raw.isBlank()) continue;
            String upper = raw.trim().toUpperCase();
            AssetStatus status = assetRepository.findBySymbol(upper)
                .map(FinancialAsset::getStatus).orElse(null);
            if (status == AssetStatus.USER || status == AssetStatus.WORTHLESS) continue;
            List<CoinCandidate> candidates;
            try {
                candidates = coinGecko.searchBySymbol(upper);
            } catch (Exception e) {
                log.warn("Candidate search failed for {}: {}", upper, e.getMessage());
                candidates = List.of();
            }
            out.add(new AssetResolutionPreview(upper, status, pickDominant(candidates), candidates));
        }
        return out;
    }

    /**
     * Candidate lookup for the <b>standing</b> mapping/verification UI (holding detail), as opposed to
     * {@link #previewResolutions} which serves the import preview. Unlike that method this never skips a
     * symbol: it returns the current registry status plus every CoinGecko candidate even for a coin
     * already settled as {@code USER}/{@code WORTHLESS}, so the operator can re-verify or correct a
     * standing mapping at any time. Nothing is persisted — the choice is applied via
     * {@link #applyUserMapping}/{@link #setManualMapping}/{@link #markWorthless}. A search failure
     * degrades to an empty candidate list.
     */
    @Transactional(readOnly = true)
    public AssetResolutionPreview previewResolution(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        String upper = ticker.trim().toUpperCase();
        AssetStatus status = assetRepository.findBySymbol(upper)
            .map(FinancialAsset::getStatus).orElse(null);
        List<CoinCandidate> candidates;
        try {
            candidates = coinGecko.searchBySymbol(upper);
        } catch (Exception e) {
            log.warn("Candidate search failed for {}: {}", upper, e.getMessage());
            candidates = List.of();
        }
        return new AssetResolutionPreview(upper, status, pickDominant(candidates), candidates);
    }

    /**
     * Best-effort bulk resolution; unresolved symbols stay {@code PENDING}. Returns the
     * (uppercase) symbols that could not be auto-resolved — the operator disambiguates those via
     * {@link #setManualMapping(String, String)}.
     */
    @Transactional
    public Set<String> resolveAll(Set<String> tickers) {
        Set<String> unresolved = new TreeSet<>();
        for (String ticker : tickers) {
            try {
                if (resolveCrypto(ticker).isEmpty()) unresolved.add(ticker.trim().toUpperCase());
            } catch (Exception e) {
                log.warn("Symbol resolution failed for {}: {}", ticker, e.getMessage());
                unresolved.add(ticker.trim().toUpperCase());
            }
        }
        return unresolved;
    }

    /**
     * Return the asset for a symbol, minting a bare {@code PENDING}/{@code UNKNOWN} passthrough row
     * the first time a symbol is seen. This is the runtime counterpart of the V52 backfill: a
     * holding must always point at an asset, so the write paths (TR/Bourso/wallet sync,
     * {@link HoldingComputeService}, {@link AccountService#upsertHolding}) resolve their symbol
     * through here. It never calls an external API — real aggregator resolution (CoinGecko search,
     * Yahoo identity) happens later via {@link #resolveCrypto} or the management UI; until then the
     * PENDING row is simply unpriced.
     */
    @Transactional
    public FinancialAsset getOrCreate(String symbol) {
        String upper = symbol.trim().toUpperCase();
        return assetRepository.findBySymbol(upper)
            .orElseGet(() -> assetRepository.save(FinancialAsset.builder()
                .symbol(upper)
                .type(AssetType.UNKNOWN)
                .status(AssetStatus.PENDING)
                .build()));
    }

    /**
     * Opportunistically label an asset that has no name yet (e.g. minted bare by
     * {@link #getOrCreate} from a wallet sync using the symbol as a placeholder). Never overwrites
     * an existing name — a shaky broker/wallet label must not clobber a canonical one (CoinGecko,
     * a prior manual mapping, or another account's earlier, better label for the same symbol).
     */
    @Transactional
    public void fillNameIfAbsent(FinancialAsset asset, String name) {
        if (name == null || name.isBlank()) return;
        if (asset.getName() != null && !asset.getName().isBlank()) return;
        asset.setName(name.trim());
        assetRepository.save(asset);
    }

    /**
     * Pin a symbol to the coin behind an operator-supplied CoinGecko link, overriding any prior
     * mapping. The coin id is read from the URL slug (e.g. {@code .../coins/loaded-lions}) and
     * validated against CoinGecko before it's persisted as {@code USER} — a link to a non-existent
     * coin is rejected rather than cached.
     *
     * @throws IllegalArgumentException if the link isn't a CoinGecko coin URL or the coin is unknown.
     */
    @Transactional
    public FinancialAsset setManualMapping(String ticker, String coingeckoUrl) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        String coinId = extractCoinId(coingeckoUrl);
        CoinCandidate coin = coinGecko.fetchCoinById(coinId).orElseThrow(() ->
            new IllegalArgumentException("No CoinGecko coin found for id '" + coinId
                + "' — check the link points to a coin page."));
        return applyUserMapping(ticker, coin.id(), coin.name());
    }

    /**
     * Pin a symbol to a known CoinGecko coin id as {@code USER}, overriding any prior mapping. The
     * id/name are trusted from the caller — a coin the operator picked from the import-preview
     * candidates, or one already fetched and validated by {@link #setManualMapping} — so this makes
     * no extra CoinGecko round-trip. Re-pinning to a <em>different</em> coin purges the symbol's
     * price history (fetched under the old, wrong id) and refetches it; re-pinning to the same coin
     * keeps it.
     */
    @Transactional
    public FinancialAsset applyUserMapping(String ticker, String coingeckoId, String name) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        if (coingeckoId == null || coingeckoId.isBlank()) {
            throw new IllegalArgumentException("A CoinGecko coin id is required.");
        }
        String upper = ticker.trim().toUpperCase();
        String coinId = coingeckoId.trim();

        FinancialAsset asset = assetRepository.findBySymbol(upper)
            .orElseGet(() -> FinancialAsset.builder().symbol(upper).build());
        String previousId = asset.getCoingeckoId();
        if (asset.getType() == AssetType.UNKNOWN) asset.setType(AssetType.CRYPTO);
        asset.setCoingeckoId(coinId);
        asset.setName(name);
        asset.setStatus(AssetStatus.USER);

        FinancialAsset saved = assetRepository.save(asset);
        log.info("User-mapped crypto symbol {} → CoinGecko id '{}' ({})", upper, coinId, name);

        // Re-pinning to a *different* coin means every price fetched under the old id is wrong:
        // purge the symbol's history and refetch it under the corrected coin. Re-pinning to the
        // same coin keeps the history — it was fetched from the right source.
        if (previousId != null && !previousId.equals(coinId)) {
            purgeAndRefetchPrices(upper);
        }
        return saved;
    }

    /**
     * Forget an asset entirely — used when a symbol was mapped by mistake. The symbol's price
     * history is purged too (it was fetched under the now-disowned coin id); the symbol goes back
     * to unregistered and the next import preview re-runs auto-resolution.
     */
    @Transactional
    public void delete(String ticker) {
        String upper = ticker.trim().toUpperCase();
        FinancialAsset asset = assetRepository.findBySymbol(upper).orElseThrow(() ->
            new IllegalArgumentException("No asset exists for symbol '" + upper + "'."));
        assetRepository.delete(asset);
        priceSnapshotRepository.deleteByTicker(upper);
        priceService.evictFromCache(upper);
        log.info("Deleted asset {} (was CoinGecko id '{}') and purged its price history",
            upper, asset.getCoingeckoId());
    }

    /**
     * Mark a symbol as <b>worthless</b> — a delisted coin CoinGecko can neither auto-resolve nor
     * price via a link. The symbol is pinned to a known-zero value instead of left silently
     * unpriced: any price fetched while it was still listed is purged, the live cache evicted, and
     * every holding of the symbol re-valued to zero. Idempotent, and reversible — re-pinning a
     * CoinGecko link ({@link #setManualMapping}) or forgetting it ({@link #delete}) undoes it.
     */
    @Transactional
    public FinancialAsset markWorthless(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        String upper = ticker.trim().toUpperCase();

        FinancialAsset asset = assetRepository.findBySymbol(upper)
            .orElseGet(() -> FinancialAsset.builder().symbol(upper).build());
        if (asset.getType() == AssetType.UNKNOWN) asset.setType(AssetType.CRYPTO);
        asset.setCoingeckoId(null);
        asset.setName(null);
        asset.setStatus(AssetStatus.WORTHLESS);
        FinancialAsset saved = assetRepository.save(asset);

        priceSnapshotRepository.deleteByTicker(upper);
        priceService.evictFromCache(upper);
        zeroHoldings(upper);
        log.info("Marked symbol {} as worthless — purged price history and zeroed its holdings", upper);
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
        log.info("Mapping for {} changed — purged {} price snapshots fetched under the old id",
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
