-- V52: account_holding references financial_asset by FK instead of carrying a ticker string,
-- and no longer carries its own display name either.
--
-- The `ticker` column is fully replaced by `asset_id` (single source of truth =
-- financial_asset.symbol), so every holding is joined to a priceable asset and the drifted crypto
-- skip-list in YahooFinancePriceProvider can be retired. The pricing layer still speaks in symbol
-- strings — call-sites recover the symbol via the join (holding.asset.symbol).
--
-- V51's Seed 2 already registered a financial_asset row for every ticker referenced by a holding
-- or an account EXCEPT EUR, Trade Republic's fake crypto ISINs (XF000…) and raw ISINs. Those
-- residual tickers are minted here on the fly as PENDING/UNKNOWN so no holding is left without an
-- asset (the FK is NOT NULL). A PENDING row carries no aggregator ref, so it stays unpriced until
-- resolved via the management UI / FinancialAssetService.resolveCrypto — exactly as before. The
-- runtime counterpart of this mint is FinancialAssetService.getOrCreate(symbol), called by the
-- holding write paths (TR/Bourso/wallet sync, HoldingComputeService, AccountService.upsertHolding).
--
-- `name` is a property of the asset (BTC is "Bitcoin" no matter which account holds it), not of
-- the (account, asset) pairing, so it moves to financial_asset.name — one label shared by every
-- holding of that symbol instead of a copy per account that could drift out of sync. Existing
-- holding names (set by OpenFIGI on broker sync) backfill financial_asset.name wherever it's still
-- empty; CoinGecko's canonical crypto names from V51 Seed 1 are never overwritten.

ALTER TABLE account_holding ADD COLUMN asset_id BIGINT;

-- Mint a passthrough asset for any holding ticker not yet in the registry.
INSERT INTO financial_asset (symbol, type, status)
SELECT DISTINCT UPPER(TRIM(h.ticker)), 'UNKNOWN', 'PENDING'
FROM account_holding h
WHERE h.ticker IS NOT NULL
  AND TRIM(h.ticker) <> ''
  AND UPPER(TRIM(h.ticker)) NOT IN (SELECT symbol FROM financial_asset);

-- Backfill the FK from the (uppercased) ticker. Every holding matches a row after the mint above.
UPDATE account_holding h
SET asset_id = fa.id
FROM financial_asset fa
WHERE fa.symbol = UPPER(TRIM(h.ticker));

-- Enforce the link and swap the uniqueness invariant from (account, ticker) to (account, asset).
-- One holding per (account, symbol) still holds since symbol ↔ asset_id is 1:1. (Brokers always
-- emit uppercase tickers, so this ADD CONSTRAINT cannot collide on case-variant duplicates.)
ALTER TABLE account_holding ALTER COLUMN asset_id SET NOT NULL;
ALTER TABLE account_holding
    ADD CONSTRAINT fk_account_holding_asset FOREIGN KEY (asset_id) REFERENCES financial_asset (id);
ALTER TABLE account_holding
    ADD CONSTRAINT uk_account_holding_account_asset UNIQUE (account_id, asset_id);

-- Backfill financial_asset.name from whichever holding of that asset already has one (any is fine
-- — name is informational only). Ties broken by lowest holding id for determinism.
UPDATE financial_asset fa
SET name = h.name
FROM (
    SELECT DISTINCT ON (ah.asset_id) ah.asset_id, ah.name
    FROM account_holding ah
    WHERE ah.name IS NOT NULL AND TRIM(ah.name) <> ''
    ORDER BY ah.asset_id, ah.id
) h
WHERE fa.id = h.asset_id AND fa.name IS NULL;

-- Drop the old ticker/name columns; ticker's UNIQUE (account_id, ticker) constraint goes with it.
ALTER TABLE account_holding DROP COLUMN ticker, DROP COLUMN name;

CREATE INDEX idx_account_holding_asset_id ON account_holding (asset_id);
