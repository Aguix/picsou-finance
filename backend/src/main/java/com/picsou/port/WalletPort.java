package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

public interface WalletPort {

    String chain();

    /**
     * Returns one entry per asset held at this address. Always at least one
     * entry for the chain's native asset (SOL, ETH, BTC...) — even if zero —
     * plus one entry per non-zero token (SPL on Solana, ERC-20 on Ethereum…).
     */
    List<WalletBalance> fetchBalances(String address);

    record WalletBalance(String symbol, BigDecimal amount) {}
}
