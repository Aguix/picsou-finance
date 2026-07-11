package com.picsou.controller;

import com.picsou.adapter.price.CoinGeckoPriceProvider.CoinCandidate;
import com.picsou.dto.AssetCandidatesResponse;
import com.picsou.dto.AssetMappingRequest;
import com.picsou.dto.AssetResponse;
import com.picsou.model.AssetStatus;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.service.FinancialAssetService;
import com.picsou.service.FinancialAssetService.AssetResolutionPreview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context) — mirrors {@code AccessKeyControllerTest}. Pins
 * the standing mapping contract: candidates flow through unchanged even for a settled coin, MAP with
 * a link vs. a picked id routes to the right service call, WORTHLESS marks worthless, an unknown
 * action is a 400-worthy {@link IllegalArgumentException}, and DELETE clears the mapping (reverts to
 * PENDING) without deleting the row.
 */
@ExtendWith(MockitoExtension.class)
class AssetControllerTest {

    @Mock FinancialAssetService assetService;

    private AssetController controller() {
        return new AssetController(assetService);
    }

    private static FinancialAsset asset(String symbol, AssetStatus status, String coingeckoId) {
        return FinancialAsset.builder()
            .symbol(symbol).name("Bitcoin").type(AssetType.CRYPTO)
            .status(status).coingeckoId(coingeckoId).build();
    }

    @Test
    void list_mapsWholeRegistry() {
        when(assetService.listAll()).thenReturn(List.of(
            asset("BTC", AssetStatus.USER, "bitcoin"),
            asset("ETH", AssetStatus.AUTO, "ethereum")));

        List<AssetResponse> res = controller().list();

        assertThat(res).extracting(AssetResponse::symbol).containsExactly("BTC", "ETH");
        assertThat(res).extracting(AssetResponse::coingeckoId).containsExactly("bitcoin", "ethereum");
        assertThat(res).extracting(AssetResponse::status).containsExactly("USER", "AUTO");
    }

    @Test
    void candidates_mapsPreviewIncludingSuggestionAndStatus() {
        when(assetService.previewResolution("BTC")).thenReturn(new AssetResolutionPreview(
            "BTC", AssetStatus.USER, new CoinCandidate("bitcoin", "Bitcoin", "btc", 1),
            List.of(new CoinCandidate("bitcoin", "Bitcoin", "btc", 1),
                    new CoinCandidate("bitcoin-bep2", "Bitcoin BEP2", "btc", 950))));

        AssetCandidatesResponse res = controller().candidates("BTC");

        assertThat(res.symbol()).isEqualTo("BTC");
        assertThat(res.currentStatus()).isEqualTo("USER");
        assertThat(res.suggestedId()).isEqualTo("bitcoin");
        assertThat(res.candidates()).extracting(AssetCandidatesResponse.Candidate::coingeckoId)
            .containsExactly("bitcoin", "bitcoin-bep2");
    }

    @Test
    void candidates_nullStatusAndNoSuggestionSurviveMapping() {
        when(assetService.previewResolution("XYZ")).thenReturn(
            new AssetResolutionPreview("XYZ", null, null, List.of()));

        AssetCandidatesResponse res = controller().candidates("XYZ");

        assertThat(res.currentStatus()).isNull();
        assertThat(res.suggestedId()).isNull();
        assertThat(res.candidates()).isEmpty();
    }

    @Test
    void map_withCoingeckoUrl_pinsViaManualMapping() {
        String url = "https://www.coingecko.com/en/coins/loaded-lions";
        when(assetService.setManualMapping("LION", url)).thenReturn(asset("LION", AssetStatus.USER, "loaded-lions"));

        AssetResponse res = controller().map("LION", new AssetMappingRequest("MAP", url, null, null));

        assertThat(res.status()).isEqualTo("USER");
        assertThat(res.coingeckoId()).isEqualTo("loaded-lions");
        verify(assetService).setManualMapping("LION", url);
        verify(assetService, never()).applyUserMapping(eq("LION"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void map_withCandidateId_pinsViaUserMappingWithoutExtraLookup() {
        when(assetService.applyUserMapping("BTC", "bitcoin", "Bitcoin"))
            .thenReturn(asset("BTC", AssetStatus.USER, "bitcoin"));

        AssetResponse res = controller().map("BTC", new AssetMappingRequest("map", null, "bitcoin", "Bitcoin"));

        assertThat(res.coingeckoId()).isEqualTo("bitcoin");
        verify(assetService).applyUserMapping("BTC", "bitcoin", "Bitcoin");
        verify(assetService, never()).setManualMapping(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void map_worthless_marksWorthless() {
        when(assetService.markWorthless("DEAD")).thenReturn(asset("DEAD", AssetStatus.WORTHLESS, null));

        AssetResponse res = controller().map("DEAD", new AssetMappingRequest("WORTHLESS", null, null, null));

        assertThat(res.status()).isEqualTo("WORTHLESS");
        verify(assetService).markWorthless("DEAD");
    }

    @Test
    void map_unknownAction_isRejected() {
        assertThatThrownBy(() -> controller().map("BTC", new AssetMappingRequest("FROB", null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        verify(assetService, never()).markWorthless(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void forget_clearsMappingToPendingWithoutDeletingTheRow() {
        when(assetService.clearMapping("BTC")).thenReturn(asset("BTC", AssetStatus.PENDING, null));

        AssetResponse res = controller().forget("BTC");

        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.coingeckoId()).isNull();
        verify(assetService).clearMapping("BTC");
        verify(assetService, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }
}
