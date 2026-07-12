-- V56: price_snapshot references financial_asset by FK (asset_id) instead of a ticker string,
-- mirroring account_holding (V52) and account (V55). A daily price snapshot is a fact about an
-- asset, so it's keyed by the asset's id; the (ticker, date) uniqueness becomes (asset_id, date).
--
-- Every priced symbol goes through the financial_asset registry now, so a passthrough asset is
-- minted (PENDING/UNKNOWN) for any snapshot ticker not yet registered, then the FK is backfilled.
-- The runtime counterpart is the resolution PriceService does before writing a snapshot.

ALTER TABLE price_snapshot ADD COLUMN asset_id BIGINT;

-- Mint a passthrough asset for any snapshot ticker not yet in the registry (uppercased to match the
-- registry's uppercase-symbol invariant).
INSERT INTO financial_asset (symbol, type, status)
SELECT DISTINCT UPPER(TRIM(ps.ticker)), 'UNKNOWN', 'PENDING'
FROM price_snapshot ps
WHERE ps.ticker IS NOT NULL
  AND TRIM(ps.ticker) <> ''
  AND UPPER(TRIM(ps.ticker)) NOT IN (SELECT symbol FROM financial_asset);

-- Backfill the FK from the (uppercased) ticker.
UPDATE price_snapshot ps
SET asset_id = fa.id
FROM financial_asset fa
WHERE fa.symbol = UPPER(TRIM(ps.ticker));

-- Every snapshot references an asset now (each ticker got minted above), so enforce NOT NULL + FK.
ALTER TABLE price_snapshot ALTER COLUMN asset_id SET NOT NULL;
ALTER TABLE price_snapshot
    ADD CONSTRAINT fk_price_snapshot_asset FOREIGN KEY (asset_id) REFERENCES financial_asset (id);

-- Swap the uniqueness/index from (ticker, date) to (asset_id, date).
ALTER TABLE price_snapshot DROP CONSTRAINT IF EXISTS uk_price_snapshot_ticker_date;
DROP INDEX IF EXISTS idx_price_snapshot_ticker_date;
ALTER TABLE price_snapshot
    ADD CONSTRAINT uk_price_snapshot_asset_date UNIQUE (asset_id, date);
CREATE INDEX idx_price_snapshot_asset_date ON price_snapshot (asset_id, date);

ALTER TABLE price_snapshot DROP COLUMN ticker;
