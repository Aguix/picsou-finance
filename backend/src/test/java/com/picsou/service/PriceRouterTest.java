package com.picsou.service;

import com.picsou.model.FinancialAsset;
import com.picsou.port.PriceProviderPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fallback the multi-aggregator registry exists to enable: an asset mapped on two
 * aggregators keeps its price when the first one is down. The router is driven with fake providers —
 * it must decide from the port alone (capability, {@code canPrice}, {@code isAvailable}) and never
 * from who the provider is.
 */
class PriceRouterTest {

    /** A provider that prices the assets it holds a ref for — "ref" being a symbol on its own list. */
    static class FakeProvider implements PriceProviderPort {
        private final String key;
        private final Set<String> known;
        private final BigDecimal price;
        boolean available = true;
        /** Symbols this provider canPrice but whose call fails mid-flight — absent from its answer. */
        final Set<String> failsOn = new java.util.HashSet<>();
        /** Symbols whose call <em>throws</em> instead of answering — a defect, not an outage. */
        final Set<String> throwsOn = new java.util.HashSet<>();
        final List<Collection<FinancialAsset>> batches = new ArrayList<>();

        FakeProvider(String key, Set<String> known, String price) {
            this.key = key;
            this.known = known;
            this.price = new BigDecimal(price);
        }

        @Override public String aggregatorKey() { return key; }
        @Override public Set<Capability> capabilities() { return EnumSet.of(Capability.SPOT); }
        @Override public boolean canPrice(FinancialAsset a) { return known.contains(a.getSymbol()); }
        @Override public boolean isAvailable() { return available; }

        @Override
        public Map<String, BigDecimal> getPricesEur(Collection<FinancialAsset> assets) {
            batches.add(assets);
            for (FinancialAsset a : assets) {
                if (canPrice(a) && throwsOn.contains(a.getSymbol())) {
                    throw new IllegalStateException("defect pricing " + a.getSymbol());
                }
            }
            Map<String, BigDecimal> out = new java.util.HashMap<>();
            for (FinancialAsset a : assets) {
                if (canPrice(a) && !failsOn.contains(a.getSymbol())) out.put(a.getSymbol(), price);
            }
            return out;
        }
    }

    private static FinancialAsset asset(String symbol) {
        return FinancialAsset.builder().symbol(symbol).build();
    }

    @Test
    void firstProviderThatCanPriceWins() {
        var primary = new FakeProvider("primary", Set.of("BTC"), "100");
        var fallback = new FakeProvider("fallback", Set.of("BTC"), "999");
        var router = new PriceRouter(List.of(primary, fallback));

        Map<String, BigDecimal> prices = router.getPricesEur(List.of(asset("BTC")));

        assertThat(prices.get("BTC")).isEqualByComparingTo("100");
        assertThat(fallback.batches).isEmpty();   // never consulted — the primary had it
    }

    @Test
    void anUnavailablePrimaryFallsThroughToTheNextAggregatorMappedForTheAsset() {
        // This is the payoff of mapping one coin on two aggregators: rate-limited (or switched off)
        // primary, price still served.
        var primary = new FakeProvider("primary", Set.of("BTC"), "100");
        var fallback = new FakeProvider("fallback", Set.of("BTC"), "999");
        primary.available = false;
        var router = new PriceRouter(List.of(primary, fallback));

        Map<String, BigDecimal> prices = router.getPricesEur(List.of(asset("BTC")));

        assertThat(prices.get("BTC")).isEqualByComparingTo("999");
        assertThat(primary.batches).isEmpty();
        assertThat(router.providerFor(asset("BTC"))).contains(fallback);
    }

    @Test
    void aProviderThatThrowsIsSkippedAndTheRestOfTheBatchStillGetsPriced() {
        // An adapter is expected to swallow its own upstream failures (HTTP error, timeout,
        // unreachable API) and answer empty; a throw therefore means a defect on our side. The router
        // must degrade to the next aggregator rather than let one bad adapter abort the loop and
        // strand every asset the remaining aggregators could have priced.
        var primary = new FakeProvider("primary", Set.of("BTC", "ETH"), "100");
        var fallback = new FakeProvider("fallback", Set.of("BTC", "ETH"), "999");
        primary.throwsOn.add("BTC");
        var router = new PriceRouter(List.of(primary, fallback));

        Map<String, BigDecimal> prices = router.getPricesEur(List.of(asset("BTC"), asset("ETH")));

        assertThat(prices.get("BTC")).isEqualByComparingTo("999");
        assertThat(prices.get("ETH")).isEqualByComparingTo("999");
    }

    @Test
    void aProviderThatFailsMidCallHandsItsLeftoversToTheNextAggregator() {
        // The primary is available — the pre-call gate passes — but its HTTP call fails (a 429
        // tripping mid-batch, a network error): BTC is missing from its answer. The waterfall hands
        // BTC to the fallback in the SAME refresh instead of stranding it until the next one.
        var primary = new FakeProvider("primary", Set.of("BTC"), "100");
        var fallback = new FakeProvider("fallback", Set.of("BTC"), "999");
        primary.failsOn.add("BTC");
        var router = new PriceRouter(List.of(primary, fallback));

        Map<String, BigDecimal> prices = router.getPricesEur(List.of(asset("BTC")));

        assertThat(prices.get("BTC")).isEqualByComparingTo("999");
        assertThat(primary.batches).hasSize(1);    // it was tried — the failure is post-call
        assertThat(fallback.batches).hasSize(1);
    }

    @Test
    void onlyTheFailedAssetsCarryOver_pricedOnesAreNeverReRequested() {
        // Partial failure: the primary answers for ETH but not BTC. Only BTC moves on — re-asking
        // ETH downstream would both waste quota and let a lower-priority quote overwrite a better one.
        var primary = new FakeProvider("primary", Set.of("BTC", "ETH"), "100");
        var fallback = new FakeProvider("fallback", Set.of("BTC", "ETH"), "999");
        primary.failsOn.add("BTC");
        var router = new PriceRouter(List.of(primary, fallback));

        Map<String, BigDecimal> prices = router.getPricesEur(List.of(asset("BTC"), asset("ETH")));

        assertThat(prices.get("ETH")).isEqualByComparingTo("100");   // primary's answer kept
        assertThat(prices.get("BTC")).isEqualByComparingTo("999");   // fallback filled the hole
        assertThat(fallback.batches.get(0)).extracting(FinancialAsset::getSymbol)
            .containsExactly("BTC");
    }

    @Test
    void anAssetMappedOnOnlyThePausedAggregatorGoesUnpriced() {
        // Routing can't invent a ref: the fallback has no id for SOL, so nothing quotes it. The fix
        // is mapping SOL on a second aggregator, not routing.
        var primary = new FakeProvider("primary", Set.of("SOL"), "100");
        var fallback = new FakeProvider("fallback", Set.of("BTC"), "999");
        primary.available = false;
        var router = new PriceRouter(List.of(primary, fallback));

        assertThat(router.getPricesEur(List.of(asset("SOL")))).isEmpty();
        assertThat(router.providerFor(asset("SOL"))).isEmpty();
    }

    @Test
    void eachProviderIsCalledOnceWithItsOwnShare() {
        var primary = new FakeProvider("primary", Set.of("BTC", "ETH"), "100");
        var fallback = new FakeProvider("fallback", Set.of("BTC", "DOGE"), "999");
        var router = new PriceRouter(List.of(primary, fallback));

        Map<String, BigDecimal> prices = router.getPricesEur(
            List.of(asset("BTC"), asset("ETH"), asset("DOGE")));

        assertThat(prices).containsOnlyKeys("BTC", "ETH", "DOGE");
        assertThat(prices.get("DOGE")).isEqualByComparingTo("999");   // only the fallback has DOGE
        assertThat(primary.batches).hasSize(1);                       // one batched call each
        assertThat(fallback.batches).hasSize(1);
        assertThat(primary.batches.get(0)).extracting(FinancialAsset::getSymbol)
            .containsExactly("BTC", "ETH");
        assertThat(fallback.batches.get(0)).extracting(FinancialAsset::getSymbol)
            .containsExactly("DOGE");
    }

    @Test
    void everyProviderPausedYieldsNoPricesAndNoCalls() {
        var primary = new FakeProvider("primary", Set.of("BTC"), "100");
        var fallback = new FakeProvider("fallback", Set.of("BTC"), "999");
        primary.available = false;
        fallback.available = false;
        var router = new PriceRouter(List.of(primary, fallback));

        assertThat(router.getPricesEur(List.of(asset("BTC")))).isEmpty();
        assertThat(primary.batches).isEmpty();
        assertThat(fallback.batches).isEmpty();
    }

    @Test
    void historyFallsThroughWhenTheFirstProviderComesBackEmpty() {
        // Same waterfall for the single-asset series: the first history-capable provider failing
        // mid-call (empty answer) doesn't strand the request when a second one holds a ref.
        var day = java.time.LocalDate.of(2026, 1, 1);
        var broken = new FakeProvider("broken", Set.of("BTC"), "100") {
            @Override public Set<Capability> capabilities() {
                return EnumSet.of(Capability.SPOT, Capability.HISTORY);
            }
            // Inherits the default empty getHistoricalPricesEur — an answerless call.
        };
        var working = new FakeProvider("working", Set.of("BTC"), "999") {
            @Override public Set<Capability> capabilities() {
                return EnumSet.of(Capability.SPOT, Capability.HISTORY);
            }
            @Override public Map<java.time.LocalDate, BigDecimal> getHistoricalPricesEur(
                FinancialAsset a, java.time.LocalDate from, java.time.LocalDate to) {
                return Map.of(from, new BigDecimal("42"));
            }
        };
        var router = new PriceRouter(List.of(broken, working));

        assertThat(router.getHistoricalPricesEur(asset("BTC"), day, day))
            .containsEntry(day, new BigDecimal("42"));
    }

    @Test
    void historyIsRoutedOnlyToAProviderDeclaringTheCapability() {
        // A spot-only aggregator (CoinMarketCap's free plan) must never be asked for history, even
        // when it's first in priority order and holds a ref for the asset.
        var spotOnly = new FakeProvider("spot-only", Set.of("BTC"), "100");
        var withHistory = new FakeProvider("with-history", Set.of("BTC"), "999") {
            @Override public Set<Capability> capabilities() {
                return EnumSet.of(Capability.SPOT, Capability.HISTORY);
            }
            @Override public Map<java.time.LocalDate, BigDecimal> getHistoricalPricesEur(
                FinancialAsset a, java.time.LocalDate from, java.time.LocalDate to) {
                return Map.of(from, new BigDecimal("42"));
            }
        };
        var router = new PriceRouter(List.of(spotOnly, withHistory));

        var day = java.time.LocalDate.of(2026, 1, 1);
        var history = router.getHistoricalPricesEur(asset("BTC"), day, day);

        assertThat(history).containsEntry(day, new BigDecimal("42"));
    }
}
