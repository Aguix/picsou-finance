package com.picsou.crypto.parsers;

import com.picsou.crypto.CryptoCsvParser;
import com.picsou.crypto.CsvTable;
import com.picsou.crypto.ParsedCryptoTx;
import com.picsou.model.RewardKind;
import com.picsou.model.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.picsou.crypto.ParserSupport.buy;
import static com.picsou.crypto.ParserSupport.inert;
import static com.picsou.crypto.ParserSupport.isFiat;
import static com.picsou.crypto.ParserSupport.num;
import static com.picsou.crypto.ParserSupport.parseDate;
import static com.picsou.crypto.ParserSupport.reward;
import static com.picsou.crypto.ParserSupport.sell;
import static com.picsou.crypto.ParserSupport.upper;

/**
 * Crypto.com <b>App</b> transaction-history export. Header:
 * <pre>
 * Timestamp (UTC),Transaction Description,Currency,Amount,To Currency,To Amount,
 * Native Currency,Native Amount,Native Amount (in USD),Transaction Kind,Transaction Hash
 * </pre>
 *
 * <p>Each row is classified by its {@code Transaction Kind}:
 * <ul>
 *   <li><b>BUY</b> — fiat-funded purchases and inbound transfers, at the row's native-fiat cost.
 *       A fiat-funded purchase carries the fiat leg in {@code Currency/Amount} and the crypto
 *       bought in {@code To Currency/To Amount} — the crypto leg wins when present.</li>
 *   <li><b>SELL</b> — sales, payments, outbound transfers and reward clawbacks.</li>
 *   <li><b>SWAP</b> ({@code crypto_exchange}) — a SELL of the source coin plus a BUY of the
 *       destination coin, both valued at the row's native fiat amount.</li>
 *   <li><b>REWARD</b> — Earn interest, staking, Supercharger, Airdrop Arena, cashback, referral,
 *       campaigns, DeFi yield: zero-cost acquisitions tagged with a {@link RewardKind}.</li>
 *   <li><b>ignored</b> — Earn/Supercharger lock &amp; unlock, fiat-only movements, DeFi-Wallet
 *       principal moves ({@code finance.*} except interest legs) and limit-order reservation
 *       legs: net-zero on the aggregated holdings.</li>
 *   <li><b>unknown</b> — anything else is recorded with {@code txType == null}: visible, inert.</li>
 * </ul>
 */
@Component
public class CryptoComAppParser implements CryptoCsvParser {

    private static final Set<String> BUY_KINDS = Set.of(
        "crypto_purchase", "viban_purchase", "recurring_buy_order",
        "crypto_deposit", "dust_conversion_credited",
        // Limit order finally filled — the matching purchase_lock/unlock legs are ignored so the
        // buy is only counted once (see isIgnored).
        "trading.limit_order.cash_account.purchase_commit");

    private static final Set<String> SELL_KINDS = Set.of(
        "crypto_withdrawal", "crypto_payment", "card_spend", "pay_checkout",
        "crypto_to_external_wallet", "dust_conversion_debited");

    private static final Set<String> SWAP_KINDS = Set.of("crypto_exchange");

    private static final Set<String> IGNORED_KINDS = Set.of(
        "crypto_earn_program_created", "crypto_earn_program_withdrawn",
        "supercharger_deposit", "supercharger_withdrawal",
        "lockup_lock", "lockup_unlock", "lockup_upgrade",
        "viban_deposit", "viban_withdrawal", "fiat_deposit", "fiat_withdrawal",
        "card_top_up", "van_initiated_withdrawal");

    /** Exact reward kind → {@link RewardKind}. Containment fallbacks live in {@link #rewardKindFor}. */
    private static final Map<String, RewardKind> REWARD_KINDS = Map.ofEntries(
        Map.entry("crypto_earn_interest_paid", RewardKind.EARN),
        Map.entry("crypto_earn_extra_interest_paid", RewardKind.EARN),
        Map.entry("crypto_earn_reward", RewardKind.EARN),
        Map.entry("mco_stake_reward", RewardKind.STAKING),
        Map.entry("staking_reward", RewardKind.STAKING),
        Map.entry("eth2_staking_reward", RewardKind.STAKING),
        Map.entry("soft_staking_reward", RewardKind.STAKING),
        Map.entry("supercharger_reward_to_app_credited", RewardKind.SUPERCHARGER),
        Map.entry("supercharger_reward", RewardKind.SUPERCHARGER),
        Map.entry("airdrop_locked", RewardKind.AIRDROP),
        Map.entry("airdrop_to_exchange_transfer", RewardKind.AIRDROP),
        // Card cashback and merchant reimbursements (Visa card rebates).
        Map.entry("card_cashback", RewardKind.CASHBACK),
        Map.entry("card_cashback_reverted", RewardKind.CASHBACK),
        Map.entry("merchant_cashback", RewardKind.CASHBACK),
        Map.entry("prime_cashback", RewardKind.CASHBACK),
        Map.entry("reimbursement", RewardKind.CASHBACK),
        // Crypto.com labels Visa-card cashback rows with this kind despite the "referral_" prefix.
        Map.entry("referral_card_cashback", RewardKind.CASHBACK),
        Map.entry("referral_bonus", RewardKind.REFERRAL),
        Map.entry("referral_gift", RewardKind.REFERRAL),
        Map.entry("signup_bonus", RewardKind.REFERRAL),
        Map.entry("gift_card_reward", RewardKind.REFERRAL),
        // DeFi Wallet / liquid-staking yield.
        Map.entry("defi_staking_reward", RewardKind.DEFI_YIELD),
        Map.entry("defi_yield_reward", RewardKind.DEFI_YIELD),
        Map.entry("liquid_staking_reward", RewardKind.DEFI_YIELD),
        Map.entry("cdc_defi_reward", RewardKind.DEFI_YIELD),
        // Promotional campaigns, missions, trading competitions, spins/quests.
        Map.entry("campaign_reward", RewardKind.CAMPAIGN),
        Map.entry("mission_reward", RewardKind.CAMPAIGN),
        Map.entry("trading_reward", RewardKind.CAMPAIGN),
        Map.entry("trading_competition_reward", RewardKind.CAMPAIGN),
        Map.entry("crypto_random_reward", RewardKind.CAMPAIGN),
        Map.entry("spin_reward", RewardKind.CAMPAIGN),
        Map.entry("lucky_draw_win", RewardKind.CAMPAIGN),
        Map.entry("quest_reward", RewardKind.CAMPAIGN),
        // "Mystery Box" promo rewards from the rewards platform.
        Map.entry("rewards_platform_deposit_credited", RewardKind.CAMPAIGN));

    @Override
    public String sourceId() {
        return "cryptocom_app";
    }

    @Override
    public String label() {
        return "Crypto.com App";
    }

    @Override
    public String provider() {
        return "Crypto.com";
    }

    @Override
    public boolean supports(CsvTable table) {
        return table.hasColumns("timestamp (utc)", "currency", "transaction kind");
    }

    @Override
    public List<ParsedCryptoTx> parse(CsvTable table) {
        List<ParsedCryptoTx> out = new ArrayList<>();
        for (int i = 0; i < table.rowCount(); i++) {
            out.addAll(mapRow(
                table.get(i, "timestamp (utc)"),
                table.get(i, "transaction description"),
                table.get(i, "currency"),
                table.get(i, "amount"),
                table.get(i, "to currency"),
                table.get(i, "to amount"),
                table.get(i, "native currency"),
                table.get(i, "native amount"),
                table.get(i, "transaction kind")));
        }
        return out;
    }

    /** Convert one row into 0..2 normalized transactions. Package-private for direct testing. */
    List<ParsedCryptoTx> mapRow(String timestampUtc, String description, String currency,
                                String amount, String toCurrency, String toAmount,
                                String nativeCurrency, String nativeAmount, String transactionKind) {
        LocalDate date = parseDate(timestampUtc);
        if (date == null) {
            return List.of();
        }
        String kind = transactionKind == null ? "" : transactionKind.trim().toLowerCase();
        if (kind.isEmpty() || isIgnored(kind)) {
            return List.of();
        }

        String ticker = upper(currency);
        BigDecimal qty = num(amount);
        BigDecimal nativeValue = num(nativeAmount).abs();
        String nativeCcy = nativeCurrency == null || nativeCurrency.isBlank()
            ? "EUR" : nativeCurrency.trim().toUpperCase();
        String desc = description == null || description.isBlank() ? humanize(kind) : description.trim();

        if (SWAP_KINDS.contains(kind)) {
            List<ParsedCryptoTx> out = new ArrayList<>(2);
            BigDecimal toQty = num(toAmount).abs();
            String toTicker = upper(toCurrency);
            if (!ticker.isEmpty() && !isFiat(ticker) && qty.signum() != 0) {
                out.add(sell(date, desc, ticker, qty.abs(), nativeValue, nativeCcy, kind));
            }
            if (!toTicker.isEmpty() && !isFiat(toTicker) && toQty.signum() > 0) {
                out.add(buy(date, desc, toTicker, toQty, nativeValue, nativeCcy, kind));
            }
            return out;
        }

        RewardKind rewardKind = rewardKindFor(kind);
        if (rewardKind != null) {
            // A negative reward row is a clawback (e.g. card_cashback_reverted) → reduce holdings.
            if (qty.signum() < 0) {
                return List.of(sell(date, desc, ticker, qty.abs(), nativeValue, nativeCcy, kind));
            }
            if (ticker.isEmpty() || isFiat(ticker) || qty.signum() <= 0) {
                return List.of();
            }
            return List.of(reward(date, desc, ticker, qty.abs(), rewardKind, nativeValue, nativeCcy, kind));
        }

        if (BUY_KINDS.contains(kind)) {
            // Real App export: a fiat-funded purchase carries the fiat leg in Currency/Amount
            // (e.g. EUR, -50.00) and the crypto received in To Currency/To Amount (e.g. ETH,
            // 0.033507). Prefer that crypto leg, valued at the row's native fiat cost.
            String toTicker = upper(toCurrency);
            BigDecimal toQty = num(toAmount).abs();
            if (!toTicker.isEmpty() && !isFiat(toTicker) && toQty.signum() > 0) {
                return List.of(buy(date, desc, toTicker, toQty, nativeValue, nativeCcy, kind));
            }
            // Older export shape: the crypto sits directly in the Currency column (positive amount).
            if (ticker.isEmpty() || isFiat(ticker) || qty.signum() <= 0) {
                return List.of();
            }
            return List.of(buy(date, desc, ticker, qty.abs(), nativeValue, nativeCcy, kind));
        }

        if (SELL_KINDS.contains(kind)) {
            if (ticker.isEmpty() || isFiat(ticker) || qty.signum() == 0) {
                return List.of();
            }
            return List.of(sell(date, desc, ticker, qty.abs(), nativeValue, nativeCcy, kind));
        }

        // Unknown kind: keep it visible but inert (no holdings impact).
        return List.of(inert(date, desc, ticker, num(nativeAmount), nativeCcy, kind));
    }

    /**
     * Kinds that must not touch holdings: principal moved into a DeFi/staking/airdrop/lockup
     * program (still owned — net-zero), and limit-order reservation bookkeeping (only the matching
     * {@code purchase_commit} is the real buy). Interest payouts are <em>not</em> ignored — they
     * are rewards handled by {@link #rewardKindFor}.
     */
    static boolean isIgnored(String kind) {
        if (IGNORED_KINDS.contains(kind)) return true;
        // Limit-order lock/cancel legs: the fill is recorded separately as purchase_commit.
        if (kind.contains("purchase_lock") || kind.contains("purchase_unlock")) return true;
        // DeFi Wallet namespace (finance.<product>.<action>.crypto_wallet): drop principal moves
        // (staking/lending deposits, Airdrop Arena deposits, card lockups). Keep the interest legs.
        if (kind.startsWith("finance.") && !kind.contains("compound_interest")) return true;
        return false;
    }

    static RewardKind rewardKindFor(String kind) {
        RewardKind exact = REWARD_KINDS.get(kind);
        if (exact != null) {
            return exact;
        }
        // DeFi Wallet & DPoS interest payouts: finance.<product>.(non_)compound_interest.crypto_wallet.
        // DPoS is on-chain staking; defi_staking / defi_lending are DeFi-wallet yield.
        if (kind.contains("compound_interest")) {
            return kind.contains("dpos") ? RewardKind.STAKING : RewardKind.DEFI_YIELD;
        }
        // Defensive fallbacks for kinds Crypto.com may add/rename. Order matters: the more
        // specific buckets (defi, campaign) are tested before the generic staking/reward ones.
        if (kind.contains("airdrop")) return RewardKind.AIRDROP;
        if (kind.contains("defi")) return RewardKind.DEFI_YIELD;
        if (kind.contains("supercharger") && kind.contains("reward")) return RewardKind.SUPERCHARGER;
        if (kind.contains("campaign") || kind.contains("mission") || kind.contains("spin")
                || kind.contains("lucky") || kind.contains("quest")
                || (kind.contains("trading") && kind.contains("reward"))) return RewardKind.CAMPAIGN;
        if (kind.contains("stake") || kind.contains("staking")) return RewardKind.STAKING;
        if (kind.contains("earn") && kind.contains("interest")) return RewardKind.EARN;
        if (kind.contains("cashback")) return RewardKind.CASHBACK;
        if (kind.contains("referral") || kind.contains("bonus")) return RewardKind.REFERRAL;
        if (kind.contains("reward")) return RewardKind.OTHER;
        return null;
    }

    private static String humanize(String kind) {
        String spaced = kind.replace('_', ' ').trim();
        return spaced.isEmpty() ? "Crypto.com" : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
