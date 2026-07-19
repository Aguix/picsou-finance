package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AssetStatus;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.port.AssetCandidate;
import com.picsou.port.AssetResolverPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves and maintains the {@code financial_asset} registry — symbol → aggregator refs — the
 * dynamic replacement for the hardcoded maps the price providers used to carry.
 *
 * <p><b>The registry is what makes an aggregator able to price an asset.</b> Each aggregator owns one
 * nullable ref column ({@code coingecko_id}, {@code yahoo_symbol}, {@code coinmarketcap_id}, …) and a
 * null ref means that aggregator can't quote that asset — there is no notion of a "crypto aggregator"
 * or an asset-type gate anywhere. Resolution is simply what fills those columns, and this service
 * drives it through {@link AssetResolverPort}, never naming a concrete adapter: it loops over the
 * injected resolvers, each contributing candidates from its own API and reading/writing only its own
 * column. Adding an aggregator is one adapter + one column + one entity field; nothing here changes.
 *
 * <p>Filling <em>several</em> refs for one asset is the point: an asset both CoinGecko and
 * CoinMarketCap can quote keeps a live price when either one is rate-limited or turned off — the
 * router falls through to the next aggregator holding a ref (see {@link PriceRouter}).
 *
 * <p>Two ways a ref gets filled:
 * <ul>
 *   <li><b>Auto</b>, at discovery time from a context that guarantees what the symbol is —
 *       {@link #resolveCrypto} (CSV import, TR sync) and {@link #getOrCreateStock} (a ticker back
 *       from an OpenFIGI ISIN lookup). Auto-resolution only accepts a <em>dominant</em>
 *       market-cap match ({@link #pickDominant}) and leaves anything ambiguous {@code PENDING}: we
 *       never guess between comparable coins.</li>
 *   <li><b>Confirmed by the operator</b>, from the import preview or the standing mapping UI —
 *       {@link #previewResolutions} offers each aggregator's candidates without persisting anything,
 *       and {@link #applyMappings} pins the picked id per aggregator as {@code USER}.</li>
 * </ul>
 *
 * <p>A correction that changes an id the price history was fetched under purges that history and
 * refetches it under the corrected id, then rebuilds the stored value history ({@code
 * balance_snapshot}) of every account holding the symbol whose value is trade-derived — otherwise
 * the net-worth curve would keep valuing those days under the old, wrong id.
 */
@Service
@RequiredArgsConstructor
public class FinancialAssetService {

    private static final Logger log = LoggerFactory.getLogger(FinancialAssetService.class);

    /**
     * When several candidates share a symbol, the top-ranked one is accepted only if it dominates the
     * runner-up by this factor (rank is 1-based, smaller = bigger cap). E.g. a coin ranked #5 beats
     * one ranked #300 (5×5=25 ≤ 300) but not one ranked #12 (5×5=25 > 12) — those stay ambiguous.
     */
    private static final int DOMINANCE_FACTOR = 5;

    /** The aggregator that auto-resolves a crypto symbol at discovery time (see {@link #resolveCrypto}). */
    private static final String CRYPTO_DISCOVERY_AGGREGATOR = "coingecko";

    /** The aggregator whose ref a Yahoo-ticker discovery fills (see {@link #getOrCreateStock}). */
    private static final String STOCK_DISCOVERY_AGGREGATOR = "yahoo";

    /** Every aggregator's resolution side, in bean order (@Order) — never a concrete adapter. */
    private final List<AssetResolverPort> resolvers;
    private final FinancialAssetRepository assetRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final TransactionRepository transactionRepository;
    private final AccountHoldingRepository accountHoldingRepository;
    private final PriceService priceService;
    private final BalanceHistoryService balanceHistoryService;

    /** All known assets, for the management UI. */
    @Transactional(readOnly = true)
    public List<FinancialAsset> listAll() {
        return assetRepository.findAll(Sort.by("symbol"));
    }

    /** The resolver for an aggregator key, or empty when no such aggregator is deployed. */
    private Optional<AssetResolverPort> resolver(String aggregatorKey) {
        return resolvers.stream()
            .filter(r -> r.aggregatorKey().equals(aggregatorKey))
            .findFirst();
    }

    /**
     * Resolve a single crypto symbol against the crypto-discovery aggregator, persisting the asset if
     * a confident match is found. Called where the context guarantees the symbol really is a coin
     * (CSV import, TR sync) — so a symbol that also exists as a stock ticker can't be mis-resolved.
     * Empty means unresolved: a {@code PENDING} row is kept so the symbol shows up in the management
     * UI and is retried on the next resolve. Other aggregators' refs are filled by the operator from
     * the preview, not guessed here.
     */
    @Transactional
    public Optional<FinancialAsset> resolveCrypto(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        String upper = ticker.trim().toUpperCase();

        Optional<FinancialAsset> existing = assetRepository.findBySymbol(upper);
        if (existing.isPresent() && existing.get().getStatus() != AssetStatus.PENDING) {
            return existing;
        }

        AssetResolverPort discovery = resolver(CRYPTO_DISCOVERY_AGGREGATOR).orElse(null);
        AssetCandidate chosen = discovery == null ? null : pickDominant(discovery.searchBySymbol(upper));
        if (chosen == null) {
            if (existing.isEmpty()) {
                assetRepository.save(FinancialAsset.builder()
                    .symbol(upper)
                    .type(AssetType.CRYPTO)
                    .status(AssetStatus.PENDING)
                    .build());
            }
            log.info("Crypto symbol {} could not be auto-resolved (ambiguous or unknown)", upper);
            return Optional.empty();
        }

        FinancialAsset asset = existing.orElseGet(() -> FinancialAsset.builder().symbol(upper).build());
        asset.setType(AssetType.CRYPTO);
        discovery.setRef(asset, chosen.id());
        asset.setName(chosen.name());
        asset.setStatus(AssetStatus.AUTO);
        FinancialAsset saved = assetRepository.save(asset);
        log.info("Resolved crypto symbol {} → {} id '{}' ({})",
            upper, discovery.aggregatorKey(), chosen.id(), chosen.name());
        return Optional.of(saved);
    }

    /**
     * One aggregator's offer for a symbol: everything it found, plus the match it would pick on its
     * own. {@code suggested} is null when the aggregator ranks nothing dominant — the operator picks,
     * or leaves this aggregator's ref unset (and it then simply doesn't price the asset).
     *
     * <p>{@code currentId} is the ref stored for this aggregator on the existing registry row, so the
     * standing editor can pre-select the current mapping; it's null for the import preview (there's no
     * "current" — the asset isn't in the registry yet) and whenever the aggregator is unmapped.
     */
    public record AggregatorResolution(
        String aggregatorKey,
        AssetCandidate suggested,
        String currentId,
        List<AssetCandidate> candidates
    ) {}

    /** Provisional resolution of one symbol across every available aggregator; persisted by nothing. */
    public record AssetResolutionPreview(
        String symbol,
        AssetStatus currentStatus,
        List<AggregatorResolution> aggregators
    ) {}

    /**
     * Provisional, <b>non-persisting</b> resolution for the crypto import preview. For every imported
     * symbol that isn't already settled ({@code USER}/{@code WORTHLESS}) it returns the current
     * registry status and one block per available aggregator: that aggregator's candidates plus its
     * dominant guess. Nothing is written — the operator confirms or corrects, and the import applies
     * the result as {@code USER} via {@link #applyMappings}. This is what stops a silent {@code AUTO}
     * mis-match (a dominant-by-market-cap guess onto the wrong coin) from being frozen in the registry
     * before the operator has seen it, and it's where the second and third refs get filled — the ones
     * that make price fallback possible at all.
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
            // Import preview: no "current" ref to pre-select (the confirmed mapping is what's being
            // decided here), so skip the per-aggregator getRef lookup entirely.
            out.add(new AssetResolutionPreview(upper, status, offersFor(upper, null)));
        }
        return out;
    }

    /**
     * Candidate lookup for the <b>standing</b> mapping/verification UI (holding detail), as opposed to
     * {@link #previewResolutions} which serves the import preview. Unlike that method this never skips
     * a symbol: it returns every aggregator's candidates even for a coin already settled as
     * {@code USER}/{@code WORTHLESS}, so a standing mapping can be re-verified or corrected at any
     * time. Nothing is persisted.
     */
    @Transactional(readOnly = true)
    public AssetResolutionPreview previewResolution(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        String upper = ticker.trim().toUpperCase();
        FinancialAsset current = assetRepository.findBySymbol(upper).orElse(null);
        AssetStatus status = current != null ? current.getStatus() : null;
        return new AssetResolutionPreview(upper, status, offersFor(upper, current));
    }

    /**
     * Ask every aggregator that can resolve right now what it has for this symbol. An aggregator with
     * nothing to offer still gets a block (with an empty candidate list): "this aggregator doesn't
     * know your symbol" is information the operator needs, and it's also just the honest answer —
     * their ref stays null and they don't price the asset. A per-aggregator failure degrades to an
     * empty list rather than failing the whole preview.
     *
     * <p>When {@code current} is non-null (the standing preview of an existing registry row) each
     * block also reports the ref that aggregator holds today, read off its own column — so the editor
     * can pre-select the current mapping. The import preview passes null: there's nothing settled yet.
     */
    private List<AggregatorResolution> offersFor(String upperSymbol, FinancialAsset current) {
        List<AggregatorResolution> blocks = new ArrayList<>();
        for (AssetResolverPort r : resolvers) {
            if (!r.isResolutionAvailable()) continue;
            List<AssetCandidate> candidates;
            try {
                candidates = r.searchBySymbol(upperSymbol);
            } catch (Exception e) {
                log.warn("Candidate search failed for {} on {}: {}",
                    upperSymbol, r.aggregatorKey(), e.getMessage());
                candidates = List.of();
            }
            String currentId = current != null ? r.getRef(current) : null;
            blocks.add(new AggregatorResolution(
                r.aggregatorKey(), pickDominant(candidates), currentId, candidates));
        }
        return blocks;
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
     * through here. It never calls an external API — real resolution happens later via
     * {@link #resolveCrypto} or the management UI; until then the PENDING row is simply unpriced.
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
     * Return the asset for a Yahoo ticker already resolved via OpenFIGI at ISIN-discovery time
     * (TR/Bourso sync, manual-transaction ISIN entry) — the stock-side counterpart of
     * {@link #resolveCrypto}. Mints a {@code STOCK} row with the Yahoo ref set to the ticker the
     * first time it's seen: for these sources the internal {@code symbol} already <em>is</em> the
     * Yahoo ticker (OpenFIGI resolved it — not a guess), so no search is needed. On an existing row
     * it only fills in what's missing, and never touches one already typed {@code CRYPTO} — a
     * stock-context ticker colliding with an existing crypto symbol must not clobber a working coin
     * mapping.
     */
    @Transactional
    public FinancialAsset getOrCreateStock(String yahooTicker) {
        String upper = yahooTicker.trim().toUpperCase();
        AssetResolverPort yahoo = resolver(STOCK_DISCOVERY_AGGREGATOR).orElse(null);
        FinancialAsset asset = assetRepository.findBySymbol(upper).orElse(null);
        if (asset == null) {
            FinancialAsset minted = FinancialAsset.builder()
                .symbol(upper)
                .type(AssetType.STOCK)
                .status(AssetStatus.PENDING)
                .build();
            if (yahoo != null) yahoo.setRef(minted, upper);
            return assetRepository.save(minted);
        }
        if (asset.getType() == AssetType.CRYPTO) {
            return asset;
        }
        boolean changed = false;
        if (asset.getType() == AssetType.UNKNOWN) {
            asset.setType(AssetType.STOCK);
            changed = true;
        }
        if (yahoo != null && yahoo.getRef(asset) == null) {
            yahoo.setRef(asset, upper);
            changed = true;
        }
        return changed ? assetRepository.save(asset) : asset;
    }

    /**
     * Opportunistically label an asset that has no name yet (e.g. minted bare by
     * {@link #getOrCreate} from a wallet sync using the symbol as a placeholder). Never overwrites
     * an existing name — a shaky broker/wallet label must not clobber a canonical one (an
     * aggregator's, a prior manual mapping's, or another account's earlier, better label for the
     * same symbol).
     */
    @Transactional
    public void fillNameIfAbsent(FinancialAsset asset, String name) {
        if (name == null || name.isBlank()) return;
        if (asset.getName() != null && !asset.getName().isBlank()) return;
        asset.setName(name.trim());
        assetRepository.save(asset);
    }

    /** A pasted link resolved to the one aggregator that claims it, with its validated candidate. */
    public record LinkResolution(String aggregatorKey, AssetCandidate candidate) {}

    /**
     * Resolve an operator-pasted aggregator link: the link is offered to each resolver in turn, the
     * one that recognises it owns the id (a CoinGecko coin URL → the CoinGecko ref), and the id is
     * validated against that aggregator before anything trusts it — a link to a non-existent asset
     * is rejected rather than cached. Shared by the standing "paste a link" path
     * ({@link #setManualMapping}) and the import wizard's per-coin link field.
     *
     * @throws IllegalArgumentException if no aggregator recognises the link, or the id is unknown.
     */
    public LinkResolution resolveLink(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("An aggregator link is required.");
        }
        for (AssetResolverPort r : resolvers) {
            String id = r.extractIdFromUrl(url).orElse(null);
            if (id == null) continue;
            AssetCandidate found = r.fetchById(id).orElseThrow(() ->
                new IllegalArgumentException("No " + r.aggregatorKey() + " asset found for id '" + id
                    + "' — check the link points to an asset page."));
            return new LinkResolution(r.aggregatorKey(), found);
        }
        throw new IllegalArgumentException(
            "Not a link any aggregator recognises — expected something like "
                + "https://www.coingecko.com/en/coins/<id>.");
    }

    /**
     * Pin a symbol to the asset behind an operator-supplied aggregator link ({@link #resolveLink}),
     * overriding that aggregator's prior mapping, as {@code USER}.
     *
     * @throws IllegalArgumentException if no aggregator recognises the link, or the id is unknown.
     */
    @Transactional
    public FinancialAsset setManualMapping(String ticker, String url) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        LinkResolution link = resolveLink(url);
        return applyMappings(ticker, Map.of(link.aggregatorKey(), link.candidate().id()),
            link.candidate().name());
    }

    /**
     * Pin a symbol to one id <b>per aggregator</b> as {@code USER}, overriding any prior mapping —
     * the confirmed outcome of a preview. Each id is written by its own aggregator's resolver into
     * its own column ({@code idsByAggregator} is keyed by {@code aggregatorKey}), so the asset ends
     * up quotable by every aggregator the operator gave an id for, and the router can fall through
     * between them. An unknown key is ignored rather than fatal — a stale client mustn't fail an
     * import.
     *
     * <p>The ids are trusted from the caller (picked from preview candidates, or already validated by
     * {@link #setManualMapping}), so no round-trip is made here. Re-pinning an aggregator to a
     * <em>different</em> id purges the symbol's price history — some of it was fetched under the old,
     * wrong id — and refetches it; filling a ref that was empty, or re-pinning the same id, keeps it.
     */
    @Transactional
    public FinancialAsset applyMappings(String ticker, Map<String, String> idsByAggregator, String name) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        if (idsByAggregator == null || idsByAggregator.isEmpty()) {
            throw new IllegalArgumentException("At least one aggregator id is required.");
        }
        String upper = ticker.trim().toUpperCase();

        FinancialAsset asset = assetRepository.findBySymbol(upper)
            .orElseGet(() -> FinancialAsset.builder().symbol(upper).build());
        if (asset.getType() == AssetType.UNKNOWN) asset.setType(AssetType.CRYPTO);

        boolean replacedAnId = false;
        Map<String, String> applied = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : idsByAggregator.entrySet()) {
            String id = e.getValue() == null ? null : e.getValue().trim();
            if (id == null || id.isEmpty()) continue;
            AssetResolverPort r = resolver(e.getKey()).orElse(null);
            if (r == null) {
                log.warn("Ignoring mapping for {} on unknown aggregator '{}'", upper, e.getKey());
                continue;
            }
            String previous = r.getRef(asset);
            if (previous != null && !previous.equals(id)) replacedAnId = true;
            r.setRef(asset, id);
            applied.put(r.aggregatorKey(), id);
        }
        if (applied.isEmpty()) {
            throw new IllegalArgumentException("None of the supplied aggregator ids could be applied.");
        }
        // Only overwrite the name when a real one is supplied — a re-map from a source without a name
        // (a Yahoo candidate has no longname, e.g.) must not wipe an existing label to null. On a
        // fresh asset with no name given, it simply stays null until something labels it.
        if (name != null && !name.isBlank()) {
            asset.setName(name.trim());
        }
        asset.setStatus(AssetStatus.USER);

        FinancialAsset saved = assetRepository.save(asset);
        log.info("User-mapped symbol {} → {} ({})", upper, applied, name);

        if (replacedAnId) {
            purgeAndRefetchPrices(saved);
            rebuildHoldingAccountsHistory(accountHoldingRepository.findByAsset_Id(saved.getId()));
        }
        return saved;
    }

    /**
     * Un-link a symbol — revert it to {@code PENDING} <b>keeping the registry row</b>, so a holding's
     * {@code account_holding.asset_id} FK stays valid. This is what the standing "forget the link"
     * action does: every aggregator's ref (and the name they carried) is dropped, the symbol's price
     * history is purged and the live cache evicted, and the next resolve/import re-runs
     * auto-resolution. Contrast with {@link #delete}, which removes the row entirely and therefore
     * fails for any symbol a holding still references (the common case).
     */
    @Transactional
    public FinancialAsset clearMapping(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required.");
        }
        String upper = ticker.trim().toUpperCase();
        FinancialAsset asset = assetRepository.findBySymbol(upper).orElseThrow(() ->
            new IllegalArgumentException("No asset exists for symbol '" + upper + "'."));
        clearAllRefs(asset);
        asset.setName(null);
        asset.setStatus(AssetStatus.PENDING);
        FinancialAsset saved = assetRepository.save(asset);

        priceSnapshotRepository.deleteByAssetId(saved.getId());
        priceService.evictFromCache(upper);
        rebuildHoldingAccountsHistory(accountHoldingRepository.findByAsset_Id(saved.getId()));
        log.info("Cleared mapping for {} — reverted to PENDING and purged its price history", upper);
        return saved;
    }

    /**
     * Drop every aggregator's ref. Each resolver clears its own column, so a newly added aggregator
     * is un-linked here without this method knowing it exists.
     */
    private void clearAllRefs(FinancialAsset asset) {
        for (AssetResolverPort r : resolvers) {
            r.setRef(asset, null);
        }
    }

    /**
     * Forget an asset entirely — remove the registry row. Only safe for an <b>orphan</b> symbol (no
     * {@code account_holding} references it), so this is <em>not</em> what the standing "forget the
     * link" button calls — that uses {@link #clearMapping}. The symbol's price history is purged too
     * (it was fetched under the now-disowned ids); the symbol goes back to unregistered and the next
     * import preview re-runs auto-resolution. No value-history rebuild is needed here: an orphan is
     * held by no account, so no {@code balance_snapshot} was valued under its price.
     */
    @Transactional
    public void delete(String ticker) {
        String upper = ticker.trim().toUpperCase();
        FinancialAsset asset = assetRepository.findBySymbol(upper).orElseThrow(() ->
            new IllegalArgumentException("No asset exists for symbol '" + upper + "'."));

        // The account_holding.asset_id FK has no cascade, so deleting a held asset would fail at the
        // DB with a raw DataIntegrityViolation (→ generic 500). Guard it up front with a clear 400:
        // a held symbol must be un-linked ({@link #clearMapping}), not removed.
        int held = accountHoldingRepository.findByAsset_Id(asset.getId()).size();
        if (held > 0) {
            throw new IllegalArgumentException("Asset '" + upper + "' is still held by " + held
                + " holding(s) — clear its mapping instead of deleting it.");
        }

        assetRepository.delete(asset);
        priceSnapshotRepository.deleteByAssetId(asset.getId());
        priceService.evictFromCache(upper);
        log.info("Deleted asset {} and purged its price history", upper);
    }

    /**
     * Mark a symbol as <b>worthless</b> — a delisted coin no aggregator can resolve or price. The
     * symbol is pinned to a known-zero value instead of left silently unpriced: every aggregator ref
     * is dropped, any price fetched while it was still listed is purged, the live cache evicted, and
     * every holding of the symbol re-valued to zero. Idempotent, and reversible — pinning a link
     * ({@link #setManualMapping}) or forgetting it ({@link #delete}) undoes it.
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
        clearAllRefs(asset);
        asset.setName(null);
        asset.setStatus(AssetStatus.WORTHLESS);
        FinancialAsset saved = assetRepository.save(asset);

        priceSnapshotRepository.deleteByAssetId(saved.getId());
        priceService.evictFromCache(upper);
        // One read of the holdings, shared by the zero-out (live value) and the history rebuild
        // (past value) — both need the same set, and it's the symbol's holders across all accounts.
        List<AccountHolding> holdings = accountHoldingRepository.findByAsset_Id(saved.getId());
        zeroHoldings(holdings);
        rebuildHoldingAccountsHistory(holdings);
        log.info("Marked symbol {} as worthless — purged price history and zeroed its holdings", upper);
        return saved;
    }

    /** Force every given holding to a known-zero current price (assumed worthless). */
    private void zeroHoldings(List<AccountHolding> holdings) {
        for (AccountHolding h : holdings) {
            h.setCurrentPrice(BigDecimal.ZERO);
        }
        accountHoldingRepository.saveAll(holdings);
    }

    /**
     * Drop everything priced under the old id (snapshots + live cache) and backfill the history under
     * the new one, anchored to the ticker's earliest transaction (12 months when it has none).
     * Backfill failures are non-fatal — the mapping is already corrected, and the boot-time runner or
     * a later import fills the gap.
     */
    private void purgeAndRefetchPrices(FinancialAsset asset) {
        String upperTicker = asset.getSymbol();
        int purged = priceSnapshotRepository.deleteByAssetId(asset.getId());
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

    /**
     * Re-price the value history of every account holding this asset, after its {@code price_snapshot}
     * history was purged/refetched/pinned-to-zero — each holder's daily {@code balance_snapshot} rows
     * were valued under the old id. Delegates one rebuild per distinct holding account to
     * {@link BalanceHistoryService#rebuildFromTransactions}, which <b>self-gates</b>: it only
     * overwrites an account whose current holdings are fully reproduced by its BUY/SELL/REWARD
     * timeline, and leaves a balance-synced account (bank/exchange/wallet, whose positions carry no
     * trade rows) untouched rather than replaying it into a partial curve. That gate — not the
     * account's type — is what makes rebuilding every holder safe.
     */
    private void rebuildHoldingAccountsHistory(List<AccountHolding> holdings) {
        Map<Long, Account> accounts = new LinkedHashMap<>();
        for (AccountHolding h : holdings) {
            accounts.putIfAbsent(h.getAccount().getId(), h.getAccount());
        }
        accounts.values().forEach(balanceHistoryService::rebuildFromTransactions);
    }

    /**
     * Pick the single dominant candidate among an aggregator's symbol matches, or null if the choice
     * is ambiguous. A candidate must carry a market-cap rank to win: ranking is the only evidence we
     * have that a symbol collision has an obvious winner, so an aggregator that doesn't rank its
     * results (Yahoo — where the "candidates" are the same security on different exchanges, and the
     * wrong pick means quoting the wrong market) never auto-suggests anything and its ref stays null
     * until an operator chooses. Among ranked candidates the best one wins only if it clearly
     * outranks the runner-up (see {@link #DOMINANCE_FACTOR}).
     */
    private AssetCandidate pickDominant(List<AssetCandidate> candidates) {
        List<AssetCandidate> ranked = candidates.stream()
            .filter(c -> c.marketCapRank() != null)
            .sorted(Comparator.comparingInt(AssetCandidate::marketCapRank))
            .toList();

        if (ranked.isEmpty()) return null;            // nobody ranked → don't guess
        if (ranked.size() == 1) return ranked.get(0); // only one ranked → it's the one

        AssetCandidate top = ranked.get(0);
        AssetCandidate second = ranked.get(1);
        boolean dominates = (long) top.marketCapRank() * DOMINANCE_FACTOR <= second.marketCapRank();
        return dominates ? top : null;
    }
}
