-- V84: account references financial_asset by FK (asset_id) instead of carrying a ticker string,
-- mirroring the account_holding.asset_id change (V81).
--
-- account.ticker held the live-price symbol of a single-asset account — a crypto/stock account
-- whose whole balance is one coin, priced via that symbol. The pricing layer still speaks in symbol
-- strings — call-sites recover the symbol via the join (account.asset.symbol). Unlike
-- account_holding, the FK is NULLABLE: most accounts (bank, savings, multi-holding investment) have
-- no dedicated asset.
--
-- Same mint-from-existing-data approach as V81: register a PENDING/UNKNOWN financial_asset for every
-- distinct account ticker not already in the registry, then backfill the FK. The runtime counterpart
-- is FinancialAssetService.getOrCreate(symbol), called by AccountService when an account ticker is set.

ALTER TABLE account ADD COLUMN asset_id BIGINT;

-- Mint a passthrough asset for any account ticker not yet in the registry (uppercased to match the
-- registry's uppercase-symbol invariant).
INSERT INTO financial_asset (symbol, type, status)
SELECT DISTINCT UPPER(TRIM(a.ticker)), 'UNKNOWN', 'PENDING'
FROM account a
WHERE a.ticker IS NOT NULL
  AND TRIM(a.ticker) <> ''
  AND UPPER(TRIM(a.ticker)) NOT IN (SELECT symbol FROM financial_asset);

-- Backfill the FK from the (uppercased) ticker.
UPDATE account a
SET asset_id = fa.id
FROM financial_asset fa
WHERE fa.symbol = UPPER(TRIM(a.ticker));

ALTER TABLE account
    ADD CONSTRAINT fk_account_asset FOREIGN KEY (asset_id) REFERENCES financial_asset (id);
CREATE INDEX idx_account_asset_id ON account (asset_id) WHERE asset_id IS NOT NULL;

ALTER TABLE account DROP COLUMN ticker;
