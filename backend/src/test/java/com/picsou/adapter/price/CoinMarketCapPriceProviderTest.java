package com.picsou.adapter.price;

import com.picsou.model.FinancialAsset;
import com.picsou.port.AssetCandidate;
import com.picsou.service.AggregatorService;
import com.picsou.service.AggregatorService.SessionCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pins what makes CoinMarketCap a usable fallback rather than a second guess at the same problem:
 * everything is addressed <b>by id</b> (never by symbol, which is exactly the ambiguity the registry
 * exists to remove), its capability is its own {@code coinmarketcap_id} and nothing else, and with no
 * API key it does nothing at all — it has no anonymous tier, so it can't quietly half-work.
 * Credential rotation and the per-session breaker mirror {@link CoinGeckoPriceProviderTest}.
 */
@ExtendWith(MockitoExtension.class)
class CoinMarketCapPriceProviderTest {

    @Mock AggregatorService aggregatorService;

    /** A BTC asset mapped on CoinMarketCap (id "1") — what makes this provider able to price it. */
    private static final FinancialAsset BTC =
        FinancialAsset.builder().symbol("BTC").coinmarketcapId("1").build();

    private final List<String> sentKeys = new ArrayList<>();
    private final List<String> requestedUris = new ArrayList<>();
    private final AtomicInteger httpCalls = new AtomicInteger();

    /** Provider whose HTTP layer records key/URI and 429s on the call indexes matched by {@code rateLimited}. */
    private CoinMarketCapPriceProvider providerThat(IntPredicate rateLimited, String body) {
        ExchangeFunction exchange = request -> {
            int call = httpCalls.incrementAndGet();
            sentKeys.add(request.headers().getFirst("X-CMC_PRO_API_KEY"));
            requestedUris.add(request.url().toString());
            if (rateLimited.test(call)) {
                return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{}").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
        };
        return new CoinMarketCapPriceProvider(aggregatorService,
            WebClient.builder().exchangeFunction(exchange).build());
    }

    /** The id-keyed quotes payload: one object per id, not a list — that's the whole point of id lookups. */
    private static final String QUOTES_BTC =
        "{\"data\":{\"1\":{\"id\":1,\"symbol\":\"BTC\",\"quote\":{\"EUR\":{\"price\":50000}}}}}";

    private void stubSessions(SessionCredentials... sessions) {
        when(aggregatorService.enabledCredentials("coinmarketcap"))
            .thenReturn(Optional.of(List.of(sessions)));
    }

    @Test
    void canPrice_onlyWhenTheAssetCarriesACoinMarketCapId() {
        var provider = providerThat(call -> false, QUOTES_BTC);

        assertThat(provider.canPrice(BTC)).isTrue();
        // No id of its own → can't price it, whatever else the asset carries. In particular a
        // CoinGecko id says nothing about what CoinMarketCap can do.
        assertThat(provider.canPrice(FinancialAsset.builder()
            .symbol("BTC").coingeckoId("bitcoin").build())).isFalse();
        assertThat(provider.canPrice(FinancialAsset.builder().symbol("XYZ").build())).isFalse();
    }

    @Test
    void getPricesEur_looksUpByIdAndKeysTheResultByOurSymbol() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> false, QUOTES_BTC);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(BTC));

        assertThat(result.get("BTC").doubleValue()).isEqualTo(50000.0);
        assertThat(sentKeys).containsExactly("key-A");
        assertThat(requestedUris.get(0)).contains("id=1").contains("convert=EUR");
        // Never the symbol endpoint: "?symbol=BTC" would return every coin calling itself BTC.
        assertThat(requestedUris.get(0)).doesNotContain("symbol=");
    }

    @Test
    void getPricesEur_skipsAssetsWithNoCoinMarketCapId() {
        var provider = providerThat(call -> false, QUOTES_BTC);

        Map<String, BigDecimal> result = provider.getPricesEur(
            List.of(FinancialAsset.builder().symbol("SOL").coingeckoId("solana").build()));

        assertThat(result).isEmpty();
        assertThat(httpCalls.get()).isZero();
    }

    @Test
    void withNoKey_makesNoCallAtAll_becauseThereIsNoAnonymousTier() {
        // Aggregator enabled but keyless: unlike CoinGecko there's no free tier to fall back to.
        when(aggregatorService.enabledCredentials("coinmarketcap")).thenReturn(Optional.of(List.of()));
        var provider = providerThat(call -> false, QUOTES_BTC);

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();
        assertThat(provider.searchBySymbol("BTC")).isEmpty();
        assertThat(provider.fetchById("1")).isEmpty();
        assertThat(provider.isAvailable()).isFalse();
        assertThat(provider.isResolutionAvailable()).isFalse();
        assertThat(httpCalls.get()).isZero();
    }

    @Test
    void whenAggregatorDisabled_makesNoCall() {
        when(aggregatorService.enabledCredentials("coinmarketcap")).thenReturn(Optional.empty());
        var provider = providerThat(call -> false, QUOTES_BTC);

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();
        assertThat(provider.isAvailable()).isFalse();
        assertThat(httpCalls.get()).isZero();
    }

    @Test
    void searchBySymbol_returnsEveryCoinSharingTheTickerWithItsRank() {
        // The namesake problem, surfaced rather than guessed at: two coins call themselves BEAT.
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> false,
            "{\"data\":[{\"id\":7,\"name\":\"MetaBeat\",\"symbol\":\"BEAT\",\"rank\":300},"
                + "{\"id\":99,\"name\":\"Beat Inu\",\"symbol\":\"BEAT\",\"rank\":5000},"
                + "{\"id\":5,\"name\":\"Unrelated\",\"symbol\":\"BEATX\",\"rank\":2}]}");

        List<AssetCandidate> candidates = provider.searchBySymbol("beat");

        assertThat(candidates).extracting(AssetCandidate::id).containsExactly("7", "99");
        assertThat(candidates.get(0).marketCapRank()).isEqualTo(300);
        assertThat(requestedUris.get(0)).contains("symbol=BEAT");
    }

    @Test
    void fetchById_readsBackTheCanonicalNameForAPickedId() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> false,
            "{\"data\":{\"1\":{\"id\":1,\"name\":\"Bitcoin\",\"symbol\":\"BTC\"}}}");

        Optional<AssetCandidate> found = provider.fetchById("1");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("1");
        assertThat(found.get().name()).isEqualTo("Bitcoin");
        assertThat(requestedUris.get(0)).contains("/v2/cryptocurrency/info").contains("id=1");
    }

    @Test
    void fetchById_unknownId_isEmpty() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> false, "{\"data\":{}}");

        assertThat(provider.fetchById("404")).isEmpty();
    }

    @Test
    void extractIdFromUrl_isNotSupported_soTheIdComesFromAPickedCandidate() {
        var provider = providerThat(call -> false, QUOTES_BTC);

        assertThat(provider.extractIdFromUrl("https://coinmarketcap.com/currencies/bitcoin/"))
            .isEmpty();
    }

    @Test
    void getRefAndSetRef_touchOnlyTheCoinMarketCapColumn() {
        var provider = providerThat(call -> false, QUOTES_BTC);
        FinancialAsset asset = FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();

        provider.setRef(asset, "1");

        assertThat(provider.getRef(asset)).isEqualTo("1");
        assertThat(asset.getCoinmarketcapId()).isEqualTo("1");
        assertThat(asset.getCoingeckoId()).isEqualTo("bitcoin");   // a sibling's column is untouched
    }

    @Test
    void on429_pausesThatKey_andNextCallRollsOverToAnother() {
        stubSessions(new SessionCredentials(1L, "key-A", null),
                     new SessionCredentials(2L, "key-B", null));
        var provider = providerThat(call -> call == 1, QUOTES_BTC);

        Map<String, BigDecimal> first = provider.getPricesEur(List.of(BTC));
        Map<String, BigDecimal> second = provider.getPricesEur(List.of(BTC));

        assertThat(first).isEmpty();
        assertThat(second.get("BTC").doubleValue()).isEqualTo(50000.0);
        assertThat(sentKeys).containsExactly("key-A", "key-B");
    }

    @Test
    void whenEveryKeyIsPaused_shortCircuitsWithoutAnHttpCall() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> true, QUOTES_BTC);

        provider.getPricesEur(List.of(BTC));                                   // trips the breaker
        Map<String, BigDecimal> second = provider.getPricesEur(List.of(BTC));  // no usable key

        assertThat(second).isEmpty();
        assertThat(httpCalls.get()).isEqualTo(1);
        assertThat(provider.isAvailable()).isFalse();
    }
}
