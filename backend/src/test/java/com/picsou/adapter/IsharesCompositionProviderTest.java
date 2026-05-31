package com.picsou.adapter;

import com.picsou.port.EtfCompositionProvider.EtfHolding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IsharesCompositionProviderTest {

    private final IsharesCompositionProvider provider = new IsharesCompositionProvider();

    private static final String HOLDINGS_CSV = """
            iShares Core MSCI World UCITS ETF USD (Acc),,,,,,,,,
            Fund Holdings as of,"30-May-2026",,,,,,,,
            Inception Date,"25-Sep-2009",,,,,,,,
            ,,,,,,,,,
            Ticker,Name,Sector,Asset Class,Market Value,Weight (%),Notional Value,Shares,Price,Location
            "AAPL","APPLE INC","Information Technology","Equity","1,234,567","4.53","1,234,567","100","180.0","United States"
            "MSFT","MICROSOFT CORP","Information Technology","Equity","1,000,000","3.47","1,000,000","50","400.0","United States"
            "NESN","NESTLE SA","Consumer Staples","Equity","500,000","1.20","500,000","20","100.0","Switzerland"
            "7203","TOYOTA MOTOR CORP","Consumer Discretionary","Equity","400,000","0.90","400,000","10","30.0","Japan"
            ,,,,,,,,,
            "The content contained herein...",,,,,,,,,
            """;

    @Test
    void parseHoldingsCsv_extractsHoldingsWithSectorAndLocation() {
        var parsed = provider.parseHoldingsCsv(HOLDINGS_CSV);

        assertThat(parsed.asOf()).isEqualTo(LocalDate.of(2026, 5, 30));
        assertThat(parsed.holdings()).hasSize(4);

        EtfHolding apple = parsed.holdings().get(0);
        assertThat(apple.name()).isEqualTo("APPLE INC");
        assertThat(apple.weightPercent()).isEqualByComparingTo("4.53");
        assertThat(apple.sector()).isEqualTo("Information Technology");
        assertThat(apple.country()).isEqualTo("United States");
    }

    @Test
    void parseHoldingsCsv_stopsAtBlankLineAfterData() {
        var parsed = provider.parseHoldingsCsv(HOLDINGS_CSV);
        // The disclaimer line after the blank separator must not be parsed as a holding.
        assertThat(parsed.holdings()).noneMatch(h -> h.name().startsWith("The content"));
    }

    @Test
    void parseHoldingsCsv_returnsEmpty_whenNoHeader() {
        var parsed = provider.parseHoldingsCsv("just,some,garbage\nwith,no,header");
        assertThat(parsed.holdings()).isEmpty();
    }

    @Test
    void splitCsvLine_honoursQuotedCommas() {
        List<String> cells = IsharesCompositionProvider.splitCsvLine(
            "\"AAPL\",\"APPLE, INC\",\"1,234\"");
        assertThat(cells).containsExactly("AAPL", "APPLE, INC", "1,234");
    }

    @Test
    void parseScreener_buildsTickerAndIsinLookup() {
        String json = """
            {
              "251882": {
                "localExchangeTicker": {"r": "IWDA"},
                "isin": {"r": "IE00B4L5Y983"},
                "productPageUrl": {"r": "/uk/individual/en/products/251882/ishares-msci-world"}
              },
              "999999": {
                "localExchangeTicker": "EIMI",
                "isin": "IE00BKM4GZ66",
                "productPageUrl": "/uk/individual/en/products/264659/ishares-msci-em-imi"
              }
            }
            """;

        Map<String, String> catalog = provider.parseScreener(json);

        assertThat(catalog).containsEntry("IWDA", "/uk/individual/en/products/251882/ishares-msci-world");
        assertThat(catalog).containsEntry("IE00B4L5Y983", "/uk/individual/en/products/251882/ishares-msci-world");
        assertThat(catalog).containsEntry("EIMI", "/uk/individual/en/products/264659/ishares-msci-em-imi");
    }

    @Test
    void supports_matchesOnIsharesName() {
        assertThat(provider.supports("IWDA.AS", "iShares Core MSCI World UCITS ETF")).isTrue();
        assertThat(provider.supports("CW8.PA", "Amundi MSCI World")).isFalse();
        assertThat(provider.supports("IWDA", null)).isFalse();
    }

    @Test
    void weightParsing_ignoresUnparseableRows() {
        String csv = """
                Header line
                Ticker,Name,Sector,Weight (%),Location
                "A","Alpha","Tech","not-a-number","US"
                "B","Beta","Tech","2.00","US"
                """;
        var parsed = provider.parseHoldingsCsv(csv);
        assertThat(parsed.holdings()).hasSize(1);
        assertThat(parsed.holdings().get(0).name()).isEqualTo("Beta");
        assertThat(parsed.holdings().get(0).weightPercent()).isEqualByComparingTo(new BigDecimal("2.00"));
    }
}
