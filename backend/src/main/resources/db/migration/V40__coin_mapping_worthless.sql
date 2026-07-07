-- V40: let a ticker be marked WORTHLESS — a delisted coin CoinGecko can no longer price
-- (e.g. a token whose CoinGecko page is gone, so it can be neither auto-resolved nor linked).
-- Such a row carries resolved_via = 'WORTHLESS' and no coingecko_id, so the column becomes
-- nullable. The ticker is then valued at a known zero rather than left silently unpriced.
-- See CoinMappingService.markWorthless.
ALTER TABLE coin_mapping ALTER COLUMN coingecko_id DROP NOT NULL;
