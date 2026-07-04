package com.picsou.crypto;

import jakarta.validation.constraints.NotBlank;

/**
 * Commit a previously previewed exchange/wallet CSV onto an account.
 *
 * @param action {@code CREATE_NEW} to build a fresh CRYPTO account, or {@code MAP_EXISTING}
 *               to import into {@code targetAccountId}.
 */
public record CryptoImportRequest(
    @NotBlank String fileToken,
    @NotBlank String action,
    Long targetAccountId,
    String accountName,
    String color
) {}
