package com.picsou.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual V57 backfill SQL (loaded verbatim from the classpath) against seeded rows on
 * an in-memory H2 in PostgreSQL-compatibility mode. The project's other tests are pure Mockito and
 * never boot Flyway, so this is a focused check that V57's row-selection logic does what its scoping
 * clauses claim: link pre-existing stock/ETF rows with {@code yahoo_symbol = symbol}, while leaving
 * every crypto flavour untouched — including the exchange/wallet coins that are minted UNKNOWN (not
 * CRYPTO) and can only be told apart from a stock by the CRYPTO-account exclusion.
 *
 * <p>The three tables are created with just the columns V57 touches (not the full Flyway DDL), so
 * this validates the migration's <em>logic</em>, not its schema compatibility.
 */
class V57BackfillYahooSymbolTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(
            "jdbc:h2:mem:v57;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE financial_asset (
                    id BIGINT PRIMARY KEY,
                    symbol VARCHAR(30) NOT NULL UNIQUE,
                    type VARCHAR(10) NOT NULL,
                    coingecko_id VARCHAR(100),
                    yahoo_symbol VARCHAR(50)
                )""");
            st.execute("""
                CREATE TABLE account (
                    id BIGINT PRIMARY KEY,
                    type VARCHAR(20) NOT NULL,
                    asset_id BIGINT
                )""");
            st.execute("""
                CREATE TABLE account_holding (
                    id BIGINT PRIMARY KEY,
                    account_id BIGINT NOT NULL,
                    asset_id BIGINT NOT NULL
                )""");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        conn.close();
    }

    private void asset(long id, String symbol, String type, String coingeckoId) throws Exception {
        try (var ps = conn.prepareStatement(
                "INSERT INTO financial_asset (id, symbol, type, coingecko_id, yahoo_symbol) VALUES (?,?,?,?,NULL)")) {
            ps.setLong(1, id);
            ps.setString(2, symbol);
            ps.setString(3, type);
            ps.setString(4, coingeckoId);
            ps.executeUpdate();
        }
    }

    private void account(long id, String type, Long assetId) throws Exception {
        try (var ps = conn.prepareStatement("INSERT INTO account (id, type, asset_id) VALUES (?,?,?)")) {
            ps.setLong(1, id);
            ps.setString(2, type);
            if (assetId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, assetId);
            ps.executeUpdate();
        }
    }

    private void holding(long id, long accountId, long assetId) throws Exception {
        try (var ps = conn.prepareStatement(
                "INSERT INTO account_holding (id, account_id, asset_id) VALUES (?,?,?)")) {
            ps.setLong(1, id);
            ps.setLong(2, accountId);
            ps.setLong(3, assetId);
            ps.executeUpdate();
        }
    }

    private void runV57() throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(
                "/db/migration/V57__backfill_yahoo_symbol.sql")) {
            assertThat(in).as("V57 migration file on classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private String yahooSymbolOf(String symbol) throws Exception {
        try (var ps = conn.prepareStatement("SELECT yahoo_symbol FROM financial_asset WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private String typeOf(String symbol) throws Exception {
        try (var ps = conn.prepareStatement("SELECT type FROM financial_asset WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    @Test
    void backfillsUnknownStockNotHeldOnACryptoAccount() throws Exception {
        // A pre-existing stock/ETF (all such rows are UNKNOWN — nothing set STOCK before V57).
        asset(1, "IWDA.AS", "UNKNOWN", null);

        runV57();

        assertThat(yahooSymbolOf("IWDA.AS")).isEqualTo("IWDA.AS");
        assertThat(typeOf("IWDA.AS")).isEqualTo("STOCK");
    }

    @Test
    void backfillsUnknownStockHeldOnABrokerageAccount() throws Exception {
        asset(2, "MC.PA", "UNKNOWN", null);
        account(101, "PEA", null);
        holding(1, 101, 2);

        runV57();

        assertThat(yahooSymbolOf("MC.PA")).isEqualTo("MC.PA");
        assertThat(typeOf("MC.PA")).isEqualTo("STOCK");
    }

    @Test
    void skipsExchangeCoinMintedUnknownButHeldOnACryptoAccount() throws Exception {
        // A Binance coin: reaches the registry via getOrCreate → UNKNOWN, no coingecko_id, so type
        // alone can't flag it as crypto. The CRYPTO-account holding is what must exclude it, or its
        // bare symbol would become a WRONG Yahoo quote (e.g. LINK/APE/GALA collide with stock tickers).
        asset(3, "DOGE", "UNKNOWN", null);
        account(100, "CRYPTO", null);
        holding(2, 100, 3);

        runV57();

        assertThat(yahooSymbolOf("DOGE")).isNull();
        assertThat(typeOf("DOGE")).isEqualTo("UNKNOWN");
    }

    @Test
    void skipsWalletCoinReferencedByACryptoAccountAsset() throws Exception {
        // A wallet coin can be the account-level ticker (account.asset_id) rather than a holding.
        asset(4, "ETH", "UNKNOWN", null);
        account(102, "CRYPTO", 4L);

        runV57();

        assertThat(yahooSymbolOf("ETH")).isNull();
    }

    @Test
    void skipsResolvedAndPendingCryptoByType() throws Exception {
        // resolveCrypto types ALL its rows CRYPTO — a linked coin and a still-PENDING one alike —
        // so the type filter excludes both regardless of coingecko_id.
        asset(5, "BTC", "CRYPTO", "bitcoin");
        asset(6, "AAA", "CRYPTO", null);

        runV57();

        assertThat(yahooSymbolOf("BTC")).isNull();
        assertThat(yahooSymbolOf("AAA")).isNull();
    }

    @Test
    void isIdempotentAndDoesNotOverwriteAnExistingYahooSymbol() throws Exception {
        // A row already carrying a yahoo_symbol (from getOrCreateStock at discovery) is left as-is,
        // so re-running the migration can't clobber it.
        asset(7, "AAPL", "STOCK", null);
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE financial_asset SET yahoo_symbol = 'AAPL' WHERE symbol = 'AAPL'");
        }

        runV57();
        runV57();

        assertThat(yahooSymbolOf("AAPL")).isEqualTo("AAPL");
        assertThat(typeOf("AAPL")).isEqualTo("STOCK");
    }
}
