package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The operator's decision for one symbol from the standing mapping UI (holding detail). Applied by
 * {@code AssetController} onto the {@code financial_asset} registry — the same
 * {@code FinancialAssetService} entry points the import preview uses, just reached outside an import:
 * <ul>
 *   <li>{@code MAP} with {@code coingeckoUrl} → pin the coin behind the pasted CoinGecko link
 *       ({@code setManualMapping});</li>
 *   <li>{@code MAP} with {@code coingeckoId} (a candidate the operator picked) → pin it directly
 *       ({@code applyUserMapping}), no extra CoinGecko round-trip;</li>
 *   <li>{@code WORTHLESS} → mark the symbol worthless, valued at zero ({@code markWorthless}).</li>
 * </ul>
 * All three land the row as {@code USER}/{@code WORTHLESS}. Forgetting a mapping is a separate
 * {@code DELETE}.
 *
 * @param coingeckoUrl a CoinGecko coin-page URL; takes precedence over {@code coingeckoId} for MAP.
 * @param coingeckoId  a known CoinGecko coin id (from the candidate list), used for MAP when no URL.
 * @param name         the coin's display name for the picked candidate (id path only).
 */
public record AssetMappingRequest(
    @NotBlank String action,
    String coingeckoUrl,
    String coingeckoId,
    String name
) {}
