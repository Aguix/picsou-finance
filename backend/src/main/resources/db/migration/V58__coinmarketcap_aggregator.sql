-- V58: CoinMarketCap as a third price aggregator — its ref column on financial_asset, and its
-- `aggregator` row so the operator can add an API key from the admin panel.
--
-- Every aggregator owns exactly one nullable ref column here (coingecko_id, yahoo_symbol, and now
-- coinmarketcap_id): the column holds the id that aggregator's own API is called with, and a NULL
-- means it can't price that asset. That's the whole capability rule — no asset-type gating — so a
-- new aggregator is one column, one entity field, one adapter.
--
-- CoinMarketCap ids are numeric ("1" = Bitcoin), stored as text like the other refs: the value is
-- opaque to us and goes into the URL verbatim.
ALTER TABLE financial_asset ADD COLUMN coinmarketcap_id VARCHAR(20);

-- No session seeded — the operator adds the API key from the admin panel. Unlike CoinGecko,
-- CoinMarketCap has no anonymous tier, so with no key it neither prices nor resolves anything: it
-- simply doesn't participate until a key exists.
INSERT INTO aggregator (aggregator_key, display_name) VALUES
    ('coinmarketcap', 'CoinMarketCap');
