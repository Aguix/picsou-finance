package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryTransactionCurrency(
    String code,
    String symbol
) {}
