package com.picsou.service;

import com.picsou.model.FinancialAsset;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock PriceRouter priceRouter;
    @Mock PriceSnapshotRepository priceSnapshotRepository;
    @Mock FinancialAssetRepository assetRepository;

    @InjectMocks PriceService service;

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<FinancialAsset>> assetCollectionCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    @Test
    void getPriceEur_unregisteredTicker_ridesTransientAssetCarryingYahooSymbolSoYahooStillPricesIt() {
        // The MCP get_price tool (and the currency seam) hand a bare ticker with no registry row.
        // Since canPrice now gates on yahoo_symbol, the transient asset must carry it or Yahoo would
        // reject an explicitly-requested ticker — the regression this locks down.
        when(assetRepository.findBySymbol("AAPL")).thenReturn(Optional.empty());
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of("AAPL", new BigDecimal("192.50")));

        BigDecimal price = service.getPriceEur("AAPL");

        assertThat(price).isEqualByComparingTo("192.50");

        ArgumentCaptor<Collection<FinancialAsset>> captor = assetCollectionCaptor();
        org.mockito.Mockito.verify(priceRouter).getPricesEur(captor.capture());
        FinancialAsset routed = captor.getValue().iterator().next();
        assertThat(routed.getSymbol()).isEqualTo("AAPL");
        assertThat(routed.getYahooSymbol()).isEqualTo("AAPL"); // verbatim, as before the gate
    }

    @Test
    void getPriceEur_registeredAssetWithoutYahooSymbol_isRoutedAsIsAndStaysGated() {
        // A registry row is deliberately NOT given a yahoo_symbol by this seam — it's only priceable
        // once resolved, so an unresolved coin doesn't waste a Yahoo call. Contrast with the transient
        // path above.
        FinancialAsset registered = FinancialAsset.builder().symbol("SHIB").build();
        when(assetRepository.findBySymbol("SHIB")).thenReturn(Optional.of(registered));
        when(priceRouter.getPricesEur(any())).thenReturn(Map.of());

        BigDecimal price = service.getPriceEur("SHIB");

        assertThat(price).isNull();
        ArgumentCaptor<Collection<FinancialAsset>> captor = assetCollectionCaptor();
        org.mockito.Mockito.verify(priceRouter).getPricesEur(captor.capture());
        assertThat(captor.getValue().iterator().next().getYahooSymbol()).isNull();
    }

    @Test
    void getPriceEur_eurOrBlank_returnsOneWithoutTouchingProviders() {
        assertThat(service.getPriceEur("EUR")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(service.getPriceEur("eur")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(service.getPriceEur((String) null)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(service.getPriceEur("  ")).isEqualByComparingTo(BigDecimal.ONE);
        org.mockito.Mockito.verifyNoInteractions(priceRouter);
    }

    @Test
    void getIntradayPricesEur_unregisteredTicker_ridesTransientAssetCarryingYahooSymbol() {
        when(assetRepository.findBySymbol("MC.PA")).thenReturn(Optional.empty());
        var from = java.time.LocalDateTime.of(2024, 1, 1, 0, 0);
        var to = java.time.LocalDateTime.of(2024, 1, 2, 0, 0);
        when(priceRouter.getIntradayPricesEur(any(), any(), any())).thenReturn(Map.of());

        service.getIntradayPricesEur("MC.PA", from, to);

        ArgumentCaptor<FinancialAsset> captor = ArgumentCaptor.forClass(FinancialAsset.class);
        org.mockito.Mockito.verify(priceRouter).getIntradayPricesEur(captor.capture(), any(), any());
        assertThat(captor.getValue().getYahooSymbol()).isEqualTo("MC.PA");
    }
}
