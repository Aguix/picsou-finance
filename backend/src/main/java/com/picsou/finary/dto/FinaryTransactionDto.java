package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryTransactionDto(
    Long id,
    String name,
    String simplifiedName,
    String displayName,
    String date,
    Double value,
    String transactionType,
    FinaryTransactionCategory category,
    FinaryTransactionCurrency currency,
    FinaryTransactionAccount account
) {}
