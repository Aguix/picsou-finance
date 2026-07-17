package com.picsou.service;

import com.picsou.model.AccountHolding;
import com.picsou.model.AssetStatus;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.port.AssetCandidate;
import com.picsou.port.AssetResolverPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The resolution engine is exercised against <b>fake aggregators</b> rather than the real adapters:
 * the service must not know CoinGecko from CoinMarketCap, so the tests hand it three interchangeable
 * resolvers that differ only in which column they own and what they answer. Each fake is spied so a
 * test can still assert what the engine did (or didn't) ask of it.
 */
@ExtendWith(MockitoExtension.class)
class FinancialAssetServiceTest {

    /** Recognises a CoinGecko-style coin link, like the real adapter's own URL form. */
    private static final Pattern COIN_URL = Pattern.compile("/coins/([^/?#]+)");

    @Mock private FinancialAssetRepository repository;
    @Mock private PriceSnapshotRepository priceSnapshotRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountHoldingRepository accountHoldingRepository;
    @Mock private PriceService priceService;

    private FakeResolver coinGecko;
    private FakeResolver yahoo;
    private FakeResolver coinMarketCap;
    private FinancialAssetService service;

    @BeforeEach
    void setUp() {
        coinGecko = spy(new FakeResolver("coingecko",
            FinancialAsset::getCoingeckoId, FinancialAsset::setCoingeckoId, COIN_URL));
        coinMarketCap = spy(new FakeResolver("coinmarketcap",
            FinancialAsset::getCoinmarketcapId, FinancialAsset::setCoinmarketcapId, null));
        yahoo = spy(new FakeResolver("yahoo",
            FinancialAsset::getYahooSymbol, FinancialAsset::setYahooSymbol, null));
        service = new FinancialAssetService(
            List.of(coinGecko, coinMarketCap, yahoo),
            repository, priceSnapshotRepository, transactionRepository, accountHoldingRepository,
            priceService);
    }

    /**
     * A stand-in aggregator. It really reads and writes its own ref column (that's the behaviour the
     * engine leans on) and answers lookups with whatever the test stubs — no aggregator is special.
     */
    static class FakeResolver implements AssetResolverPort {
        private final String key;
        private final Function<FinancialAsset, String> getter;
        private final BiConsumer<FinancialAsset, String> setter;
        private final Pattern urlPattern;   // null = this aggregator has no link form
        boolean resolutionAvailable = true;

        FakeResolver(String key, Function<FinancialAsset, String> getter,
                     BiConsumer<FinancialAsset, String> setter, Pattern urlPattern) {
            this.key = key;
            this.getter = getter;
            this.setter = setter;
            this.urlPattern = urlPattern;
        }

        @Override public String aggregatorKey() { return key; }
        @Override public boolean isResolutionAvailable() { return resolutionAvailable; }
        @Override public List<AssetCandidate> searchBySymbol(String symbol) { return List.of(); }
        @Override public Optional<AssetCandidate> fetchById(String id) { return Optional.empty(); }
        @Override public String getRef(FinancialAsset asset) { return getter.apply(asset); }
        @Override public void setRef(FinancialAsset asset, String id) { setter.accept(asset, id); }

        @Override
        public Optional<String> extractIdFromUrl(String url) {
            if (urlPattern == null) return Optional.empty();
            Matcher m = urlPattern.matcher(url);
            return m.find() ? Optional.of(m.group(1)) : Optional.empty();
        }
    }

    private static AssetCandidate coin(String id, String symbol, Integer rank) {
        return new AssetCandidate(id, id, symbol, rank);
    }

    /** One aggregator's block in a preview. */
    private static FinancialAssetService.AggregatorResolution block(
        FinancialAssetService.AssetResolutionPreview preview, String aggregatorKey) {
        return preview.aggregators().stream()
            .filter(a -> a.aggregatorKey().equals(aggregatorKey))
            .findFirst().orElseThrow(() ->
                new AssertionError("no block for " + aggregatorKey + " in " + preview.aggregators()));
    }

    private void expectSaveEcho() {
        when(repository.save(any(FinancialAsset.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void clearMapping_revertsToPendingAndPurgesHistoryKeepingTheRow() {
        FinancialAsset mapped = FinancialAsset.builder()
            .symbol("BTC").coingeckoId("bitcoin").name("Bitcoin")
            .type(AssetType.CRYPTO).status(AssetStatus.USER).build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(mapped));
        expectSaveEcho();

        FinancialAsset result = service.clearMapping("btc");

        assertThat(result.getStatus()).isEqualTo(AssetStatus.PENDING);
        assertThat(result.getCoingeckoId()).isNull();
        assertThat(result.getName()).isNull();
        verify(priceSnapshotRepository).deleteByAssetId(any());
        verify(priceService).evictFromCache("BTC");
        verify(repository, never()).delete(any());
    }

    @Test
    void clearMapping_dropsEveryAggregatorsRefNotJustTheFirst() {
        // Un-linking has to leave nothing behind: a ref left on a second aggregator would keep the
        // symbol silently priced by it after the operator asked to forget the link.
        FinancialAsset mapped = FinancialAsset.builder()
            .symbol("BTC").coingeckoId("bitcoin").coinmarketcapId("1").yahooSymbol("BTC-EUR")
            .name("Bitcoin").type(AssetType.CRYPTO).status(AssetStatus.USER).build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(mapped));
        expectSaveEcho();

        FinancialAsset result = service.clearMapping("BTC");

        assertThat(result.getCoingeckoId()).isNull();
        assertThat(result.getCoinmarketcapId()).isNull();
        assertThat(result.getYahooSymbol()).isNull();
        assertThat(result.getStatus()).isEqualTo(AssetStatus.PENDING);
    }

    @Test
    void delete_refusesWhenSymbolIsStillHeld() {
        FinancialAsset asset = FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(asset));
        when(accountHoldingRepository.findByAsset_Id(any()))
            .thenReturn(List.of(mock(AccountHolding.class)));

        assertThatThrownBy(() -> service.delete("btc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("still held");

        verify(repository, never()).delete(any());
        verify(priceSnapshotRepository, never()).deleteByAssetId(any());
    }

    @Test
    void delete_removesOrphanAssetAndPurgesHistory() {
        FinancialAsset asset = FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(asset));
        when(accountHoldingRepository.findByAsset_Id(any())).thenReturn(List.of());

        service.delete("btc");

        verify(repository).delete(asset);
        verify(priceSnapshotRepository).deleteByAssetId(any());
        verify(priceService).evictFromCache("BTC");
    }

    @Test
    void returnsRegisteredAssetWithoutSearching() {
        FinancialAsset registered = FinancialAsset.builder()
            .symbol("BTC").coingeckoId("bitcoin").status(AssetStatus.AUTO).build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(registered));

        Optional<FinancialAsset> result = service.resolveCrypto("btc");

        assertThat(result).contains(registered);
        verifyNoInteractions(coinGecko);
        verify(repository, never()).save(any());
    }

    @Test
    void resolvesAndPersistsSingleSymbolMatch() {
        when(repository.findBySymbol("SOL")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("SOL")).thenReturn(List.of(coin("solana", "sol", 5)));
        expectSaveEcho();

        Optional<FinancialAsset> result = service.resolveCrypto("SOL");

        assertThat(result).isPresent();
        assertThat(result.get().getCoingeckoId()).isEqualTo("solana");
        assertThat(result.get().getStatus()).isEqualTo(AssetStatus.AUTO);
        assertThat(result.get().getType()).isEqualTo(AssetType.CRYPTO);
        verify(repository).save(any(FinancialAsset.class));
    }

    @Test
    void autoResolutionOnlyAsksTheCryptoDiscoveryAggregator() {
        // Discovery-time resolution is a single-aggregator shortcut on a context-guaranteed symbol,
        // not the multi-aggregator preview: it must not fan out a search to every aggregator on
        // every imported coin.
        when(repository.findBySymbol("SOL")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("SOL")).thenReturn(List.of(coin("solana", "sol", 5)));
        expectSaveEcho();

        service.resolveCrypto("SOL");

        verify(coinMarketCap, never()).searchBySymbol(anyString());
        verify(yahoo, never()).searchBySymbol(anyString());
    }

    @Test
    void resolvesDominantMatchByMarketCapRank() {
        // Two coins share the symbol MATIC; the #12 vastly outranks the #900 (12*5=60 <= 900).
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("MATIC")).thenReturn(List.of(
            coin("matic-network", "matic", 12),
            coin("some-clone", "matic", 900)));
        expectSaveEcho();

        Optional<FinancialAsset> result = service.resolveCrypto("MATIC");

        assertThat(result).isPresent();
        assertThat(result.get().getCoingeckoId()).isEqualTo("matic-network");
    }

    @Test
    void keepsAPendingRowWhenRanksAreComparable() {
        // #10 and #14 are comparable (10*5=50 > 14) → don't guess; keep a PENDING row so the
        // symbol shows up in the management UI and is retried later.
        when(repository.findBySymbol("AAA")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("AAA")).thenReturn(List.of(
            coin("alpha", "aaa", 10),
            coin("beta", "aaa", 14)));

        Optional<FinancialAsset> result = service.resolveCrypto("AAA");

        assertThat(result).isEmpty();
        verify(repository).save(argThat(a ->
            a.getSymbol().equals("AAA")
                && a.getStatus() == AssetStatus.PENDING
                && a.getType() == AssetType.CRYPTO
                && a.getCoingeckoId() == null));
    }

    @Test
    void keepsAPendingRowWhenNoSymbolMatches() {
        when(repository.findBySymbol("ZZZ")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("ZZZ")).thenReturn(List.of());

        Optional<FinancialAsset> result = service.resolveCrypto("ZZZ");

        assertThat(result).isEmpty();
        verify(repository).save(argThat(a ->
            a.getSymbol().equals("ZZZ") && a.getStatus() == AssetStatus.PENDING));
    }

    @Test
    void keepsAPendingRowWhenMultipleMatchesButNoneRanked() {
        when(repository.findBySymbol("NUL")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("NUL")).thenReturn(List.of(
            coin("nul-one", "nul", null),
            coin("nul-two", "nul", null)));

        Optional<FinancialAsset> result = service.resolveCrypto("NUL");

        assertThat(result).isEmpty();
        verify(repository).save(argThat(a -> a.getStatus() == AssetStatus.PENDING));
    }

    @Test
    void keepsAPendingRowWhenTheOnlyMatchIsUnranked() {
        // A rank is the only evidence a symbol collision has an obvious winner. Without one we don't
        // auto-resolve even a lone match — it stays PENDING and the preview asks the operator. This
        // is also what keeps an aggregator that never ranks (Yahoo) from auto-suggesting anything.
        when(repository.findBySymbol("SOLO")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("SOLO")).thenReturn(List.of(coin("solo-coin", "solo", null)));

        Optional<FinancialAsset> result = service.resolveCrypto("SOLO");

        assertThat(result).isEmpty();
        verify(repository).save(argThat(a ->
            a.getStatus() == AssetStatus.PENDING && a.getCoingeckoId() == null));
    }

    @Test
    void pendingAssetIsRetriedAndUpgradedOnNextResolve() {
        FinancialAsset pending = FinancialAsset.builder()
            .symbol("AAA").type(AssetType.CRYPTO).status(AssetStatus.PENDING).build();
        when(repository.findBySymbol("AAA")).thenReturn(Optional.of(pending));
        when(coinGecko.searchBySymbol("AAA")).thenReturn(List.of(coin("alpha", "aaa", 10)));
        expectSaveEcho();

        Optional<FinancialAsset> result = service.resolveCrypto("AAA");

        // The existing PENDING row is upgraded in place — no duplicate row for the symbol.
        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(pending);
        assertThat(pending.getCoingeckoId()).isEqualTo("alpha");
        assertThat(pending.getStatus()).isEqualTo(AssetStatus.AUTO);
    }

    @Test
    void pendingAssetIsNotDuplicatedWhenStillUnresolved() {
        FinancialAsset pending = FinancialAsset.builder()
            .symbol("AAA").type(AssetType.CRYPTO).status(AssetStatus.PENDING).build();
        when(repository.findBySymbol("AAA")).thenReturn(Optional.of(pending));
        when(coinGecko.searchBySymbol("AAA")).thenReturn(List.of());

        Optional<FinancialAsset> result = service.resolveCrypto("AAA");

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void resolveAllContinuesAfterAFailureAndReportsUnresolved() {
        when(repository.findBySymbol("BAD")).thenThrow(new RuntimeException("boom"));
        when(repository.findBySymbol("OK")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("OK")).thenReturn(List.of(coin("okcoin", "ok", 3)));
        expectSaveEcho();

        var unresolved = service.resolveAll(new java.util.LinkedHashSet<>(List.of("BAD", "OK")));

        // OK resolved and was persisted; BAD blew up and is reported for manual disambiguation.
        assertThat(unresolved).containsExactly("BAD");
        verify(repository).save(any(FinancialAsset.class));
        verify(coinGecko).searchBySymbol("OK");
    }

    @Test
    void previewResolutionsSurfacesCandidatesAndSkipsSettledCoinsWithoutPersisting() {
        // BTC already USER (settled) → not surfaced. META unseen → surfaced with a dominant guess.
        // AMBI is PENDING with two comparable coins → surfaced, no guess. Nothing is persisted.
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(
            FinancialAsset.builder().symbol("BTC").status(AssetStatus.USER).build()));
        when(repository.findBySymbol("META")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("META")).thenReturn(List.of(
            coin("metabeat", "meta", 300), coin("meta-inu", "meta", 5000)));
        when(repository.findBySymbol("AMBI")).thenReturn(Optional.of(
            FinancialAsset.builder().symbol("AMBI").status(AssetStatus.PENDING).build()));
        when(coinGecko.searchBySymbol("AMBI")).thenReturn(List.of(
            coin("ambi-one", "ambi", 10), coin("ambi-two", "ambi", 14)));

        var previews = service.previewResolutions(
            new java.util.LinkedHashSet<>(List.of("btc", "meta", "ambi")));

        assertThat(previews).extracting(FinancialAssetService.AssetResolutionPreview::symbol)
            .containsExactly("META", "AMBI");
        assertThat(block(previews.get(0), "coingecko").suggested().id())
            .isEqualTo("metabeat");                                          // #300 dominates #5000
        assertThat(block(previews.get(0), "coingecko").candidates()).hasSize(2);
        assertThat(previews.get(0).currentStatus()).isNull();                // never seen
        assertThat(block(previews.get(1), "coingecko").suggested()).isNull(); // #10 vs #14 comparable
        assertThat(previews.get(1).currentStatus()).isEqualTo(AssetStatus.PENDING);
        verify(repository, never()).save(any());
    }

    @Test
    void previewOffersOneBlockPerAggregatorEachWithItsOwnCandidatesAndGuess() {
        // The whole point of the preview: every aggregator gets to offer its own id for the symbol,
        // so the operator can map the coin on more than one and the price survives one being down.
        when(repository.findBySymbol("BTC")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("BTC")).thenReturn(List.of(coin("bitcoin", "btc", 1)));
        when(coinMarketCap.searchBySymbol("BTC")).thenReturn(List.of(coin("1", "btc", 1)));

        var preview = service.previewResolution("btc");

        assertThat(preview.aggregators()).extracting(
                FinancialAssetService.AggregatorResolution::aggregatorKey)
            .containsExactly("coingecko", "coinmarketcap", "yahoo");
        assertThat(block(preview, "coingecko").suggested().id()).isEqualTo("bitcoin");
        assertThat(block(preview, "coinmarketcap").suggested().id()).isEqualTo("1");
        // Yahoo knows nothing about this symbol → empty block, its ref stays null, it won't price it.
        assertThat(block(preview, "yahoo").candidates()).isEmpty();
        assertThat(block(preview, "yahoo").suggested()).isNull();
    }

    @Test
    void previewOmitsAnAggregatorThatCannotResolveRightNow() {
        // CoinMarketCap has no anonymous tier: with no key it can't search at all, so it isn't
        // offered rather than shown as an empty picker.
        coinMarketCap.resolutionAvailable = false;
        when(repository.findBySymbol("BTC")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("BTC")).thenReturn(List.of(coin("bitcoin", "btc", 1)));

        var preview = service.previewResolution("BTC");

        assertThat(preview.aggregators()).extracting(
                FinancialAssetService.AggregatorResolution::aggregatorKey)
            .containsExactly("coingecko", "yahoo");
        verify(coinMarketCap, never()).searchBySymbol(anyString());
    }

    @Test
    void previewNeverSuggestsAnUnrankedCandidate() {
        // Yahoo's "candidates" are the same security on different exchanges and carry no rank —
        // pre-selecting one would silently quote the wrong market.
        when(repository.findBySymbol("IWDA")).thenReturn(Optional.empty());
        when(yahoo.searchBySymbol("IWDA")).thenReturn(List.of(
            new AssetCandidate("IWDA.AS", "iShares Core MSCI World", "IWDA.AS", null),
            new AssetCandidate("IWDA.L", "iShares Core MSCI World", "IWDA.L", null)));

        var preview = service.previewResolution("iwda");

        assertThat(block(preview, "yahoo").candidates()).hasSize(2);
        assertThat(block(preview, "yahoo").suggested()).isNull();
    }

    @Test
    void previewResolutionsDegradesToNoCandidatesWhenTheSearchFails() {
        when(repository.findBySymbol("BOOM")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("BOOM")).thenThrow(new RuntimeException("rate limit"));
        when(coinMarketCap.searchBySymbol("BOOM")).thenReturn(List.of(coin("42", "boom", 900)));

        var previews = service.previewResolutions(new java.util.LinkedHashSet<>(List.of("boom")));

        // One aggregator blowing up must not cost the operator the others' offers.
        assertThat(previews).hasSize(1);
        assertThat(previews.get(0).symbol()).isEqualTo("BOOM");
        assertThat(block(previews.get(0), "coingecko").candidates()).isEmpty();
        assertThat(block(previews.get(0), "coingecko").suggested()).isNull();
        assertThat(block(previews.get(0), "coinmarketcap").suggested().id()).isEqualTo("42");
    }

    @Test
    void applyUserMappingPinsACandidateIdAsUserWithoutCallingTheAggregator() {
        // The import path pins a coin the operator picked from preview candidates — the id/name are
        // trusted, so no extra lookup round-trip.
        when(repository.findBySymbol("META")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.applyUserMapping("meta", "metabeat", "MetaBeat");

        assertThat(result.getSymbol()).isEqualTo("META");
        assertThat(result.getCoingeckoId()).isEqualTo("metabeat");
        assertThat(result.getName()).isEqualTo("MetaBeat");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
        assertThat(result.getType()).isEqualTo(AssetType.CRYPTO);
        verify(coinGecko, never()).fetchById(anyString());
    }

    @Test
    void applyMappingsWritesEachAggregatorsIdIntoItsOwnColumn() {
        // The multi-aggregator confirm: one coin, one id per aggregator, each landing in the column
        // its own adapter owns — this is what gives the asset a price fallback.
        when(repository.findBySymbol("BTC")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.applyMappings("btc",
            Map.of("coingecko", "bitcoin", "coinmarketcap", "1"), "Bitcoin");

        assertThat(result.getCoingeckoId()).isEqualTo("bitcoin");
        assertThat(result.getCoinmarketcapId()).isEqualTo("1");
        assertThat(result.getYahooSymbol()).isNull();          // not picked → stays unable to price it
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
    }

    @Test
    void applyMappingsIgnoresAnUnknownAggregatorRatherThanFailingTheImport() {
        when(repository.findBySymbol("BTC")).thenReturn(Optional.empty());
        expectSaveEcho();

        Map<String, String> ids = new java.util.LinkedHashMap<>();
        ids.put("coingecko", "bitcoin");
        ids.put("retired-aggregator", "whatever");   // a client that outlived an aggregator

        FinancialAsset result = service.applyMappings("BTC", ids, "Bitcoin");

        assertThat(result.getCoingeckoId()).isEqualTo("bitcoin");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
    }

    @Test
    void applyMappingsKeepsAnExistingNameWhenTheNewMappingCarriesNone() {
        // Re-mapping from a source without a name (a Yahoo candidate has no longname) must not wipe
        // the label the coin already had. The operator can still rename it via the editable field.
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("BTC").coingeckoId("bitcoin").name("Bitcoin")
            .type(AssetType.CRYPTO).status(AssetStatus.USER).build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        FinancialAsset result = service.applyMappings("BTC", Map.of("yahoo", "BTC-EUR"), null);

        assertThat(result.getName()).isEqualTo("Bitcoin");            // not nulled
        assertThat(result.getYahooSymbol()).isEqualTo("BTC-EUR");     // the new ref still applied
    }

    @Test
    void applyMappingsTrimsAndAppliesASuppliedName() {
        when(repository.findBySymbol("BTC")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.applyMappings("BTC", Map.of("coingecko", "bitcoin"), "  Bitcoin  ");

        assertThat(result.getName()).isEqualTo("Bitcoin");
    }

    @Test
    void applyMappingsRejectsAMappingThatWouldWriteNothing() {
        assertThatThrownBy(() -> service.applyMappings("BTC", Map.of("nobody", "x"), "Bitcoin"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void applyMappingsFillingAnEmptyRefKeepsThePriceHistory() {
        // Adding CoinMarketCap alongside a working CoinGecko id doesn't invalidate anything already
        // priced — nothing was ever fetched under a wrong id.
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("BTC").coingeckoId("bitcoin").type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        FinancialAsset result = service.applyMappings("BTC",
            Map.of("coingecko", "bitcoin", "coinmarketcap", "1"), "Bitcoin");

        assertThat(result.getCoinmarketcapId()).isEqualTo("1");
        verifyNoInteractions(priceSnapshotRepository, priceService);
    }

    @Test
    void applyMappingsReplacingAnExistingIdPurgesAndRefetches() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("META").coingeckoId("wrong-meta")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(repository.findBySymbol("META")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        service.applyMappings("META", Map.of("coingecko", "metabeat"), "MetaBeat");

        verify(priceSnapshotRepository).deleteByAssetId(any());
        verify(priceService).evictFromCache("META");
        verify(priceService).backfillHistoricalPrices(anyMap());
    }

    @Test
    void applyUserMappingPurgesHistoryWhenItChangesAnExistingCoinId() {
        FinancialAsset existing = FinancialAsset.builder().symbol("META").coingeckoId("wrong-meta")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(repository.findBySymbol("META")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        service.applyUserMapping("META", "metabeat", "MetaBeat");

        verify(priceSnapshotRepository).deleteByAssetId(any());
        verify(priceService).evictFromCache("META");
        verify(priceService).backfillHistoricalPrices(anyMap());
    }

    @Test
    void setManualMappingExtractsIdFromLinkAndPersistsAsUser() {
        when(coinGecko.fetchById("loaded-lions"))
            .thenReturn(Optional.of(new AssetCandidate("loaded-lions", "Loaded Lions", "lion", 3500)));
        when(repository.findBySymbol("LION")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("lion",
            "https://www.coingecko.com/en/coins/loaded-lions");

        assertThat(result.getSymbol()).isEqualTo("LION");
        assertThat(result.getCoingeckoId()).isEqualTo("loaded-lions");
        assertThat(result.getName()).isEqualTo("Loaded Lions");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
        assertThat(result.getType()).isEqualTo(AssetType.CRYPTO);
    }

    @Test
    void resolveLink_returnsTheClaimingAggregatorAndItsValidatedCandidate() {
        // The import wizard's per-coin link field goes through this without applying anything yet —
        // the same routing setManualMapping uses, exposed for callers that merge the id themselves.
        when(coinGecko.fetchById("loaded-lions"))
            .thenReturn(Optional.of(new AssetCandidate("loaded-lions", "Loaded Lions", "lion", 3500)));

        var link = service.resolveLink("https://www.coingecko.com/en/coins/loaded-lions");

        assertThat(link.aggregatorKey()).isEqualTo("coingecko");
        assertThat(link.candidate().id()).isEqualTo("loaded-lions");
        assertThat(link.candidate().name()).isEqualTo("Loaded Lions");
        verify(repository, never()).save(any());   // resolves only — nothing persisted
    }

    @Test
    void resolveLink_rejectsALinkNoAggregatorClaims_andAnUnknownId() {
        assertThatThrownBy(() -> service.resolveLink("https://example.com/whatever"))
            .isInstanceOf(IllegalArgumentException.class);

        when(coinGecko.fetchById("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveLink("https://www.coingecko.com/en/coins/ghost"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setManualMappingRoutesTheLinkToTheAggregatorThatRecognisesIt() {
        // The engine doesn't parse links itself — each aggregator claims its own URL form, and the
        // id lands in that aggregator's column. Ones with no link form simply don't claim it.
        when(coinGecko.fetchById("loaded-lions"))
            .thenReturn(Optional.of(new AssetCandidate("loaded-lions", "Loaded Lions", "lion", 3500)));
        when(repository.findBySymbol("LION")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("LION",
            "https://www.coingecko.com/en/coins/loaded-lions");

        assertThat(result.getCoingeckoId()).isEqualTo("loaded-lions");
        assertThat(result.getCoinmarketcapId()).isNull();
        verify(coinMarketCap, never()).fetchById(anyString());
        verify(yahoo, never()).fetchById(anyString());
    }

    @Test
    void setManualMappingReadsSlugFromLocalizedUrlWithQuery() {
        when(coinGecko.fetchById("capybara-nation"))
            .thenReturn(Optional.of(new AssetCandidate("capybara-nation", "Capybara Nation", "bara", null)));
        when(repository.findBySymbol("BARA")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("BARA",
            "https://www.coingecko.com/fr/coins/capybara-nation?utm=share#markets");

        assertThat(result.getCoingeckoId()).isEqualTo("capybara-nation");
        verify(coinGecko).fetchById("capybara-nation");
    }

    @Test
    void setManualMappingOverridesAnExistingAutoMapping() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("MATIC").coingeckoId("wrong-clone").name("Clone")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(coinGecko.fetchById("matic-network"))
            .thenReturn(Optional.of(new AssetCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("MATIC",
            "https://www.coingecko.com/en/coins/matic-network");

        assertThat(result.getCoingeckoId()).isEqualTo("matic-network");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
    }

    @Test
    void remappingToADifferentCoinPurgesAndRefetchesTheTickersPriceHistory() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("MATIC").coingeckoId("wrong-clone")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(coinGecko.fetchById("matic-network"))
            .thenReturn(Optional.of(new AssetCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        service.setManualMapping("MATIC", "https://www.coingecko.com/en/coins/matic-network");

        // Everything priced under the old id is wrong → purged, evicted, and refetched.
        verify(priceSnapshotRepository).deleteByAssetId(any());
        verify(priceService).evictFromCache("MATIC");
        verify(priceService).backfillHistoricalPrices(anyMap());
    }

    @Test
    void remappingToTheSameCoinKeepsThePriceHistory() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("MATIC").coingeckoId("matic-network")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(coinGecko.fetchById("matic-network"))
            .thenReturn(Optional.of(new AssetCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("MATIC",
            "https://www.coingecko.com/en/coins/matic-network");

        // Same coin — history was fetched from the right source, only the provenance changes.
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
        verifyNoInteractions(priceSnapshotRepository, priceService);
    }

    @Test
    void firstManualMappingDoesNotPurgeAnything() {
        when(coinGecko.fetchById("loaded-lions"))
            .thenReturn(Optional.of(new AssetCandidate("loaded-lions", "Loaded Lions", "lion", 3500)));
        when(repository.findBySymbol("LION")).thenReturn(Optional.empty());
        expectSaveEcho();

        service.setManualMapping("LION", "https://www.coingecko.com/en/coins/loaded-lions");

        verifyNoInteractions(priceSnapshotRepository, priceService);
    }

    @Test
    void remappingSurvivesABackfillFailure() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("MATIC").coingeckoId("wrong-clone")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(coinGecko.fetchById("matic-network"))
            .thenReturn(Optional.of(new AssetCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.of(existing));
        when(priceService.backfillHistoricalPrices(anyMap())).thenThrow(new RuntimeException("rate limit"));
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("MATIC",
            "https://www.coingecko.com/en/coins/matic-network");

        // The mapping is corrected even if the refetch fails — the boot runner fills the gap later.
        assertThat(result.getCoingeckoId()).isEqualTo("matic-network");
        verify(priceSnapshotRepository).deleteByAssetId(any());
    }

    @Test
    void deleteForgetsTheAssetAndPurgesItsPriceHistory() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("LION").coingeckoId("loaded-lions")
            .type(AssetType.CRYPTO).status(AssetStatus.USER).build();
        when(repository.findBySymbol("LION")).thenReturn(Optional.of(existing));

        service.delete("lion");

        verify(repository).delete(existing);
        verify(priceSnapshotRepository).deleteByAssetId(any());
        verify(priceService).evictFromCache("LION");
    }

    @Test
    void deleteRejectsAnUnknownTicker() {
        when(repository.findBySymbol("ZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("zzz"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).delete(any(FinancialAsset.class));
        verifyNoInteractions(priceSnapshotRepository);
    }

    @Test
    void markWorthlessPinsAZeroValueAndPurgesTheTickersPrices() {
        when(repository.findBySymbol("METABEAT")).thenReturn(Optional.empty());
        when(accountHoldingRepository.findByAsset_Id(any())).thenReturn(List.of());
        expectSaveEcho();

        FinancialAsset result = service.markWorthless("metabeat");

        assertThat(result.getSymbol()).isEqualTo("METABEAT");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.WORTHLESS);
        assertThat(result.getCoingeckoId()).isNull();
        assertThat(result.isWorthless()).isTrue();
        // Any price fetched while it was still listed is dropped, live cache evicted, holdings re-valued.
        verify(priceSnapshotRepository).deleteByAssetId(any());
        verify(priceService).evictFromCache("METABEAT");
        verify(accountHoldingRepository).findByAsset_Id(any());
        verify(coinGecko, never()).fetchById(anyString());
    }

    @Test
    void markWorthlessZeroesEveryHoldingOfTheTicker() {
        var holdingA = com.picsou.model.AccountHolding.builder()
            .asset(FinancialAsset.builder().symbol("METABEAT").build())
            .quantity(new java.math.BigDecimal("1000"))
            .currentPrice(new java.math.BigDecimal("0.12")).build();
        var holdingB = com.picsou.model.AccountHolding.builder()
            .asset(FinancialAsset.builder().symbol("METABEAT").build())
            .quantity(new java.math.BigDecimal("50")).build();
        when(repository.findBySymbol("METABEAT")).thenReturn(Optional.empty());
        when(accountHoldingRepository.findByAsset_Id(any()))
            .thenReturn(List.of(holdingA, holdingB));
        expectSaveEcho();

        service.markWorthless("METABEAT");

        assertThat(holdingA.getCurrentPrice()).isEqualByComparingTo("0");
        assertThat(holdingB.getCurrentPrice()).isEqualByComparingTo("0");
        verify(accountHoldingRepository).saveAll(List.of(holdingA, holdingB));
    }

    @Test
    void markWorthlessOverExistingAssetClearsEveryAggregatorRef() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("METABEAT").coingeckoId("metabeat").coinmarketcapId("9999").name("MetaBeat")
            .type(AssetType.CRYPTO).status(AssetStatus.USER).build();
        when(repository.findBySymbol("METABEAT")).thenReturn(Optional.of(existing));
        when(accountHoldingRepository.findByAsset_Id(any())).thenReturn(List.of());
        expectSaveEcho();

        FinancialAsset result = service.markWorthless("METABEAT");

        // Worthless means no aggregator prices it — a ref left behind would keep one quoting it.
        assertThat(result.getStatus()).isEqualTo(AssetStatus.WORTHLESS);
        assertThat(result.getCoingeckoId()).isNull();
        assertThat(result.getCoinmarketcapId()).isNull();
        assertThat(result.getName()).isNull();
    }

    @Test
    void reMappingAWorthlessTickerRestoresPricing() {
        FinancialAsset worthless = FinancialAsset.builder()
            .symbol("METABEAT").coingeckoId(null)
            .type(AssetType.CRYPTO).status(AssetStatus.WORTHLESS).build();
        when(coinGecko.fetchById("metabeat"))
            .thenReturn(Optional.of(new AssetCandidate("metabeat", "MetaBeat", "metabeat", 4200)));
        when(repository.findBySymbol("METABEAT")).thenReturn(Optional.of(worthless));
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("METABEAT",
            "https://www.coingecko.com/en/coins/metabeat");

        // Was worthless (no id) → now a real USER mapping; no purge since there was no old coin id.
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
        assertThat(result.getCoingeckoId()).isEqualTo("metabeat");
        verify(priceSnapshotRepository, never()).deleteByAssetId(any());
    }

    @Test
    void setManualMappingRejectsALinkNoAggregatorRecognises() {
        assertThatThrownBy(() -> service.setManualMapping("BTC", "https://www.coingecko.com/en/categories"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(coinGecko, never()).fetchById(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void setManualMappingRejectsAnUnknownCoinId() {
        when(coinGecko.fetchById("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setManualMapping("XYZ",
            "https://www.coingecko.com/en/coins/does-not-exist"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void getOrCreateReturnsExistingAssetWithoutSaving() {
        FinancialAsset existing = FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(existing));

        FinancialAsset result = service.getOrCreate("btc");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void getOrCreateMintsABarePendingAssetWhenSymbolIsUnseen() {
        when(repository.findBySymbol("SHIB")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.getOrCreate("shib");

        assertThat(result.getSymbol()).isEqualTo("SHIB");
        assertThat(result.getType()).isEqualTo(AssetType.UNKNOWN);
        assertThat(result.getStatus()).isEqualTo(AssetStatus.PENDING);
        assertThat(result.getName()).isNull();
    }

    @Test
    void getOrCreateStockMintsAStockWithYahooSymbolWhenSymbolIsUnseen() {
        when(repository.findBySymbol("IWDA.AS")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.getOrCreateStock("iwda.as");

        assertThat(result.getSymbol()).isEqualTo("IWDA.AS");
        assertThat(result.getType()).isEqualTo(AssetType.STOCK);
        assertThat(result.getStatus()).isEqualTo(AssetStatus.PENDING);
        assertThat(result.getYahooSymbol()).isEqualTo("IWDA.AS");
    }

    @Test
    void getOrCreateStockFillsInMissingYahooSymbolAndUpgradesUnknownType() {
        // A row minted earlier by the generic getOrCreate() (e.g. a prior recompute) has no
        // yahoo_symbol yet; a later OpenFIGI-backed discovery of the same ticker fills it in.
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("RKLB").type(AssetType.UNKNOWN).status(AssetStatus.PENDING).build();
        when(repository.findBySymbol("RKLB")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        FinancialAsset result = service.getOrCreateStock("RKLB");

        assertThat(result.getType()).isEqualTo(AssetType.STOCK);
        assertThat(result.getYahooSymbol()).isEqualTo("RKLB");
        verify(repository).save(existing);
    }

    @Test
    void getOrCreateStockNeverTouchesAnExistingCryptoRow() {
        // A stock-context ticker colliding with an already-resolved crypto symbol must not
        // clobber the working coin mapping.
        FinancialAsset crypto = FinancialAsset.builder()
            .symbol("SOL").coingeckoId("solana").type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(repository.findBySymbol("SOL")).thenReturn(Optional.of(crypto));

        FinancialAsset result = service.getOrCreateStock("SOL");

        assertThat(result).isSameAs(crypto);
        assertThat(result.getYahooSymbol()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void getOrCreateStockReturnsExistingAssetWithoutSavingWhenAlreadyComplete() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("RKLB").type(AssetType.STOCK).status(AssetStatus.PENDING).yahooSymbol("RKLB").build();
        when(repository.findBySymbol("RKLB")).thenReturn(Optional.of(existing));

        FinancialAsset result = service.getOrCreateStock("RKLB");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void fillNameIfAbsentSetsTheNameWhenAssetHasNone() {
        FinancialAsset asset = FinancialAsset.builder().symbol("IWDA.AS").build();
        expectSaveEcho();

        service.fillNameIfAbsent(asset, "iShares Core MSCI World UCITS ETF");

        assertThat(asset.getName()).isEqualTo("iShares Core MSCI World UCITS ETF");
        verify(repository).save(asset);
    }

    @Test
    void fillNameIfAbsentNeverOverwritesAnExistingName() {
        // A canonical name (an aggregator's, a prior sync's) must survive a later, possibly worse label.
        FinancialAsset asset = FinancialAsset.builder().symbol("BTC").name("Bitcoin").build();

        service.fillNameIfAbsent(asset, "some broker's label for BTC");

        assertThat(asset.getName()).isEqualTo("Bitcoin");
        verify(repository, never()).save(any());
    }

    @Test
    void fillNameIfAbsentIgnoresNullOrBlankNames() {
        FinancialAsset asset = FinancialAsset.builder().symbol("VWCE.DE").build();

        service.fillNameIfAbsent(asset, null);
        service.fillNameIfAbsent(asset, "   ");

        assertThat(asset.getName()).isNull();
        verify(repository, never()).save(any());
    }
}
