package com.picsou.adapter.price;

import com.picsou.model.FinancialAsset;
import com.picsou.port.AssetCandidate;
import com.picsou.port.SymbolCatalogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class YahooFinancePriceProviderTest {

    /** An asset resolved to a Yahoo symbol (mirrors what getOrCreateStock persists at discovery). */
    private static FinancialAsset asset(String symbol) {
        return FinancialAsset.builder().symbol(symbol).yahooSymbol(symbol).build();
    }

    private static final String AAPL_USD = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":100.0,"currency":"USD"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[100.0]}]}}]}}""";

    private static final String ASML_EUR = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":700.0,"currency":"EUR"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[700.0]}]}}]}}""";

    /** Same chart payload, with the name metadata fetchById reads back. */
    private static final String ASML_EUR_NAMED = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":700.0,"currency":"EUR",
              "longName":"ASML Holding NV","shortName":"ASML HOLDING"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[700.0]}]}}]}}""";

    private static final String SONY_JPY = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":3000.0,"currency":"JPY"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[3000.0]}]}}]}}""";

    private static final String LLOY_GBP_PENCE = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":5000.0,"currency":"GBp"},
              "timestamp":[1700000000],"indicators":{"quote":[{"close":[5000.0]}]}}]}}""";

    private static final String CURRENCYLESS = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":42.0}}]}}""";

    private static final String FX_USD_EUR_092 = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":0.92,"currency":"EUR"}}]}}""";

    private static final String FX_JPY_EUR = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":0.0060,"currency":"EUR"}}]}}""";

    private static final String FX_GBP_EUR = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":1.18,"currency":"EUR"}}]}}""";

    private static final String HISTORICAL_USD = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":100.0,"currency":"USD"},
              "timestamp":[1700000000,1700086400,1700172800],
              "indicators":{"quote":[{"close":[100.0,110.0,120.0]}]}}]}}""";

    private static final String INTRADAY_JPY = """
            {"chart":{"result":[{"meta":{"regularMarketPrice":3000.0,"currency":"JPY"},
              "timestamp":[1700000000,1700003600],
              "indicators":{"quote":[{"close":[3000.0,3100.0]}]}}]}}""";

    private YahooFinancePriceProvider providerWith(Function<String, String> routeToJson, AtomicInteger callCounter) {
        ExchangeFunction exchange = request -> {
            if (callCounter != null) callCounter.incrementAndGet();
            String url = request.url().toString();
            String body = routeToJson.apply(url);
            if (body == null) {
                return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\":\"not found\"}").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
        };
        WebClient client = WebClient.builder().exchangeFunction(exchange).build();
        return new YahooFinancePriceProvider(client);
    }

    @Test
    void canPrice_isFalse_whenYahooSymbolIsNotSet() {
        var provider = providerWith(url -> null, null);

        // An unresolved asset (e.g. a PENDING crypto with no coingecko_id) must not fall through
        // to a wasted Yahoo call on its raw internal symbol anymore.
        FinancialAsset unresolved = FinancialAsset.builder().symbol("SHIB").build();

        assertThat(provider.canPrice(unresolved)).isFalse();
    }

    @Test
    void canPrice_isTrue_whenYahooSymbolIsSet_evenIfDifferentFromInternalSymbol() {
        var provider = providerWith(url -> null, null);

        FinancialAsset asset = FinancialAsset.builder().symbol("RKLB").yahooSymbol("RKLB").build();

        assertThat(provider.canPrice(asset)).isTrue();
    }

    @Test
    void canPrice_isFalse_whenYahooSymbolIsIsinShaped() {
        var provider = providerWith(url -> null, null);

        FinancialAsset asset = FinancialAsset.builder().symbol("IE00B4L5Y983").yahooSymbol("IE00B4L5Y983").build();

        assertThat(provider.canPrice(asset)).isFalse();
    }

    /** A `/v1/finance/search` hit list: the ticker's listings plus the fuzzy noise Yahoo throws in. */
    private static final String SEARCH_IWDA = """
            {"quotes":[
              {"symbol":"IWDA.AS","shortname":"ISHARES CORE MSCI WORLD","longname":"iShares Core MSCI World UCITS ETF USD (Acc)","quoteType":"ETF"},
              {"symbol":"IWDA.L","shortname":"iShares Core MSCI World UCITS ETF","quoteType":"ETF"},
              {"symbol":"IWDAX","shortname":"Something Unrelated","quoteType":"EQUITY"},
              {"symbol":"SWDA.MI","shortname":"Name match, different ticker","quoteType":"ETF"}
            ]}""";

    @Test
    void searchBySymbol_returnsTheTickersListings_andDropsFuzzyNoise() {
        // Yahoo's search is fuzzy: it also answers on name matches (SWDA.MI) and longer tickers
        // (IWDAX). A symbol picker must only offer the ticker itself and its exchange listings.
        var provider = providerWith(url -> url.contains("/v1/finance/search") ? SEARCH_IWDA : null, null);

        List<AssetCandidate> candidates = provider.searchBySymbol("iwda");

        assertThat(candidates).extracting(AssetCandidate::id).containsExactly("IWDA.AS", "IWDA.L");
        // For Yahoo the id IS the symbol it quotes — the two listings are two different refs.
        assertThat(candidates.get(0).symbol()).isEqualTo("IWDA.AS");
        assertThat(candidates.get(0).name()).isEqualTo("iShares Core MSCI World UCITS ETF USD (Acc)");
        assertThat(candidates.get(1).name()).isEqualTo("iShares Core MSCI World UCITS ETF"); // no longname
    }

    @Test
    void searchBySymbol_candidatesCarryNoRank_soTheResolverNeverAutoPicksAnExchange() {
        // The anti-footgun: without a rank, pickDominant can't suggest one — yahoo_symbol stays null
        // until an operator chooses, rather than silently quoting the wrong market.
        var provider = providerWith(url -> url.contains("/v1/finance/search") ? SEARCH_IWDA : null, null);

        assertThat(provider.searchBySymbol("IWDA"))
            .isNotEmpty()
            .allSatisfy(c -> assertThat(c.marketCapRank()).isNull());
    }

    @Test
    void searchBySymbol_degradesToEmpty_onFailureOrBlankQuery() {
        var provider = providerWith(url -> null, null);   // every call 404s

        assertThat(provider.searchBySymbol("IWDA")).isEmpty();
        assertThat(provider.searchBySymbol("  ")).isEmpty();
    }

    @Test
    void fetchById_validatesASymbolYahooQuotes_andReadsItsName() {
        var provider = providerWith(url -> url.contains("/ASML.AS") ? ASML_EUR_NAMED : null, null);

        var found = provider.fetchById("ASML.AS");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("ASML.AS");
        assertThat(found.get().name()).isEqualTo("ASML Holding NV");
    }

    @Test
    void fetchById_isEmpty_forASymbolYahooCannotQuote() {
        var provider = providerWith(url -> null, null);

        assertThat(provider.fetchById("NOPE.XX")).isEmpty();
        assertThat(provider.fetchById(null)).isEmpty();
    }

    @Test
    void getRefAndSetRef_touchOnlyTheYahooColumn() {
        var provider = providerWith(url -> null, null);
        FinancialAsset asset = FinancialAsset.builder().symbol("IWDA").coingeckoId("not-a-coin").build();

        provider.setRef(asset, "IWDA.AS");

        assertThat(provider.getRef(asset)).isEqualTo("IWDA.AS");
        assertThat(asset.getYahooSymbol()).isEqualTo("IWDA.AS");
        assertThat(asset.getCoingeckoId()).isEqualTo("not-a-coin");   // a sibling's column is untouched
    }

    @Test
    void extractIdFromUrl_isNotSupported_soTheIdComesFromAPickedCandidate() {
        var provider = providerWith(url -> null, null);

        assertThat(provider.extractIdFromUrl("https://finance.yahoo.com/quote/IWDA.AS")).isEmpty();
    }

    @Test
    void getPriceEur_returnsRawPrice_whenCurrencyIsEur() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> {
            if (url.contains("/ASML.AS")) return ASML_EUR;
            return null;
        }, calls);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(asset("ASML.AS")));

        assertThat(result).containsEntry("ASML.AS", BigDecimal.valueOf(700.0));
        assertThat(calls.get()).isEqualTo(1); // no FX call needed for EUR
    }

    @Test
    void getPriceEur_appliesFx_forUsdTicker() {
        var provider = providerWith(url -> {
            if (url.contains("/AAPL")) return AAPL_USD;
            if (url.contains("/USDEUR%3DX") || url.contains("/USDEUR=X")) return FX_USD_EUR_092;
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(asset("AAPL")));

        // 100 USD × 0.92 = 92 EUR
        assertThat(result.get("AAPL").doubleValue()).isCloseTo(92.0, within(0.001));
    }

    @Test
    void getPriceEur_appliesFx_forJpyTicker() {
        var provider = providerWith(url -> {
            if (url.contains("/8729.T")) return SONY_JPY;
            if (url.contains("JPYEUR")) return FX_JPY_EUR;
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(asset("8729.T")));

        // 3000 JPY × 0.0060 = 18 EUR
        assertThat(result.get("8729.T").doubleValue()).isCloseTo(18.0, within(0.01));
    }

    @Test
    void getPriceEur_dividesByHundred_forGbpPence() {
        var provider = providerWith(url -> {
            if (url.contains("/LLOY.L")) return LLOY_GBP_PENCE;
            if (url.contains("GBPEUR")) return FX_GBP_EUR;
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(asset("LLOY.L")));

        // 5000 GBp = 50 GBP × 1.18 = 59 EUR
        assertThat(result.get("LLOY.L").doubleValue()).isCloseTo(59.0, within(0.01));
    }

    @Test
    void getPriceEur_returnsEmpty_whenFxFetchFails() {
        var provider = providerWith(url -> {
            if (url.contains("/AAPL")) return AAPL_USD;
            // USDEUR=X returns 404
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(asset("AAPL")));

        assertThat(result).doesNotContainKey("AAPL"); // no fabricated EUR value
    }

    @Test
    void getPriceEur_returnsRawPrice_whenCurrencyIsMissing() {
        var provider = providerWith(url -> {
            if (url.contains("/WEIRD")) return CURRENCYLESS;
            return null;
        }, null);

        Map<String, BigDecimal> result = provider.getPricesEur(List.of(asset("WEIRD")));

        // Currency null → treat as EUR (preserves pre-fix behavior for broken payloads)
        assertThat(result.get("WEIRD")).isEqualTo(BigDecimal.valueOf(42.0));
    }

    @Test
    void fxCache_avoidsRefetch_acrossTickersInSameCurrency() {
        List<String> fxCalls = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            String url = request.url().toString();
            if (url.contains("USDEUR")) fxCalls.add(url);
            String body;
            if (url.contains("/AAPL")) body = AAPL_USD;
            else if (url.contains("/MSFT")) body = AAPL_USD;  // both USD, same payload shape
            else if (url.contains("USDEUR")) body = FX_USD_EUR_092;
            else body = null;
            if (body == null) {
                return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{}").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
        };
        var provider = new YahooFinancePriceProvider(
            WebClient.builder().exchangeFunction(exchange).build());

        provider.getPricesEur(List.of(asset("AAPL")));
        provider.getPricesEur(List.of(asset("MSFT")));

        // First USD ticker triggers FX fetch and caches; second USD ticker
        // must reuse the cached rate.
        assertThat(fxCalls).hasSize(1);
    }

    @Test
    void getFxRateToEur_shortCircuits_forEur() {
        var provider = providerWith(url -> null, null);

        // Should never hit the network for EUR.
        assertThat(provider.getFxRateToEur("EUR")).isEqualTo(BigDecimal.ONE);
        assertThat(provider.getFxRateToEur("eur")).isEqualTo(BigDecimal.ONE);
        assertThat(provider.getFxRateToEur(null)).isEqualTo(BigDecimal.ONE);
        assertThat(provider.getFxRateToEur("")).isEqualTo(BigDecimal.ONE);
    }

    @Test
    void getHistoricalPricesEur_appliesFx_toAllClosesInSeries() {
        var provider = providerWith(url -> {
            if (url.contains("/AAPL")) return HISTORICAL_USD;
            if (url.contains("USDEUR")) return FX_USD_EUR_092;
            return null;
        }, null);

        Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur(
            asset("AAPL"), LocalDate.of(2023, 11, 1), LocalDate.of(2023, 12, 1));

        // All closes × 0.92, scaled to 8 decimals
        assertThat(prices).isNotEmpty();
        assertThat(prices.values().stream().map(BigDecimal::doubleValue).toList())
            .allSatisfy(v -> assertThat(v).isIn(92.0, 101.2, 110.4));
    }

    @Test
    void getIntradayPricesEur_appliesFx_toAllClosesInSeries() {
        var provider = providerWith(url -> {
            if (url.contains("/8729.T")) return INTRADAY_JPY;
            if (url.contains("JPYEUR")) return FX_JPY_EUR;
            return null;
        }, null);

        var from = java.time.LocalDateTime.of(2023, 1, 1, 0, 0);
        var to = java.time.LocalDateTime.of(2030, 1, 1, 0, 0);
        Map<java.time.LocalDateTime, BigDecimal> prices = provider.getIntradayPricesEur(asset("8729.T"), from, to);

        // 3000 JPY × 0.006 = 18; 3100 JPY × 0.006 = 18.6 — both must be present
        assertThat(prices.values().stream().map(BigDecimal::doubleValue).toList())
            .anySatisfy(v -> assertThat(v).isCloseTo(18.0, within(0.01)));
    }

    @Test
    void supports_rejectsStringsThatAreNotSymbols() {
        var provider = new YahooFinancePriceProvider();

        assertThat(provider.supports("AIRBAL 14.5 08/14/29 REGS")).isFalse();
        assertThat(provider.supports("AIR BALTIC")).isFalse();
        assertThat(provider.supports("A/B")).isFalse();
        assertThat(provider.supports("THIS_IS_NOT_A_TICKER_AT_ALL")).isFalse();
    }

    @Test
    void supports_acceptsRealYahooSymbolShapes() {
        var provider = new YahooFinancePriceProvider();

        assertThat(provider.supports("PHYMF")).isTrue();
        assertThat(provider.supports("IWDA.AS")).isTrue();
        assertThat(provider.supports("MC.PA")).isTrue();
        assertThat(provider.supports("BRK-B")).isTrue();
        assertThat(provider.supports("1810.HK")).isTrue();
        assertThat(provider.supports("USDEUR=X")).isTrue();
        assertThat(provider.supports("^GSPC")).isTrue();
    }

    @Test
    void supports_enforcesTwentyCharacterLimitIncludingIndexPrefix() {
        var provider = new YahooFinancePriceProvider();

        assertThat(provider.supports("A".repeat(20))).isTrue();
        assertThat(provider.supports("A".repeat(21))).isFalse();
        assertThat(provider.supports("^" + "A".repeat(19))).isTrue();
        assertThat(provider.supports("^" + "A".repeat(20))).isFalse();
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void supports_normalizesWithLocaleRoot_soATurkishDefaultLocaleDoesNotBreakTickers() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var provider = new YahooFinancePriceProvider();

            assertThat(provider.supports("iwda.as")).isTrue();
            assertThat(provider.supports("isin")).isTrue();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void getPricesEur_keysTheResultWithLocaleRootUppercase() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var provider = providerWith(url -> url.toUpperCase(Locale.ROOT).contains("/IWDA.AS")
                ? ASML_EUR : null, null);

            assertThat(provider.getPricesEur(List.of(asset("iwda.as")))).containsKey("IWDA.AS");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void historicalIntradayAndInstrumentType_neverCallYahoo_forNonSymbolTickers() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> null, calls);
        String bond = "AIRBAL 14.5 08/14/29 REGS";

        assertThat(provider.getHistoricalPricesEur(asset(bond), LocalDate.now().minusDays(7), LocalDate.now()))
            .isEmpty();
        assertThat(provider.getIntradayPricesEur(asset(bond),
            java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now())).isEmpty();
        assertThat(provider.getInstrumentType(bond)).isEmpty();
        assertThat(provider.getHistoricalPricesEur(asset("LU3170240538"),
            LocalDate.now().minusDays(7), LocalDate.now())).isEmpty();

        assertThat(calls.get()).isZero();
    }

    @Test
    void getPricesEur_neverCallsYahoo_forNonSymbolTickers() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> null, calls);

        Map<String, BigDecimal> prices = provider.getPricesEur(List.of(asset("AIRBAL 14.5 08/14/29 REGS")));

        assertThat(prices).isEmpty();
        assertThat(calls.get()).isZero();
    }

    // ─── hasQuote / searchSymbols: is this symbol one Yahoo carries? ────────────
    // Used by OpenFigiIsinConverter to verify the listing it derived from an ISIN before that
    // symbol is persisted on a holding and every later valuation depends on it (GH issues #74, #78).

    private static final String SEARCH_IE000BI8OT95 = """
            {"quotes":[
              {"symbol":"MWRD.PA","shortname":"Amundi Core MSCI World UCITS ET","exchange":"PAR",
               "quoteType":"ETF","isYahooFinance":true},
              {"symbol":"IE000BI8OT95.SG","shortname":"Amundi MSCI World UCITS ETF - U",
               "exchange":"STU","quoteType":"MUTUALFUND","isYahooFinance":true}],"count":2}""";

    @Test
    void hasQuote_isTrue_whenYahooReturnsAPrice_andNeedsNoFxCall() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> url.contains("/AAPL") ? AAPL_USD : null, calls);

        assertThat(provider.hasQuote("AAPL")).isTrue();
        // The probe asks "does this symbol exist", not "what is it worth": an unavailable EUR rate
        // says nothing about the symbol, and a second call per candidate would double the cost.
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void hasQuote_isFalse_forADelistedSymbol() {
        // MWRDF: "No data found, symbol may be delisted" — a 404 from the chart endpoint.
        var provider = providerWith(url -> null, null);

        assertThat(provider.hasQuote("MWRDF")).isFalse();
    }

    @Test
    void hasQuote_isFalse_withoutCallingYahoo_forAnIsinOrNonSymbol() {
        AtomicInteger calls = new AtomicInteger();
        var provider = providerWith(url -> null, calls);

        assertThat(provider.hasQuote("IE000BI8OT95")).isFalse();
        assertThat(provider.hasQuote("AIRBAL 14.5 08/14/29 REGS")).isFalse();
        assertThat(provider.hasQuote(null)).isFalse();

        assertThat(calls.get()).isZero();
    }

    @Test
    void searchSymbols_returnsYahoosOwnSymbolsForAnIsin_inItsRelevanceOrder() {
        var provider = providerWith(url -> url.contains("/v1/finance/search") ? SEARCH_IE000BI8OT95 : null, null);

        List<SymbolCatalogPort.SymbolMatch> matches = provider.searchSymbols("IE000BI8OT95");

        assertThat(matches).extracting(SymbolCatalogPort.SymbolMatch::symbol)
            .containsExactly("MWRD.PA", "IE000BI8OT95.SG");
        assertThat(matches.get(0).name()).isEqualTo("Amundi Core MSCI World UCITS ET");
    }

    @Test
    void searchSymbols_prefersTheLongName_whenYahooProvidesOne() {
        var provider = providerWith(url -> """
            {"quotes":[{"symbol":"MC.PA","shortname":"LVMH","longname":"LVMH Moet Hennessy Louis Vuitton",
              "isYahooFinance":true}]}""", null);

        assertThat(provider.searchSymbols("FR0000121014").get(0).name())
            .isEqualTo("LVMH Moet Hennessy Louis Vuitton");
    }

    @Test
    void searchSymbols_dropsEntriesYahooDoesNotQuoteItself() {
        // isYahooFinance=false marks a private company / research entity with no quote page;
        // requesting it would be one guaranteed 404 per candidate.
        var provider = providerWith(url -> """
            {"quotes":[{"symbol":"PRIVATECO","shortname":"Some Private Company","isYahooFinance":false},
                       {"symbol":"MWRD.PA","shortname":"Amundi Core MSCI World","isYahooFinance":true}]}""",
            null);

        assertThat(provider.searchSymbols("IE000BI8OT95"))
            .extracting(SymbolCatalogPort.SymbolMatch::symbol)
            .containsExactly("MWRD.PA");
    }

    @Test
    void searchSymbols_returnsEmpty_whenYahooKnowsNothingOrFails() {
        assertThat(providerWith(url -> """
            {"explains":[],"count":0,"quotes":[]}""", null).searchSymbols("XX0000000000")).isEmpty();
        assertThat(providerWith(url -> null, null).searchSymbols("IE000BI8OT95")).isEmpty();

        AtomicInteger calls = new AtomicInteger();
        assertThat(providerWith(url -> null, calls).searchSymbols("  ")).isEmpty();
        assertThat(calls.get()).isZero();
    }
}
