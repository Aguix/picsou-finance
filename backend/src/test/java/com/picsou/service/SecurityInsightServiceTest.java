package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.port.EtfCompositionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityInsightServiceTest {

    @Mock YahooFinancePriceProvider yahoo;
    @Mock CoinGeckoPriceProvider coinGecko;

    private SecurityInsightService serviceWith(EtfCompositionProvider... providers) {
        return new SecurityInsightService(List.of(providers), yahoo, coinGecko);
    }

    private static EtfCompositionProvider fakeProvider(boolean supports,
                                                       EtfCompositionProvider.RawEtfHoldings holdings) {
        return new EtfCompositionProvider() {
            @Override public boolean supports(String ticker, String name) { return supports; }
            @Override public Optional<RawEtfHoldings> fetch(String ticker, String name) {
                return Optional.ofNullable(holdings);
            }
        };
    }

    @Test
    void crypto_isDetectedFromCoinGecko_andHasNoComposition() {
        when(coinGecko.supports("BTC")).thenReturn(true);
        var service = serviceWith();

        SecurityInsightResponse r = service.getInsight("BTC", "Bitcoin");

        assertThat(r.assetType()).isEqualTo("CRYPTO");
        assertThat(r.composition()).isNull();
        verify(yahoo, never()).getInstrumentType("BTC");
    }

    @Test
    void equity_mapsToStock_withNoComposition() {
        when(coinGecko.supports("MC.PA")).thenReturn(false);
        when(yahoo.getInstrumentType("MC.PA")).thenReturn(Optional.of("EQUITY"));
        var service = serviceWith();

        SecurityInsightResponse r = service.getInsight("MC.PA", "LVMH");

        assertThat(r.assetType()).isEqualTo("STOCK");
        assertThat(r.composition()).isNull();
    }

    @Test
    void unknown_whenInstrumentTypeMissing() {
        when(coinGecko.supports("XYZ")).thenReturn(false);
        when(yahoo.getInstrumentType("XYZ")).thenReturn(Optional.empty());
        var service = serviceWith();

        assertThat(service.getInsight("XYZ", null).assetType()).isEqualTo("UNKNOWN");
    }

    @Test
    void etf_aggregatesCompaniesCountriesAndSectors() {
        when(coinGecko.supports("IWDA")).thenReturn(false);
        when(yahoo.getInstrumentType("IWDA")).thenReturn(Optional.of("ETF"));

        var raw = new EtfCompositionProvider.RawEtfHoldings(List.of(
            new EtfCompositionProvider.EtfHolding("APPLE", new BigDecimal("4.5"), "United States", "Tech"),
            new EtfCompositionProvider.EtfHolding("MICROSOFT", new BigDecimal("3.5"), "United States", "Tech"),
            new EtfCompositionProvider.EtfHolding("NESTLE", new BigDecimal("1.0"), "Switzerland", "Staples")
        ), "iShares", LocalDate.of(2026, 5, 30));

        var service = serviceWith(fakeProvider(true, raw));

        SecurityInsightResponse r = service.getInsight("IWDA", "iShares Core MSCI World");

        assertThat(r.assetType()).isEqualTo("ETF");
        assertThat(r.composition()).isNotNull();
        assertThat(r.composition().source()).isEqualTo("iShares");
        // Companies sorted desc by weight
        assertThat(r.composition().companies()).extracting(s -> s.label())
            .containsExactly("APPLE", "MICROSOFT", "NESTLE");
        // United States = 4.5 + 3.5 = 8.0, ahead of Switzerland
        assertThat(r.composition().countries()).first()
            .satisfies(s -> {
                assertThat(s.label()).isEqualTo("United States");
                assertThat(s.percent()).isEqualByComparingTo("8.00");
            });
        // Tech sector summed
        assertThat(r.composition().sectors()).first()
            .satisfies(s -> {
                assertThat(s.label()).isEqualTo("Tech");
                assertThat(s.percent()).isEqualByComparingTo("8.00");
            });
    }

    @Test
    void etf_withNoResolvingProvider_hasNullComposition() {
        when(coinGecko.supports("CW8")).thenReturn(false);
        when(yahoo.getInstrumentType("CW8")).thenReturn(Optional.of("ETF"));
        // Provider does not support this issuer.
        var service = serviceWith(fakeProvider(false, null));

        SecurityInsightResponse r = service.getInsight("CW8", "Amundi MSCI World");

        assertThat(r.assetType()).isEqualTo("ETF");
        assertThat(r.composition()).isNull();
    }

    @Test
    void result_isCached_acrossCalls() {
        lenient().when(coinGecko.supports("MC.PA")).thenReturn(false);
        when(yahoo.getInstrumentType("MC.PA")).thenReturn(Optional.of("EQUITY"));
        var service = serviceWith();

        service.getInsight("MC.PA", "LVMH");
        service.getInsight("MC.PA", "LVMH");

        // Second call served from cache — Yahoo hit only once.
        verify(yahoo, times(1)).getInstrumentType("MC.PA");
    }
}
