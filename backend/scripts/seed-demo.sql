-- =============================================================================
-- Picsou — demo data seed
-- =============================================================================
-- Adds a set of demo accounts (stocks & ETF, a second crypto wallet, a bank
-- checking account with a monthly balance, and a Livret A) onto the ADMIN
-- member, on top of whatever is already in the database.
--
-- Idempotent: re-running deletes the seeded accounts by name first, so it never
-- creates duplicates. Holdings / balance snapshots / transactions cascade with
-- the account. Prices and dates are relative to CURRENT_DATE, so the history
-- always looks recent no matter when you reseed.
--
-- Schema note: holdings reference `financial_asset` by FK (asset_id). A
-- holding's displayed value is driven by `financial_asset.last_eur_value`, so
-- the seed sets that (plus the account's stored current_balance) explicitly.
--
-- Run:
--   docker exec -i -e PGPASSWORD=<pwd> <db-container> \
--     psql -U <user> -d <db> -v ON_ERROR_STOP=1 < backend/scripts/seed-demo.sql
--   (or use backend/scripts/seed-demo.ps1)
-- =============================================================================

\set ON_ERROR_STOP on

-- Resolve the ADMIN member id once into :admin_member (psql client variable).
SELECT member_id AS admin_member
FROM app_user
WHERE role = 'ADMIN'
ORDER BY id
LIMIT 1 \gset

\echo 'Seeding demo accounts onto admin member id =' :admin_member

BEGIN;

-- ---------------------------------------------------------------------------
-- 1) Financial assets (upsert by symbol). Crypto rows already exist from the
--    Crypto.com import; we (re)price the ones our holdings use. Stocks & ETFs
--    are created here.
-- ---------------------------------------------------------------------------
INSERT INTO financial_asset (symbol, name, type, status, isin, yahoo_symbol, last_eur_value, price_synced_at)
VALUES
  ('BTC',  'Bitcoin',                     'CRYPTO', 'AUTO', NULL,           NULL,       95000.00, now()),
  ('ADA',  'Cardano',                     'CRYPTO', 'AUTO', NULL,           NULL,           0.55, now()),
  ('USDC', 'USDC',                        'CRYPTO', 'AUTO', NULL,           NULL,           0.92, now()),
  ('AAPL', 'Apple Inc.',                  'STOCK',  'AUTO', 'US0378331005', 'AAPL',       220.00, now()),
  ('MSFT', 'Microsoft Corp.',             'STOCK',  'AUTO', 'US5949181045', 'MSFT',       420.00, now()),
  ('CW8',  'Amundi MSCI World UCITS ETF', 'ETF',    'AUTO', 'LU1681043599', 'CW8.PA',     520.00, now()),
  ('IWDA', 'iShares Core MSCI World ETF', 'ETF',    'AUTO', 'IE00B4L5Y983', 'IWDA.AS',    105.00, now())
ON CONFLICT (symbol) DO UPDATE SET
  name            = EXCLUDED.name,
  type            = EXCLUDED.type,
  status          = EXCLUDED.status,
  isin            = COALESCE(financial_asset.isin, EXCLUDED.isin),
  yahoo_symbol    = COALESCE(financial_asset.yahoo_symbol, EXCLUDED.yahoo_symbol),
  last_eur_value  = EXCLUDED.last_eur_value,
  price_synced_at = EXCLUDED.price_synced_at,
  updated_at      = now();

-- ---------------------------------------------------------------------------
-- 2) Clean slate for the seeded accounts (idempotency). Cascades to holdings,
--    balance snapshots and transactions.
-- ---------------------------------------------------------------------------
DELETE FROM account
WHERE member_id = :admin_member
  AND name IN ('Actions & ETF', 'Kraken Crypto', 'Compte Courant', 'Livret A');

-- ---------------------------------------------------------------------------
-- 3) Accounts
--    current_balance = Σ(quantity × asset price) for holding-based accounts.
-- ---------------------------------------------------------------------------
INSERT INTO account (name, type, provider, currency, current_balance, is_manual, color, member_id, last_synced_at)
VALUES
  ('Actions & ETF',  'COMPTE_TITRES', 'Trade Republic',   'EUR', 22810.00, true, '#0ea5e9', :admin_member, now()),
  ('Kraken Crypto',  'CRYPTO',        'KRAKEN',           'EUR', 10265.00, true, '#7c3aed', :admin_member, now()),
  ('Compte Courant', 'CHECKING',      'Boursorama',       'EUR',  4275.00, true, '#22c55e', :admin_member, now()),
  ('Livret A',       'SAVINGS',       'Caisse d''Epargne','EUR', 15000.00, true, '#f59e0b', :admin_member, now());

-- ---------------------------------------------------------------------------
-- 4) Holdings (resolved to financial_asset by symbol)
-- ---------------------------------------------------------------------------
-- Actions & ETF: 15*220 + 8*420 + 25*520 + 30*105 = 22810  (invested 17050)
INSERT INTO account_holding (account_id, asset_id, quantity, average_buy_in, current_price, last_synced_at)
SELECT a.id, fa.id, v.qty, v.avg, v.price, now()
FROM account a
JOIN (VALUES
  ('AAPL', 15, 150, 220),
  ('MSFT',  8, 300, 420),
  ('CW8',  25, 400, 520),
  ('IWDA', 30,  80, 105)
) AS v(sym, qty, avg, price) ON true
JOIN financial_asset fa ON fa.symbol = v.sym
WHERE a.name = 'Actions & ETF' AND a.member_id = :admin_member;

-- Kraken Crypto: 0.08*95000 + 1500*0.55 + 2000*0.92 = 10265  (invested 6040)
INSERT INTO account_holding (account_id, asset_id, quantity, average_buy_in, current_price, last_synced_at)
SELECT a.id, fa.id, v.qty, v.avg, v.price, now()
FROM account a
JOIN (VALUES
  ('BTC',  0.08, 45000, 95000),
  ('ADA',  1500,  0.40,   0.55),
  ('USDC', 2000,  0.92,   0.92)
) AS v(sym, qty, avg, price) ON true
JOIN financial_asset fa ON fa.symbol = v.sym
WHERE a.name = 'Kraken Crypto' AND a.member_id = :admin_member;

-- ---------------------------------------------------------------------------
-- 5) Balance snapshots
-- ---------------------------------------------------------------------------
-- Daily history (last 30 days) for the investment/crypto accounts: interpolated
-- upward trend with noise, ending exactly on current_balance today.
INSERT INTO balance_snapshot (account_id, date, balance, invested_amount)
SELECT a.id, d::date,
  ROUND((18800 + (22810 - 18800) * ((d::date - (CURRENT_DATE - 29))::numeric / 29)
         + (random() - 0.5) * 450)::numeric, 2),
  17050
FROM account a,
     generate_series(CURRENT_DATE - 29, CURRENT_DATE - 1, INTERVAL '1 day') d
WHERE a.name = 'Actions & ETF' AND a.member_id = :admin_member;

INSERT INTO balance_snapshot (account_id, date, balance, invested_amount)
SELECT a.id, CURRENT_DATE, 22810.00, 17050
FROM account a WHERE a.name = 'Actions & ETF' AND a.member_id = :admin_member;

INSERT INTO balance_snapshot (account_id, date, balance, invested_amount)
SELECT a.id, d::date,
  ROUND((8200 + (10265 - 8200) * ((d::date - (CURRENT_DATE - 29))::numeric / 29)
         + (random() - 0.5) * 300)::numeric, 2),
  6040
FROM account a,
     generate_series(CURRENT_DATE - 29, CURRENT_DATE - 1, INTERVAL '1 day') d
WHERE a.name = 'Kraken Crypto' AND a.member_id = :admin_member;

INSERT INTO balance_snapshot (account_id, date, balance, invested_amount)
SELECT a.id, CURRENT_DATE, 10265.00, 6040
FROM account a WHERE a.name = 'Kraken Crypto' AND a.member_id = :admin_member;

-- Monthly history (6 prior month-ends + today) for the cash accounts.
-- Compte Courant: fluctuating balance.
INSERT INTO balance_snapshot (account_id, date, balance, invested_amount)
SELECT a.id, m.d, m.bal, m.bal
FROM account a
JOIN LATERAL (
  SELECT (date_trunc('month', CURRENT_DATE) - make_interval(months => v.k)
          + INTERVAL '1 month' - INTERVAL '1 day')::date AS d,
         v.bal::numeric AS bal
  FROM (VALUES (6, 3200), (5, 3550), (4, 2980), (3, 4100), (2, 3820), (1, 4450)) AS v(k, bal)
  UNION ALL
  SELECT CURRENT_DATE, 4275
) m ON true
WHERE a.name = 'Compte Courant' AND a.member_id = :admin_member;

-- Livret A: steadily growing (deposits + interest).
INSERT INTO balance_snapshot (account_id, date, balance, invested_amount)
SELECT a.id, m.d, m.bal, m.bal
FROM account a
JOIN LATERAL (
  SELECT (date_trunc('month', CURRENT_DATE) - make_interval(months => v.k)
          + INTERVAL '1 month' - INTERVAL '1 day')::date AS d,
         v.bal::numeric AS bal
  FROM (VALUES (6, 12000), (5, 12500), (4, 13000), (3, 13500), (2, 14000), (1, 14500)) AS v(k, bal)
  UNION ALL
  SELECT CURRENT_DATE, 15000
) m ON true
WHERE a.name = 'Livret A' AND a.member_id = :admin_member;

COMMIT;

\echo 'Done. Seeded accounts:'
SELECT a.id, a.name, a.type, a.current_balance
FROM account a
WHERE a.member_id = :admin_member
  AND a.name IN ('Actions & ETF', 'Kraken Crypto', 'Compte Courant', 'Livret A')
ORDER BY a.id;
