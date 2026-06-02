package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryTransactionAccount(
    String id,
    String name,
    String slug
) {}
