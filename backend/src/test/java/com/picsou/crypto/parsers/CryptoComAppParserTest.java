package com.picsou.crypto.parsers;

import com.picsou.crypto.CsvTable;
import com.picsou.crypto.ParsedCryptoTx;
import com.picsou.model.RewardKind;
import com.picsou.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoComAppParserTest {

    private final CryptoComAppParser parser = new CryptoComAppParser();

    /** kind used as both description and transaction kind; no To leg. */
    private List<ParsedCryptoTx> map(String kind, String currency, String amount, String nativeAmount) {
        return parser.mapRow("2024-05-10 08:00:00", kind, currency, amount, "", "", "EUR", nativeAmount, kind);
    }

    @Test
    void detectsAppExportHeader() {
        CsvTable app = CsvTable.parse(
            "Timestamp (UTC),Transaction Description,Currency,Amount,To Currency,To Amount,"
            + "Native Currency,Native Amount,Native Amount (in USD),Transaction Kind,Transaction Hash\n");
        CsvTable foreign = CsvTable.parse("Date,Label,Amount\n");

        assertThat(parser.supports(app)).isTrue();
        assertThat(parser.supports(foreign)).isFalse();
    }

    @Test
    void parsesFullTable() {
        CsvTable table = CsvTable.parse(
            "Timestamp (UTC),Transaction Description,Currency,Amount,To Currency,To Amount,"
            + "Native Currency,Native Amount,Native Amount (in USD),Transaction Kind,Transaction Hash\n"
            + "2026-06-23 12:28:31,Bought ETH,EUR,-50.00,ETH,0.033507,EUR,50.0,57.20,viban_purchase,\n"
            + "2026-06-23 06:13:10,CRO Pool Rewards,BTC,0.00000102,,,EUR,0.055,0.063,supercharger_reward_to_app_credited,\n");

        List<ParsedCryptoTx> out = parser.parse(table);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).txType()).isEqualTo(TransactionType.BUY);
        assertThat(out.get(0).ticker()).isEqualTo("ETH");
        assertThat(out.get(1).txType()).isEqualTo(TransactionType.REWARD);
        assertThat(out.get(1).rewardKind()).isEqualTo(RewardKind.SUPERCHARGER);
    }

    @Test
    void purchase_mapsToBuyWithCostBasis() {
        List<ParsedCryptoTx> out = map("viban_purchase", "BTC", "0.01", "300.00");

        assertThat(out).hasSize(1);
        ParsedCryptoTx tx = out.get(0);
        assertThat(tx.txType()).isEqualTo(TransactionType.BUY);
        assertThat(tx.ticker()).isEqualTo("BTC");
        assertThat(tx.quantity()).isEqualByComparingTo("0.01");
        // 300 / 0.01 = 30000 per unit
        assertThat(tx.pricePerUnit()).isEqualByComparingTo("30000");
        // Cash flow is negative (money out)
        assertThat(tx.amount()).isEqualByComparingTo("-300.00");
        assertThat(tx.date()).isEqualTo(LocalDate.of(2024, 5, 10));
    }

    @Test
    void fiatFundedPurchase_readsCryptoFromToCurrency() {
        // Real Crypto.com App export: Currency=EUR/-50, crypto bought sits in To Currency/To Amount.
        List<ParsedCryptoTx> out = parser.mapRow(
            "2026-06-23 12:28:31", "Bought ETH", "EUR", "-50.00", "ETH", "0.033507",
            "EUR", "50.0", "viban_purchase");

        assertThat(out).hasSize(1);
        ParsedCryptoTx tx = out.get(0);
        assertThat(tx.txType()).isEqualTo(TransactionType.BUY);
        assertThat(tx.ticker()).isEqualTo("ETH");
        assertThat(tx.quantity()).isEqualByComparingTo("0.033507");
        // 50 / 0.033507 ≈ 1492.23 per ETH
        assertThat(tx.pricePerUnit()).isBetween(new BigDecimal("1492"), new BigDecimal("1493"));
        assertThat(tx.amount()).isEqualByComparingTo("-50.0");
    }

    @Test
    void earnInterest_mapsToRewardEarnAtZeroCost() {
        List<ParsedCryptoTx> out = map("crypto_earn_interest_paid", "CRO", "5", "1.25");

        assertThat(out).hasSize(1);
        ParsedCryptoTx tx = out.get(0);
        assertThat(tx.txType()).isEqualTo(TransactionType.REWARD);
        assertThat(tx.rewardKind()).isEqualTo(RewardKind.EARN);
        assertThat(tx.quantity()).isEqualByComparingTo("5");
        assertThat(tx.pricePerUnit()).isEqualByComparingTo("0");
        // Reward value is recorded as positive income
        assertThat(tx.amount()).isEqualByComparingTo("1.25");
    }

    @Test
    void rewardKinds_areClassifiedByProgram() {
        assertThat(map("mco_stake_reward", "CRO", "1", "0.5").get(0).rewardKind())
            .isEqualTo(RewardKind.STAKING);
        assertThat(map("supercharger_reward_to_app_credited", "CRO", "1", "0.5").get(0).rewardKind())
            .isEqualTo(RewardKind.SUPERCHARGER);
        assertThat(map("airdrop_locked", "SHIB", "1000", "0.5").get(0).rewardKind())
            .isEqualTo(RewardKind.AIRDROP);
        assertThat(map("card_cashback", "CRO", "2", "0.9").get(0).rewardKind())
            .isEqualTo(RewardKind.CASHBACK);
        assertThat(map("referral_bonus", "CRO", "10", "4").get(0).rewardKind())
            .isEqualTo(RewardKind.REFERRAL);
    }

    @Test
    void campaignRewards_areClassifiedAsCampaign() {
        assertThat(map("campaign_reward", "CRO", "5", "1").get(0).rewardKind())
            .isEqualTo(RewardKind.CAMPAIGN);
        assertThat(map("mission_reward", "CRO", "5", "1").get(0).rewardKind())
            .isEqualTo(RewardKind.CAMPAIGN);
        assertThat(map("trading_reward", "CRO", "5", "1").get(0).rewardKind())
            .isEqualTo(RewardKind.CAMPAIGN);
        // Unknown future campaign-ish kind resolves via the containment fallback.
        assertThat(map("weekly_spin_reward", "CRO", "5", "1").get(0).rewardKind())
            .isEqualTo(RewardKind.CAMPAIGN);
    }

    @Test
    void defiYieldRewards_areClassifiedAsDefiYield() {
        assertThat(map("defi_staking_reward", "DOT", "1", "5").get(0).rewardKind())
            .isEqualTo(RewardKind.DEFI_YIELD);
        assertThat(map("liquid_staking_reward", "ETH", "0.01", "20").get(0).rewardKind())
            .isEqualTo(RewardKind.DEFI_YIELD);
        // "defi" containment wins over the generic "stake"/"reward" fallbacks.
        assertThat(map("cdc_defi_some_new_reward", "CRO", "5", "1").get(0).rewardKind())
            .isEqualTo(RewardKind.DEFI_YIELD);
    }

    @Test
    void cardCashback_isClassifiedAsCashback() {
        assertThat(map("card_cashback", "CRO", "2", "0.9").get(0).rewardKind())
            .isEqualTo(RewardKind.CASHBACK);
        assertThat(map("prime_cashback", "CRO", "2", "0.9").get(0).rewardKind())
            .isEqualTo(RewardKind.CASHBACK);
        assertThat(map("merchant_cashback", "CRO", "2", "0.9").get(0).rewardKind())
            .isEqualTo(RewardKind.CASHBACK);
        // Crypto.com tags real Visa-card cashback rows as referral_card_cashback.
        assertThat(map("referral_card_cashback", "CRO", "5.3", "0.28").get(0).rewardKind())
            .isEqualTo(RewardKind.CASHBACK);
    }

    @Test
    void defiWalletInterest_isClassifiedByProduct() {
        // finance.<product>.(non_)compound_interest.crypto_wallet — positive yield payouts.
        assertThat(map("finance.defi_staking.non_compound_interest.crypto_wallet", "LION", "80.86", "0.106")
            .get(0).rewardKind()).isEqualTo(RewardKind.DEFI_YIELD);
        assertThat(map("finance.defi_lending.compound_interest.crypto_wallet", "ETH", "0.00000469", "0.0069")
            .get(0).rewardKind()).isEqualTo(RewardKind.DEFI_YIELD);
        // DPoS is on-chain staking.
        assertThat(map("finance.dpos.compound_interest.crypto_wallet", "SOL", "0.00114", "0.07")
            .get(0).rewardKind()).isEqualTo(RewardKind.STAKING);
        assertThat(map("finance.dpos.non_compound_interest.crypto_wallet", "CRO", "0.97", "0.049")
            .get(0).rewardKind()).isEqualTo(RewardKind.STAKING);
        // "Mystery Box" promo rewards.
        assertThat(map("rewards_platform_deposit_credited", "CRO", "7.3", "0.42").get(0).rewardKind())
            .isEqualTo(RewardKind.CAMPAIGN);
    }

    @Test
    void defiWalletPrincipalAndLockups_areIgnored() {
        // Crypto leaving the spot wallet into a staking/lending/airdrop/lockup program is still owned.
        assertThat(map("finance.defi_staking.staking.crypto_wallet", "LION", "-16000.0", "46.2")).isEmpty();
        assertThat(map("finance.defi_lending.staking.crypto_wallet", "SOL", "-1.0", "75.57")).isEmpty();
        assertThat(map("finance.dpos.staking.crypto_wallet", "ETH", "-0.1", "181.57")).isEmpty();
        assertThat(map("finance.airdrop_arena.deposit.crypto_wallet", "CRO", "-1500.0", "84.86")).isEmpty();
        assertThat(map("finance.lockup.dpos_lock.crypto_wallet", "CRO", "-4867.0", "450.0")).isEmpty();
    }

    @Test
    void limitOrder_onlyCommitCounts() {
        // A filled limit buy carries the crypto in To Currency/To Amount.
        List<ParsedCryptoTx> out = parser.mapRow(
            "2025-11-21 11:13:54", "Bought CRO", "EUR", "-100.00", "CRO", "1102.2",
            "EUR", "100.0", "trading.limit_order.cash_account.purchase_commit");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).txType()).isEqualTo(TransactionType.BUY);
        assertThat(out.get(0).ticker()).isEqualTo("CRO");
        assertThat(out.get(0).quantity()).isEqualByComparingTo("1102.2");

        // The lock (reservation) and unlock (cancel) legs must not double-count or sell.
        assertThat(parser.mapRow(
            "2025-11-19 12:05:58", "Buy CRO", "EUR", "-100.00", "CRO", "1102.2",
            "EUR", "100.0", "trading.limit_order.cash_account.purchase_lock")).isEmpty();
        assertThat(parser.mapRow(
            "2026-01-23 01:53:20", "Buy CRO", "EUR", "50.00", "CRO", "708.6",
            "EUR", "50.0", "trading.limit_order.cash_account.purchase_unlock")).isEmpty();
    }

    @Test
    void swap_emitsSellOfSourceAndBuyOfDestination() {
        List<ParsedCryptoTx> out = parser.mapRow(
            "2024-06-01 09:00:00", "ETH -> SOL", "ETH", "1.0", "SOL", "20.0",
            "EUR", "2000.00", "crypto_exchange");

        assertThat(out).hasSize(2);
        ParsedCryptoTx sell = out.stream().filter(t -> t.txType() == TransactionType.SELL).findFirst().orElseThrow();
        ParsedCryptoTx buy = out.stream().filter(t -> t.txType() == TransactionType.BUY).findFirst().orElseThrow();
        assertThat(sell.ticker()).isEqualTo("ETH");
        assertThat(sell.quantity()).isEqualByComparingTo("1.0");
        assertThat(buy.ticker()).isEqualTo("SOL");
        assertThat(buy.quantity()).isEqualByComparingTo("20.0");
        // 2000 / 20 = 100 per SOL
        assertThat(buy.pricePerUnit()).isEqualByComparingTo("100");
    }

    @Test
    void withdrawal_mapsToSell() {
        List<ParsedCryptoTx> out = map("crypto_withdrawal", "BTC", "-0.5", "15000");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).txType()).isEqualTo(TransactionType.SELL);
        assertThat(out.get(0).quantity()).isEqualByComparingTo("0.5");
    }

    @Test
    void cashbackClawback_mapsToSell() {
        // A reverted/negative reward must reduce holdings, not add zero-cost quantity.
        List<ParsedCryptoTx> out = map("card_cashback", "CRO", "-2", "0.9");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).txType()).isEqualTo(TransactionType.SELL);
        assertThat(out.get(0).quantity()).isEqualByComparingTo("2");
    }

    @Test
    void lockAndUnlock_areIgnored() {
        assertThat(map("crypto_earn_program_created", "CRO", "-100", "50")).isEmpty();
        assertThat(map("supercharger_deposit", "CRO", "-100", "50")).isEmpty();
        assertThat(map("viban_deposit", "EUR", "100", "100")).isEmpty();
    }

    @Test
    void fiatCurrency_isNotTreatedAsCrypto() {
        assertThat(map("crypto_purchase", "EUR", "100", "100")).isEmpty();
    }

    @Test
    void unknownKind_recordedButInert() {
        List<ParsedCryptoTx> out = map("some_future_kind", "CRO", "3", "1");
        assertThat(out).hasSize(1);
        // No txType → holdings are not affected, but the row stays visible.
        assertThat(out.get(0).txType()).isNull();
        assertThat(out.get(0).rawKind()).isEqualTo("some_future_kind");
    }
}
