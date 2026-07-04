package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFigiIsinConverterTest {

    @Test
    void isIsin_recognizesValidIsinCodes() {
        // 2-letter country prefix + 9 alphanumerics + 1 check digit = 12 chars
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y983")).isTrue(); // iShares Core MSCI World
        assertThat(OpenFigiIsinConverter.isIsin("US0378331005")).isTrue(); // Apple
        assertThat(OpenFigiIsinConverter.isIsin("DE0007100000")).isTrue(); // Mercedes-Benz
        assertThat(OpenFigiIsinConverter.isIsin("KYG9830T1067")).isTrue(); // Xiaomi
    }

    @Test
    void isIsin_normalizesCaseAndWhitespace() {
        assertThat(OpenFigiIsinConverter.isIsin("ie00b4l5y983")).isTrue();
        assertThat(OpenFigiIsinConverter.isIsin("  IE00B4L5Y983  ")).isTrue();
    }

    @Test
    void isIsin_rejectsTickersAndNonIsinStrings() {
        assertThat(OpenFigiIsinConverter.isIsin("IWDA.AS")).isFalse(); // Yahoo ticker (has a dot)
        assertThat(OpenFigiIsinConverter.isIsin("AAPL")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("BTC")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y98")).isFalse();  // 11 chars
        assertThat(OpenFigiIsinConverter.isIsin("IE00B4L5Y9833")).isFalse(); // 13 chars
        assertThat(OpenFigiIsinConverter.isIsin("12345678901X")).isFalse();  // digits in country position
    }

    @Test
    void isIsin_rejectsNullAndBlank() {
        assertThat(OpenFigiIsinConverter.isIsin(null)).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("")).isFalse();
        assertThat(OpenFigiIsinConverter.isIsin("   ")).isFalse();
    }

    @Test
    void resolve_parsesTickerAndNameForTradeRepublicCryptoIsins() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter();

        // Ticker is now the parsed symbol (not the fake ISIN), so the holding becomes
        // price-resolvable via CoinGeckoPriceProvider instead of staying stuck on averageBuyIn.
        OpenFigiIsinConverter.TickerResult btc = converter.resolve("XF000BTC0017");
        assertThat(btc.ticker()).isEqualTo("BTC");
        assertThat(btc.name()).isEqualTo("Bitcoin");

        OpenFigiIsinConverter.TickerResult eth = converter.resolve("XF000ETH0017");
        assertThat(eth.ticker()).isEqualTo("ETH");
        assertThat(eth.name()).isEqualTo("Ethereum");
    }

    @Test
    void resolve_parsesAnyKnownCryptoSymbolNotJustBtcAndEth() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter();

        // The symbol is parsed generically from the "XF000<SYMBOL><digits>" pattern and
        // validated against CoinGeckoPriceProvider's known tickers -- SOL isn't hardcoded
        // anywhere in OpenFigiIsinConverter, unlike the old 2-entry map (GH issue #22).
        OpenFigiIsinConverter.TickerResult sol = converter.resolve("XF000SOL0042");
        assertThat(sol.ticker()).isEqualTo("SOL");
        assertThat(sol.name()).isEqualTo("SOL");
    }

    @Test
    void resolve_normalizesCaseAndWhitespaceConsistently() {
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter();

        OpenFigiIsinConverter.TickerResult padded = converter.resolve(" xf000btc0017 ");
        assertThat(padded.ticker()).isEqualTo("BTC");
        assertThat(padded.name()).isEqualTo("Bitcoin");
    }
}
