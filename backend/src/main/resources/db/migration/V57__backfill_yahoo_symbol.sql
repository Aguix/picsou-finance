-- V57: one-shot backfill of yahoo_symbol for existing stock/ETF assets, ahead of gating
-- YahooFinancePriceProvider.canPrice() on yahoo_symbol instead of falling back to the internal
-- symbol. For every asset discovered so far via TR/Bourso ISIN resolution or a manual stock
-- ticker, the internal symbol already IS the Yahoo ticker (e.g. "IWDA.AS") -- OpenFIGI resolved
-- it at discovery time, not a guess -- so yahoo_symbol = symbol is exact. Going forward,
-- FinancialAssetService.getOrCreateStock() does the same thing at discovery time; this migration
-- only catches what it didn't see.
--
-- Scoping is deliberately conservative so an unresolved coin never becomes Yahoo-priceable on its
-- bare symbol (which for collisions like LINK/APE/GALA would yield a WRONG stock price):
--   * type = 'UNKNOWN' -- every pre-existing stock/ETF was minted UNKNOWN (no code sets STOCK/ETF
--     before this change), while resolveCrypto types ALL its rows CRYPTO (even PENDING/failed ones,
--     including TR-native crypto), so 'UNKNOWN' cleanly targets stocks and skips resolveCrypto coins.
--   * coingecko_id IS NULL -- redundant guard against any linked coin.
--   * NOT held/referenced by a CRYPTO-type account -- exchange (Binance) and on-chain wallet
--     holdings bypass resolveCrypto entirely (accountService.upsertHolding -> getOrCreate), so they
--     land as UNKNOWN with no coingecko_id and type alone can't flag them. This clause is what keeps
--     a Binance/wallet coin out of the backfill.
UPDATE financial_asset fa
SET yahoo_symbol = fa.symbol,
    type = 'STOCK'
WHERE fa.type = 'UNKNOWN'
  AND fa.coingecko_id IS NULL
  AND fa.yahoo_symbol IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM account_holding ah
      JOIN account a ON a.id = ah.account_id
      WHERE ah.asset_id = fa.id AND a.type = 'CRYPTO'
  )
  AND NOT EXISTS (
      SELECT 1 FROM account a
      WHERE a.asset_id = fa.id AND a.type = 'CRYPTO'
  );
