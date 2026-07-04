-- Crypto.com CSV import: classify zero-cost reward acquisitions (Earn interest,
-- staking, Supercharger, Airdrop Arena, cashback, referral) so per-crypto gain
-- statistics can attribute income by program type. tx_type gains a new logical
-- value REWARD (the column is a plain VARCHAR(20), no DB-level enum to extend).
-- reward_kind is non-null only for REWARD rows; see RewardKind.java.
ALTER TABLE transaction
  ADD COLUMN reward_kind VARCHAR(20) NULL;

CREATE INDEX idx_transaction_reward_kind ON transaction(account_id, reward_kind);
