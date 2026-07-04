-- V39: Dynamic ticker → CoinGecko coin-id mapping.
-- Replaces the hardcoded TICKER_TO_ID map that used to live in CoinGeckoPriceProvider.
-- Rows are a persistent cache: resolved automatically from CoinGecko's /search (a single
-- dominant match by market-cap rank auto-resolves) or entered by the user when a symbol is
-- ambiguous. The table starts EMPTY — nothing is seeded — and fills in as crypto is imported.
-- See CoinMappingService.java for the resolution rules.
CREATE TABLE coin_mapping (
    -- Uppercase ticker as seen in the CSV / holding (BTC, ETH, …). One id per ticker.
    ticker          VARCHAR(30) PRIMARY KEY,
    -- CoinGecko coin id (e.g. 'bitcoin', 'matic-network'). Used verbatim in API calls.
    coingecko_id    VARCHAR(100) NOT NULL,
    -- Human name from CoinGecko at resolution time; purely informational.
    coin_name       VARCHAR(120),
    -- How this row was decided: 'AUTO' (single/dominant market-cap match) or 'USER'
    -- (operator supplied the CoinGecko link when the symbol was ambiguous).
    resolved_via    VARCHAR(10) NOT NULL DEFAULT 'AUTO',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
