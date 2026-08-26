package com.picsou.adapter.price;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.model.FinancialAsset;
import com.picsou.service.AggregatorService;
import com.picsou.service.AggregatorService.SessionCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Covers the adapter's failure contract, which is the part of this class that repeatedly
 * regressed: an <em>expected</em> upstream failure must return no prices and be logged at a
 * severity matching whose problem it is, while a genuine bug must propagate rather than hide
 * behind an empty map.
 *
 * <p>Failures are injected as {@code Mono.error(...)} rather than by real I/O, which also
 * exercises the reactor wrapping that matters here: {@code block()} wraps a <em>checked</em>
 * exception (notably {@link TimeoutException}) in a {@code ReactiveException}, so the adapter
 * has to unwrap before classifying. Matching on declared types alone would silently stop
 * catching timeouts.
 *
 * <p>Kept apart from {@link CoinGeckoPriceProviderTest}, which owns the credential/breaker
 * wiring: these tests run on the anonymous session so a 429 is judged on what it logs and how
 * long it pauses, not on which key it rolls over to.
 */
@ExtendWith(MockitoExtension.class)
class CoinGeckoFailureContractTest {

    @Mock AggregatorService aggregatorService;

    /** A priceable BTC asset — the provider reads its {@code coingecko_id} to build the request. */
    private static final FinancialAsset BTC =
        FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        // Enabled with no key: the anonymous free tier, so no rotation is involved.
        when(aggregatorService.enabledCredentials("coingecko")).thenReturn(Optional.of(List.of()));
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private CoinGeckoPriceProvider providerReturning(Mono<ClientResponse> response) {
        ExchangeFunction exchange = request -> response;
        return new CoinGeckoPriceProvider(aggregatorService,
            WebClient.builder().exchangeFunction(exchange).build());
    }

    private CoinGeckoPriceProvider providerWithJson(String json) {
        return providerReturning(Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(json).build()));
    }

    private CoinGeckoPriceProvider providerWithStatus(HttpStatus status, String body) {
        return providerReturning(Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body).build()));
    }

    private CoinGeckoPriceProvider providerWithStatus(HttpStatus status, String header, String value) {
        return providerReturning(Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header(header, value)
            .body("{}").build()));
    }

    private CoinGeckoPriceProvider providerFailingWith(Throwable ex) {
        return providerReturning(Mono.error(ex));
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    // ── HTTP failures ─────────────────────────────────────────────────────────

    @Test
    void serverError_returnsNoPrices_andWarnsRatherThanErrors() {
        // Their outage, not our bug. These callers run on a scheduler, so an hours-long outage
        // would otherwise pour ERROR lines into a self-hosted instance's log.
        CoinGeckoPriceProvider provider =
            providerWithStatus(HttpStatus.BAD_GATEWAY, "<html>Bad gateway</html>");

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();
        assertThat(eventsAt(Level.ERROR)).isEmpty();
        assertThat(eventsAt(Level.WARN)).isNotEmpty();
    }

    @Test
    void notFound_returnsNoPrices_andErrors() {
        // An unknown coin id points at a bad coingecko_id in the registry -- something an
        // operator can actually fix, so it is worth an ERROR.
        CoinGeckoPriceProvider provider =
            providerWithStatus(HttpStatus.NOT_FOUND, "{\"error\":\"coin not found\"}");

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();
        assertThat(eventsAt(Level.ERROR)).isNotEmpty();
    }

    @Test
    void forbidden_returnsNoPrices_andWarns() {
        // Free-tier access policy, not a defect on our side.
        CoinGeckoPriceProvider provider =
            providerWithStatus(HttpStatus.FORBIDDEN, "{\"status\":{\"error_code\":403}}");

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();
        assertThat(eventsAt(Level.ERROR)).isEmpty();
        assertThat(eventsAt(Level.WARN)).isNotEmpty();
    }

    @Test
    void timeout_returnsNoPrices_andWarns_despiteReactorWrapping() {
        // Mono.timeout() signals a *checked* TimeoutException, which block() wraps in a reactor
        // ReactiveException: without unwrapping, the most common real failure goes unclassified.
        CoinGeckoPriceProvider provider =
            providerFailingWith(new TimeoutException("Did not observe any item or terminal signal"));

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();
        assertThat(eventsAt(Level.WARN)).isNotEmpty();
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void connectionFailure_returnsNoPrices_andWarns() {
        // Never reached the server: DNS failure, connection refused, TLS handshake.
        CoinGeckoPriceProvider provider = providerFailingWith(new WebClientRequestException(
            new IOException("Connection refused"), org.springframework.http.HttpMethod.GET,
            URI.create("https://api.coingecko.com/api/v3/simple/price"),
            org.springframework.http.HttpHeaders.EMPTY));

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();
        assertThat(eventsAt(Level.WARN)).isNotEmpty();
        assertThat(eventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    void unexpectedException_propagates_insteadOfBeingSwallowedAsNoPrices() {
        // A bug that presents as "no prices" is indistinguishable from a quiet outage and would
        // never get fixed. PriceRouter guards the call, so it degrades to the next aggregator.
        CoinGeckoPriceProvider provider =
            providerFailingWith(new IllegalStateException("parse defect on our side"));

        assertThatThrownBy(() -> provider.getPricesEur(List.of(BTC)))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── Rate limiting ─────────────────────────────────────────────────────────

    @Test
    void rateLimit_honoursRetryAfter_withinReason() {
        CoinGeckoPriceProvider provider =
            providerWithStatus(HttpStatus.TOO_MANY_REQUESTS, "Retry-After", "120");

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();

        Optional<Instant> pausedUntil = provider.pausedUntil();
        assertThat(pausedUntil).isPresent();
        assertThat(Duration.between(Instant.now(), pausedUntil.get()))
            .isBetween(Duration.ofSeconds(100), Duration.ofSeconds(120));
    }

    @Test
    void rateLimit_ignoresAnAbsurdRetryAfter() {
        // A misconfigured (or hostile) "86400" would otherwise park the key for a day, long
        // after the real limit lifted.
        CoinGeckoPriceProvider provider =
            providerWithStatus(HttpStatus.TOO_MANY_REQUESTS, "Retry-After", "86400");

        assertThat(provider.getPricesEur(List.of(BTC))).isEmpty();

        assertThat(provider.pausedUntil()).isPresent();
        assertThat(Duration.between(Instant.now(), provider.pausedUntil().get()))
            .isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    // ── Response shape ────────────────────────────────────────────────────────

    @Test
    void getHistoricalPricesEur_parsesWellFormedPoints() {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(2);
        long epochMillis = from.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        CoinGeckoPriceProvider provider =
            providerWithJson("{\"prices\":[[" + epochMillis + ",42000.5]]}");

        Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur(BTC, from, to);

        assertThat(prices).hasSize(1);
        assertThat(prices.values().iterator().next()).isEqualByComparingTo("42000.5");
    }

    @Test
    void getHistoricalPricesEur_skipsMalformedPoints_withoutThrowing() {
        // A shape change upstream must degrade to a warn and a skip: since an unexpected
        // exception now propagates, a blind cast here would bounce the whole aggregator out of
        // the price waterfall over a formatting change.
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(2);
        long epochMillis = from.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        CoinGeckoPriceProvider provider = providerWithJson(
            "{\"prices\":[[" + epochMillis + "],[\"not-a-number\",\"nope\"],[" + epochMillis + ",42000.5]]}");

        Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur(BTC, from, to);

        assertThat(prices).hasSize(1);
        assertThat(eventsAt(Level.WARN)).isNotEmpty();
    }

    @Test
    void getHistoricalPricesEur_nonListPricesField_returnsNothing_andWarns() {
        CoinGeckoPriceProvider provider = providerWithJson("{\"prices\":{\"unexpected\":\"object\"}}");

        assertThat(provider.getHistoricalPricesEur(BTC, LocalDate.now().minusDays(2), LocalDate.now()))
            .isEmpty();
        assertThat(eventsAt(Level.WARN)).isNotEmpty();
    }

    /** Unused here, kept so the anonymous-session shape stays explicit if a test needs a key. */
    @SuppressWarnings("unused")
    private static SessionCredentials anonymous() {
        return new SessionCredentials(null, null, null);
    }
}
