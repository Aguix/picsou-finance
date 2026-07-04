package com.picsou.model;

public enum TransactionType {
    DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND, FEE,
    /**
     * A crypto position acquired at zero cash cost — interest, staking yield,
     * Supercharger / Airdrop Arena payouts, cashback. Counts toward quantity and
     * dilutes the average buy-in (free coins lower cost basis); the program that
     * produced it is recorded in {@link RewardKind}.
     */
    REWARD
}
