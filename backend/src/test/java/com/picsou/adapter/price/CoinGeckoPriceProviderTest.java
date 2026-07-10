package com.picsou.adapter.price;

import com.picsou.model.FinancialAsset;
import com.picsou.repository.FinancialAssetRepository;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Exercises the credential/breaker wiring added when the provider moved off the {@code
 * COINGECKO_DEMO_API_KEY} env var to per-session keys from {@link AggregatorService}: the chosen
 * session's key is sent as the {@code x-cg-demo-api-key} header, a 429 pauses only that key (the
 * next call rolls over to another), and once every key is paused the provider short-circuits with
 * no HTTP call.
 */
@ExtendWith(MockitoExtension.class)
class CoinGeckoPriceProviderTest {

    @Mock FinancialAssetRepository assetRepository;
    @Mock AggregatorService aggregatorService;

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
        return new CoinGeckoPriceProvider(assetRepository, aggregatorService,
            WebClient.builder().exchangeFunction(exchange).build());
    }

    private void stubBtcAsset() {
        FinancialAsset btc = FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();
        when(assetRepository.findBySymbolIn(Set.of("BTC"))).thenReturn(List.of(btc));
    }

    private void stubSessions(SessionCredentials... sessions) {
        when(aggregatorService.enabledCredentials("coingecko")).thenReturn(Optional.of(List.of(sessions)));
    }

    private void stubAggregatorDisabled() {
        when(aggregatorService.enabledCredentials("coingecko")).thenReturn(Optional.empty());
    }

    @Test
    void getPricesEur_sendsChosenSessionKeyAsHeader() {
        stubBtcAsset();
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> false);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("BTC"));

        assertThat(result.get("BTC").doubleValue()).isEqualTo(50000.0);
        assertThat(sentKeys).containsExactly("key-A");
    }

    @Test
    void getPricesEur_noSessions_fallsBackToAnonymous_withoutKeyHeader() {
        stubBtcAsset();
        stubSessions();   // no configured keys → anonymous free tier
        var provider = providerThat(call -> false);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("BTC"));

        assertThat(result.get("BTC").doubleValue()).isEqualTo(50000.0);
        assertThat(sentKeys).containsExactly((String) null);   // no x-cg-demo-api-key sent
    }

    @Test
    void getPricesEur_on429_pausesThatKey_andNextCallRollsOverToAnotherKey() {
        stubBtcAsset();
        stubSessions(new SessionCredentials(1L, "key-A", null),
                     new SessionCredentials(2L, "key-B", null));
        var provider = providerThat(call -> call == 1);   // only the first request (key-A) 429s

        Map<String, BigDecimal> first = provider.getPricesEur(Set.of("BTC"));    // key-A → 429 → paused
        Map<String, BigDecimal> second = provider.getPricesEur(Set.of("BTC"));   // rolls over to key-B

        assertThat(first).isEmpty();
        assertThat(second.get("BTC").doubleValue()).isEqualTo(50000.0);
        assertThat(sentKeys).containsExactly("key-A", "key-B");
    }

    @Test
    void getPricesEur_rotatesAcrossUsableKeys_leastRecentlyUsedFirst() {
        stubBtcAsset();
        stubSessions(new SessionCredentials(1L, "key-A", null),
                     new SessionCredentials(2L, "key-B", null));
        var provider = providerThat(call -> false);   // no rate limiting — pure rotation

        provider.getPricesEur(Set.of("BTC"));   // both never used → tie broken by id → key-A
        provider.getPricesEur(Set.of("BTC"));   // key-A just used → key-B is now least-recently-used
        provider.getPricesEur(Set.of("BTC"));   // key-B just used → back to key-A

        assertThat(sentKeys).containsExactly("key-A", "key-B", "key-A");
    }

    @Test
    void getPricesEur_whenAggregatorDisabled_makesNoCall_notEvenAnonymous() {
        stubBtcAsset();
        stubAggregatorDisabled();   // Optional.empty() → provider is fully off
        var provider = providerThat(call -> false);

        Map<String, BigDecimal> result = provider.getPricesEur(Set.of("BTC"));

        assertThat(result).isEmpty();
        assertThat(httpCalls.get()).isZero();
        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void getPricesEur_whenEveryKeyIsPaused_shortCircuitsWithoutAnHttpCall() {
        stubBtcAsset();
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> true);   // key-A 429s on first use, then stays paused

        provider.getPricesEur(Set.of("BTC"));                                   // trips the breaker (1 HTTP call)
        Map<String, BigDecimal> second = provider.getPricesEur(Set.of("BTC"));  // no usable key → no request

        assertThat(second).isEmpty();
        assertThat(httpCalls.get()).isEqualTo(1);
    }

    @Test
    void isAvailable_falseOnlyWhenNoKeyIsUsable() {
        stubBtcAsset();
        stubSessions(new SessionCredentials(1L, "key-A", null));
        var provider = providerThat(call -> true);

        assertThat(provider.isAvailable()).isTrue();
        provider.getPricesEur(Set.of("BTC"));   // 429 pauses the only key
        assertThat(provider.isAvailable()).isFalse();
    }
}
