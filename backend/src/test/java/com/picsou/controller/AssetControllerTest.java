package com.picsou.controller;

import com.picsou.dto.AssetCandidatesResponse;
import com.picsou.dto.AssetMappingRequest;
import com.picsou.dto.AssetResponse;
import com.picsou.model.AssetStatus;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.port.AssetCandidate;
import com.picsou.service.FinancialAssetService;
import com.picsou.service.FinancialAssetService.AggregatorResolution;
import com.picsou.service.FinancialAssetService.AssetResolutionPreview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context) — mirrors {@code AccessKeyControllerTest}. Pins
 * the standing mapping contract: candidates flow through <em>every</em> aggregator block unchanged
 * (each with its current ref and suggestion) even for a settled coin, MAP with a pasted link vs.
 * picked ids per aggregator routes to the right service call, WORTHLESS marks worthless, an unknown
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
    void candidates_returnsEveryAggregatorBlockWithItsCurrentRefAndSuggestion() {
        // The service resolves across every aggregator; the standing editor shows one picker per
        // aggregator, so all blocks flow through (no narrowing), each carrying its own suggestion,
        // its currently-stored ref, and its candidates.
        when(assetService.previewResolution("BTC")).thenReturn(new AssetResolutionPreview(
            "BTC", AssetStatus.USER, List.of(
                new AggregatorResolution("coingecko",
                    new AssetCandidate("bitcoin", "Bitcoin", "btc", 1),
                    "bitcoin",
                    List.of(new AssetCandidate("bitcoin", "Bitcoin", "btc", 1),
                            new AssetCandidate("bitcoin-bep2", "Bitcoin BEP2", "btc", 950))),
                new AggregatorResolution("coinmarketcap",
                    new AssetCandidate("1", "Bitcoin", "BTC", 1),
                    null,
                    List.of(new AssetCandidate("1", "Bitcoin", "BTC", 1))))));

        AssetCandidatesResponse res = controller().candidates("BTC");

        assertThat(res.symbol()).isEqualTo("BTC");
        assertThat(res.currentStatus()).isEqualTo("USER");
        assertThat(res.aggregators()).extracting(AssetCandidatesResponse.AggregatorBlock::aggregatorKey)
            .containsExactly("coingecko", "coinmarketcap");

        AssetCandidatesResponse.AggregatorBlock coingecko = res.aggregators().get(0);
        assertThat(coingecko.suggestedId()).isEqualTo("bitcoin");
        assertThat(coingecko.currentId()).isEqualTo("bitcoin");
        assertThat(coingecko.candidates()).extracting(AssetCandidatesResponse.Candidate::id)
            .containsExactly("bitcoin", "bitcoin-bep2");

        AssetCandidatesResponse.AggregatorBlock coinmarketcap = res.aggregators().get(1);
        assertThat(coinmarketcap.suggestedId()).isEqualTo("1");
        assertThat(coinmarketcap.currentId()).isNull();
    }

    @Test
    void candidates_nullStatusAndEmptyBlockSurviveMapping() {
        when(assetService.previewResolution("XYZ")).thenReturn(new AssetResolutionPreview(
            "XYZ", null, List.of(new AggregatorResolution("coingecko", null, null, List.of()))));

        AssetCandidatesResponse res = controller().candidates("XYZ");

        assertThat(res.currentStatus()).isNull();
        assertThat(res.aggregators()).singleElement()
            .satisfies(b -> {
                assertThat(b.aggregatorKey()).isEqualTo("coingecko");
                assertThat(b.suggestedId()).isNull();
                assertThat(b.currentId()).isNull();
                assertThat(b.candidates()).isEmpty();
            });
    }

    @Test
    void map_withUrl_pinsViaManualMapping() {
        String url = "https://www.coingecko.com/en/coins/loaded-lions";
        when(assetService.setManualMapping("LION", url)).thenReturn(asset("LION", AssetStatus.USER, "loaded-lions"));

        AssetResponse res = controller().map("LION", new AssetMappingRequest("MAP", url, null, null));

        assertThat(res.status()).isEqualTo("USER");
        assertThat(res.coingeckoId()).isEqualTo("loaded-lions");
        verify(assetService).setManualMapping("LION", url);
        verify(assetService, never()).applyMappings(eq("LION"), any(), any());
    }

    @Test
    void map_withPickedIds_pinsViaApplyMappingsWithoutExtraLookup() {
        Map<String, String> ids = Map.of("coingecko", "bitcoin", "coinmarketcap", "1");
        when(assetService.applyMappings("BTC", ids, "Bitcoin"))
            .thenReturn(asset("BTC", AssetStatus.USER, "bitcoin"));

        AssetResponse res = controller().map("BTC", new AssetMappingRequest("map", null, ids, "Bitcoin"));

        assertThat(res.coingeckoId()).isEqualTo("bitcoin");
        verify(assetService).applyMappings("BTC", ids, "Bitcoin");
        verify(assetService, never()).setManualMapping(anyString(), anyString());
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
        verify(assetService, never()).markWorthless(anyString());
    }

    @Test
    void forget_clearsMappingToPendingWithoutDeletingTheRow() {
        when(assetService.clearMapping("BTC")).thenReturn(asset("BTC", AssetStatus.PENDING, null));

        AssetResponse res = controller().forget("BTC");

        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.coingeckoId()).isNull();
        verify(assetService).clearMapping("BTC");
        verify(assetService, never()).delete(anyString());
    }
}
