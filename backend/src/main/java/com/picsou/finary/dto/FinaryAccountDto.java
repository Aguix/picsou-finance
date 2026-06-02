package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryAccountDto(
    String id,
    String name,
    String slug,
    Double balance,
    Double organizationBalance,
    FinaryAccountInstitution institution,
    FinaryAccountCurrency currency,
    boolean isManual
) {}
