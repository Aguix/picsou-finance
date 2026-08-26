package com.picsou.service;

import com.picsou.model.FinancialAsset;
import com.picsou.model.PriceSnapshot;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock PriceRouter priceRouter;
    @Mock PriceSnapshotRepository priceSnapshotRepository;
    @Mock FinancialAssetRepository assetRepository;

    @InjectMocks PriceService service;

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<FinancialAsset>> assetCollectionCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    @Test
    void getPriceEur_unregisteredTicker_ridesTransientAssetCarryingYahooSymbolSoYahooStillPricesIt() {
        // The MCP get_price tool (and the currency seam) hand a bare ticker with no registry row.
        // Since canPrice now gates on yahoo_symbol, the transient asset must carry it or Yahoo would
        // reject an explicitly-requested ticker — the regression this locks down.
        when(assetRepository.findBySymbol("AAPL")).thenReturn(Optional.empty());
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of("AAPL", new BigDecimal("192.50")));

        BigDecimal price = service.getPriceEur("AAPL");

        assertThat(price).isEqualByComparingTo("192.50");

        ArgumentCaptor<Collection<FinancialAsset>> captor = assetCollectionCaptor();
        org.mockito.Mockito.verify(priceRouter).getPricesEur(captor.capture());
        FinancialAsset routed = captor.getValue().iterator().next();
        assertThat(routed.getSymbol()).isEqualTo("AAPL");
        assertThat(routed.getYahooSymbol()).isEqualTo("AAPL"); // verbatim, as before the gate
    }

    @Test
    void getPriceEur_registeredAssetWithoutYahooSymbol_isRoutedAsIsAndStaysGated() {
        // A registry row is deliberately NOT given a yahoo_symbol by this seam — it's only priceable
        // once resolved, so an unresolved coin doesn't waste a Yahoo call. Contrast with the transient
        // path above.
        FinancialAsset registered = FinancialAsset.builder().symbol("SHIB").build();
        when(assetRepository.findBySymbol("SHIB")).thenReturn(Optional.of(registered));
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of());

        BigDecimal price = service.getPriceEur("SHIB");

        assertThat(price).isNull();
        ArgumentCaptor<Collection<FinancialAsset>> captor = assetCollectionCaptor();
        org.mockito.Mockito.verify(priceRouter).getPricesEur(captor.capture());
        assertThat(captor.getValue().iterator().next().getYahooSymbol()).isNull();
    }

    @Test
    void getPriceEur_persistsLastPriceByAssetId_forARegisteredAsset() {
        FinancialAsset registered = FinancialAsset.builder().id(42L).symbol("BTC")
            .coingeckoId("bitcoin").build();
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of("BTC", new BigDecimal("84500")));

        BigDecimal price = service.getPriceEur(registered);

        assertThat(price).isEqualByComparingTo("84500");
        org.mockito.Mockito.verify(assetRepository)
            .updateLastPrice(org.mockito.ArgumentMatchers.eq(42L), any(), any());
    }

    @Test
    void getPriceEur_transientAsset_neverWritesLastPrice() {
        // A bare currency code / unregistered MCP ticker has no registry row, hence no id — there is
        // nothing to persist the price to, so the update must be skipped, not fired at a null id.
        when(assetRepository.findBySymbol("USD")).thenReturn(Optional.empty());
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of("USD", new BigDecimal("0.92")));

        BigDecimal price = service.getPriceEur("USD");

        assertThat(price).isEqualByComparingTo("0.92");
        org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never())
            .updateLastPrice(any(), any(), any());
    }

    @Test
    void getPriceEur_eurOrBlank_returnsOneWithoutTouchingProviders() {
        assertThat(service.getPriceEur("EUR")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(service.getPriceEur("eur")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(service.getPriceEur((String) null)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(service.getPriceEur("  ")).isEqualByComparingTo(BigDecimal.ONE);
        org.mockito.Mockito.verifyNoInteractions(priceRouter);
    }

    @Test
    void getIntradayPricesEur_unregisteredTicker_ridesTransientAssetCarryingYahooSymbol() {
        when(assetRepository.findBySymbol("MC.PA")).thenReturn(Optional.empty());
        var from = java.time.LocalDateTime.of(2024, 1, 1, 0, 0);
        var to = java.time.LocalDateTime.of(2024, 1, 2, 0, 0);
        when(priceRouter.getIntradayPricesEur(any(), any(), any())).thenReturn(Map.of());

        service.getIntradayPricesEur("MC.PA", from, to);

        ArgumentCaptor<FinancialAsset> captor = ArgumentCaptor.forClass(FinancialAsset.class);
        org.mockito.Mockito.verify(priceRouter).getIntradayPricesEur(captor.capture(), any(), any());
        assertThat(captor.getValue().getYahooSymbol()).isEqualTo("MC.PA");
    }

    // ── Freshness and the last-known-price fallback ───────────────────────────

    private static FinancialAsset registered(String symbol, long id) {
        return FinancialAsset.builder().id(id).symbol(symbol).coingeckoId(symbol.toLowerCase()).build();
    }

    private static PriceSnapshot snapshot(FinancialAsset asset, String price, LocalDate date) {
        return PriceSnapshot.builder().asset(asset).date(date).priceEur(new BigDecimal(price)).build();
    }

    @Test
    void getQuotes_liveAggregatorAnswer_isQuotedAsFreshAndTodayDated() {
        FinancialAsset btc = registered("BTC", 1L);
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(btc));
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of("BTC", new BigDecimal("50000")));

        Map<String, PriceService.Quote> quotes = service.getQuotes(Set.of("BTC"));

        assertThat(quotes.get("BTC").price()).isEqualByComparingTo("50000");
        assertThat(quotes.get("BTC").live()).isTrue();
        assertThat(quotes.get("BTC").asOf()).isEqualTo(LocalDate.now());
    }

    @Test
    void getQuotes_noLivePrice_fallsBackToTheLastRecordedOne_markedStale() {
        // The point of the fallback: an outage degrades the price's *age*, not its existence, so
        // the callers that write a valuation into balance_snapshot do not engrave a hole.
        FinancialAsset btc = registered("BTC", 1L);
        LocalDate recorded = LocalDate.now().minusDays(2);
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(btc));
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByAssetIds(any(), any(), any()))
            .thenReturn(List.of(snapshot(btc, "48000", recorded)));

        Map<String, PriceService.Quote> quotes = service.getQuotes(Set.of("BTC"));

        assertThat(quotes.get("BTC").price()).isEqualByComparingTo("48000");
        assertThat(quotes.get("BTC").live()).isFalse();
        assertThat(quotes.get("BTC").asOf()).isEqualTo(recorded);
    }

    @Test
    void getQuotes_fallbackIsLookedUpByAssetId_notBySymbol() {
        // A coin and a listed equity can share a symbol. Reading the fallback by id is what stops
        // an unresolved coin from being valued at the stock's recorded price.
        FinancialAsset sui = registered("SUI", 42L);
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(sui));
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByAssetIds(any(), any(), any())).thenReturn(List.of());

        service.getQuotes(Set.of("SUI"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        org.mockito.Mockito.verify(priceSnapshotRepository)
            .findRecentByAssetIds(ids.capture(), any(), any());
        assertThat(ids.getValue()).containsExactly(42L);
    }

    @Test
    void getQuotes_worthlessAsset_isAKnownZero_withNoAggregatorCallAndNoFallback() {
        FinancialAsset dead = FinancialAsset.builder().id(7L).symbol("DEAD")
            .status(com.picsou.model.AssetStatus.WORTHLESS).build();
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(dead));

        Map<String, PriceService.Quote> quotes = service.getQuotes(Set.of("DEAD"));

        assertThat(quotes.get("DEAD").price()).isEqualByComparingTo("0");
        assertThat(quotes.get("DEAD").live()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(priceRouter);
        org.mockito.Mockito.verifyNoInteractions(priceSnapshotRepository);
    }

    @Test
    void getCryptoQuotes_unregisteredSymbol_isLeftUnpriced_ratherThanQuotedAsTheEquity() {
        // The crypto-only guard: without it the symbol would ride a transient asset carrying
        // yahoo_symbol = symbol, and an exchange coin would be valued at the share price of the
        // company trading under the same ticker -- written into the balance, with nothing to show
        // for it in the logs.
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of());

        Map<String, PriceService.Quote> quotes = service.getCryptoQuotes(Set.of("SUI"));

        assertThat(quotes).doesNotContainKey("SUI");
        // Neither an aggregator call nor a fallback lookup: with no registry row there is no id to
        // read a history by, so the symbol simply stays unpriced.
        org.mockito.Mockito.verifyNoInteractions(priceRouter);
        org.mockito.Mockito.verifyNoInteractions(priceSnapshotRepository);
    }

    @Test
    void getQuotes_sameSymbolUnregistered_stillReachesTheAggregatorThroughATransientAsset() {
        // The plain (non-crypto) variant keeps the passthrough: a caller-supplied ticker is
        // explicitly requested, so it is priced verbatim exactly as before.
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of());
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of("SUI", new BigDecimal("3")));

        assertThat(service.getQuotes(Set.of("SUI")).get("SUI").price()).isEqualByComparingTo("3");
    }

    @Test
    void refreshCryptoQuotes_recordsOnlyLivePrices_butStillValuesFromTheFallback() {
        // Re-recording a fallback under today's date would launder a stale price into a fresh one
        // and let the fallback walk itself forward for good.
        FinancialAsset btc = registered("BTC", 1L);
        LocalDate recorded = LocalDate.now().minusDays(1);
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(btc));
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByAssetIds(any(), any(), any()))
            .thenReturn(List.of(snapshot(btc, "48000", recorded)));

        Map<String, PriceService.Quote> quotes = service.refreshCryptoQuotes(Set.of("BTC"));

        assertThat(quotes.get("BTC").price()).isEqualByComparingTo("48000");
        assertThat(quotes.get("BTC").live()).isFalse();
        org.mockito.Mockito.verify(priceSnapshotRepository, org.mockito.Mockito.never())
            .save(any(PriceSnapshot.class));
    }

    @Test
    void refreshPrices_servesAStillFreshCacheEntry_insteadOfRefetchingIt() {
        // GET /prices is polled by the frontend on an interval: bypassing the TTL here would turn
        // every open dashboard tab into a steady stream of aggregator calls.
        FinancialAsset btc = registered("BTC", 1L);
        when(assetRepository.findBySymbolIn(any())).thenReturn(List.of(btc));
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of("BTC", new BigDecimal("50000")));
        when(priceSnapshotRepository.findByAssetIdAndDate(any(), any())).thenReturn(Optional.empty());

        Map<String, BigDecimal> first = service.refreshPrices(Set.of("BTC"));
        Map<String, BigDecimal> second = service.refreshPrices(Set.of("BTC"));

        assertThat(first.get("BTC")).isEqualByComparingTo("50000");
        assertThat(second.get("BTC")).isEqualByComparingTo("50000");
        // One aggregator round-trip for the two refreshes, and one snapshot write: the second pass
        // was served from the cache, and a cache-served value is not re-recorded.
        org.mockito.Mockito.verify(priceRouter, org.mockito.Mockito.times(1)).getPricesEur(any());
        org.mockito.Mockito.verify(priceSnapshotRepository, org.mockito.Mockito.times(1))
            .save(any(PriceSnapshot.class));
    }
}
