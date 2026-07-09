package com.picsou.crypto;

import com.picsou.model.RewardKind;
import com.picsou.model.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A normalized Picsou-side transaction derived from one exchange/wallet CSV row.
 * A single CSV row can yield 0 (ignored), 1 (buy/sell/reward) or 2 (a swap → sell + buy) of these.
 *
 * @param txType      BUY / SELL / REWARD, or {@code null} for a row recorded for visibility only
 *                    (unknown kind) that must NOT influence holdings.
 * @param rewardKind  non-null only when {@code txType == REWARD}.
 * @param quantity    absolute quantity of {@code ticker} moved.
 * @param pricePerUnit native-fiat cost per unit: the buy price for BUY, the sale price for SELL,
 *                     and {@link BigDecimal#ZERO} for REWARD (free coins, drives the diluted VWAP).
 * @param amount      signed native-fiat cash flow: negative for BUY, positive for SELL, and the
 *                    positive fiat value received for REWARD (income, not a cash movement).
 *                    {@code ZERO} when the source CSV carries no fiat valuation — the import
 *                    service then values the row from the historical price history (enrichment).
 * @param rawKind     the source's own row type, kept verbatim for traceability.
 */
public record ParsedCryptoTx(
    LocalDate date,
    String description,
    String ticker,
    String name,
    TransactionType txType,
    RewardKind rewardKind,
    BigDecimal quantity,
    BigDecimal pricePerUnit,
    BigDecimal amount,
    String nativeCurrency,
    String rawKind
) {

    /** Copy with a fiat valuation filled in by the price-history enrichment step. */
    public ParsedCryptoTx withValuation(BigDecimal newAmount, BigDecimal newPricePerUnit) {
        return new ParsedCryptoTx(date, description, ticker, name, txType, rewardKind,
            quantity, newPricePerUnit, newAmount, nativeCurrency, rawKind);
    }

    /** True when this row moves holdings but carries no usable fiat valuation. */
    public boolean needsValuation() {
        return txType != null
            && quantity != null && quantity.signum() > 0
            && ticker != null && !ticker.isBlank()
            && (amount == null || amount.signum() == 0);
    }
}
