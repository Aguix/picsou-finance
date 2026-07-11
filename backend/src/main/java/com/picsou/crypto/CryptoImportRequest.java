package com.picsou.crypto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Commit a previously previewed exchange/wallet CSV onto an account.
 *
 * @param action        {@code CREATE_NEW} to build a fresh CRYPTO account, or {@code MAP_EXISTING}
 *                      to import into {@code targetAccountId}.
 * @param assetMappings the operator's confirmed coin decisions from the preview
 *                      ({@link ImportAssetChoice} → {@link ImportAssetMapping}); applied as
 *                      {@code USER}/{@code WORTHLESS} before the price backfill. Optional — coins
 *                      left out stay unresolved and import unpriced.
 */
public record CryptoImportRequest(
    @NotBlank String fileToken,
    @NotBlank String action,
    Long targetAccountId,
    String accountName,
    String color,
    List<ImportAssetMapping> assetMappings
) {}
