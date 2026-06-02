package com.picsou.dto;

import com.picsou.model.AccountType;

public record NewAccountDetails(
    String name,
    AccountType type,
    String provider,
    String currency,
    String color
) {}
