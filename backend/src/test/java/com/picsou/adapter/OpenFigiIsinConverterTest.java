package com.picsou.adapter;

import com.picsou.model.AssetStatus;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.service.FinancialAssetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenFigiIsinConverterTest {

    @Mock private FinancialAssetService assetService;

    private static Optional<FinancialAsset> asset(String symbol, String name) {
        return Optional.of(FinancialAsset.builder()
            .symbol(symbol).name(name).coingeckoId(name.toLowerCase().replace(' ', '-'))
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO)
            .build());
    }

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
    void isTrCryptoIsin_detectsXf000PrefixCaseAndWhitespaceInsensitively() {
        // Shared with TradeRepublicAdapter's exchange choice; must tolerate the same
        // case/whitespace variants resolve()'s normalization does (unlike a raw startsWith).
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("XF000BTC0017")).isTrue();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin(" xf000btc0017 ")).isTrue();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("IE00B4L5Y983")).isFalse(); // real ISIN
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin("BTC")).isFalse();
        assertThat(OpenFigiIsinConverter.isTrCryptoIsin(null)).isFalse();
    }

    @Test
    void resolve_parsesTickerAndNameForTradeRepublicCryptoIsins() {
        when(assetService.resolveCrypto("BTC")).thenReturn(asset("BTC", "Bitcoin"));
        when(assetService.resolveCrypto("ETH")).thenReturn(asset("ETH", "Ethereum"));
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(assetService);

        // Ticker is the parsed symbol (not the fake ISIN), so the holding becomes
        // price-resolvable via the product registry instead of staying stuck on averageBuyIn.
        OpenFigiIsinConverter.TickerResult btc = converter.resolve("XF000BTC0017");
        assertThat(btc.ticker()).isEqualTo("BTC");
        assertThat(btc.name()).isEqualTo("Bitcoin");

        OpenFigiIsinConverter.TickerResult eth = converter.resolve("XF000ETH0017");
        assertThat(eth.ticker()).isEqualTo("ETH");
        assertThat(eth.name()).isEqualTo("Ethereum");
    }

    @Test
    void resolve_parsesAnyResolvableCryptoSymbolNotJustBtcAndEth() {
        when(assetService.resolveCrypto("SOL")).thenReturn(asset("SOL", "Solana"));
        when(assetService.resolveCrypto("MATIC")).thenReturn(asset("MATIC", "Polygon"));
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(assetService);

        // The symbol is parsed generically from the "XF000<SYMBOL><digits>" pattern and resolved
        // through FinancialAssetService — SOL isn't hardcoded anywhere in OpenFigiIsinConverter
        // (GH issue #22), and a coin never seen before is looked up on CoinGecko and registered on
        // the fly. The display name comes from the product registry.
        OpenFigiIsinConverter.TickerResult sol = converter.resolve("XF000SOL0042");
        assertThat(sol.ticker()).isEqualTo("SOL");
        assertThat(sol.name()).isEqualTo("Solana");

        OpenFigiIsinConverter.TickerResult matic = converter.resolve("XF000MATIC0099");
        assertThat(matic.ticker()).isEqualTo("MATIC");
        assertThat(matic.name()).isEqualTo("Polygon");
    }

    @Test
    void resolve_normalizesCaseAndWhitespaceAndCachesTheResult() {
        when(assetService.resolveCrypto("BTC")).thenReturn(asset("BTC", "Bitcoin"));
        OpenFigiIsinConverter converter = new OpenFigiIsinConverter(assetService);

        OpenFigiIsinConverter.TickerResult padded = converter.resolve(" xf000btc0017 ");
        assertThat(padded.ticker()).isEqualTo("BTC");
        assertThat(padded.name()).isEqualTo("Bitcoin");

        // Same ISIN in canonical form hits the converter cache — no second registry lookup.
        OpenFigiIsinConverter.TickerResult canonical = converter.resolve("XF000BTC0017");
        assertThat(canonical.ticker()).isEqualTo("BTC");
        verify(assetService, times(1)).resolveCrypto("BTC");
    }
}
