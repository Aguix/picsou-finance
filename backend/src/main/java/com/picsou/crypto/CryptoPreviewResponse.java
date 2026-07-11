package com.picsou.crypto;

import com.picsou.dto.AccountResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Summary returned after parsing an exchange/wallet CSV, before the user commits the import.
 * Lets the UI show what was detected (source format, coins, date range, reward breakdown) and
 * pick a target account.
 *
 * @param source       machine id of the auto-detected format (e.g. {@code kraken}).
 * @param sourceLabel  human label of the detected source (e.g. {@code Kraken}).
 * @param unvaluedCount rows that move holdings but carry no fiat valuation in the CSV; they are
 *                      valued from daily price history at import time.
 * @param assetChoices  every imported coin that isn't already settled ({@code USER}/{@code WORTHLESS}),
 *                      with the provisional best match and all CoinGecko candidates, for the operator
 *                      to confirm or correct before the import commits — this is what surfaces a
 *                      silent {@code AUTO} mis-match. Nothing here blocks the import; unconfirmed
 *                      coins simply import unpriced.
 */
public record CryptoPreviewResponse(
    String fileToken,
    String source,
    String sourceLabel,
    int rowCount,
    int transactionCount,
    int buyCount,
    int sellCount,
    int rewardCount,
    int unknownCount,
    int unvaluedCount,
    LocalDate firstDate,
    LocalDate lastDate,
    List<String> currencies,
    String nativeCurrency,
    BigDecimal totalInvested,
    BigDecimal totalRewards,
    Map<String, BigDecimal> rewardsByKind,
    List<ImportAssetChoice> assetChoices,
    List<AccountResponse> existingAccounts
) {}
