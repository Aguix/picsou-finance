package com.picsou.service;

import com.picsou.adapter.price.CoinGeckoPriceProvider;
import com.picsou.adapter.price.CoinGeckoPriceProvider.CoinCandidate;
import com.picsou.model.AccountHolding;
import com.picsou.model.AssetStatus;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialAssetServiceTest {

    @Mock private CoinGeckoPriceProvider coinGecko;
    @Mock private FinancialAssetRepository repository;
    @Mock private PriceSnapshotRepository priceSnapshotRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountHoldingRepository accountHoldingRepository;
    @Mock private PriceService priceService;

    @InjectMocks private FinancialAssetService service;

    private static CoinCandidate coin(String id, String symbol, Integer rank) {
        return new CoinCandidate(id, id, symbol, rank);
    }

    private void expectSaveEcho() {
        when(repository.save(any(FinancialAsset.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void clearMapping_revertsToPendingAndPurgesHistoryKeepingTheRow() {
        FinancialAsset mapped = FinancialAsset.builder()
            .symbol("BTC").coingeckoId("bitcoin").name("Bitcoin")
            .type(AssetType.CRYPTO).status(AssetStatus.USER).build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(mapped));
        expectSaveEcho();

        FinancialAsset result = service.clearMapping("btc");

        assertThat(result.getStatus()).isEqualTo(AssetStatus.PENDING);
        assertThat(result.getCoingeckoId()).isNull();
        assertThat(result.getName()).isNull();
        verify(priceSnapshotRepository).deleteByTicker("BTC");
        verify(priceService).evictFromCache("BTC");
        verify(repository, never()).delete(any());
    }

    @Test
    void delete_refusesWhenSymbolIsStillHeld() {
        FinancialAsset asset = FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(asset));
        when(accountHoldingRepository.findByTickerIgnoreCase("BTC"))
            .thenReturn(List.of(mock(AccountHolding.class)));

        assertThatThrownBy(() -> service.delete("btc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("still held");

        verify(repository, never()).delete(any());
        verify(priceSnapshotRepository, never()).deleteByTicker(anyString());
    }

    @Test
    void delete_removesOrphanAssetAndPurgesHistory() {
        FinancialAsset asset = FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(asset));
        when(accountHoldingRepository.findByTickerIgnoreCase("BTC")).thenReturn(List.of());

        service.delete("btc");

        verify(repository).delete(asset);
        verify(priceSnapshotRepository).deleteByTicker("BTC");
        verify(priceService).evictFromCache("BTC");
    }

    @Test
    void returnsRegisteredAssetWithoutSearching() {
        FinancialAsset registered = FinancialAsset.builder()
            .symbol("BTC").coingeckoId("bitcoin").status(AssetStatus.AUTO).build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(registered));

        Optional<FinancialAsset> result = service.resolveCrypto("btc");

        assertThat(result).contains(registered);
        verifyNoInteractions(coinGecko);
        verify(repository, never()).save(any());
    }

    @Test
    void resolvesAndPersistsSingleSymbolMatch() {
        when(repository.findBySymbol("SOL")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("SOL")).thenReturn(List.of(coin("solana", "sol", 5)));
        expectSaveEcho();

        Optional<FinancialAsset> result = service.resolveCrypto("SOL");

        assertThat(result).isPresent();
        assertThat(result.get().getCoingeckoId()).isEqualTo("solana");
        assertThat(result.get().getStatus()).isEqualTo(AssetStatus.AUTO);
        assertThat(result.get().getType()).isEqualTo(AssetType.CRYPTO);
        verify(repository).save(any(FinancialAsset.class));
    }

    @Test
    void resolvesDominantMatchByMarketCapRank() {
        // Two coins share the symbol MATIC; the #12 vastly outranks the #900 (12*5=60 <= 900).
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("MATIC")).thenReturn(List.of(
            coin("matic-network", "matic", 12),
            coin("some-clone", "matic", 900)));
        expectSaveEcho();

        Optional<FinancialAsset> result = service.resolveCrypto("MATIC");

        assertThat(result).isPresent();
        assertThat(result.get().getCoingeckoId()).isEqualTo("matic-network");
    }

    @Test
    void keepsAPendingRowWhenRanksAreComparable() {
        // #10 and #14 are comparable (10*5=50 > 14) → don't guess; keep a PENDING row so the
        // symbol shows up in the management UI and is retried later.
        when(repository.findBySymbol("AAA")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("AAA")).thenReturn(List.of(
            coin("alpha", "aaa", 10),
            coin("beta", "aaa", 14)));

        Optional<FinancialAsset> result = service.resolveCrypto("AAA");

        assertThat(result).isEmpty();
        verify(repository).save(argThat(a ->
            a.getSymbol().equals("AAA")
                && a.getStatus() == AssetStatus.PENDING
                && a.getType() == AssetType.CRYPTO
                && a.getCoingeckoId() == null));
    }

    @Test
    void keepsAPendingRowWhenNoSymbolMatches() {
        when(repository.findBySymbol("ZZZ")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("ZZZ")).thenReturn(List.of());

        Optional<FinancialAsset> result = service.resolveCrypto("ZZZ");

        assertThat(result).isEmpty();
        verify(repository).save(argThat(a ->
            a.getSymbol().equals("ZZZ") && a.getStatus() == AssetStatus.PENDING));
    }

    @Test
    void keepsAPendingRowWhenMultipleMatchesButNoneRanked() {
        when(repository.findBySymbol("NUL")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("NUL")).thenReturn(List.of(
            coin("nul-one", "nul", null),
            coin("nul-two", "nul", null)));

        Optional<FinancialAsset> result = service.resolveCrypto("NUL");

        assertThat(result).isEmpty();
        verify(repository).save(argThat(a -> a.getStatus() == AssetStatus.PENDING));
    }

    @Test
    void pendingAssetIsRetriedAndUpgradedOnNextResolve() {
        FinancialAsset pending = FinancialAsset.builder()
            .symbol("AAA").type(AssetType.CRYPTO).status(AssetStatus.PENDING).build();
        when(repository.findBySymbol("AAA")).thenReturn(Optional.of(pending));
        when(coinGecko.searchBySymbol("AAA")).thenReturn(List.of(coin("alpha", "aaa", 10)));
        expectSaveEcho();

        Optional<FinancialAsset> result = service.resolveCrypto("AAA");

        // The existing PENDING row is upgraded in place — no duplicate row for the symbol.
        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(pending);
        assertThat(pending.getCoingeckoId()).isEqualTo("alpha");
        assertThat(pending.getStatus()).isEqualTo(AssetStatus.AUTO);
    }

    @Test
    void pendingAssetIsNotDuplicatedWhenStillUnresolved() {
        FinancialAsset pending = FinancialAsset.builder()
            .symbol("AAA").type(AssetType.CRYPTO).status(AssetStatus.PENDING).build();
        when(repository.findBySymbol("AAA")).thenReturn(Optional.of(pending));
        when(coinGecko.searchBySymbol("AAA")).thenReturn(List.of());

        Optional<FinancialAsset> result = service.resolveCrypto("AAA");

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void resolveAllContinuesAfterAFailureAndReportsUnresolved() {
        when(repository.findBySymbol("BAD")).thenThrow(new RuntimeException("boom"));
        when(repository.findBySymbol("OK")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("OK")).thenReturn(List.of(coin("okcoin", "ok", 3)));
        expectSaveEcho();

        var unresolved = service.resolveAll(new java.util.LinkedHashSet<>(List.of("BAD", "OK")));

        // OK resolved and was persisted; BAD blew up and is reported for manual disambiguation.
        assertThat(unresolved).containsExactly("BAD");
        verify(repository).save(any(FinancialAsset.class));
        verify(coinGecko).searchBySymbol("OK");
    }

    @Test
    void previewResolutionsSurfacesCandidatesAndSkipsSettledCoinsWithoutPersisting() {
        // BTC already USER (settled) → not surfaced. META unseen → surfaced with a dominant guess.
        // AMBI is PENDING with two comparable coins → surfaced, no guess. Nothing is persisted.
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(
            FinancialAsset.builder().symbol("BTC").status(AssetStatus.USER).build()));
        when(repository.findBySymbol("META")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("META")).thenReturn(List.of(
            coin("metabeat", "meta", 300), coin("meta-inu", "meta", 5000)));
        when(repository.findBySymbol("AMBI")).thenReturn(Optional.of(
            FinancialAsset.builder().symbol("AMBI").status(AssetStatus.PENDING).build()));
        when(coinGecko.searchBySymbol("AMBI")).thenReturn(List.of(
            coin("ambi-one", "ambi", 10), coin("ambi-two", "ambi", 14)));

        var previews = service.previewResolutions(
            new java.util.LinkedHashSet<>(List.of("btc", "meta", "ambi")));

        assertThat(previews).extracting(FinancialAssetService.AssetResolutionPreview::symbol)
            .containsExactly("META", "AMBI");
        assertThat(previews.get(0).suggested().id()).isEqualTo("metabeat"); // #300 dominates #5000
        assertThat(previews.get(0).candidates()).hasSize(2);
        assertThat(previews.get(0).currentStatus()).isNull();               // never seen
        assertThat(previews.get(1).suggested()).isNull();                   // #10 vs #14 comparable
        assertThat(previews.get(1).currentStatus()).isEqualTo(AssetStatus.PENDING);
        verify(repository, never()).save(any());
    }

    @Test
    void previewResolutionsDegradesToNoCandidatesWhenTheSearchFails() {
        when(repository.findBySymbol("BOOM")).thenReturn(Optional.empty());
        when(coinGecko.searchBySymbol("BOOM")).thenThrow(new RuntimeException("rate limit"));

        var previews = service.previewResolutions(new java.util.LinkedHashSet<>(List.of("boom")));

        assertThat(previews).hasSize(1);
        assertThat(previews.get(0).symbol()).isEqualTo("BOOM");
        assertThat(previews.get(0).candidates()).isEmpty();
        assertThat(previews.get(0).suggested()).isNull();
    }

    @Test
    void applyUserMappingPinsACandidateIdAsUserWithoutCallingCoinGecko() {
        // The import path pins a coin the operator picked from preview candidates — the id/name are
        // trusted, so no extra /coins/{id} round-trip.
        when(repository.findBySymbol("META")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.applyUserMapping("meta", "metabeat", "MetaBeat");

        assertThat(result.getSymbol()).isEqualTo("META");
        assertThat(result.getCoingeckoId()).isEqualTo("metabeat");
        assertThat(result.getName()).isEqualTo("MetaBeat");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
        assertThat(result.getType()).isEqualTo(AssetType.CRYPTO);
        verify(coinGecko, never()).fetchCoinById(anyString());
    }

    @Test
    void applyUserMappingPurgesHistoryWhenItChangesAnExistingCoinId() {
        FinancialAsset existing = FinancialAsset.builder().symbol("META").coingeckoId("wrong-meta")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(repository.findBySymbol("META")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        service.applyUserMapping("META", "metabeat", "MetaBeat");

        verify(priceSnapshotRepository).deleteByTicker("META");
        verify(priceService).evictFromCache("META");
        verify(priceService).backfillHistoricalPrices(anyMap());
    }

    @Test
    void setManualMappingExtractsIdFromLinkAndPersistsAsUser() {
        when(coinGecko.fetchCoinById("loaded-lions"))
            .thenReturn(Optional.of(new CoinCandidate("loaded-lions", "Loaded Lions", "lion", 3500)));
        when(repository.findBySymbol("LION")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("lion",
            "https://www.coingecko.com/en/coins/loaded-lions");

        assertThat(result.getSymbol()).isEqualTo("LION");
        assertThat(result.getCoingeckoId()).isEqualTo("loaded-lions");
        assertThat(result.getName()).isEqualTo("Loaded Lions");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
        assertThat(result.getType()).isEqualTo(AssetType.CRYPTO);
    }

    @Test
    void setManualMappingReadsSlugFromLocalizedUrlWithQuery() {
        when(coinGecko.fetchCoinById("capybara-nation"))
            .thenReturn(Optional.of(new CoinCandidate("capybara-nation", "Capybara Nation", "bara", null)));
        when(repository.findBySymbol("BARA")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("BARA",
            "https://www.coingecko.com/fr/coins/capybara-nation?utm=share#markets");

        assertThat(result.getCoingeckoId()).isEqualTo("capybara-nation");
        verify(coinGecko).fetchCoinById("capybara-nation");
    }

    @Test
    void setManualMappingOverridesAnExistingAutoMapping() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("MATIC").coingeckoId("wrong-clone").name("Clone")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(coinGecko.fetchCoinById("matic-network"))
            .thenReturn(Optional.of(new CoinCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("MATIC",
            "https://www.coingecko.com/en/coins/matic-network");

        assertThat(result.getCoingeckoId()).isEqualTo("matic-network");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
    }

    @Test
    void remappingToADifferentCoinPurgesAndRefetchesTheTickersPriceHistory() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("MATIC").coingeckoId("wrong-clone")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(coinGecko.fetchCoinById("matic-network"))
            .thenReturn(Optional.of(new CoinCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        service.setManualMapping("MATIC", "https://www.coingecko.com/en/coins/matic-network");

        // Everything priced under the old id is wrong → purged, evicted, and refetched.
        verify(priceSnapshotRepository).deleteByTicker("MATIC");
        verify(priceService).evictFromCache("MATIC");
        verify(priceService).backfillHistoricalPrices(anyMap());
    }

    @Test
    void remappingToTheSameCoinKeepsThePriceHistory() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("MATIC").coingeckoId("matic-network")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(coinGecko.fetchCoinById("matic-network"))
            .thenReturn(Optional.of(new CoinCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.of(existing));
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("MATIC",
            "https://www.coingecko.com/en/coins/matic-network");

        // Same coin — history was fetched from the right source, only the provenance changes.
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
        verifyNoInteractions(priceSnapshotRepository, priceService);
    }

    @Test
    void firstManualMappingDoesNotPurgeAnything() {
        when(coinGecko.fetchCoinById("loaded-lions"))
            .thenReturn(Optional.of(new CoinCandidate("loaded-lions", "Loaded Lions", "lion", 3500)));
        when(repository.findBySymbol("LION")).thenReturn(Optional.empty());
        expectSaveEcho();

        service.setManualMapping("LION", "https://www.coingecko.com/en/coins/loaded-lions");

        verifyNoInteractions(priceSnapshotRepository, priceService);
    }

    @Test
    void remappingSurvivesABackfillFailure() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("MATIC").coingeckoId("wrong-clone")
            .type(AssetType.CRYPTO).status(AssetStatus.AUTO).build();
        when(coinGecko.fetchCoinById("matic-network"))
            .thenReturn(Optional.of(new CoinCandidate("matic-network", "Polygon", "matic", 12)));
        when(repository.findBySymbol("MATIC")).thenReturn(Optional.of(existing));
        when(priceService.backfillHistoricalPrices(anyMap())).thenThrow(new RuntimeException("rate limit"));
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("MATIC",
            "https://www.coingecko.com/en/coins/matic-network");

        // The mapping is corrected even if the refetch fails — the boot runner fills the gap later.
        assertThat(result.getCoingeckoId()).isEqualTo("matic-network");
        verify(priceSnapshotRepository).deleteByTicker("MATIC");
    }

    @Test
    void deleteForgetsTheAssetAndPurgesItsPriceHistory() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("LION").coingeckoId("loaded-lions")
            .type(AssetType.CRYPTO).status(AssetStatus.USER).build();
        when(repository.findBySymbol("LION")).thenReturn(Optional.of(existing));

        service.delete("lion");

        verify(repository).delete(existing);
        verify(priceSnapshotRepository).deleteByTicker("LION");
        verify(priceService).evictFromCache("LION");
    }

    @Test
    void deleteRejectsAnUnknownTicker() {
        when(repository.findBySymbol("ZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("zzz"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).delete(any(FinancialAsset.class));
        verifyNoInteractions(priceSnapshotRepository);
    }

    @Test
    void markWorthlessPinsAZeroValueAndPurgesTheTickersPrices() {
        when(repository.findBySymbol("METABEAT")).thenReturn(Optional.empty());
        when(accountHoldingRepository.findByTickerIgnoreCase("METABEAT")).thenReturn(List.of());
        expectSaveEcho();

        FinancialAsset result = service.markWorthless("metabeat");

        assertThat(result.getSymbol()).isEqualTo("METABEAT");
        assertThat(result.getStatus()).isEqualTo(AssetStatus.WORTHLESS);
        assertThat(result.getCoingeckoId()).isNull();
        assertThat(result.isWorthless()).isTrue();
        // Any price fetched while it was still listed is dropped, live cache evicted, holdings re-valued.
        verify(priceSnapshotRepository).deleteByTicker("METABEAT");
        verify(priceService).evictFromCache("METABEAT");
        verify(accountHoldingRepository).findByTickerIgnoreCase("METABEAT");
        verify(coinGecko, never()).fetchCoinById(anyString());
    }

    @Test
    void markWorthlessZeroesEveryHoldingOfTheTicker() {
        var holdingA = com.picsou.model.AccountHolding.builder()
            .asset(FinancialAsset.builder().symbol("METABEAT").build())
            .quantity(new java.math.BigDecimal("1000"))
            .currentPrice(new java.math.BigDecimal("0.12")).build();
        var holdingB = com.picsou.model.AccountHolding.builder()
            .asset(FinancialAsset.builder().symbol("METABEAT").build())
            .quantity(new java.math.BigDecimal("50")).build();
        when(repository.findBySymbol("METABEAT")).thenReturn(Optional.empty());
        when(accountHoldingRepository.findByTickerIgnoreCase("METABEAT"))
            .thenReturn(List.of(holdingA, holdingB));
        expectSaveEcho();

        service.markWorthless("METABEAT");

        assertThat(holdingA.getCurrentPrice()).isEqualByComparingTo("0");
        assertThat(holdingB.getCurrentPrice()).isEqualByComparingTo("0");
        verify(accountHoldingRepository).saveAll(List.of(holdingA, holdingB));
    }

    @Test
    void markWorthlessOverExistingAssetClearsTheCoinId() {
        FinancialAsset existing = FinancialAsset.builder()
            .symbol("METABEAT").coingeckoId("metabeat").name("MetaBeat")
            .type(AssetType.CRYPTO).status(AssetStatus.USER).build();
        when(repository.findBySymbol("METABEAT")).thenReturn(Optional.of(existing));
        when(accountHoldingRepository.findByTickerIgnoreCase("METABEAT")).thenReturn(List.of());
        expectSaveEcho();

        FinancialAsset result = service.markWorthless("METABEAT");

        assertThat(result.getStatus()).isEqualTo(AssetStatus.WORTHLESS);
        assertThat(result.getCoingeckoId()).isNull();
        assertThat(result.getName()).isNull();
    }

    @Test
    void reMappingAWorthlessTickerRestoresPricing() {
        FinancialAsset worthless = FinancialAsset.builder()
            .symbol("METABEAT").coingeckoId(null)
            .type(AssetType.CRYPTO).status(AssetStatus.WORTHLESS).build();
        when(coinGecko.fetchCoinById("metabeat"))
            .thenReturn(Optional.of(new CoinCandidate("metabeat", "MetaBeat", "metabeat", 4200)));
        when(repository.findBySymbol("METABEAT")).thenReturn(Optional.of(worthless));
        expectSaveEcho();

        FinancialAsset result = service.setManualMapping("METABEAT",
            "https://www.coingecko.com/en/coins/metabeat");

        // Was worthless (no id) → now a real USER mapping; no purge since there was no old coin id.
        assertThat(result.getStatus()).isEqualTo(AssetStatus.USER);
        assertThat(result.getCoingeckoId()).isEqualTo("metabeat");
        verify(priceSnapshotRepository, never()).deleteByTicker(anyString());
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

    @Test
    void getOrCreateReturnsExistingAssetWithoutSaving() {
        FinancialAsset existing = FinancialAsset.builder().symbol("BTC").coingeckoId("bitcoin").build();
        when(repository.findBySymbol("BTC")).thenReturn(Optional.of(existing));

        FinancialAsset result = service.getOrCreate("btc");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void getOrCreateMintsABarePendingAssetWhenSymbolIsUnseen() {
        when(repository.findBySymbol("SHIB")).thenReturn(Optional.empty());
        expectSaveEcho();

        FinancialAsset result = service.getOrCreate("shib");

        assertThat(result.getSymbol()).isEqualTo("SHIB");
        assertThat(result.getType()).isEqualTo(AssetType.UNKNOWN);
        assertThat(result.getStatus()).isEqualTo(AssetStatus.PENDING);
        assertThat(result.getName()).isNull();
    }

    @Test
    void fillNameIfAbsentSetsTheNameWhenAssetHasNone() {
        FinancialAsset asset = FinancialAsset.builder().symbol("IWDA.AS").build();
        expectSaveEcho();

        service.fillNameIfAbsent(asset, "iShares Core MSCI World UCITS ETF");

        assertThat(asset.getName()).isEqualTo("iShares Core MSCI World UCITS ETF");
        verify(repository).save(asset);
    }

    @Test
    void fillNameIfAbsentNeverOverwritesAnExistingName() {
        // A canonical name (CoinGecko, a prior sync) must survive a later, possibly worse label.
        FinancialAsset asset = FinancialAsset.builder().symbol("BTC").name("Bitcoin").build();

        service.fillNameIfAbsent(asset, "some broker's label for BTC");

        assertThat(asset.getName()).isEqualTo("Bitcoin");
        verify(repository, never()).save(any());
    }

    @Test
    void fillNameIfAbsentIgnoresNullOrBlankNames() {
        FinancialAsset asset = FinancialAsset.builder().symbol("VWCE.DE").build();

        service.fillNameIfAbsent(asset, null);
        service.fillNameIfAbsent(asset, "   ");

        assertThat(asset.getName()).isNull();
        verify(repository, never()).save(any());
    }
}
