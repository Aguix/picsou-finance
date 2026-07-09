package com.picsou.model;

/**
 * Sub-type of a {@link TransactionType#REWARD} crypto acquisition, used to attribute
 * zero-cost income by the program that produced it. Mapped from Crypto.com App
 * CSV {@code Transaction Kind} values during import.
 */
public enum RewardKind {
    /** Crypto.com Earn interest. */
    EARN,
    /** On-chain / soft staking rewards (ETH2, CRO, ...). */
    STAKING,
    /** Supercharger pool rewards. */
    SUPERCHARGER,
    /** Airdrop Arena and other airdrops. */
    AIRDROP,
    /** Card cashback / reimbursements. */
    CASHBACK,
    /** Referral, sign-up and gift bonuses. */
    REFERRAL,
    /** Promotional campaigns, missions, trading competitions, spins/quests. */
    CAMPAIGN,
    /** DeFi Wallet / liquid-staking yield. */
    DEFI_YIELD,
    /** Any other reward / bonus payout. */
    OTHER
}
