package com.picsou.dto;

import com.picsou.model.AccountType;

public record ImportedAccountSummary(
    Long id,
    String name,
    AccountType type,
    double currentBalance,
    String color
) {}
