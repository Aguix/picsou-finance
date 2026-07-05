package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.CoinGeckoPriceProvider.CoinCandidate;
import com.picsou.model.CoinMapping;
import com.picsou.repository.CoinMappingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoinMappingServiceTest {

    @Mock private CoinGeckoPriceProvider coinGecko;
    @Mock private CoinMappingRepository repository;

    @InjectMocks private CoinMappingService service;

    private static CoinCandidate coin(String id, String symbol, Integer rank) {
        return new CoinCandidate(id, id, symbol, rank);
    }

    private void expectSaveEcho() {
        when(repository.save(any(CoinMapping.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void returnsCachedMappingWithoutSearching() {
        CoinMapping cached = CoinMapping.builder().ticker("BTC").coingeckoId("bitcoin").build();
        when(repository.findByTicker("BTC")).thenReturn(Optional.of(cached));

        Optional<CoinMapping> result = service.resolve("btc");

        assertThat(result).contains(cached);
        verifyNoInteractions(coinGecko);
        verify(repository, never()).save(any());
    }

    @Test
    void resolvesAndPersistsSingleSymbolMatch() {
        when(repository.findByTicker("SOL")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("SOL")).thenReturn(List.of(coin("solana", "sol", 5)));
        expectSaveEcho();

        Optional<CoinMapping> result = service.resolve("SOL");

        assertThat(result).isPresent();
        assertThat(result.get().getCoingeckoId()).isEqualTo("solana");
        assertThat(result.get().getResolvedVia()).isEqualTo("AUTO");
        verify(repository).save(any(CoinMapping.class));
    }

    @Test
    void resolvesDominantMatchByMarketCapRank() {
        // Two coins share the symbol MATIC; the #12 vastly outranks the #900 (12*5=60 <= 900).
        when(repository.findByTicker("MATIC")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("MATIC")).thenReturn(List.of(
            coin("matic-network", "matic", 12),
            coin("some-clone", "matic", 900)));
        expectSaveEcho();

        Optional<CoinMapping> result = service.resolve("MATIC");

        assertThat(result).isPresent();
        assertThat(result.get().getCoingeckoId()).isEqualTo("matic-network");
    }

    @Test
    void leavesAmbiguousComparableRanksUnresolved() {
        // #10 and #14 are comparable (10*5=50 > 14) → don't guess.
        when(repository.findByTicker("AAA")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("AAA")).thenReturn(List.of(
            coin("alpha", "aaa", 10),
            coin("beta", "aaa", 14)));

        Optional<CoinMapping> result = service.resolve("AAA");

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void leavesUnresolvedWhenNoSymbolMatches() {
        when(repository.findByTicker("ZZZ")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("ZZZ")).thenReturn(List.of());

        Optional<CoinMapping> result = service.resolve("ZZZ");

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void leavesUnresolvedWhenMultipleMatchesButNoneRanked() {
        when(repository.findByTicker("NUL")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("NUL")).thenReturn(List.of(
            coin("nul-one", "nul", null),
            coin("nul-two", "nul", null)));

        Optional<CoinMapping> result = service.resolve("NUL");

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void resolveAllContinuesAfterAFailureAndReportsUnresolved() {
        when(repository.findByTicker("BAD")).thenThrow(new RuntimeException("boom"));
        when(repository.findByTicker("OK")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("OK")).thenReturn(List.of(coin("okcoin", "ok", 3)));
        expectSaveEcho();

        var unresolved = service.resolveAll(new java.util.LinkedHashSet<>(List.of("BAD", "OK")));

        // OK resolved and was persisted; BAD blew up and is reported for manual disambiguation.
        assertThat(unresolved).containsExactly("BAD");
        verify(repository).save(any(CoinMapping.class));
        verify(coinGecko).searchBySymbol("OK");
    }

    @Test
    void setManualMappingExtractsIdFromLinkAndPersistsAsUser() {
        when(coinGecko.fetchCoinById("loaded-lions"))
            .thenReturn(Optional.of(new CoinCandidate("loaded-lions", "Loaded Lions", "lion", 3500)));
        when(repository.findByTicker("LION")).thenReturn(Optional.empty());
        expectSaveEcho();

        CoinMapping result = service.setManualMapping("lion",
            "https://www.coingecko.com/en/coins/loaded-lions");

        assertThat(result.getTicker()).isEqualTo("LION");
        assertThat(result.getCoingeckoId()).isEqualTo("loaded-lions");
        assertThat(result.getCoinName()).isEqualTo("Loaded Lions");
        assertThat(result.getResolvedVia()).isEqualTo("USER");
    }

    @Test
    void setManualMappingReadsSlugFromLocalizedUrlWithQuery() {
        when(coinGecko.fetchCoinById("capybara-nation"))
            .thenReturn(Optional.of(new CoinCandidate("capybara-nation", "Capybara Nation", "bara", null)));
        when(repository.findByTicker("BARA")).thenReturn(Optional.empty());
        expectSaveEcho();

        CoinMapping result = service.setManualMapping("BARA",
            "https://www.coingecko.com/fr/coins/capybara-nation?utm=share#markets");

        assertThat(result.getCoingeckoId()).isEqualTo("capybara-nation");
        verify(coinGecko).fetchCoinById("capybara-nation");
    }

    @Test
    void setManualMappingOverridesAnExistingAutoMapping() {
        CoinMapping existing = CoinMapping.builder()
            .ticker("MATIC").coingeckoId("wrong-clone").coinName("Clone").resolvedVia("AUTO").build();
        when(coinGecko.fetchCoinById("matic-network"))
            .thenReturn(Optional.of(new CoinCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findByTicker("MATIC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        CoinMapping result = service.setManualMapping("MATIC",
            "https://www.coingecko.com/en/coins/matic-network");

        assertThat(result.getCoingeckoId()).isEqualTo("matic-network");
        assertThat(result.getResolvedVia()).isEqualTo("USER");
    }

    @Test
    void setManualMappingRejectsALinkThatIsNotACoinUrl() {
        assertThatThrownBy(() -> service.setManualMapping("BTC", "https://www.coingecko.com/en/categories"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(coinGecko);
        verify(repository, never()).save(any());
    }

    @Test
    void setManualMappingRejectsAnUnknownCoinId() {
        when(coinGecko.fetchCoinById("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setManualMapping("XYZ",
            "https://www.coingecko.com/en/coins/does-not-exist"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }
}
