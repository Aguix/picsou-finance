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
 * Exercises the credential/breaker wiring added when the provider moved off the {@code
 * COINGECKO_DEMO_API_KEY} env var to per-session keys from {@link AggregatorService}: the chosen
 * session's key is sent as the {@code x-cg-demo-api-key} header, a 429 pauses only that key (the
 * next call rolls over to another), and once every key is paused the provider short-circuits with
 * no HTTP call. The provider reads {@code coingecko_id} straight off the asset passed in, so no
 * registry lookup is stubbed — the test hands it a BTC asset directly.
 */
@ExtendWith(MockitoExtension.class)
class CoinGeckoPriceProviderTest {

    @Mock AggregatorService aggregatorService;

    /** A priceable BTC asset — the provider reads its {@code coingecko_id} to build the request. */
    private static final FinancialAsset BTC =
        FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();

    // Populated by the fake exchange: the x-cg-demo-api-key header of each request (null if absent).
    private final List<String> sentKeys = new ArrayList<>();
    private final AtomicInteger httpCalls = new AtomicInteger();

    /** Build a provider whose HTTP layer records the key header and 429s on the call indexes matched by {@code rateLimited}. */
    private CoinGeckoPriceProvider providerThat(IntPredicate rateLimited) {
        ExchangeFunction exchange = request -> {
            int call = httpCalls.incrementAndGet();
            sentKeys.add(request.headers().getFirst("x-cg-demo-api-key"));
            if (rateLimited.test(call)) {
                return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{}").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"bitcoin\":{\"eur\":50000}}").build());
        };
        return new CoinGeckoPriceProvider(aggregatorService,
            WebClient.builder().exchangeFunction(exchange).build());
    }

    /** A never-rate-limited provider answering every call with {@code body}. */
    private CoinGeckoPriceProvider providerReturning(String body) {
        ExchangeFunction exchange = request -> {
            httpCalls.incrementAndGet();
            sentKeys.add(request.headers().getFirst("x-cg-demo-api-key"));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
        };
        return new CoinGeckoPriceProvider(aggregatorService,
            WebClient.builder().exchangeFunction(exchange).build());
    }

    private void stubSessions(SessionCredentials... sessions) {
        when(aggregatorService.enabledCredentials("coingecko")).thenReturn(Optional.of(List.of(sessions)));
    }

    private void stubAggregatorDisabled() {
        when(aggregatorService.enabledCredentials("coingecko")).thenReturn(Optional.empty());
    }

    @Test
    void getPricesEur_sendsChosenSessionKeyAsHeader() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> false);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(BTC));

        assertThat(result.get("BTC").doubleValue()).isEqualTo(50000.0);
        assertThat(sentKeys).containsExactly("key-A");
    }

    @Test
    void getPricesEur_noSessions_fallsBackToAnonymous_withoutKeyHeader() {
        stubSessions();   // no configured keys → anonymous free tier
        var provider = providerThat(call -> false);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(BTC));

        assertThat(result.get("BTC").doubleValue()).isEqualTo(50000.0);
        assertThat(sentKeys).containsExactly((String) null);   // no x-cg-demo-api-key sent
    }

    @Test
    void getPricesEur_on429_pausesThatKey_andNextCallRollsOverToAnotherKey() {
        stubSessions(new SessionCredentials(1L, "key-A", null),
                     new SessionCredentials(2L, "key-B", null));
        var provider = providerThat(call -> call == 1);   // only the first request (key-A) 429s

        Map<String, BigDecimal> first = provider.getPricesEur(List.of(BTC));    // key-A → 429 → paused
        Map<String, BigDecimal> second = provider.getPricesEur(List.of(BTC));   // rolls over to key-B

        assertThat(first).isEmpty();
        assertThat(second.get("BTC").doubleValue()).isEqualTo(50000.0);
        assertThat(sentKeys).containsExactly("key-A", "key-B");
    }

    @Test
    void getPricesEur_rotatesAcrossUsableKeys_leastRecentlyUsedFirst() {
        stubSessions(new SessionCredentials(1L, "key-A", null),
                     new SessionCredentials(2L, "key-B", null));
        var provider = providerThat(call -> false);   // no rate limiting — pure rotation

        provider.getPricesEur(List.of(BTC));   // both never used → tie broken by id → key-A
        provider.getPricesEur(List.of(BTC));   // key-A just used → key-B is now least-recently-used
        provider.getPricesEur(List.of(BTC));   // key-B just used → back to key-A

        assertThat(sentKeys).containsExactly("key-A", "key-B", "key-A");
    }

    @Test
    void getPricesEur_whenAggregatorDisabled_makesNoCall_notEvenAnonymous() {
        stubAggregatorDisabled();   // Optional.empty() → provider is fully off
        var provider = providerThat(call -> false);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(BTC));

        assertThat(result).isEmpty();
        assertThat(httpCalls.get()).isZero();
        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void getPricesEur_whenEveryKeyIsPaused_shortCircuitsWithoutAnHttpCall() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> true);   // key-A 429s on first use, then stays paused

        provider.getPricesEur(List.of(BTC));                                   // trips the breaker (1 HTTP call)
        Map<String, BigDecimal> second = provider.getPricesEur(List.of(BTC));  // no usable key → no request

        assertThat(second).isEmpty();
        assertThat(httpCalls.get()).isEqualTo(1);
    }

    // --- Resolution side (AssetResolverPort) -------------------------------------------------
    // The coin-URL parsing lives here, in the aggregator that owns the URL form — the engine offers
    // a pasted link to every resolver and the one that recognises it owns the id. These tests are
    // the real regex's only coverage: FinancialAssetServiceTest drives fakes on purpose.

    @Test
    void extractIdFromUrl_readsTheCoinSlug_fromEveryShapeOfCoinLink() {
        var provider = providerReturning("{}");

        assertThat(provider.extractIdFromUrl("https://www.coingecko.com/en/coins/loaded-lions"))
            .contains("loaded-lions");
        // Localised path, query string and fragment all still carry the same slug.
        assertThat(provider.extractIdFromUrl("https://www.coingecko.com/fr/coins/capybara-nation?utm=share#markets"))
            .contains("capybara-nation");
        assertThat(provider.extractIdFromUrl("  https://www.coingecko.com/en/coins/MATIC-Network  "))
            .contains("matic-network");   // ids are lowercase
    }

    @Test
    void extractIdFromUrl_claimsNothingThatIsNotACoinLink() {
        var provider = providerReturning("{}");

        // Not claiming it is what makes the engine report "no aggregator recognises this link"
        // instead of resolving something wrong.
        assertThat(provider.extractIdFromUrl("https://www.coingecko.com/en/categories")).isEmpty();
        assertThat(provider.extractIdFromUrl("https://coinmarketcap.com/currencies/bitcoin/")).isEmpty();
        assertThat(provider.extractIdFromUrl("https://www.coingecko.com/en/coins/")).isEmpty();
        assertThat(provider.extractIdFromUrl("not a url")).isEmpty();
        assertThat(provider.extractIdFromUrl(null)).isEmpty();
        assertThat(httpCalls.get()).isZero();   // pure parsing, no call
    }

    @Test
    void searchBySymbol_keepsOnlyExactSymbolMatches_withTheirRank() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerReturning("""
            {"coins":[
              {"id":"metabeat","name":"MetaBeat","symbol":"beat","market_cap_rank":300},
              {"id":"beat-inu","name":"Beat Inu","symbol":"beat","market_cap_rank":5000},
              {"id":"beatcoin","name":"BeatCoin","symbol":"beatx","market_cap_rank":2}
            ]}""");

        List<AssetCandidate> candidates = provider.searchBySymbol("BEAT");

        // CoinGecko's /search matches names too; only the coins actually calling themselves BEAT
        // are candidates for the symbol.
        assertThat(candidates).extracting(AssetCandidate::id).containsExactly("metabeat", "beat-inu");
        assertThat(candidates.get(0).marketCapRank()).isEqualTo(300);
        assertThat(candidates.get(0).name()).isEqualTo("MetaBeat");
    }

    @Test
    void searchBySymbol_degradesToEmpty_whenTheAggregatorIsOff() {
        stubAggregatorDisabled();
        var provider = providerReturning("{}");

        assertThat(provider.searchBySymbol("BTC")).isEmpty();
        assertThat(httpCalls.get()).isZero();
    }

    @Test
    void fetchById_narrowsTheCoinDetailToACandidate() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerReturning(
            "{\"id\":\"loaded-lions\",\"name\":\"Loaded Lions\",\"symbol\":\"lion\",\"market_cap_rank\":3500}");

        var found = provider.fetchById("loaded-lions");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("loaded-lions");
        assertThat(found.get().name()).isEqualTo("Loaded Lions");
    }

    @Test
    void getRefAndSetRef_touchOnlyTheCoinGeckoColumn() {
        var provider = providerReturning("{}");
        FinancialAsset asset = FinancialAsset.builder().symbol("BTC").yahooSymbol("BTC-EUR").build();

        provider.setRef(asset, "bitcoin");

        assertThat(provider.getRef(asset)).isEqualTo("bitcoin");
        assertThat(asset.getCoingeckoId()).isEqualTo("bitcoin");
        assertThat(asset.getYahooSymbol()).isEqualTo("BTC-EUR");   // a sibling's column is untouched
    }

    @Test
    void isAvailable_falseOnlyWhenNoKeyIsUsable() {
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> true);

        assertThat(provider.isAvailable()).isTrue();
        provider.getPricesEur(List.of(BTC));   // 429 pauses the only key
        assertThat(provider.isAvailable()).isFalse();
    }
}
