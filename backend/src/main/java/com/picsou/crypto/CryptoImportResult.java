package com.picsou.crypto;

import java.math.BigDecimal;

public record CryptoImportResult(
    Long accountId,
    String accountName,
    String source,
    int transactionsImported,
    int holdingsCount,
    BigDecimal totalRewards
) {}
